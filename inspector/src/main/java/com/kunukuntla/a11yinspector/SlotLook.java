package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Display;

import java.util.function.Consumer;

/**
 * Whether the slot's round button is chosen: the page reports no state for it, so a screenshot
 * shows it - chosen, its circle is filled with the app's purple; not chosen, an empty ring.
 * {@code done} gets true / false, or null when it can't be told (no screenshot before Android 11).
 */
final class SlotLook {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Handler worker;

    private SlotLook() {
    }

    static void chosen(AccessibilityService service, Rect r, Consumer<Boolean> hide, Consumer<Boolean> done) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || r == null || r.width() < 8 || r.height() < 8) {
            done.accept(null);
            return;
        }
        if (worker == null) {
            HandlerThread t = new HandlerThread("slot-look");
            t.start();
            worker = new Handler(t.getLooper());
        }
        shoot(service, r, hide, done, 0);
    }

    private static void shoot(AccessibilityService service, Rect r, Consumer<Boolean> hide,
            Consumer<Boolean> done, int tries) {
        hide.accept(true);
        MAIN.postDelayed(() -> {
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, worker::post,
                        new AccessibilityService.TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(AccessibilityService.ScreenshotResult result) {
                                Boolean out = null;
                                HardwareBuffer hb = result.getHardwareBuffer();
                                try {
                                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                                    if (hw != null) {
                                        Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                                        hw.recycle();
                                        if (bmp != null) {
                                            out = filled(bmp, r);
                                            bmp.recycle();
                                        }
                                    }
                                } catch (RuntimeException ignored) {
                                } finally {
                                    hb.close();
                                }
                                Boolean result2 = out;
                                MAIN.post(() -> {
                                    hide.accept(false);
                                    done.accept(result2);
                                });
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                MAIN.post(() -> {
                                    hide.accept(false);
                                    // Screenshots come at most ~3 a second: the calendar's just
                                    // before may be too close - tried again shortly.
                                    if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                                            && tries < 3) {
                                        MAIN.postDelayed(() -> shoot(service, r, hide, done, tries + 1), 350);
                                    } else {
                                        done.accept(null);
                                    }
                                });
                            }
                        });
            } catch (RuntimeException e) {
                hide.accept(false);
                done.accept(null);
            }
        }, 60);
    }

    /** The middle of the circle (its inner half) is mostly coloured, not white: chosen. */
    static boolean filled(Bitmap bmp, Rect r) {
        int coloured = 0, seen = 0;
        int cx = r.centerX(), cy = r.centerY(), rad = Math.min(r.width(), r.height()) / 4;
        for (int dx = -rad; dx <= rad; dx += Math.max(1, rad / 4)) {
            for (int dy = -rad; dy <= rad; dy += Math.max(1, rad / 4)) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= bmp.getWidth() || y >= bmp.getHeight()) continue;
                seen++;
                int c = bmp.getPixel(x, y);
                if (isFill((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF)) coloured++;
            }
        }
        return seen > 0 && coloured * 10 >= seen * 4;
    }

    /** A filled dot: the app's purple, or any clearly coloured / dark pixel (not white or pale grey). */
    static boolean isFill(int r, int g, int b) {
        if (DayPick.isPurple(r, g, b)) return true;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max < 150 || max - min > 60;
    }
}
