package com.kunukuntla.a11yinspector;

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
import android.os.Looper;
import android.util.DisplayMetrics;

import java.nio.ByteBuffer;

/**
 * 📺 Share: the screen shared with the app while it's on (Android's "Start sharing?"), so the
 * pop-ups' buttons are found on the live screen at once - no screenshot, no ~3-a-second limit.
 * Only while you have it on: tap 📺 again, or Stop in its notification, and it ends - nothing
 * is kept. Without it, screenshots are used as before.
 */
public final class ScreenShare extends Service {

    static final String EXTRA_CODE = "code", EXTRA_DATA = "data", ACTION_STOP = "stop";
    private static final String CHANNEL = "share";

    private static volatile boolean on;
    private static final Object LOCK = new Object();
    private static ImageReader reader;
    /** The newest frame of the screen, held (one at a time) until a newer one comes. */
    private static Image held;
    private static int width, height;
    /** Told on the main thread when sharing starts or stops (the 📺 button's look). */
    static Runnable onChange;

    private MediaProjection projection;
    private VirtualDisplay display;
    private HandlerThread thread;

    static boolean active() {
        return on;
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, ScreenShare.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Android 14+: the notification first, then the screen.
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(7, n);
        }
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        if (mpm == null || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (RuntimeException e) {
            projection = null;
        }
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        thread = new HandlerThread("share");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf(); // stopped by the phone (or its own "Stop sharing")
            }
        }, h);
        DisplayMetrics dm = new DisplayMetrics();
        getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(dm);
        width = dm.widthPixels;
        height = dm.heightPixels;
        ImageReader r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        r.setOnImageAvailableListener(ir -> {
            Image img;
            try {
                img = ir.acquireLatestImage();
            } catch (RuntimeException e) {
                return;
            }
            if (img == null) return;
            synchronized (LOCK) {
                if (held != null) held.close();
                held = img; // only the newest is kept: two left for the screen to draw into
            }
        }, h);
        synchronized (LOCK) {
            reader = r;
        }
        try {
            display = projection.createVirtualDisplay("gowtham-share", width, height, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.getSurface(), null, h);
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        on = true;
        changed();
        return START_NOT_STICKY;
    }

    /** The screen as it is now (the newest frame), or null when there is none yet. */
    static Bitmap frame() {
        synchronized (LOCK) {
            if (!on || held == null) return null;
            try {
                Image.Plane p = held.getPlanes()[0];
                ByteBuffer buf = p.getBuffer();
                buf.rewind();
                int pixel = p.getPixelStride(), rowW = p.getRowStride() / pixel;
                Bitmap wide = Bitmap.createBitmap(rowW, held.getHeight(), Bitmap.Config.ARGB_8888);
                wide.copyPixelsFromBuffer(buf);
                if (rowW == held.getWidth()) return wide;
                Bitmap exact = Bitmap.createBitmap(wide, 0, 0, held.getWidth(), held.getHeight());
                wide.recycle();
                return exact;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen shared with Gowtham",
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, ScreenShare.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("📺 Screen shared with Gowtham")
                .setContentText("Pop-ups are found faster. Tap Stop (or 📺) to end it.")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    @Override
    public void onDestroy() {
        on = false;
        synchronized (LOCK) {
            if (held != null) held.close();
            held = null;
            if (reader != null) reader.close();
            reader = null;
        }
        if (display != null) display.release();
        if (projection != null) {
            try {
                projection.stop();
            } catch (RuntimeException ignored) {
            }
        }
        if (thread != null) thread.quitSafely();
        changed();
        super.onDestroy();
    }

    private static void changed() {
        Runnable r = onChange;
        if (r != null) new Handler(Looper.getMainLooper()).post(r);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
