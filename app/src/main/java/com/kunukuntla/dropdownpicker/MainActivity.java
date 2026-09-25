package com.kunukuntla.dropdownpicker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Setup screen: turn on the accessibility service, then share the screen,
 * then tap Start on the floating button.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_SHARE = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView accessibilityStatus;
    private TextView shareStatus;
    private Button shareButton;

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

        root.addView(text("Step 1 - Turn on the app\n"
                + "Tap below and turn on \"Dropdown Picker\". This lets it tap for you and "
                + "shows the floating Start and See buttons.", pad));
        Button open = new Button(this);
        open.setText("Open accessibility settings");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(open);
        accessibilityStatus = text("", 0);
        root.addView(accessibilityStatus);

        root.addView(text("Step 2 - Share your screen\n"
                + "Tap Share screen and choose \"Entire screen\" if asked, then Start now. "
                + "The app uses this to see the screen and find the dropdowns.", pad));
        shareButton = new Button(this);
        shareButton.setOnClickListener(v -> toggleSharing());
        root.addView(shareButton);
        shareStatus = text("", 0);
        root.addView(shareStatus);

        root.addView(text("Keywords (optional)\n"
                + "Words to look for in the options, separated by commas, most wanted first. "
                + "Start picks the option containing the first keyword it finds. If no option "
                + "matches, or this is empty, it picks the first option.", pad));
        EditText keywords = new EditText(this);
        keywords.setHint("e.g. Tirumala, Male Only");
        keywords.setSingleLine(true);
        keywords.setText(Keywords.load(this));
        keywords.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                Keywords.save(MainActivity.this, s.toString());
            }
        });
        root.addView(keywords, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(text("Step 3 - Start\n"
                + "Open the page with the dropdown and tap the purple Start button. The app "
                + "takes a screenshot, finds the dropdown (an underline with a down arrow at "
                + "its right end), taps it once and selects the option matching your "
                + "keywords, or else the first option it can read. "
                + "If it can't read any option, it taps half a centimetre below the line.\n\n"
                + "Tap the green See button to see what the app sees, with each dropdown's "
                + "underline (red) and arrow (green) marked. Share saves that picture to your "
                + "Gallery (Pictures/DropdownPicker) and sends it.", pad));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView text(String s, int topPad) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setPadding(0, topPad, 0, topPad / 2);
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        accessibilityStatus.setText(isServiceEnabled()
                ? "Status: ON ✅" : "Status: OFF - turn it on in settings");
        boolean sharing = ScreenCaptureService.isSharing();
        shareButton.setText(sharing ? "Stop sharing" : "Share screen");
        shareStatus.setText(sharing ? "Screen sharing: ON ✅" : "Screen sharing: OFF");
    }

    private void toggleSharing() {
        if (ScreenCaptureService.isSharing()) {
            startService(new Intent(this, ScreenCaptureService.class)
                    .setAction(ScreenCaptureService.ACTION_STOP));
            handler.postDelayed(this::refresh, 300);
            return;
        }
        // Let the "sharing your screen" notification show on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        // Android 9 and older need storage permission to save the shared picture.
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
        Intent service = new Intent(this, ScreenCaptureService.class)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
        startForegroundService(service);
        Toast.makeText(this, "Screen shared. Now open your page and tap Start.",
                Toast.LENGTH_LONG).show();
        handler.postDelayed(this::refresh, 500);
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName() + "/");
    }
}
