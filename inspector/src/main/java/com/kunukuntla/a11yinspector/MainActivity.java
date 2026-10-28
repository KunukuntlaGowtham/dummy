package com.kunukuntla.a11yinspector;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** The app's own screen: whether the Scan button is on, and the last scan's full report. */
public class MainActivity extends Activity {

    /** The accent (iOS indigo) - the name kept from the green it was. */
    private static final int GREEN = Glass.ACCENT;
    /** An unchosen chip: clear glass tinted with the accent. */
    private static final int CHIP_OFF = 0x1F5856D6;
    private TextView status;
    private Runnable refreshDate;
    private TextView report;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF3A2FA8);
        getWindow().setNavigationBarColor(0xFF3D8BD9);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Glass.wallpaper()); // glass panels sit on an indigo-blue wash

        // Head: the name, whether the service is on (tap: Accessibility settings), the tabs.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(22), dp(20), dp(12));
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(dp(14), dp(8), dp(14), dp(8));
        status.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(8);
        head.addView(status, stLp);
        LinearLayout tabs = new LinearLayout(this);
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsLp.topMargin = dp(14);
        head.addView(tabs, tabsLp);
        page.addView(head);

        // Tab 1: the last run's report, with Share / Copy / Save.
        LinearLayout reportTab = new LinearLayout(this);
        reportTab.setOrientation(LinearLayout.VERTICAL);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(dp(12), dp(12), dp(12), dp(2));
        buttons.addView(pill("Share", v -> share()));
        buttons.addView(pill("Copy", v -> copy()));
        buttons.addView(pill("Save to Downloads", v -> save()));
        reportTab.addView(buttons);
        report = new TextView(this);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextSize(11);
        report.setTextColor(0xFF22262A);
        report.setTextIsSelectable(true);
        report.setPadding(dp(14), dp(12), dp(14), dp(24));
        HorizontalScrollView wide = new HorizontalScrollView(this);
        wide.addView(report);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(wide);
        scroll.setBackground(Glass.lightCard(getResources().getDisplayMetrics().density));
        scroll.setClipToOutline(true);
        LinearLayout.LayoutParams sheetLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        sheetLp.setMargins(dp(12), dp(10), dp(12), dp(12));
        reportTab.addView(scroll, sheetLp);

        // Tab 2: settings - which buttons show, the Auto flow, test mode - as cards.
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(0, dp(4), 0, dp(24));
        settings.addView(card("Round buttons on the screen", 0xFF2E7D32, shownChooser()));
        settings.addView(card("🤖 Auto flow (the 🤖 button turns it on / off)", 0xFF37474F, autoFlow()));
        settings.addView(card("🧪 Test mode", 0xFFC62828, testMode()));
        settings.addView(card("📱 Phone setup", 0xFF1565C0, phoneSetup()));
        ScrollView settingsTab = new ScrollView(this);
        settingsTab.addView(settings);

        // Tab 3: how to use - the flows drawn step by step, and every button.
        ScrollView helpTab = new ScrollView(this);
        helpTab.addView(helpGuide());

        View[] panes = {reportTab, settingsTab, helpTab};
        String[] names = {"📋 Report", "⚙ Settings", "❓ Help"};
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        for (View v : panes) {
            body.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        android.content.SharedPreferences ui = getSharedPreferences("settings", MODE_PRIVATE);
        TextView[] tabViews = new TextView[panes.length];
        Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            int open = ui.getInt("tab", 0);
            for (int i = 0; i < panes.length; i++) {
                boolean on = i == open;
                panes[i].setVisibility(on ? View.VISIBLE : View.GONE);
                tabViews[i].setTextColor(on ? GREEN : Color.WHITE);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(18));
                bg.setColor(on ? 0xF2FFFFFF : 0x2EFFFFFF);
                if (!on) bg.setStroke(dp(1), 0x59FFFFFF);
                tabViews[i].setBackground(bg);
            }
        };
        for (int i = 0; i < panes.length; i++) {
            int k = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextSize(13);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(6), dp(9), dp(6), dp(9));
            t.setOnClickListener(v -> {
                ui.edit().putInt("tab", k).apply();
                paint[0].run();
            });
            tabViews[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(i == 0 ? 0 : dp(4), 0, i == panes.length - 1 ? 0 : dp(4), 0);
            tabs.addView(t, lp);
        }
        paint[0].run();
        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (refreshDate != null) refreshDate.run();
        boolean on = serviceOn();
        status.setText(on ? "●  Scan button is on" : "○  Off - tap to turn on in Accessibility");
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(18));
        chip.setColor(on ? 0x33FFFFFF : 0x66D64545);
        status.setBackground(chip);
        String text = lastReport();
        report.setText(text.isEmpty() ? "No scan yet." : text);
    }

    private String lastReport() {
        try (FileInputStream in = openFileInput(InspectorService.REPORT_FILE)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return buf.toString(StandardCharsets.UTF_8.name());
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private void share() {
        String text = lastReport();
        if (text.isEmpty()) {
            Toast.makeText(this, "No scan yet", Toast.LENGTH_SHORT).show();
            return;
        }
        // Very long reports go as a file (text shares have size limits).
        if (text.length() > 90000) {
            Uri uri = saveFile(text);
            if (uri == null) return;
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri("report", uri));
            startActivity(Intent.createChooser(send, "Share the scan"));
            return;
        }
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text), "Share the scan"));
    }

    private void copy() {
        String text = lastReport();
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("A11y scan", text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void save() {
        String text = lastReport();
        if (text.isEmpty()) {
            Toast.makeText(this, "No scan yet", Toast.LENGTH_SHORT).show();
            return;
        }
        if (saveFile(text) != null) {
            Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_LONG).show();
        }
    }

    /** Writes the report into Downloads (Android 10+); returns its address, or null. */
    private Uri saveFile(String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Saving needs Android 10 or newer - use Share or Copy",
                    Toast.LENGTH_LONG).show();
            return null;
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, "a11y-scan-" + System.currentTimeMillis() + ".txt");
        v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return null;
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            return uri;
        } catch (java.io.IOException e) {
            Toast.makeText(this, "Couldn't save: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private static final String[] SHOWN_NAMES = {"☑ Tick", "📅 Book", "▶ Go", "🤖 Auto", "🗑 Delete"};

    /**
     * Which round buttons are on the screen: one chip each, tap to show / hide it. The buttons
     * left out only go from the screen - everything they do stays (a linked Tick still runs).
     */
    private View shownChooser() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(note("Tap to show / hide."));
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(6), 0, 0);
        android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        for (int i = 0; i < InspectorService.SHOWN_KEYS.length; i++) {
            String key = "show_" + InspectorService.SHOWN_KEYS[i];
            String name = SHOWN_NAMES[i];
            TextView chip = new TextView(this);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextSize(13);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            Runnable paint = () -> {
                boolean on = sp.getBoolean(key, true);
                chip.setText((on ? "✓ " : "") + name);
                chip.setTextColor(on ? Color.WHITE : GREEN);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(18));
                bg.setColor(on ? GREEN : CHIP_OFF);
                chip.setBackground(bg);
                chip.setContentDescription(name + (on ? ", shown" : ", hidden"));
            };
            paint.run();
            chip.setOnClickListener(v -> {
                sp.edit().putBoolean(key, !sp.getBoolean(key, true)).apply();
                paint.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(10);
            row.addView(chip, lp);
        }
        // Not a round button: the 📍 badge at the top saying which page of the booking is open.
        row.addView(settingChip(sp, "show_page", "📍 Page", "Page badge"), chipLp());
        HorizontalScrollView wide = new HorizontalScrollView(this);
        wide.setHorizontalScrollBarEnabled(false);
        wide.addView(row);
        box.addView(wide);
        return box;
    }

    /** The Auto flow as steps, each with its setting next to it. */
    private View autoFlow() {
        android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(note("With 🤖 on, one press of ▶ Go runs the booking through:"));
        box.addView(step("Page 2", "▶ Go fills the dropdown, the date, the checkbox and the slot, then Continue."));
        // Go's saved choices, here too: the date can be typed here; all three by long-pressing ▶ Go.
        TextView saved = note("");
        saved.setPadding(dp(16), dp(4), 0, 0);
        Runnable showSaved = () -> {
            String opt = sp.getString("book_option", ""), radio = sp.getString("book_radio", "");
            saved.setText("Option: " + (opt.isEmpty() ? "-" : opt) + "\nSlot: " + (radio.isEmpty() ? "the first" : radio)
                    + "\n(choose them from the page's own list: 📅 Book on page 2)");
        };
        showSaved.run();
        box.addView(saved);
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        dateRow.setPadding(dp(16), dp(2), 0, 0);
        TextView dateLabel = new TextView(this);
        dateLabel.setText("Date: ");
        dateLabel.setTextColor(0xFF4A4F4A);
        dateLabel.setTextSize(13);
        dateRow.addView(dateLabel);
        // The date picked on a calendar (no typing): tap the pill.
        TextView datePill = (TextView) pill("", null);
        Runnable showDate = () -> {
            String d = sp.getString("book_date", "");
            datePill.setText(d.isEmpty() ? "📅 Pick a date" : "📅 " + d);
        };
        refreshDate = showDate; // shown afresh when you come back (📅 Book may have changed it)
        showDate.run();
        datePill.setOnClickListener(v -> {
            java.util.Calendar c = java.util.Calendar.getInstance();
            Booker.Plan p = new Booker.Plan();
            if (Booker.parseDate(sp.getString("book_date", ""), p)) c.set(p.year, p.month - 1, p.day);
            android.app.DatePickerDialog dlg = new android.app.DatePickerDialog(this, (view, y, m, d) -> {
                sp.edit().putString("book_date", d + "/" + (m + 1) + "/" + y).apply();
                showDate.run();
            }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH));
            dlg.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
            dlg.show();
        });
        TextView clear = new TextView(this);
        clear.setText("✕ no date");
        clear.setTextColor(0xFF6A6F6A);
        clear.setTextSize(12);
        clear.setPadding(dp(4), dp(8), dp(8), dp(8));
        clear.setOnClickListener(v -> {
            sp.edit().putString("book_date", "").apply();
            showDate.run();
        });
        dateRow.addView(datePill);
        dateRow.addView(clear);
        box.addView(dateRow);
        TextView dateHead = note("If the date isn't open (green / yellow), take:");
        dateHead.setPadding(dp(16), dp(4), 0, 0);
        box.addView(dateHead);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(16), dp(6), 0, dp(4));
        java.util.List<Runnable> paints = new java.util.ArrayList<>();
        for (String mode : DayPick.MODES) {
            TextView chip = new TextView(this);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextSize(13);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            Runnable paint = () -> {
                boolean on = mode.equals(sp.getString("date_fallback", DayPick.WEEK));
                chip.setText((on ? "● " : "") + DayPick.label(mode));
                chip.setTextColor(on ? Color.WHITE : GREEN);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(18));
                bg.setColor(on ? GREEN : CHIP_OFF);
                chip.setBackground(bg);
                chip.setContentDescription(DayPick.label(mode) + (on ? ", chosen" : ""));
            };
            paints.add(paint);
            chip.setOnClickListener(v -> {
                sp.edit().putString("date_fallback", mode).apply();
                for (Runnable r : paints) r.run();
            });
            row.addView(chip, chipLp());
        }
        for (Runnable r : paints) r.run();
        HorizontalScrollView wide = new HorizontalScrollView(this);
        wide.setHorizontalScrollBarEnabled(false);
        wide.addView(row);
        box.addView(wide);
        box.addView(step("Page 3", "☑ Tick starts 1 s later and ticks every sevak. All ticked: Continue."));
        LinearLayout retry = new LinearLayout(this);
        retry.setPadding(dp(16), dp(6), 0, dp(4));
        retry.addView(settingChip(sp, "auto_delete_missed", "🤖 After every Tick: rows not ticked - back, delete, retry once",
                "Auto deletes rows not ticked", false), chipLp());
        HorizontalScrollView wide2 = new HorizontalScrollView(this);
        wide2.setHorizontalScrollBarEnabled(false);
        wide2.addView(retry);
        box.addView(wide2);
        box.addView(step("Page 4", "Confirm is pressed the moment it shows."));
        return box;
    }

    /** Test mode: the trip back to page 1 and forward, with numbers to delete (0 = none). */
    private View testMode() {
        android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(settingChip(sp, "test_mode", "🧪 Test mode", "Test mode", false), chipLp());
        TextView label = new TextView(this);
        label.setText("Delete:");
        label.setTextColor(0xFF4A4F4A);
        label.setTextSize(13);
        row.addView(label);
        android.widget.EditText numbers = new android.widget.EditText(this);
        numbers.setSingleLine(true);
        numbers.setText(sp.getString("test_delete", "0"));
        numbers.setHint("0 or 2, 5");
        numbers.setTextSize(14);
        numbers.setMinWidth(dp(110));
        numbers.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        numbers.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                sp.edit().putString("test_delete", e.toString().trim()).apply();
            }
        });
        row.addView(numbers);
        box.addView(row);
        box.addView(note("• On: the red 🗑 button shows, and the page badge says 🧪 TEST."));
        box.addView(note("• After the next Tick (🤖 on or off), it goes back to page 1, deletes these numbers (0 = none), "
                + "then forward: page 2's checkbox, Continue, page 3's Continue - and stops on page 4: "
                + "a test never presses Confirm. Then test mode turns itself off."));
        box.addView(note("• 🗑 tap: the same trip now. 🗑 long-press: only delete, here on page 1."));
        box.addView(note("• Numbers go in order, each as numbered on screen at its turn (2, 5: 2, then whoever is 5 after)."));
        return box;
    }

    /** What other phones may need: battery, restricted settings, and what older Android can't do. */
    private View phoneSetup() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            TextView old = note("⚠ Android " + Build.VERSION.RELEASE + ": screenshots need Android 11+. Hidden "
                    + "pop-ups are cleared only at their remembered OK place, and Go can't see green / yellow days.");
            old.setTextColor(0xFFC62828);
            box.addView(old);
        }
        box.addView(note("• Buttons disappear after a while (Xiaomi, Redmi, POCO, Oppo, Realme, Vivo, OnePlus): "
                + "set Battery to No restrictions and allow Autostart."));
        box.addView(note("• Android 13+: the Accessibility switch is greyed out (\"Restricted setting\"): App info → ⋮ → "
                + "Allow restricted settings."));
        box.addView(note("• Dark mode or a vivid screen colour setting can change colours: if Go picks the wrong "
                + "day, its 📋 Report lists each day's colour."));
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(8), 0, 0);
        row.addView(pill("Battery settings", v -> open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))));
        row.addView(pill("App info", v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())))));
        HorizontalScrollView wide = new HorizontalScrollView(this);
        wide.setHorizontalScrollBarEnabled(false);
        wide.addView(row);
        box.addView(wide);
        return box;
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(this, "This phone has no such settings screen", Toast.LENGTH_SHORT).show();
        }
    }

    /** A titled frosted card; its title in the card's colour. */
    private View card(String title, int colour, View content) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(Glass.lightCard(getResources().getDisplayMetrics().density));
        c.setElevation(dp(4));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(colour);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(15);
        t.setPadding(0, 0, 0, dp(6));
        c.addView(t);
        c.addView(content);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(10), dp(12), 0);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF4A4F4A);
        t.setTextSize(13);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    /** "Page 3  ☑ Tick ..." - a step of the flow. */
    private View step(String page, String what) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(8), 0, dp(2));
        TextView p = new TextView(this);
        p.setText(page);
        p.setTextColor(Color.WHITE);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(12);
        p.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(GREEN);
        p.setBackground(bg);
        r.addView(p);
        TextView w = new TextView(this);
        w.setText(what);
        w.setTextColor(0xFF22262A);
        w.setTextSize(13);
        w.setPadding(dp(10), 0, 0, 0);
        r.addView(w, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    // ---- ❓ Help: the flows, drawn step by step, and every button ------------------------

    private static final int PAGE1 = 0xFF1565C0, PAGE2 = 0xFF00897B, PAGE3 = 0xFF6A2C91, PAGE4 = 0xFFC62828;

    private View helpGuide() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(0, dp(4), 0, dp(24));

        LinearLayout start = new LinearLayout(this);
        start.setOrientation(LinearLayout.VERTICAL);
        start.addView(numbered(1, "Turn A11y Inspector on in Accessibility (the chip at the top of this app)."));
        start.addView(numbered(2, "⚙ Settings → 🤖 Auto flow: choose page 2's option, date and slot (or 📅 Book on page 2)."));
        start.addView(numbered(3, "Open the TTD app. The round buttons float at the side - drag them anywhere."));
        start.addView(numbered(4, "On the sevaks list, long-press 🤖 once: \"this is page 1\" (locked - Back never goes past it)."));
        start.addView(numbered(5, "Tap ▶ Go on any page - it runs the rest by itself."));
        h.addView(card("🚀 Getting started", 0xFF2E7D32, start));

        LinearLayout flow = new LinearLayout(this);
        flow.setOrientation(LinearLayout.VERTICAL);
        flow.addView(note("One tap on ▶ Go runs it all from the page you're on (🤖 Auto on or off):"));
        flow.addView(flowStep(PAGE1, "Page 1 · Sevaks list", "▶ Go presses Continue"));
        flow.addView(flowArrow());
        flow.addView(flowStep(PAGE2, "Page 2 · Calendar & slot", "Go picks the option, the date, the slot, ticks \"I hereby declare\" once, then Continue"));
        flow.addView(flowArrow());
        flow.addView(flowStep(PAGE3, "Page 3 · Ticking sevaks", "☑ Tick starts 1 s later, ticks every sevak and clears each pop-up. All ticked → Continue"));
        flow.addView(flowArrow());
        flow.addView(flowStep(PAGE4, "Page 4 · Confirm", "Confirm is pressed the moment it shows"));
        flow.addView(note("Rows Tick couldn't add: back to page 1, deleted, forward - by itself.\n■ Stop (tap Go while it runs) or a step that fails: do that step by hand, then tap ▶ Go again - it goes on from the page you're on."));
        h.addView(card("🤖 A booking, start to end", 0xFF37474F, flow));

        LinearLayout manual = new LinearLayout(this);
        manual.setOrientation(LinearLayout.VERTICAL);
        manual.addView(note("👆 TAP = manual. ☑ Tick only ticks and remembers who it couldn't add; you go back and 🗑 deletes them:"));
        manual.addView(flowStep(PAGE3, "Page 3 · you tap ☑ Tick", "it only ticks. Rows not ticked go in the red ❌ box, e.g. 3, 7, 11"));
        manual.addView(flowArrow("you go Back to page 1"));
        manual.addView(flowStep(PAGE1, "Page 1 · you tap 🗑 Delete", "deletes exactly those people: 3, then 6 (old 7), then 9 (old 11) - each checked by its name. Nothing else is pressed"));
        manual.addView(flowArrow("you go on"));
        manual.addView(flowStep(PAGE2, "Page 2 → 3 → 4", "▶ Go, ☑ Tick ... as you like"));
        manual.addView(note("🗑 tapped on page 2 or 3: it says to go back first."));
        h.addView(card("👆 Manual: tap ☑ Tick, then tap 🗑", 0xFF6A2C91, manual));

        LinearLayout retry = new LinearLayout(this);
        retry.setOrientation(LinearLayout.VERTICAL);
        retry.addView(note("✋ LONG-PRESS = automatic. Long-press ☑ Tick (or long-press 🗑 after a Tick) and it all runs by itself:"));
        retry.addView(flowStep(PAGE3, "Page 3 · you long-press ☑ Tick", "it ticks; rows not ticked go in the red ❌ box, e.g. rows 3, 7, 11"));
        retry.addView(flowArrow("Back"));
        retry.addView(flowStep(PAGE2, "Page 2", "passed straight through"));
        retry.addView(flowArrow("Back"));
        retry.addView(flowStep(PAGE1, "Page 1", "🗑 deletes them one by one: 3, then 6 (old 7), then 9 (old 11) - each checked by its name. Then Continue"));
        retry.addView(flowArrow());
        retry.addView(flowStep(PAGE2, "Page 2", "only the checkbox (ticked once), then Continue"));
        retry.addView(flowArrow());
        retry.addView(flowStep(PAGE3, "Page 3", "nothing to tick - Continue at once"));
        retry.addView(flowArrow());
        retry.addView(flowStep(PAGE4, "Page 4", "Confirm at once"));
        retry.addView(note("All rows ticked: nothing to delete - with 🤖 Auto on, Continue → Confirm as usual. ⚙ Settings can make 🤖 Auto do this trip after every Tick."));
        h.addView(card("✋ Automatic: long-press ☑ Tick", 0xFFC62828, retry));

        LinearLayout test = new LinearLayout(this);
        test.setOrientation(LinearLayout.VERTICAL);
        test.addView(note("Try the trip back and forward without booking:"));
        test.addView(flowStep(0xFF455A64, "⚙ Settings", "🧪 Test mode on, type the numbers to delete (\"2,5\"; 0 = delete nobody)"));
        test.addView(flowArrow());
        test.addView(flowStep(PAGE3, "Page 3", "☑ Tick (or 🗑 Delete) - the trip starts by itself"));
        test.addView(flowArrow("Back, Back"));
        test.addView(flowStep(PAGE1, "Page 1", "deletes your numbers (2, then whoever is 5 now), Continue"));
        test.addView(flowArrow());
        test.addView(flowStep(PAGE2, "Page 2 → 3", "checkbox, Continue, Continue"));
        test.addView(flowArrow());
        test.addView(flowStep(PAGE4, "Page 4", "Confirm is NOT pressed - left for you. Test mode turns off"));
        h.addView(card("🧪 Test mode", 0xFFC62828, test));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.addView(buttonHelp(0x6A2C91, "☑", "Tick", "page 3",
                "MANUAL - ticks every sevak and clears the pop-up after each; rows it couldn't add go in the red ❌ box. Tap again: stop",
                "AUTOMATIC - ticks, then rows not added → back to page 1, delete them, forward to page 4 by itself"));
        buttons.addView(buttonHelp(0x1565C0, "📅", "Book", "page 2",
                "a form: the option (from the page's own list), the date (calendar) and the slot - Save fills the page",
                null));
        buttons.addView(buttonHelp(0x00897B, "▶", "Go", "page 1, 2 or 3",
                "FULLY AUTOMATIC from the page you're on - page 1: Continue, page 2: fills it, page 3: Tick (missed rows: back, delete, forward), page 4: Confirm. While it runs (■ Stop): tap to stop; do a step by hand, tap ▶ Go to go on",
                "the same form as 📅 Book"));
        buttons.addView(buttonHelp(0x37474F, "🤖", "Auto", "any page",
                "Auto on / off: Go → Tick → Continue → Confirm by itself",
                "\"this page is page 1\" (locked) - pages 2, 3, 4 follow in order, and Back never goes past page 1"));
        buttons.addView(buttonHelp(0xC62828, "🗑", "Delete", "page 1",
                "MANUAL (on page 1) - deletes the people the last Tick couldn't add (the red ❌ box; test mode: its numbers), nothing else pressed",
                "AUTOMATIC (any page) - back to page 1, delete them, forward to page 4 by itself"));
        h.addView(card("🔘 The round buttons", 0xFF2E7D32, buttons));

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.addView(note("📍 Page 1-4 - the pill at the top: which page is open (🧪 TEST in test mode)."));
        screen.addView(note("❌ Not added - the red box: the rows Tick couldn't add, numbered as they will be after each delete."));
        h.addView(card("👀 On the screen", 0xFF1565C0, screen));

        LinearLayout problem = new LinearLayout(this);
        problem.setOrientation(LinearLayout.VERTICAL);
        problem.addView(numbered(1, "Right after it goes wrong: open this app → 📋 Report."));
        problem.addView(numbered(2, "Share that report (Book, Tick, Delete or Auto retry)."));
        h.addView(card("🛠 Something wrong?", 0xFF455A64, problem));
        return h;
    }

    /** A page of a flow: its coloured pill and what happens there. */
    private View flowStep(int colour, String page, String what) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(12), dp(8), dp(12), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor((colour & 0x00FFFFFF) | 0x1F000000);
        bg.setStroke(dp(1), (colour & 0x00FFFFFF) | 0x66000000);
        r.setBackground(bg);
        TextView p = new TextView(this);
        p.setText(page);
        p.setTextColor(Color.WHITE);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(12);
        p.setPadding(dp(9), dp(3), dp(9), dp(3));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(10));
        pill.setColor(colour | 0xFF000000);
        p.setBackground(pill);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        r.addView(p, plp);
        TextView w = new TextView(this);
        w.setText(what);
        w.setTextColor(0xFF22262A);
        w.setTextSize(13);
        w.setLineSpacing(dp(2), 1f);
        w.setPadding(dp(2), dp(5), 0, 0);
        r.addView(w);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        r.setLayoutParams(lp);
        return r;
    }

    private View flowArrow() {
        return flowArrow(null);
    }

    /** ↓ between two steps, with what takes it there ("Back"). */
    private View flowArrow(String how) {
        TextView a = new TextView(this);
        a.setText(how == null ? "↓" : "↓  " + how);
        a.setTextColor(0xFF5F6368);
        a.setTypeface(Typeface.DEFAULT_BOLD);
        a.setTextSize(15);
        a.setPadding(dp(22), dp(2), 0, 0);
        return a;
    }

    /** "1  Turn it on ..." - a numbered step. */
    private View numbered(int n, String text) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(6), 0, dp(2));
        TextView num = new TextView(this);
        num.setText(String.valueOf(n));
        num.setTextColor(Color.WHITE);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setTextSize(12);
        num.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(GREEN);
        num.setBackground(bg);
        r.addView(num, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF22262A);
        t.setTextSize(13);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(dp(10), dp(2), 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    /** A round button as it floats on the screen, with where it works, its tap and its long-press. */
    private View buttonHelp(int colour, String icon, String name, String where, String tap, String longPress) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(10), 0, dp(8));
        TextView b = new TextView(this);
        b.setText(icon + "\n" + name);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(10.5f);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Glass.button(colour, getResources().getDisplayMetrics().density));
        r.addView(b, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText(name + "  ·  " + where);
        title.setTextColor(colour | 0xFF000000);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(14);
        text.addView(title);
        text.addView(note("👆 Tap: " + tap));
        if (longPress != null) text.addView(note("✋ Long-press: " + longPress));
        r.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }


    /** A chip turning a setting (on until turned off) on / off. */
    private TextView settingChip(android.content.SharedPreferences sp, String key, String name, String spoken) {
        return settingChip(sp, key, name, spoken, true);
    }

    private TextView settingChip(android.content.SharedPreferences sp, String key, String name, String spoken,
                                 boolean byDefault) {
        TextView chip = new TextView(this);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextSize(13);
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        Runnable paint = () -> {
            boolean on = sp.getBoolean(key, byDefault);
            chip.setText((on ? "✓ " : "") + name);
            chip.setTextColor(on ? Color.WHITE : GREEN);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(on ? GREEN : CHIP_OFF);
            chip.setBackground(bg);
            chip.setContentDescription(spoken + (on ? ", on" : ", off"));
        };
        paint.run();
        chip.setOnClickListener(v -> {
            sp.edit().putBoolean(key, !sp.getBoolean(key, byDefault)).apply();
            paint.run();
        });
        return chip;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        return lp;
    }

    private View pill(String label, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(GREEN);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(9), dp(16), dp(9));
        // A white glass pill: readable on the wash and inside the cards.
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(0xE6FFFFFF);
        bg.setStroke(dp(1), 0xFFFFFFFF);
        b.setBackground(bg);
        if (onClick != null) b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private boolean serviceOn() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        TextUtils.SimpleStringSplitter split = new TextUtils.SimpleStringSplitter(':');
        split.setString(enabled);
        while (split.hasNext()) {
            if (split.next().startsWith(getPackageName() + "/")) return true;
        }
        return false;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
