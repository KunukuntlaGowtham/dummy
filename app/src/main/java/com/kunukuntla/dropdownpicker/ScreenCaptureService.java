package com.kunukuntla.dropdownpicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Keeps the screen shared (Android's "share your screen" / screen capture) so
 * the picker can look at the latest frame whenever it needs a screenshot.
 * Started from {@link MainActivity} after the user allows sharing.
 */
public class ScreenCaptureService extends Service {

    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";
    static final String ACTION_STOP = "com.kunukuntla.dropdownpicker.STOP_SHARING";

    private static final String CHANNEL_ID = "screen_share";
    private static final int NOTIFICATION_ID = 1;

    private static volatile ScreenCaptureService instance;

    private final Object frameLock = new Object();
    private Image latest;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private int width, height;

    static boolean isSharing() {
        return instance != null;
    }

    /** The latest frame of the shared screen, or null if not sharing or nothing arrived yet. */
    static Bitmap grab() {
        ScreenCaptureService s = instance;
        return s == null ? null : s.latestBitmap();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Android requires the foreground notification before the capture starts.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification());
        }

        release(); // in case sharing was started again while already running

        int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (RuntimeException e) {
            projection = null;
        }
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        thread = new HandlerThread("screen-share");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // The user stopped sharing from the notification or status bar.
                stopSelf();
            }
        }, handler);

        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        width = dm.widthPixels;
        height = dm.heightPixels;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(r -> {
            Image img;
            try {
                img = r.acquireLatestImage();
            } catch (IllegalStateException e) {
                return;
            }
            if (img == null) return;
            synchronized (frameLock) {
                if (latest != null) latest.close();
                latest = img;
            }
        }, handler);
        display = projection.createVirtualDisplay("DropdownPicker", width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        instance = this;
        return START_NOT_STICKY;
    }

    private Bitmap latestBitmap() {
        synchronized (frameLock) {
            if (latest == null) return null;
            try {
                Image.Plane plane = latest.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();
                buffer.rewind();
                int pixelStride = plane.getPixelStride();
                int rowWidth = plane.getRowStride() / pixelStride;
                Bitmap padded = Bitmap.createBitmap(rowWidth, latest.getHeight(), Bitmap.Config.ARGB_8888);
                padded.copyPixelsFromBuffer(buffer);
                if (rowWidth == latest.getWidth()) return padded;
                Bitmap exact = Bitmap.createBitmap(padded, 0, 0, latest.getWidth(), latest.getHeight());
                padded.recycle();
                return exact;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Screen sharing",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, ScreenCaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Dropdown Picker is sharing your screen")
                .setContentText("Tap Start on the floating button. Tap Stop sharing here when done.")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop sharing", stop).build())
                .setOngoing(true)
                .build();
    }

    private void release() {
        instance = null;
        if (display != null) display.release();
        display = null;
        MediaProjection p = projection;
        projection = null;
        if (p != null) p.stop();
        synchronized (frameLock) {
            if (latest != null) latest.close();
            latest = null;
        }
        if (reader != null) reader.close();
        reader = null;
        if (thread != null) thread.quitSafely();
        thread = null;
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }
}
