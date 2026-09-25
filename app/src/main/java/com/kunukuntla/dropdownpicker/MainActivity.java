package com.kunukuntla.dropdownpicker;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.checkboxticker.CheckboxService;
import com.example.checkboxticker.ScreenService;

import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** One settings page for everything: setup, dropdown, calendar, tick. Changes save as you go. */
public class MainActivity extends Activity {

    private static final int REQUEST_SHARE = 1;
    private static final int ACCENT = 0xFF6A3FA0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView accessibilityState;
    private TextView shareState;
    private Button shareButton;
    private TextView lastRun;
    private int pad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(24), pad, dp(32));
        root.setBackgroundColor(0xFFF4F2F8);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF222222);
        title.setPadding(dp(4), 0, 0, dp(8));
        root.addView(title);

        // Setup
        LinearLayout setup = card(root, "Setup");
        accessibilityState = new TextView(this);
        shareState = new TextView(this);
        setup.addView(row(button("Accessibility", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))), accessibilityState));
        shareButton = button("Share screen", v -> toggleSharing());
        setup.addView(row(shareButton, shareState));

        // Dropdown
        LinearLayout drop = card(root, "Dropdown");
        drop.addView(field("Keywords", Keywords.load(this), InputType.TYPE_CLASS_TEXT,
                s -> Keywords.save(this, s)));
        drop.addView(radios(new String[] {"4 mm below", "Contains", "Exact"},
                new int[] {Keywords.PICK_BELOW, Keywords.PICK_CONTAINS, Keywords.PICK_EXACT},
                Keywords.loadPickMode(this), v -> Keywords.savePickMode(this, v)));

        // Calendar
        LinearLayout cal = card(root, "Calendar");
        cal.addView(field("Date (DD/MM/YYYY)", DayChoice.loadDate(this), InputType.TYPE_CLASS_DATETIME,
                s -> DayChoice.saveDate(this, s)));
        cal.addView(radios(new String[] {"Off", "Exact", "Best"},
                new int[] {DayChoice.MODE_OFF, DayChoice.MODE_EXACT, DayChoice.MODE_BEST},
                DayChoice.loadMode(this), v -> DayChoice.saveMode(this, v)));
        LinearLayout colours = new LinearLayout(this);
        Set<String> open = DayChoice.loadOpenColours(this);
        for (String[] c : new String[][] {{"GREEN", "Green"}, {"YELLOW", "Yellow"},
                {"GREY", "Grey"}, {"WHITE", "White"}}) {
            colours.addView(check(c[1], open.contains(c[0]),
                    on -> DayChoice.setOpenColour(this, c[0], on)));
        }
        cal.addView(colours);
        cal.addView(check("Then Available, checkbox, Continue", Keywords.loadFinish(this),
                on -> Keywords.saveFinish(this, on)));

        // Tick (Checkbox Ticker)
        SharedPreferences tp = getSharedPreferences(CheckboxService.PREFS, Context.MODE_PRIVATE);
        LinearLayout tick = card(root, "Tick");
        tick.addView(check("Clear the pop-up after each tick", tp.getBoolean("tapColour", true),
                on -> tickPrefs(e -> e.putBoolean("tapColour", on))));
        tick.addView(field("Pop-up button colour",
                String.format(Locale.ROOT, "#%06X", tp.getInt("colour", CheckboxService.DEFAULT_COLOUR)),
                InputType.TYPE_CLASS_TEXT, s -> {
                    Integer c = parseColour(s);
                    if (c != null) tickPrefs(e -> e.putInt("colour", c));
                }));
        tick.addView(numberRow("Max boxes", tp.getInt("maxBoxes", 15), "maxBoxes", 1, 500));
        tick.addView(numberRow("Wait after tick (ms)", tp.getInt("tickWaitMs", 300), "tickWaitMs", 0, 10000));
        tick.addView(numberRow("Wait after pop-up (ms)", tp.getInt("clearWaitMs", 300), "clearWaitMs", 0, 10000));
        tick.addView(numberRow("Wait after scroll (ms)", tp.getInt("scrollWaitMs", 300), "scrollWaitMs", 0, 10000));
        tick.addView(check("Show status line", tp.getBoolean("showStatus", true),
                on -> tickPrefs(e -> e.putBoolean("showStatus", on))));

        // Last run
        LinearLayout log = card(root, "Last run");
        lastRun = new TextView(this);
        lastRun.setTextSize(12);
        lastRun.setTextColor(0xFF444444);
        lastRun.setTextIsSelectable(true);
        log.addView(lastRun);
        log.addView(button("Share", v -> {
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Dropdown Picker last run:\n"
                            + Keywords.loadLastRun(this));
            startActivity(Intent.createChooser(send, "Share last run"));
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF4F2F8);
        scroll.addView(root);
        setContentView(scroll);
    }

    // ---- Building blocks ---------------------------------------------------------

    private LinearLayout card(LinearLayout parent, String heading) {
        TextView h = new TextView(this);
        h.setText(heading.toUpperCase(Locale.ROOT));
        h.setTextSize(12);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(ACCENT);
        h.setLetterSpacing(0.08f);
        h.setPadding(dp(4), dp(14), 0, dp(6));
        parent.addView(h);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        card.setElevation(dp(1));
        parent.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private LinearLayout row(View left, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(right, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (right instanceof TextView) ((TextView) right).setPadding(dp(12), 0, 0, 0);
        return row;
    }

    private Button button(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    private EditText field(String hint, String value, int inputType, Consumer<String> onChange) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setTextSize(15);
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

    private LinearLayout numberRow(String label, int value, String key, int min, int max) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(14);
        l.setTextColor(0xFF333333);
        EditText e = field("", String.valueOf(value), InputType.TYPE_CLASS_NUMBER, s -> {
            try {
                int v = Math.max(min, Math.min(max, Integer.parseInt(s.trim())));
                tickPrefs(ed -> ed.putInt(key, v));
            } catch (NumberFormatException ignored) {
            }
        });
        e.setGravity(Gravity.END);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(e, new LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private CheckBox check(String text, boolean on, Consumer<Boolean> onChange) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextSize(14);
        box.setChecked(on);
        box.setOnCheckedChangeListener((b, checked) -> onChange.accept(checked));
        return box;
    }

    private RadioGroup radios(String[] labels, int[] values, int saved, Consumer<Integer> onChange) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(labels[i]);
            rb.setTextSize(14);
            rb.setPadding(0, 0, dp(12), 0);
            int value = values[i];
            rb.setOnCheckedChangeListener((b, checked) -> {
                if (checked) onChange.accept(value);
            });
            group.addView(rb);
            if (value == saved) rb.setChecked(true);
        }
        return group;
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

    // ---- State ------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = isServiceEnabled();
        accessibilityState.setText(on ? "On" : "Off");
        accessibilityState.setTextColor(on ? 0xFF2E7D32 : 0xFFC62828);
        boolean sharing = ScreenService.Companion.getInstance() != null;
        shareButton.setText(sharing ? "Stop sharing" : "Share screen");
        shareState.setText(sharing ? "On" : "Off");
        shareState.setTextColor(sharing ? 0xFF2E7D32 : 0xFFC62828);
        String log = Keywords.loadLastRun(this);
        lastRun.setText(log.isEmpty() ? "-" : log.trim());
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
