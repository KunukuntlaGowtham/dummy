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
 * Page 2 as it is drawn: the page reports no state for the slot's round button and a stale
 * one for the declaration checkbox, so a screenshot shows them - the slot chosen when its
 * circle is filled, the checkbox ticked when its row has a coloured (filled) square.
 */
final class SlotLook {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Handler worker;

    private SlotLook() {
    }

    static boolean canShoot() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /**
     * A screenshot (our buttons hidden for it), handed to {@code done} on the main thread -
     * the caller recycles it - or null when none can be taken (before Android 11).
     */
    static void shot(AccessibilityService service, Consumer<Boolean> hide, Consumer<Bitmap> done) {
        if (!canShoot()) {
            done.accept(null);
            return;
        }
        if (worker == null) {
            HandlerThread t = new HandlerThread("slot-look");
            t.start();
            worker = new Handler(t.getLooper());
        }
        shoot(service, hide, done, 0);
    }

    private static void shoot(AccessibilityService service, Consumer<Boolean> hide, Consumer<Bitmap> done, int tries) {
        hide.accept(true);
        MAIN.postDelayed(() -> {
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, worker::post,
                        new AccessibilityService.TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(AccessibilityService.ScreenshotResult result) {
                                Bitmap out = null;
                                HardwareBuffer hb = result.getHardwareBuffer();
                                try {
                                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                                    if (hw != null) {
                                        out = hw.copy(Bitmap.Config.ARGB_8888, false);
                                        hw.recycle();
                                    }
                                } catch (RuntimeException ignored) {
                                } finally {
                                    hb.close();
                                }
                                Bitmap bmp = out;
                                MAIN.post(() -> {
                                    hide.accept(false);
                                    done.accept(bmp);
                                });
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                MAIN.post(() -> {
                                    hide.accept(false);
                                    // Screenshots come at most ~3 a second: one just before may
                                    // be too close - tried again shortly.
                                    if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                                            && tries < 4) {
                                        MAIN.postDelayed(() -> shoot(service, hide, done, tries + 1), 350);
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

    /** Share (0-100) of the circle's middle (its inner half) that is coloured, not white. */
    static int fill(Bitmap bmp, Rect r) {
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
        return seen == 0 ? 0 : coloured * 100 / seen;
    }

    /** The slot's round button is chosen: its middle mostly filled. */
    static boolean filled(Bitmap bmp, Rect r) {
        return fill(bmp, r) >= 40;
    }

    /** Coloured (saturated) pixels in this band of the screen, counted every 2nd pixel ×4. */
    static int colouredIn(Bitmap bmp, Rect band) {
        int n = 0;
        int x0 = Math.max(0, band.left), x1 = Math.min(bmp.getWidth(), band.right);
        int y0 = Math.max(0, band.top), y1 = Math.min(bmp.getHeight(), band.bottom);
        for (int y = y0; y < y1; y += 2) {
            for (int x = x0; x < x1; x += 2) {
                int c = bmp.getPixel(x, y);
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (isColour(r, g, b)) n++;
            }
        }
        return n * 4;
    }

    /**
     * The checkbox's row has a filled square: at least ~30% of a square the row's height in
     * colour (an empty box is a grey outline, its words black / grey).
     */
    static boolean ticked(int coloured, int rowHeight) {
        return coloured >= rowHeight * rowHeight * 3 / 10;
    }

    /** A filled dot: the app's purple, or any clearly coloured / dark pixel (not white or pale grey). */
    static boolean isFill(int r, int g, int b) {
        if (DayPick.isPurple(r, g, b)) return true;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max < 150 || max - min > 60;
    }

    /** A clearly coloured pixel (not white, grey or black). */
    static boolean isColour(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max - min > 70 && max > 90;
    }
}
