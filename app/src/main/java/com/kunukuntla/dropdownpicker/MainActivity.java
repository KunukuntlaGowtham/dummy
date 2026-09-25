package com.kunukuntla.dropdownpicker;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Explains how to use the app and links to the accessibility settings. */
public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("1. Tap the button below and turn on \"Dropdown Picker\".\n\n"
                + "2. A round purple Start button and a green See button will float on screen. Drag them anywhere.\n\n"
                + "3. Open the page with the dropdowns and tap Start. The app takes a "
                + "screenshot, finds the dropdowns (an underline with a down arrow at its "
                + "right end) and goes through them one after another, top to bottom: "
                + "open it, select its first option, take a new screenshot, next one. "
                + "Tap Stop to stop early.\n\n"
                + "4. Tap the green See button (under Start) to see what the app sees: the "
                + "screenshot with each dropdown's underline (red) and arrow (green) marked. "
                + "Tap Share to save that picture to your Gallery (Pictures/DropdownPicker) "
                + "and send it. If it can't take a screenshot it tells you why.");
        help.setTextSize(16);
        help.setPadding(0, pad, 0, pad);
        root.addView(help);

        Button open = new Button(this);
        open.setText("Open accessibility settings");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(open);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, pad, 0, 0);
        root.addView(status);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText(isServiceEnabled() ? "Status: ON ✅" : "Status: OFF — enable it in settings");
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName() + "/");
    }
}
