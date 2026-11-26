package com.kunukuntla.gowthamadmin;

import android.app.Activity;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Gowtham Admin: signs in with your admin login and controls every phone running Gowtham -
 * stop a phone (all its buttons die, a message shows), turn single buttons off, block a phone,
 * or stop everyone at once. Only emails you listed in public.admins may change anything.
 */
public class MainActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private String token = "";
    private static final String[] BTN_KEYS = {"tick", "fast", "auto", "delete", "book", "go"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        showLogin();
    }

    // ---- sign in ----

    private void showLogin() {
        LinearLayout box = column();
        box.setPadding(dp(24), dp(40), dp(24), dp(24));
        box.addView(title("Gowtham Admin"));
        box.addView(label("Sign in with your admin login (the email you put in public.admins)."));
        EditText email = field("Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText pass = field("Password", InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(email);
        box.addView(pass);
        Button in = button("Sign in", 0xFFC62828);
        box.addView(in);
        in.setOnClickListener(v -> {
            in.setEnabled(false);
            signIn(email.getText().toString().trim(), pass.getText().toString(), ok -> {
                in.setEnabled(true);
                if (ok) load();
                else toast("Sign-in failed - check the email and password");
            });
        });
        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        setContentView(sc);
    }

    private void signIn(String email, String password, java.util.function.Consumer<Boolean> done) {
        new Thread(() -> {
            boolean ok = false;
            try {
                JSONObject body = new JSONObject().put("email", email).put("password", password);
                HttpURLConnection c = open("POST", "/auth/v1/token?grant_type=password", null, body);
                if (c.getResponseCode() == 200) {
                    JSONObject j = new JSONObject(read(c));
                    token = j.optString("access_token", "");
                    ok = !token.isEmpty();
                }
                c.disconnect();
            } catch (Exception ignored) {
            }
            boolean r = ok;
            main.post(() -> done.accept(r));
        }).start();
    }

    // ---- the device list ----

    private void load() {
        setContentView(loadingView("Loading phones…"));
        rpc("admin_list_devices", new JSONObject(), (code, arr, obj) -> {
            if (code != 200 || arr == null) {
                setContentView(errorView(code == 403 || code == 401
                        ? "This login isn't an admin. Add it: insert into public.admins (email) values ('you@email');"
                        : "Couldn't load (code " + code + ")"));
                return;
            }
            showDevices(arr);
        });
    }

    private void showDevices(JSONArray arr) {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(28), dp(16), dp(24));
        box.addView(title("Gowtham Admin"));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        Button stopAll = button("⛔ Stop everyone", 0xFFC62828);
        Button freeAll = button("▶ Resume everyone", 0xFF2E7D32);
        stopAll.setOnClickListener(v -> callVoid("admin_stop_all", new JSONObject(), true, "p_on", "Stopped everyone"));
        freeAll.setOnClickListener(v -> callVoid("admin_stop_all", new JSONObject(), false, "p_on", "Resumed everyone"));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        half.setMargins(0, 0, dp(6), 0);
        top.addView(stopAll, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        half2.setMargins(dp(6), 0, 0, 0);
        top.addView(freeAll, half2);
        box.addView(top);

        Button refresh = button("↻ Refresh", 0xFF455A64);
        refresh.setOnClickListener(v -> load());
        box.addView(refresh);

        box.addView(label(arr.length() + " phone(s). Newest first."));
        for (int i = 0; i < arr.length(); i++) {
            box.addView(deviceCard(arr.optJSONObject(i)));
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        setContentView(sc);
    }

    private View deviceCard(JSONObject d) {
        final String deviceId = d.optString("device_id", "");
        boolean stopped = d.optBoolean("stopped", false);
        boolean revoked = d.optBoolean("revoked", false);
        List<String> off = new ArrayList<>();
        JSONArray offArr = d.optJSONArray("buttons_off");
        if (offArr != null) for (int i = 0; i < offArr.length(); i++) off.add(offArr.optString(i));

        LinearLayout card = column();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(stopped || revoked ? 0xFFFBE3E3 : 0xFFF1F3F4);
        card.setBackground(bg);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, dp(8), 0, 0);
        card.setLayoutParams(cardLp);

        TextView who = new TextView(this);
        who.setText(d.optString("email", "?") + "\n" + d.optString("model", "") + " · seen " + ago(d.optString("last_seen", "")));
        who.setTextColor(0xFF22262A);
        who.setTextSize(14);
        who.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(who);

        TextView state = new TextView(this);
        state.setTextColor(stopped ? 0xFFC62828 : revoked ? 0xFFC62828 : 0xFF2E7D32);
        state.setText(revoked ? "● blocked" : stopped ? "● stopped" : off.isEmpty() ? "● running" : "● running, off: " + off);
        state.setPadding(0, dp(4), 0, dp(6));
        card.addView(state);

        // Stop / allow this phone, keeping its button-off list.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button stop = button(stopped ? "▶ Allow" : "⛔ Stop", stopped ? 0xFF2E7D32 : 0xFFC62828);
        stop.setOnClickListener(v -> setDevice(deviceId, !stopped, off));
        Button block = button(revoked ? "Unblock" : "Block", 0xFF6A1B9A);
        block.setOnClickListener(v -> rpcVoid("admin_revoke_device",
                obj("p_device", deviceId, "p_revoked", !revoked), "Done - refreshing"));
        LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        g.setMargins(0, 0, dp(6), 0);
        row.addView(stop, g);
        LinearLayout.LayoutParams g2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        g2.setMargins(dp(6), 0, 0, 0);
        row.addView(block, g2);
        card.addView(row);

        // Turn single buttons off / on.
        TextView t = new TextView(this);
        t.setText("Buttons (tap to turn off / on):");
        t.setTextColor(0xFF5F6368);
        t.setPadding(0, dp(8), 0, dp(4));
        card.addView(t);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (String key : BTN_KEYS) {
            boolean isOff = off.contains(key);
            TextView chip = new TextView(this);
            chip.setText(key);
            chip.setTextColor(isOff ? Color.WHITE : 0xFF22262A);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            GradientDrawable cb = new GradientDrawable();
            cb.setCornerRadius(dp(16));
            cb.setColor(isOff ? 0xFFC62828 : 0xFFDADCE0);
            chip.setBackground(cb);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, 0, dp(6), 0);
            chip.setOnClickListener(v -> {
                List<String> next = new ArrayList<>(off);
                if (isOff) next.remove(key); else next.add(key);
                setDevice(deviceId, stopped, next);
            });
            chips.addView(chip, clp);
        }
        card.addView(chips);
        return card;
    }

    private void setDevice(String deviceId, boolean stopped, List<String> off) {
        JSONArray a = new JSONArray();
        for (String s : off) a.put(s);
        JSONObject body = obj("p_device", deviceId, "p_stopped", stopped);
        try {
            body.put("p_off", a);
        } catch (Exception ignored) {
        }
        rpcVoid("admin_set_device", body, "Saved - refreshing");
    }

    // ---- RPC plumbing ----

    private interface RpcResult {
        void done(int code, JSONArray arr, JSONObject obj);
    }

    private void rpc(String fn, JSONObject body, RpcResult then) {
        new Thread(() -> {
            int code = -1;
            JSONArray arr = null;
            JSONObject obj = null;
            try {
                HttpURLConnection c = open("POST", "/rest/v1/rpc/" + fn, token, body);
                code = c.getResponseCode();
                String s = read(c);
                c.disconnect();
                if (s != null && s.startsWith("[")) arr = new JSONArray(s);
                else if (s != null && s.startsWith("{")) obj = new JSONObject(s);
            } catch (Exception ignored) {
            }
            int fc = code;
            JSONArray fa = arr;
            JSONObject fo = obj;
            main.post(() -> then.done(fc, fa, fo));
        }).start();
    }

    private void rpcVoid(String fn, JSONObject body, String okMsg) {
        rpc(fn, body, (code, arr, obj) -> {
            if (code == 200 || code == 204) {
                toast(okMsg);
                load();
            } else {
                toast("Failed (code " + code + ")");
            }
        });
    }

    private void callVoid(String fn, JSONObject body, boolean on, String key, String okMsg) {
        try {
            body.put(key, on);
        } catch (Exception ignored) {
        }
        rpcVoid(fn, body, okMsg);
    }

    private HttpURLConnection open(String method, String path, String bearer, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.SUPABASE_URL + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
        c.setRequestProperty("Content-Type", "application/json");
        if (bearer != null && !bearer.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes("UTF-8"));
            }
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

    // ---- little UI helpers ----

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

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFFC62828);
        t.setPadding(0, 0, 0, dp(12));
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF5F6368);
        t.setPadding(0, dp(4), 0, dp(10));
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
        LinearLayout l = column();
        l.setPadding(dp(24), dp(40), dp(24), dp(24));
        l.addView(title("Gowtham Admin"));
        l.addView(label(s));
        Button again = button("Try again", 0xFF455A64);
        again.setOnClickListener(v -> showLogin());
        l.addView(again);
        return l;
    }

    private static String ago(String iso) {
        if (iso == null || iso.length() < 10) return "?";
        return iso.replace('T', ' ').substring(0, Math.min(16, iso.length()));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
