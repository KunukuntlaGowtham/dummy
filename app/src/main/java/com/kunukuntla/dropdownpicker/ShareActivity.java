package com.kunukuntla.dropdownpicker;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

import com.example.checkboxticker.ScreenService;

/**
 * An invisible screen that only shows Android's "share your screen" prompt, so sharing can be
 * switched on from the floating buttons without leaving the page being worked on.
 */
public class ShareActivity extends Activity {

    private static final int REQUEST_SHARE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ScreenService.Companion.getInstance() != null) {
            finish();
            return;
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_SHARE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SHARE) {
            if (resultCode == RESULT_OK && data != null) {
                startForegroundService(new Intent(this, ScreenService.class)
                        .putExtra("code", resultCode)
                        .putExtra("data", data));
                Toast.makeText(this, "Screen sharing on", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Screen sharing not started", Toast.LENGTH_SHORT).show();
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
