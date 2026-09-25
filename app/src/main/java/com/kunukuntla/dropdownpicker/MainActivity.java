package com.kunukuntla.dropdownpicker;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.example.checkboxticker.CheckboxService;
import com.example.checkboxticker.ScreenService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** One settings page for everything: setup, dropdown, calendar, tick. Changes save as you go. */
public class MainActivity extends Activity {

    private static final int REQUEST_SHARE = 1;

    // Palette
    private static final int BG = 0xFFF3F1F8;
    private static final int CARD = 0xFFFFFFFF;
    private static final int INK = 0xFF1D1B26;
    private static final int MUTED = 0xFF77738A;
    private static final int LINE = 0xFFE4E0EE;
    private static final int ACCENT = 0xFF6C3FD1;
    private static final int ACCENT_2 = 0xFF9A6BFF;
    private static final int ACCENT_SOFT = 0xFFEDE6FD;
    private static final int GOOD = 0xFF1E9E61;
    private static final int BAD = 0xFFD64545;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView accessibilityChip;
    private TextView screenChip;
    private TextView lastRun;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(ACCENT);
        getWindow().setNavigationBarColor(BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);

        page.addView(header());

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(32));
        page.addView(body);

        // Dropdown
        LinearLayout drop = card(body, "▼", "Dropdown", 0xFF37474F);
        drop.addView(label("Keywords"));
        drop.addView(input("e.g. Srivari Seva, Tirumala", Keywords.load(this),
                InputType.TYPE_CLASS_TEXT, s -> Keywords.save(this, s)));
        drop.addView(label("When it opens"));
        drop.addView(segmented(new String[] {"4 mm below", "Contains", "Exact"},
                new int[] {Keywords.PICK_BELOW, Keywords.PICK_CONTAINS, Keywords.PICK_EXACT},
                Keywords.loadPickMode(this), v -> Keywords.savePickMode(this, v)));

        // Calendar
        LinearLayout cal = card(body, "📅", "Calendar", 0xFF1565C0);
        cal.addView(label("Date"));
        cal.addView(input("DD/MM/YYYY", DayChoice.loadDate(this), InputType.TYPE_CLASS_DATETIME,
                s -> DayChoice.saveDate(this, s)));
        cal.addView(label("Day"));
        cal.addView(segmented(new String[] {"Off", "Exact", "Best"},
                new int[] {DayChoice.MODE_OFF, DayChoice.MODE_EXACT, DayChoice.MODE_BEST},
                DayChoice.loadMode(this), v -> DayChoice.saveMode(this, v)));
        cal.addView(label("Available colours"));
        cal.addView(colourChips());
        cal.addView(toggle("Then Available, checkbox, Continue", Keywords.loadFinish(this),
                on -> Keywords.saveFinish(this, on)));

        // Tick (Checkbox Ticker)
        SharedPreferences tp = getSharedPreferences(CheckboxService.PREFS, Context.MODE_PRIVATE);
        LinearLayout tick = card(body, "✔", "Tick", 0xFF00897B);
        tick.addView(toggle("Clear pop-up after each tick", tp.getBoolean("tapColour", true),
                on -> tickPrefs(e -> e.putBoolean("tapColour", on))));
        tick.addView(toggle("Show status line", tp.getBoolean("showStatus", true),
                on -> tickPrefs(e -> e.putBoolean("showStatus", on))));
        tick.addView(divider());
        tick.addView(valueRow("Pop-up colour", String.format(Locale.ROOT, "#%06X",
                tp.getInt("colour", CheckboxService.DEFAULT_COLOUR)), InputType.TYPE_CLASS_TEXT, s -> {
                    Integer c = parseColour(s);
                    if (c != null) tickPrefs(e -> e.putInt("colour", c));
                }));
        tick.addView(numberRow("Max boxes", tp.getInt("maxBoxes", 15), "maxBoxes", 1, 500));
        tick.addView(numberRow("After tick (ms)", tp.getInt("tickWaitMs", 300), "tickWaitMs", 0, 10000));
        tick.addView(numberRow("After pop-up (ms)", tp.getInt("clearWaitMs", 300), "clearWaitMs", 0, 10000));
        tick.addView(numberRow("After scroll (ms)", tp.getInt("scrollWaitMs", 300), "scrollWaitMs", 0, 10000));

        // Last run
        LinearLayout log = card(body, "≡", "Last run", 0xFF6D6A7C);
        lastRun = new TextView(this);
        lastRun.setTextSize(12);
        lastRun.setTypeface(Typeface.MONOSPACE);
        lastRun.setTextColor(0xFF4A4658);
        lastRun.setTextIsSelectable(true);
        lastRun.setPadding(dp(12), dp(10), dp(12), dp(10));
        lastRun.setBackground(rounded(0xFFF6F4FA, dp(10), 0));
        log.addView(lastRun, matchWrap());
        View share = pillButton("Share", false, v -> {
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Dropdown Picker last run:\n"
                            + Keywords.loadLastRun(this));
            startActivity(Intent.createChooser(send, "Share last run"));
        });
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shareLp.gravity = Gravity.END;
        shareLp.topMargin = dp(10);
        log.addView(share, shareLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);
    }

    // ---- Header ----------------------------------------------------------------------

    private View header() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(22), dp(28), dp(22), dp(26));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {ACCENT, ACCENT_2});
        float r = dp(28);
        bg.setCornerRadii(new float[] {0, 0, 0, 0, r, r, r, r});
        head.setBackground(bg);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(26);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(Color.WHITE);
        head.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Drop  ·  Cal  ·  Tick");
        sub.setTextSize(14);
        sub.setTextColor(0xCCFFFFFF);
        sub.setPadding(0, dp(2), 0, dp(18));
        head.addView(sub);

        LinearLayout chips = new LinearLayout(this);
        accessibilityChip = statusChip(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        screenChip = statusChip(v -> toggleSharing());
        chips.addView(accessibilityChip);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = dp(10);
        chips.addView(screenChip, gap);
        head.addView(chips);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(head, matchWrap());
        wrap.setPadding(0, 0, 0, dp(8));
        return wrap;
    }

    private TextView statusChip(View.OnClickListener onClick) {
        TextView chip = new TextView(this);
        chip.setTextSize(13);
        chip.setTextColor(Color.WHITE);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        chip.setBackground(rounded(0x33FFFFFF, dp(20), 0));
        chip.setOnClickListener(onClick);
        return chip;
    }

    // ---- Building blocks ---------------------------------------------------------------

    private LinearLayout card(LinearLayout parent, String icon, String heading, int iconColour) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        card.setBackground(rounded(CARD, dp(18), 0));
        card.setElevation(dp(2));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = new TextView(this);
        badge.setText(icon);
        badge.setTextSize(13);
        badge.setTextColor(Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(iconColour);
        badge.setBackground(dot);
        top.addView(badge, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView h = new TextView(this);
        h.setText(heading);
        h.setTextSize(17);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        h.setTextColor(INK);
        h.setPadding(dp(12), 0, 0, 0);
        top.addView(h);
        card.addView(top);

        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(12);
        parent.addView(card, lp);
        return card;
    }

    private TextView label(String text) {
        TextView l = new TextView(this);
        l.setText(text);
        l.setTextSize(12);
        l.setTextColor(MUTED);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        l.setPadding(dp(2), dp(14), 0, dp(6));
        return l;
    }

    private EditText input(String hint, String value, int inputType, Consumer<String> onChange) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFFB0ACBE);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setTextSize(15);
        e.setTextColor(INK);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setBackground(rounded(0xFFFAF9FD, dp(12), LINE));
        e.setOnFocusChangeListener((v, focus) ->
                v.setBackground(rounded(0xFFFAF9FD, dp(12), focus ? ACCENT : LINE)));
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                onChange.accept(s.toString());
            }
        });
        return e;
    }

    /** A row of pills where exactly one is picked. */
    private LinearLayout segmented(String[] labels, int[] values, int saved, Consumer<Integer> onChange) {
        LinearLayout group = new LinearLayout(this);
        group.setPadding(dp(4), dp(4), dp(4), dp(4));
        group.setBackground(rounded(0xFFF1EEF7, dp(14), 0));
        List<TextView> pills = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            TextView pill = new TextView(this);
            pill.setText(labels[i]);
            pill.setTextSize(14);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(6), dp(10), dp(6), dp(10));
            int value = values[i];
            pill.setTag(value);
            pill.setOnClickListener(v -> {
                for (TextView p : pills) stylePill(p, p == v);
                onChange.accept(value);
            });
            pills.add(pill);
            group.addView(pill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        for (TextView p : pills) stylePill(p, (int) p.getTag() == saved);
        return group;
    }

    private void stylePill(TextView pill, boolean on) {
        pill.setTextColor(on ? Color.WHITE : MUTED);
        pill.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        if (on) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[] {ACCENT, ACCENT_2});
            g.setCornerRadius(dp(11));
            pill.setBackground(g);
        } else {
            pill.setBackground(null);
        }
    }

    /** Day colours as tappable chips with a colour dot. */
    private LinearLayout colourChips() {
        LinearLayout row = new LinearLayout(this);
        Set<String> open = DayChoice.loadOpenColours(this);
        String[][] colours = {{"GREEN", "Green", "#43A047"}, {"YELLOW", "Yellow", "#FBC02D"},
                {"GREY", "Grey", "#9E9E9E"}, {"WHITE", "White", "#FFFFFF"}};
        for (String[] c : colours) {
            TextView chip = new TextView(this);
            chip.setText("●  " + c[1]);
            chip.setTextSize(13);
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            boolean[] on = {open.contains(c[0])};
            int dotColour = Color.parseColor(c[2]);
            Runnable style = () -> {
                android.text.SpannableString s = new android.text.SpannableString("●  " + c[1]);
                s.setSpan(new android.text.style.ForegroundColorSpan(
                        c[0].equals("WHITE") ? 0xFFCCCCCC : dotColour), 0, 1, 0);
                chip.setText(s);
                chip.setTextColor(on[0] ? ACCENT : MUTED);
                chip.setTypeface(on[0] ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                chip.setBackground(rounded(on[0] ? ACCENT_SOFT : 0xFFF6F4FA, dp(18),
                        on[0] ? ACCENT : LINE));
            };
            style.run();
            chip.setOnClickListener(v -> {
                on[0] = !on[0];
                DayChoice.setOpenColour(this, c[0], on[0]);
                style.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            row.addView(chip, lp);
        }
        return row;
    }

    private LinearLayout toggle(String text, boolean on, Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, 0);
        TextView l = new TextView(this);
        l.setText(text);
        l.setTextSize(15);
        l.setTextColor(INK);
        Switch s = new Switch(this);
        s.setChecked(on);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[] {ACCENT, 0xFFFFFFFF}));
        s.setTrackTintList(new ColorStateList(states, new int[] {ACCENT_2, 0xFFCFCBDA}));
        s.setOnCheckedChangeListener((b, checked) -> onChange.accept(checked));
        row.setOnClickListener(v -> s.toggle());
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(s);
        return row;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(4);
        d.setLayoutParams(lp);
        return d;
    }

    private LinearLayout valueRow(String text, String value, int inputType, Consumer<String> onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        TextView l = new TextView(this);
        l.setText(text);
        l.setTextSize(15);
        l.setTextColor(INK);
        EditText e = input("", value, inputType, onChange);
        e.setGravity(Gravity.CENTER);
        e.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(e, new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private LinearLayout numberRow(String text, int value, String key, int min, int max) {
        return valueRow(text, String.valueOf(value), InputType.TYPE_CLASS_NUMBER, s -> {
            try {
                int v = Math.max(min, Math.min(max, Integer.parseInt(s.trim())));
                tickPrefs(ed -> ed.putInt(key, v));
            } catch (NumberFormatException ignored) {
            }
        });
    }

    private View pillButton(String text, boolean filled, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(22), dp(10), dp(22), dp(10));
        if (filled) {
            b.setTextColor(Color.WHITE);
            b.setBackground(rounded(ACCENT, dp(22), 0));
        } else {
            b.setTextColor(ACCENT);
            b.setBackground(rounded(ACCENT_SOFT, dp(22), 0));
        }
        b.setOnClickListener(onClick);
        return b;
    }

    private GradientDrawable rounded(int fill, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private interface PrefEdit {
        void apply(SharedPreferences.Editor e);
    }

    /** Saves a Checkbox Ticker setting and tells the running service. */
    private void tickPrefs(PrefEdit edit) {
        SharedPreferences.Editor e =
                getSharedPreferences(CheckboxService.PREFS, Context.MODE_PRIVATE).edit();
        edit.apply(e);
        e.apply();
        CheckboxService service = CheckboxService.Companion.getInstance();
        if (service != null) service.applySettings();
    }

    private static Integer parseColour(String text) {
        String t = text.trim().replace("#", "");
        if (t.length() != 6) return null;
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- State -------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = isServiceEnabled();
        accessibilityChip.setText((on ? "●  " : "○  ") + "Accessibility " + (on ? "on" : "off"));
        accessibilityChip.setBackground(rounded(on ? 0x33FFFFFF : 0x55D64545, dp(20), 0));
        boolean sharing = ScreenService.Companion.getInstance() != null;
        screenChip.setText((sharing ? "●  " : "○  ") + "Screen " + (sharing ? "shared" : "not shared"));
        screenChip.setBackground(rounded(sharing ? 0x33FFFFFF : 0x55D64545, dp(20), 0));
        String log = Keywords.loadLastRun(this);
        lastRun.setText(log.isEmpty() ? "No runs yet" : log.trim());
    }

    private void toggleSharing() {
        if (ScreenService.Companion.getInstance() != null) {
            stopService(new Intent(this, ScreenService.class));
            handler.postDelayed(this::refresh, 300);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_SHARE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SHARE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen sharing was not allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        startForegroundService(new Intent(this, ScreenService.class)
                .putExtra("code", resultCode)
                .putExtra("data", data));
        handler.postDelayed(this::refresh, 500);
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName() + "/");
    }
}
