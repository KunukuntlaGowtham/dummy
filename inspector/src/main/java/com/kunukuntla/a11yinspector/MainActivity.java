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

    private static final int GREEN = 0xFF2E7D32;
    private TextView status;
    private TextView report;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(GREEN);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFFF4F6F4);

        // Head: the name, whether the service is on (tap: Accessibility settings), the tabs.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(22), dp(20), dp(12));
        head.setBackgroundColor(GREEN);
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
        GradientDrawable sheet = new GradientDrawable();
        sheet.setColor(Color.WHITE);
        sheet.setCornerRadius(dp(14));
        sheet.setStroke(dp(1), 0xFFDCE3DC);
        scroll.setBackground(sheet);
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
        ScrollView settingsTab = new ScrollView(this);
        settingsTab.addView(settings);

        // Tab 3: how to use, short.
        TextView help = new TextView(this);
        help.setText(HELP);
        help.setTextColor(0xFF22262A);
        help.setTextSize(14);
        help.setLineSpacing(dp(3), 1f);
        help.setPadding(dp(18), dp(14), dp(18), dp(24));
        ScrollView helpTab = new ScrollView(this);
        helpTab.addView(help);

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
                bg.setColor(on ? Color.WHITE : 0x33FFFFFF);
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

    private static final String[] SHOWN_NAMES = {"🔍 Scan", "☑ Tick", "✖ Clear", "📅 Book", "▶ Go", "🤖 Auto", "📋 Report", "🗑 Delete"};

    /**
     * Which round buttons are on the screen: one chip each, tap to show / hide it. The buttons
     * left out only go from the screen - everything they do stays (a linked Tick still runs).
     */
    private View shownChooser() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(note("Tap to show / hide. 🗑 shows only in test mode."));
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(6), 0, 0);
        android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        for (int i = 0; i < InspectorService.SHOWN_KEYS.length; i++) {
            if (InspectorService.SHOWN_KEYS[i].equals("delete")) continue;
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
                bg.setColor(on ? GREEN : 0xFFDDEEDD);
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
        dateLabel.setText("Date:");
        dateLabel.setTextColor(0xFF4A4F4A);
        dateLabel.setTextSize(13);
        dateRow.addView(dateLabel);
        android.widget.EditText date = new android.widget.EditText(this);
        date.setSingleLine(true);
        date.setText(sp.getString("book_date", ""));
        date.setHint("15/11/2026");
        date.setTextSize(14);
        date.setMinWidth(dp(140));
        date.setInputType(android.text.InputType.TYPE_CLASS_DATETIME | android.text.InputType.TYPE_DATETIME_VARIATION_DATE);
        TextView dateOk = new TextView(this);
        dateOk.setTextSize(12);
        dateOk.setPadding(dp(8), 0, 0, 0);
        date.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                String d = e.toString().trim();
                boolean ok = d.isEmpty() || Booker.parseDate(d, new Booker.Plan());
                if (ok) sp.edit().putString("book_date", d).apply();
                dateOk.setText(d.isEmpty() ? "no date: left as the page has it" : ok ? "✓ saved" : "type it like 15/11/2026");
                dateOk.setTextColor(ok ? GREEN : 0xFFC62828);
            }
        });
        dateRow.addView(date);
        dateRow.addView(dateOk);
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
                bg.setColor(on ? GREEN : 0xFFDDEEDD);
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
        retry.addView(settingChip(sp, "auto_delete_missed", "Rows not ticked: back, delete them, retry once",
                "Retry after rows not ticked"), chipLp());
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

    /** A titled white card with a coloured edge. */
    private View card(String title, int colour, View content) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(2), colour);
        c.setBackground(bg);
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
        bg.setColor(0xFF37474F);
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

    private static final String HELP =
            "1. Turn A11y Inspector on in Accessibility (tap the chip at the top).\n"
            + "2. Open the TTD app. The round buttons float on the left; drag them anywhere.\n\n"
            + "ROUND BUTTONS\n"
            + "🔍 Scan - what the screen has (long-press: deep scan + screenshot, to send me).\n"
            + "☑ Tick - ticks every sevak on page 3 and clears the pop-up after each.\n"
            + "✖ Clear - clears the pop-ups up now (long-press: Auto-clear on / off).\n"
            + "📅 Book - on page 2: choose the dropdown option (from the page's own list), the date and the slot, then Save.\n"
            + "▶ Go - fills page 2 with those saved choices (long-press: the same form as Book). On page 1 it presses Continue first; on page 3 (with 🤖 on) Tick starts at once.\n"
            + "🤖 Auto - on: Go → Tick → Continue → Confirm runs by itself (see Settings).\n"
            + "📋 Report - the last run's result; the full report is in this app.\n"
            + "🗑 Delete - only in test mode (Settings).\n\n"
            + "AT THE TOP OF THE SCREEN\n"
            + "📍 Page 1-4 - which page of the booking is open (🧪 TEST when test mode is on).\n"
            + "❌ Not added - the rows Tick couldn't add, numbered as after each delete.\n\n"
            + "THE PAGES\n"
            + "Page 1 - the sevaks list (the page before page 2).\n"
            + "Page 2 - dropdown, calendar, checkbox, slot.\n"
            + "Page 3 - the sevak rows to tick.\n"
            + "Page 4 - Confirm.\n\n"
            + "SENDING ME A PROBLEM\n"
            + "📋 Report → Share, or a deep scan (long-press 🔍) with its screenshot.";

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
            bg.setColor(on ? GREEN : 0xFFDDEEDD);
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
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(0xFFDDEEDD);
        b.setBackground(bg);
        b.setOnClickListener(onClick);
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
