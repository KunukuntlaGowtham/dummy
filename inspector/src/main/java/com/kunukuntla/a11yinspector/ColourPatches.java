package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Takes an accessibility screenshot and finds every patch of one colour on it (the pop-up's
 * purple button, and anything else that colour), in screen pixels. Android 11+.
 */
final class ColourPatches {

    /** The pop-up button's purple (same as the main app's default). */
    static final int PURPLE = 0x663398;
    private static final int TOLERANCE = 60;   // sum of r, g, b differences
    private static final int STEP = 3;         // work on a 1/3 size copy
    private static final int MIN_CELLS = 40;   // smaller specks are ignored

    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private long lastShot;

    ColourPatches(AccessibilityService service) {
        this.service = service;
    }

    /** Patches of {@link #PURPLE}, or null when no screenshot could be taken. */
    void find(Consumer<List<Rect>> done) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            main.post(() -> done.accept(null));
            return;
        }
        long wait = Math.max(0, lastShot + 350 - android.os.SystemClock.uptimeMillis());
        main.postDelayed(() -> take(done, 5), wait);
    }

    private void take(Consumer<List<Rect>> done, int triesLeft) {
        lastShot = android.os.SystemClock.uptimeMillis();
        service.takeScreenshot(Display.DEFAULT_DISPLAY, worker,
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        List<Rect> patches = null;
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        try {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                            if (hw != null) patches = patches(hw);
                        } catch (RuntimeException ignored) {
                        } finally {
                            buffer.close();
                        }
                        List<Rect> out = patches;
                        main.post(() -> done.accept(out));
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        if (triesLeft > 0) {
                            main.postDelayed(() -> take(done, triesLeft - 1), 350);
                        } else {
                            main.post(() -> done.accept(null));
                        }
                    }
                });
    }

    /** Runs on the worker: shrink to 1/3 in standard colours, then group the purple cells. */
    private List<Rect> patches(Bitmap hw) {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        service.getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm);
        int sw = dm.widthPixels > 0 ? dm.widthPixels : hw.getWidth();
        int sh = dm.heightPixels > 0 ? dm.heightPixels : hw.getHeight();
        if ((hw.getWidth() > hw.getHeight()) != (sw > sh)) {
            int t = sw;
            sw = sh;
            sh = t;
        }
        Bitmap soft = hw.copy(Bitmap.Config.ARGB_8888, false);
        int w = Math.max(1, sw / STEP), h = Math.max(1, sh / STEP);
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, true,
                ColorSpace.get(ColorSpace.Named.SRGB));
        new Canvas(small).drawBitmap(soft, null, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        soft.recycle();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();

        int tr = (PURPLE >> 16) & 255, tg = (PURPLE >> 8) & 255, tb = PURPLE & 255;
        boolean[] on = new boolean[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int d = Math.abs(((c >> 16) & 255) - tr) + Math.abs(((c >> 8) & 255) - tg)
                    + Math.abs((c & 255) - tb);
            on[i] = d <= TOLERANCE;
        }
        List<Rect> out = new ArrayList<>();
        int[] queue = new int[w * h];
        boolean[] seen = new boolean[w * h];
        for (int i = 0; i < on.length; i++) {
            if (!on[i] || seen[i]) continue;
            int head = 0, tail = 0, count = 0;
            int minX = w, minY = h, maxX = 0, maxY = 0;
            queue[tail++] = i;
            seen[i] = true;
            while (head < tail) {
                int p = queue[head++];
                int x = p % w, y = p / w;
                count++;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                int[] next = {p - 1, p + 1, p - w, p + w};
                for (int k = 0; k < 4; k++) {
                    int q = next[k];
                    if (q < 0 || q >= on.length || seen[q] || !on[q]) continue;
                    if ((k == 0 && x == 0) || (k == 1 && x == w - 1)) continue;
                    seen[q] = true;
                    queue[tail++] = q;
                }
            }
            if (count >= MIN_CELLS) {
                out.add(new Rect(minX * STEP, minY * STEP, (maxX + 1) * STEP, (maxY + 1) * STEP));
            }
        }
        return out;
    }
}
