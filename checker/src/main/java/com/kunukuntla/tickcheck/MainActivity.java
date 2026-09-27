package com.kunukuntla.tickcheck;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** How to use it, whether the Check button is on, and the last check. */
public class MainActivity extends Activity {

    private static final int PURPLE = 0xFF6A2C91;
    private TextView status;
    private TextView last;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(PURPLE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFFF6F3F8);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(24), dp(20), dp(18));
        head.setBackgroundColor(PURPLE);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);
        TextView how = new TextView(this);
        how.setText("1. Turn it on in Accessibility (tap the chip below).\n"
                + "2. Open the page. Tick the boxes and close the pop-ups yourself; after every 3-4 "
                + "ticks tap the purple ☑+ Save button (4-5 times over the whole page).\n"
                + "3. At the end tap the green 📋 List button: the rows not ticked, from all the saves. "
                + "Copy numbers copies them; New list starts over.\n\n"
                + "Each Save takes a screenshot: the empty boxes on it (like the main app's Tick) and the numbers beside them. Nothing is tapped or changed. Android 11 or newer.");
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

        last = new TextView(this);
        last.setTextSize(15);
        last.setTextColor(0xFF22262A);
        last.setTextIsSelectable(true);
        last.setPadding(dp(18), dp(16), dp(18), dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(last);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = serviceOn();
        status.setText(on ? "●  Check button is on" : "○  Off - tap to turn on in Accessibility");
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(18));
        chip.setColor(on ? 0x33FFFFFF : 0x66D64545);
        status.setBackground(chip);
        String text = read();
        last.setText(text.isEmpty() ? "No check yet." : "Last check\n\n" + text);
    }

    private String read() {
        try (FileInputStream in = openFileInput(CheckService.RESULT_FILE)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return buf.toString(StandardCharsets.UTF_8.name());
        } catch (java.io.IOException e) {
            return "";
        }
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
