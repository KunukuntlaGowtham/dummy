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

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(24), dp(20), dp(18));
        head.setBackgroundColor(GREEN);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);
        TextView how = new TextView(this);
        how.setText("Turn it on in Accessibility, open any app, then use the round buttons - all "
                + "work through accessibility, no screenshots. Green 🔍 Scan lists everything the app "
                + "reports on that screen, and what Tick, Clear and Book would find there: pop-ups "
                + "and the button that clears each, every checkbox with its row, the calendar "
                + "(month, open and closed days, arrows), the dropdown, radios and Continue. "
                + "Long-press it for a deep scan: web pages woken, every property of every element "
                + "and the app's own details, plus Whole page (scrolls to the end) and a 15 s "
                + "recording of the app's events. Purple ☑ Tick ticks every checkbox and clears "
                + "the pop-up after each; orange ✖ Clear clears the pop-ups up now; blue 📅 Book "
                + "picks the dropdown option, the date, the checkbox, the radio and Continue.");
        how.setTextColor(0xE6FFFFFF);
        how.setTextSize(13);
        how.setPadding(0, dp(6), 0, dp(12));
        head.addView(how);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(dp(14), dp(8), dp(14), dp(8));
        status.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        head.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(head);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(dp(12), dp(12), dp(12), dp(4));
        buttons.addView(pill("Share", v -> share()));
        buttons.addView(pill("Copy", v -> copy()));
        buttons.addView(pill("Save to Downloads", v -> save()));
        page.addView(buttons);

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
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
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
        lp.rightMargin = dp(8);
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
