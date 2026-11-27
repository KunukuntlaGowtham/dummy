package com.kunukuntla.ttdfiller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * TTD Filler: sign in (the same login as Gowtham), pick the booking to fill from ttd_db, turn the
 * filler on in Accessibility. On the TTD pilgrim page the 📝 Fill button then fills every pilgrim.
 */
public class MainActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private ScrollView scroll;
    private LinearLayout listBox;
    private TextView listHead;
    private String query = "";
    private boolean loading;

    private SharedPreferences prefs() {
        return Booking.prefs(this);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Supa.signedIn(this)) showLogin();
        else {
            showMain();
            if (Booking.cached(this).isEmpty() || System.currentTimeMillis() - Booking.refreshedAt(this) > 10 * 60_000) reload();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from Accessibility settings or the TTD app: what changed there shows here.
        if (Supa.signedIn(this) && scroll != null) showMain();
    }

    // ---- sign in ----

    private void showLogin() {
        scroll = null;
        LinearLayout box = column();
        box.setPadding(dp(24), dp(40), dp(24), dp(24));
        box.addView(title("TTD Filler"));
        box.addView(text("Sign in with your Gowtham login (the same email and password). "
                + "The bookings come from your ttd_db table.", 0xFF424242));
        EditText email = field("Email", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(Supa.email(this));
        EditText pass = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(email);
        box.addView(pass);
        Button in = button("Sign in", 0xFF5E35B1);
        box.addView(in);
        in.setOnClickListener(v -> {
            in.setEnabled(false);
            in.setText("Signing in…");
            String e = email.getText().toString().trim();
            String p = pass.getText().toString();
            new Thread(() -> {
                String err = Supa.signIn(this, e, p);
                String loadErr = err.isEmpty() ? Booking.refresh(this) : "";
                main.post(() -> {
                    if (!err.isEmpty()) {
                        in.setEnabled(true);
                        in.setText("Sign in");
                        toast("Sign-in failed: " + err);
                        return;
                    }
                    showMain();
                    if (!loadErr.isEmpty()) toast(loadErr);
                });
            }).start();
        });
        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        setContentView(sc);
    }

    // ---- the main screen ----

    private void showMain() {
        int keepY = scroll != null ? scroll.getScrollY() : 0;
        LinearLayout box = column();
        box.setPadding(dp(14), dp(18), dp(14), dp(24));

        box.addView(title("TTD Filler"));
        LinearLayout who = new LinearLayout(this);
        who.setGravity(Gravity.CENTER_VERTICAL);
        who.addView(text("Signed in as " + Supa.email(this), 0xFF616161),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button out = small("Sign out", 0xFF757575);
        out.setOnClickListener(v -> confirm("Sign out?", "The bookings kept on this phone are cleared too.", () -> {
            Supa.signOut(this);
            prefs().edit().remove("rows").remove("rows_at").remove("picked_id").apply();
            showLogin();
        }));
        who.addView(out);
        box.addView(who);

        // 1) the filler on
        boolean on = serviceOn();
        LinearLayout a = card(on ? 0xFFE8F5E9 : 0xFFFFEBEE);
        a.addView(bold(on ? "✅ The filler is on" : "❌ The filler is off"));
        a.addView(text(on ? "📝 Fill shows on the TTD app. Drag it anywhere."
                : "Turn on \"TTD Filler\" in Accessibility (Installed apps / Downloaded apps → TTD Filler → On).", 0xFF424242));
        if (!on) {
            Button go = button("Open Accessibility settings", 0xFFC62828);
            go.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            a.addView(go);
        }
        box.addView(a);

        // 2) the booking picked
        Booking picked = Booking.picked(this);
        LinearLayout p = card(0xFFEDE7F6);
        p.addView(bold(picked == null ? "No booking picked" : "Picked: " + picked.title()));
        if (picked == null) {
            p.addView(text("Tap a booking below (or 👥 on the TTD page).", 0xFF424242));
        } else {
            List<Booking.Person> ps = picked.people();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ps.size(); i++) {
                Booking.Person x = ps.get(i);
                if (i > 0) sb.append('\n');
                sb.append(i + 1).append(". ").append(x.name)
                        .append(" · ").append(x.age.isEmpty() ? "no age" : Match.age(x.age))
                        .append(" · ").append(x.gender.isEmpty() ? "no gender" : x.gender)
                        .append(" · ").append(Match.proofName(x.idType)).append(' ').append(Booking.masked(x.idNumber));
            }
            p.addView(text(sb.toString(), 0xFF212121));
        }
        box.addView(p);

        // 3) how it fills
        LinearLayout s = card(0xFFF5F5F5);
        CheckBox auto = check("Fill by itself when the pilgrim page opens", prefs().getBoolean("auto", true));
        auto.setOnCheckedChangeListener((v, c) -> prefs().edit().putBoolean("auto", c).apply());
        CheckBox cont = check("Press Continue after filling (only when every box is right)", prefs().getBoolean("continue", false));
        cont.setOnCheckedChangeListener((v, c) -> prefs().edit().putBoolean("continue", c).apply());
        s.addView(auto);
        s.addView(cont);
        CheckBox clock = check("🕒 Show the clock (internet time, the same on every phone)", prefs().getBoolean("show_clock", true));
        clock.setOnCheckedChangeListener((v, c) -> prefs().edit().putBoolean("show_clock", c).apply());
        s.addView(clock);
        box.addView(s);

        // 3b) ▶ Go: the slot page to Pay Now
        LinearLayout g = card(0xFFE8F5E9);
        g.addView(bold("▶ Go - from the Darshan Slots page to Pay Now"));
        g.addView(text("Open the slot page (the calendar) and press ▶ Go: your date, Number of Tickets = the people in "
                + "the picked booking, the best slot in your hours, Continue, every pilgrim filled, Continue, Pay Now. "
                + "No slot on any date: Refresh and try again until the time below runs out. Tap ⏹ to stop.", 0xFF424242));
        g.addView(text("Dates, in the order to try:", 0xFF212121));
        EditText dates = field("22/12/2026, 23/12/2026", InputType.TYPE_CLASS_TEXT);
        dates.setText(prefs().getString("go_dates", ""));
        TextView datesSeen = text("", 0xFF2E7D32);
        datesSeen.setTextSize(12);
        Runnable showDates = () -> {
            java.util.List<int[]> ds = SlotPage.parseDates(dates.getText().toString(), System.currentTimeMillis());
            StringBuilder b = new StringBuilder();
            for (int[] d : ds) b.append(b.length() > 0 ? " → " : "").append(SlotPage.text(d));
            datesSeen.setText(ds.isEmpty() ? "No date yet (day/month/year)" : "Tries " + b);
            datesSeen.setTextColor(ds.isEmpty() ? 0xFFC62828 : 0xFF2E7D32);
        };
        showDates.run();
        dates.addTextChangedListener(saver(e -> {
            prefs().edit().putString("go_dates", e).apply();
            showDates.run();
        }));
        g.addView(dates);
        g.addView(datesSeen);
        LinearLayout hours = new LinearLayout(this);
        hours.setGravity(Gravity.CENTER_VERTICAL);
        hours.addView(text("Slots from ", 0xFF212121));
        hours.addView(numField("slot_from", 12), new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        hours.addView(text(" to ", 0xFF212121));
        hours.addView(numField("slot_to", 22), new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        g.addView(hours);
        TextView hint = text("24-hour clock: 12 = 12 PM, 22 = 10 PM (a slot starting at 10 PM counts)", 0xFF757575);
        hint.setTextSize(12);
        g.addView(hint);
        CheckBox earliest = check("Earliest slot (instead of the one with the most tickets left)", prefs().getBoolean("slot_earliest", false));
        earliest.setOnCheckedChangeListener((v, c) -> prefs().edit().putBoolean("slot_earliest", c).apply());
        CheckBox anyTime = check("No slot in those hours: take one at another time", prefs().getBoolean("slot_any", false));
        anyTime.setOnCheckedChangeListener((v, c) -> prefs().edit().putBoolean("slot_any", c).apply());
        g.addView(earliest);
        g.addView(anyTime);
        LinearLayout retry = new LinearLayout(this);
        retry.setGravity(Gravity.CENTER_VERTICAL);
        retry.addView(text("Keep trying for ", 0xFF212121));
        retry.addView(numField("go_retry_s", 120), new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));
        retry.addView(text(" seconds", 0xFF212121));
        g.addView(retry);
        CheckBox pay = check("Press Pay Now at the end", prefs().getBoolean("pay_now", true));
        pay.setOnCheckedChangeListener((v, c) -> prefs().edit().putBoolean("pay_now", c).apply());
        g.addView(pay);
        box.addView(g);

        // 4) the last fill
        String last = prefs().getString("last_report", "");
        if (!last.isEmpty()) {
            LinearLayout r = card(last.startsWith("✅") ? 0xFFE8F5E9 : 0xFFFFF3E0);
            LinearLayout rh = new LinearLayout(this);
            rh.setGravity(Gravity.CENTER_VERTICAL);
            rh.addView(bold("Last fill · " + ago(prefs().getLong("last_report_at", 0))),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button copy = small("📋 Copy", 0xFF5E35B1);
            copy.setOnClickListener(v -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                String all = last + "\n\n" + prefs().getString("last_details", "");
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("TTD Filler report", all.trim()));
                toast("Report copied - paste it to Claude");
            });
            rh.addView(copy);
            r.addView(rh);
            TextView body = text(last, 0xFF212121);
            body.setTextIsSelectable(true);
            r.addView(body);
            String details = prefs().getString("last_details", "");
            if (!details.isEmpty()) {
                TextView dv = text(details, 0xFF757575);
                dv.setTextSize(11);
                dv.setTextIsSelectable(true);
                r.addView(dv);
            }
            box.addView(r);
        }

        // 5) the bookings
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(14), 0, 0);
        listHead = bold("");
        head.addView(listHead, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button re = small(loading ? "…" : "⟳ Refresh", 0xFF5E35B1);
        re.setOnClickListener(v -> reload());
        head.addView(re);
        box.addView(head);

        EditText search = field("Search code, name, mobile", InputType.TYPE_CLASS_TEXT);
        search.setText(query);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a1, int b1, int c1) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a1, int b1, int c1) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                query = e.toString();
                fillList();
            }
        });
        box.addView(search);
        listBox = column();
        box.addView(listBox);
        fillList();

        scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);
        scroll.post(() -> scroll.scrollTo(0, keepY));
    }

    private void fillList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        List<Booking> all = Booking.cached(this);
        Booking picked = Booking.picked(this);
        int shown = 0;
        for (Booking b : all) {
            if (!b.matches(query)) continue;
            if (++shown > 400) break;
            boolean on = picked != null && picked.id() == b.id();
            LinearLayout c = card(on ? 0xFFC8E6C9 : Color.WHITE);
            c.addView(bold((on ? "✅ " : "") + b.title()));
            List<Booking.Person> ps = b.people();
            StringBuilder sb = new StringBuilder();
            if (ps.size() > 1) {
                sb.append("with ");
                for (int i = 1; i < ps.size(); i++) sb.append(i > 1 ? ", " : "").append(ps.get(i).name);
            }
            if (!b.s("mobile").isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append("📞 ").append(b.s("mobile"));
            if (sb.length() > 0) c.addView(text(sb.toString(), 0xFF616161));
            c.setOnClickListener(v -> {
                Booking.pick(this, b.id());
                toast("Picked " + b.code());
                showMain();
            });
            listBox.addView(c);
        }
        String when = Booking.refreshedAt(this) == 0 ? "" : " · " + ago(Booking.refreshedAt(this));
        if (listHead != null) listHead.setText("Bookings (" + all.size() + ")" + when);
        if (all.isEmpty()) listBox.addView(text(loading ? "Loading…" : "No bookings - press ⟳ Refresh", 0xFF757575));
        else if (shown == 0) listBox.addView(text("Nothing matches \"" + query + "\"", 0xFF757575));
    }

    private void reload() {
        if (loading) return;
        loading = true;
        if (scroll != null) showMain();
        new Thread(() -> {
            String err = Booking.refresh(this);
            main.post(() -> {
                loading = false;
                if (err.startsWith("Sign in again")) {
                    Supa.signOut(this);
                    toast(err);
                    showLogin();
                    return;
                }
                if (!err.isEmpty()) toast(err);
                if (scroll != null) showMain();
            });
        }).start();
    }

    private boolean serviceOn() {
        if (FillService.instance != null) return true;
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.toLowerCase(Locale.ROOT).contains((getPackageName() + "/").toLowerCase(Locale.ROOT));
    }

    private static String ago(long at) {
        if (at <= 0) return "";
        long m = (System.currentTimeMillis() - at) / 60_000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + " h ago";
        return (h / 24) + " days ago";
    }

    // ---- small things ----

    /** A number box kept in the settings under {@code key}. */
    private EditText numField(String key, int def) {
        EditText e = field(String.valueOf(def), InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(prefs().getInt(key, def)));
        e.addTextChangedListener(saver(t -> {
            try {
                prefs().edit().putInt(key, Integer.parseInt(t.trim())).apply();
            } catch (NumberFormatException ignored) {
            }
        }));
        return e;
    }

    private static TextWatcher saver(java.util.function.Consumer<String> save) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                save.accept(e.toString());
            }
        };
    }

    private void confirm(String title, String msg, Runnable yes) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("Yes", (d, w) -> yes.run()).setNegativeButton("No", null).show();
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout card(int colour) {
        LinearLayout c = column();
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable d = new GradientDrawable();
        d.setColor(colour);
        d.setCornerRadius(dp(12));
        d.setStroke(1, 0x22000000);
        c.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF311B92);
        return t;
    }

    private TextView bold(String s) {
        TextView t = text(s, 0xFF212121);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(15);
        return t;
    }

    private TextView text(String s, int colour) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(colour);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private CheckBox check(String s, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextSize(14);
        c.setChecked(on);
        return c;
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        return e;
    }

    private Button button(String s, int colour) {
        Button b = small(s, colour);
        b.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private Button small(String s, int colour) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        GradientDrawable d = new GradientDrawable();
        d.setColor(colour);
        d.setCornerRadius(dp(10));
        b.setBackground(d);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinHeight(dp(38));
        b.setMinimumHeight(dp(38));
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
