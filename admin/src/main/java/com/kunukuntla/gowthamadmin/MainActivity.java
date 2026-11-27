package com.kunukuntla.gowthamadmin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gowtham Admin: signs in with your admin login (kept, renewed by itself) and controls every phone
 * running Gowtham - stop a phone (its buttons die, your message shows), turn single buttons off,
 * block a phone, remove a person (all their phones), or stop everyone at once. The list refreshes
 * every 20 s; a phone that checked in within the last 90 s shows as online.
 */
public class MainActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    /** The buttons that can be turned off one by one, as the app names them. */
    private static final String[] BTN_KEYS = {"fast", "tick", "auto", "delete", "go", "book", "clear", "watch", "scan"};
    private static final long ONLINE_MS = 90_000;
    private static final long REFRESH_MS = 20_000;

    private String token = "", refresh = "";
    private long expiresAt;
    private boolean listShown, busy;
    private ScrollView listScroll;

    private SharedPreferences prefs() {
        return getSharedPreferences("session", MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences sp = prefs();
        token = sp.getString("access", "");
        refresh = sp.getString("refresh", "");
        expiresAt = sp.getLong("expires", 0);
        if (refresh.isEmpty()) {
            showLogin();
        } else {
            // Signed in before: renewed by itself, straight to the list.
            setContentView(loadingView("Signing in…"));
            renew(ok -> {
                if (ok) load(true);
                else showLogin();
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(autoRefresh);
        main.postDelayed(autoRefresh, REFRESH_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(autoRefresh);
    }

    /** The list read again every 20 s while the app is open (last seen, online, states). */
    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (listShown && !busy) load(false);
            main.postDelayed(this, REFRESH_MS);
        }
    };

    // ---- sign in, kept and renewed ----

    private void showLogin() {
        listShown = false;
        LinearLayout box = column();
        box.setPadding(dp(24), dp(40), dp(24), dp(24));
        box.addView(title("Gowtham Admin"));
        box.addView(label("Sign in with your admin login (an email listed in public.admins)."));
        EditText email = field("Email", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(prefs().getString("email", ""));
        EditText pass = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(email);
        box.addView(pass);
        Button in = button("Sign in", 0xFFC62828);
        box.addView(in);
        in.setOnClickListener(v -> {
            in.setEnabled(false);
            String e = email.getText().toString().trim();
            signIn(e, pass.getText().toString(), err -> {
                in.setEnabled(true);
                if (err.isEmpty()) {
                    prefs().edit().putString("email", e).apply();
                    load(true);
                } else {
                    toast("Sign-in failed: " + err);
                }
            });
        });
        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        setContentView(sc);
    }

    private void signIn(String email, String password, java.util.function.Consumer<String> done) {
        new Thread(() -> {
            String err;
            try {
                JSONObject body = new JSONObject().put("email", email).put("password", password);
                HttpURLConnection c = open("/auth/v1/token?grant_type=password", null, body);
                int code = c.getResponseCode();
                String s = read(c);
                c.disconnect();
                if (code == 200) {
                    err = keepSession(new JSONObject(s)) ? "" : "no token returned";
                } else {
                    err = "(" + code + ") " + authError(s);
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String fe = err;
            main.post(() -> done.accept(fe));
        }).start();
    }

    /** A new access token from the kept refresh token (sign-ins last an hour; this keeps you in). */
    private void renew(java.util.function.Consumer<Boolean> done) {
        new Thread(() -> {
            boolean ok = false;
            try {
                HttpURLConnection c = open("/auth/v1/token?grant_type=refresh_token", null,
                        new JSONObject().put("refresh_token", refresh));
                int code = c.getResponseCode();
                String s = read(c);
                c.disconnect();
                ok = code == 200 && keepSession(new JSONObject(s));
            } catch (Exception ignored) {
            }
            boolean r = ok;
            main.post(() -> done.accept(r));
        }).start();
    }

    private boolean keepSession(JSONObject j) {
        String a = j.optString("access_token", "");
        if (a.isEmpty()) return false;
        token = a;
        refresh = j.optString("refresh_token", refresh);
        expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000;
        prefs().edit().putString("access", token).putString("refresh", refresh).putLong("expires", expiresAt).apply();
        return true;
    }

    private void signOut() {
        prefs().edit().remove("access").remove("refresh").remove("expires").apply();
        token = "";
        refresh = "";
        showLogin();
    }

    private static String authError(String s) {
        try {
            JSONObject j = new JSONObject(s);
            String m = j.optString("error_description", j.optString("msg", j.optString("error", "")));
            if (!m.isEmpty()) return m;
        } catch (Exception ignored) {
        }
        return s;
    }

    // ---- the list ----

    /** Reads the switches and every phone; {@code fresh}: a spinner first (else the list stays while it loads). */
    private void load(boolean fresh) {
        if (busy) return;
        busy = true;
        if (fresh) setContentView(loadingView("Loading phones…"));
        rpc("admin_status", new JSONObject(), (sc, sa, status) -> rpc("admin_list_devices", new JSONObject(), (code, arr, obj) -> {
            busy = false;
            if (code != 200 || arr == null) {
                if (!fresh && listShown) return; // a background refresh that failed: the list stays
                String msg = obj == null ? "" : obj.optString("message", "");
                String hint;
                if (msg.toLowerCase().contains("not an admin")) {
                    hint = "This login isn't an admin.\n\nIn Supabase → SQL Editor run:\ninsert into public.admins (email) values ('"
                            + prefs().getString("email", "your-login-email") + "');";
                } else if (code == 404) {
                    hint = "The admin functions aren't on the server yet.\nRun the WHOLE admin_controls.sql file once.";
                } else if (code == 401) {
                    hint = "Your sign-in ended. Sign in again.";
                } else {
                    hint = "Couldn't load (code " + code + ")" + (msg.isEmpty() ? "" : "\n" + msg);
                }
                setContentView(errorView(hint));
                return;
            }
            showDevices(arr, sc == 200 ? status : null);
        }));
    }

    private void showDevices(JSONArray arr, JSONObject status) {
        int keepY = listShown && listScroll != null ? listScroll.getScrollY() : 0;
        boolean stopAll = status != null && status.optBoolean("stop_all", false);
        boolean kill = status != null && status.optBoolean("kill", false);

        // Phones grouped by person (newest first within).
        Map<String, List<JSONObject>> people = new LinkedHashMap<>();
        int online = 0, stopped = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.optJSONObject(i);
            if (d == null) continue;
            people.computeIfAbsent(d.optString("email", "?"), k -> new ArrayList<>()).add(d);
            if (isOnline(d)) online++;
            if (d.optBoolean("stopped") || d.optBoolean("revoked") || !d.optBoolean("active", true)) stopped++;
        }

        LinearLayout box = column();
        box.setPadding(dp(16), dp(24), dp(16), dp(32));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = title("Gowtham Admin");
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView out = small("Sign out", 0xFF5F6368);
        out.setOnClickListener(v -> signOut());
        head.addView(out);
        box.addView(head);

        TextView summary = label(arr.length() + " phone(s) · " + people.size() + " person(s) · 🟢 " + online
                + " online · ⛔ " + stopped + " stopped/blocked");
        box.addView(summary);

        if (kill) box.addView(notice("⚠ The kill switch is ON (app_config 'kill'): every app is locked. Turn it off in SQL.", 0xFFC62828));
        if (stopAll) box.addView(notice("⛔ EVERYONE IS STOPPED. No phone can tick until you resume.", 0xFFC62828));
        if (status == null) box.addView(notice("Re-run admin_controls.sql to see the Stop-everyone state here.", 0xFFEF6C00));

        LinearLayout top = new LinearLayout(this);
        Button all = button(stopAll ? "▶ Resume everyone" : "⛔ Stop everyone", stopAll ? 0xFF2E7D32 : 0xFFC62828);
        all.setOnClickListener(v -> confirm(stopAll ? "Resume everyone?" : "Stop EVERY phone?",
                stopAll ? "All phones can tick again (unless stopped one by one)."
                        : "Every phone's buttons stop within ~30 s, with your message.",
                () -> rpcThen("admin_stop_all", obj("p_on", !stopAll), stopAll ? "Everyone resumed" : "Everyone stopped")));
        top.addView(all, weight(0, 6));
        Button again = button("↻ Refresh", 0xFF455A64);
        again.setOnClickListener(v -> load(false));
        top.addView(again, weight(6, 0));
        box.addView(top);

        Button registry = button("🧾 Sevak Registry", 0xFF0F0F0F);
        registry.setTextColor(0xFFFFE500);
        registry.setOnClickListener(v -> startActivity(new android.content.Intent(this, RegistryActivity.class)));
        box.addView(registry);

        if (arr.length() == 0) box.addView(label("No phones yet. A phone shows here after it signs in once."));
        for (Map.Entry<String, List<JSONObject>> p : people.entrySet()) box.addView(personCard(p.getKey(), p.getValue()));

        listScroll = new ScrollView(this);
        listScroll.addView(box);
        setContentView(listScroll);
        listShown = true;
        int y = keepY;
        ScrollView sv = listScroll;
        sv.post(() -> sv.scrollTo(0, y)); // stays where you were after a change
    }

    private View personCard(String email, List<JSONObject> phones) {
        boolean active = phones.get(0).optBoolean("active", true);
        LinearLayout card = card(active ? 0xFFF1F3F4 : 0xFFFBE3E3);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView who = new TextView(this);
        who.setText("👤 " + email + (active ? "" : "  · LICENCE REMOVED"));
        who.setTextColor(active ? 0xFF22262A : 0xFFC62828);
        who.setTextSize(15);
        who.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(who, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView act = small(active ? "Remove person" : "Restore", active ? 0xFFC62828 : 0xFF2E7D32);
        act.setOnClickListener(v -> confirm(active ? "Remove " + email + "?" : "Restore " + email + "?",
                active ? "Every phone of this login stops within ~30 s, and no new phone can sign in with it."
                        : "This login can use the app again.",
                () -> rpcThen("admin_set_license", obj("p_email", email, "p_active", !active),
                        active ? "Removed - their phones stop" : "Restored")));
        row.addView(act);
        card.addView(row);
        for (JSONObject d : phones) card.addView(phoneView(d));
        return card;
    }

    private View phoneView(JSONObject d) {
        final String deviceId = d.optString("device_id", "");
        final boolean stopped = d.optBoolean("stopped", false);
        final boolean revoked = d.optBoolean("revoked", false);
        final String message = d.optString("admin_message", "");
        final List<String> off = new ArrayList<>();
        JSONArray offArr = d.optJSONArray("buttons_off");
        if (offArr != null) for (int i = 0; i < offArr.length(); i++) off.add(offArr.optString(i));

        LinearLayout v = column();
        v.setBackgroundColor(Color.TRANSPARENT);
        v.setPadding(0, dp(10), 0, 0);

        TextView phone = new TextView(this);
        String model = d.optString("model", "");
        phone.setText((isOnline(d) ? "🟢 " : "⚪ ") + (model.isEmpty() ? "Phone" : model)
                + " · " + seen(d.optString("last_seen", "")));
        phone.setTextColor(0xFF22262A);
        phone.setTextSize(14);
        v.addView(phone);

        TextView state = new TextView(this);
        String st = revoked ? "● blocked" : stopped ? "● stopped" : "● running";
        if (!off.isEmpty()) st += " · off: " + String.join(", ", off);
        if (!message.isEmpty()) st += "\n💬 " + message;
        state.setText(st);
        state.setTextColor(revoked || stopped ? 0xFFC62828 : 0xFF2E7D32);
        state.setPadding(0, dp(2), 0, dp(4));
        v.addView(state);

        LinearLayout row = new LinearLayout(this);
        Button stop = button(stopped ? "▶ Allow" : "⛔ Stop", stopped ? 0xFF2E7D32 : 0xFFC62828);
        stop.setOnClickListener(x -> setDevice(deviceId, !stopped, off, message,
                stopped ? "Allowed - it can tick within ~30 s" : "Stopped - lands within ~30 s"));
        row.addView(stop, weight(0, 4));
        Button msg = button("✏ Message", 0xFF1565C0);
        msg.setOnClickListener(x -> editMessage(deviceId, stopped, off, message));
        row.addView(msg, weight(4, 4));
        Button block = button(revoked ? "Unblock" : "Block", 0xFF6A1B9A);
        block.setOnClickListener(x -> confirm(revoked ? "Unblock this phone?" : "Block this phone?",
                revoked ? "It can sign in and work again."
                        : "This phone stops within ~30 s and is logged out at its next check.\n"
                        + "Note: its place on the licence becomes free - to stop the PERSON, use Remove person.",
                () -> rpcThen("admin_revoke_device", obj("p_device", deviceId, "p_revoked", !revoked),
                        revoked ? "Unblocked" : "Blocked")));
        row.addView(block, weight(4, 0));
        v.addView(row);

        // Single buttons off / on (a scrolling row: fits any screen).
        LinearLayout chips = new LinearLayout(this);
        for (String key : BTN_KEYS) {
            boolean isOff = off.contains(key);
            TextView chip = new TextView(this);
            chip.setText((isOff ? "✕ " : "") + key);
            chip.setTextColor(isOff ? Color.WHITE : 0xFF22262A);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            GradientDrawable cb = new GradientDrawable();
            cb.setCornerRadius(dp(16));
            cb.setColor(isOff ? 0xFFC62828 : 0xFFDADCE0);
            chip.setBackground(cb);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, 0, dp(6), 0);
            chip.setOnClickListener(x -> {
                List<String> next = new ArrayList<>(off);
                if (isOff) next.remove(key);
                else next.add(key);
                setDevice(deviceId, stopped, next, message, (isOff ? "Turned on: " : "Turned off: ") + key);
            });
            chips.addView(chip, clp);
        }
        HorizontalScrollView wide = new HorizontalScrollView(this);
        wide.setHorizontalScrollBarEnabled(false);
        wide.setPadding(0, dp(6), 0, 0);
        wide.addView(chips);
        v.addView(wide);
        return v;
    }

    private void editMessage(String deviceId, boolean stopped, List<String> off, String current) {
        EditText e = new EditText(this);
        e.setText(current);
        e.setHint("e.g. Contact Gowtham to continue");
        e.setPadding(dp(20), dp(12), dp(20), dp(12));
        new AlertDialog.Builder(this)
                .setTitle("Message shown on this phone when it is stopped")
                .setView(e)
                .setPositiveButton("Save", (dl, w) -> setDevice(deviceId, stopped, off,
                        e.getText().toString().trim(), "Message saved"))
                .setNeutralButton("Clear", (dl, w) -> setDevice(deviceId, stopped, off, "", "Message cleared"))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Saves one phone's orders - always with its message kept (a toggle never wipes it). */
    private void setDevice(String deviceId, boolean stopped, List<String> off, String message, String okMsg) {
        JSONArray a = new JSONArray();
        for (String s : off) a.put(s);
        rpcThen("admin_set_device", obj("p_device", deviceId, "p_stopped", stopped, "p_off", a, "p_message", message), okMsg);
    }

    // ---- server calls (renewed once on an expired sign-in) ----

    private interface RpcResult {
        void done(int code, JSONArray arr, JSONObject obj);
    }

    private void rpc(String fn, JSONObject body, RpcResult then) {
        Runnable call = () -> new Thread(() -> {
            int[] code = {-1};
            JSONArray[] arr = {null};
            JSONObject[] obj = {null};
            send(fn, body, code, arr, obj);
            if (code[0] == 401 && !refresh.isEmpty()) {
                // The hour ran out: renewed once, then the same call again.
                try {
                    HttpURLConnection c = open("/auth/v1/token?grant_type=refresh_token", null,
                            new JSONObject().put("refresh_token", refresh));
                    if (c.getResponseCode() == 200) keepSession(new JSONObject(read(c)));
                    c.disconnect();
                } catch (Exception ignored) {
                }
                send(fn, body, code, arr, obj);
            }
            main.post(() -> then.done(code[0], arr[0], obj[0]));
        }).start();
        // Renewed ahead when it is about to end (a minute's margin).
        if (!refresh.isEmpty() && System.currentTimeMillis() > expiresAt - 60_000) renew(ok -> call.run());
        else call.run();
    }

    private void send(String fn, JSONObject body, int[] code, JSONArray[] arr, JSONObject[] obj) {
        try {
            HttpURLConnection c = open("/rest/v1/rpc/" + fn, token, body);
            code[0] = c.getResponseCode();
            String s = read(c).trim();
            c.disconnect();
            arr[0] = null;
            obj[0] = null;
            if (s.startsWith("[")) arr[0] = new JSONArray(s);
            else if (s.startsWith("{")) obj[0] = new JSONObject(s);
        } catch (Exception e) {
            code[0] = -1;
        }
    }

    /** A change: said, then the list read again (staying where you were). */
    private void rpcThen(String fn, JSONObject body, String okMsg) {
        rpc(fn, body, (code, arr, obj) -> {
            if (code == 200 || code == 204) {
                toast(okMsg);
                load(false);
            } else {
                String m = obj == null ? "" : obj.optString("message", "");
                toast("Failed (code " + code + ")" + (m.isEmpty() ? "" : ": " + m));
            }
        });
    }

    private HttpURLConnection open(String path, String bearer, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.SUPABASE_URL.replaceAll("/+$", "") + path).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
        c.setRequestProperty("Content-Type", "application/json");
        if (bearer != null && !bearer.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + bearer);
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write((body == null ? "{}" : body.toString()).getBytes("UTF-8"));
        }
        return c;
    }

    private String read(HttpURLConnection c) {
        try {
            java.io.InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ---- time ----

    private static long ageMs(String iso) {
        try {
            return Duration.between(OffsetDateTime.parse(iso).toInstant(), java.time.Instant.now()).toMillis();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean isOnline(JSONObject d) {
        return ageMs(d.optString("last_seen", "")) < ONLINE_MS;
    }

    /** "online now", "seen 5 min ago", "seen 2 h ago", "seen 3 days ago". */
    private static String seen(String iso) {
        long ms = ageMs(iso);
        if (ms == Long.MAX_VALUE) return "never seen";
        if (ms < ONLINE_MS) return "online now";
        long min = ms / 60_000;
        if (min < 60) return "seen " + min + " min ago";
        long h = min / 60;
        if (h < 48) return "seen " + h + " h ago";
        return "seen " + (h / 24) + " days ago";
    }

    // ---- little UI helpers ----

    private void confirm(String title, String text, Runnable yes) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(text)
                .setPositiveButton("Yes", (d, w) -> yes.run())
                .setNegativeButton("Cancel", null).show();
    }

    private JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) {
        }
        return o;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(Color.WHITE);
        return l;
    }

    private LinearLayout card(int colour) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(colour);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, 0);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView notice(String s, int colour) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(colour);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(6));
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout.LayoutParams weight(int left, int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(dp(left), 0, dp(right), 0);
        return lp;
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFFC62828);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView small(String s, int colour) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(colour);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(10), dp(8), dp(4), dp(8));
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF5F6368);
        t.setPadding(0, dp(4), 0, dp(8));
        return t;
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setTextColor(0xFF22262A);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        e.setLayoutParams(lp);
        return e;
    }

    private Button button(String s, int colour) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(colour);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private View loadingView(String s) {
        LinearLayout l = column();
        l.setGravity(Gravity.CENTER);
        l.addView(label(s));
        return l;
    }

    private View errorView(String s) {
        listShown = false;
        LinearLayout l = column();
        l.setPadding(dp(24), dp(40), dp(24), dp(24));
        l.addView(title("Gowtham Admin"));
        TextView t = label(s);
        t.setTextIsSelectable(true);
        l.addView(t);
        Button again = button("Try again", 0xFF455A64);
        again.setOnClickListener(v -> load(true));
        l.addView(again);
        Button out = button("Sign in again", 0xFFC62828);
        out.setOnClickListener(v -> signOut());
        l.addView(out);
        ScrollView sc = new ScrollView(this);
        sc.addView(l);
        return sc;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
