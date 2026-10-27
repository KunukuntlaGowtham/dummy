package com.kunukuntla.a11yinspector;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/** Asks Android once ("Start sharing?") and hands the answer to {@link ScreenShare}. No screen of its own. */
public final class ShareActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        if (mpm == null) {
            finish();
            return;
        }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), 1);
        } catch (RuntimeException e) {
            Toast.makeText(this, "This phone can't share its screen", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            Intent svc = new Intent(this, ScreenShare.class)
                    .putExtra(ScreenShare.EXTRA_CODE, resultCode)
                    .putExtra(ScreenShare.EXTRA_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
            Toast.makeText(this, "📺 Sharing on - pop-ups are found faster", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "📺 Not shared", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
