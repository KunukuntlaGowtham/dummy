package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Finds a pop-up's purple button in the middle of the screen (OK, Proceed, Close ... - any
 * words, an icon or none; a button the page draws but doesn't report to accessibility)
 * on the accessibility screenshot of the screen - used only for pop-ups; everything else goes
 * through accessibility. A match is a filled purple button (any words on it) that the
 * page does NOT report as a button (so the page's own purple Continue is never taken) and that
 * isn't one of our own floating buttons.
 */
final class PurpleFinder {

    private static final java.util.concurrent.Executor WORKER = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Android allows about 3 screenshots a second. */
    private static final long MIN_GAP = 340;

    private final AccessibilityService service;
    private long lastShot;
    private boolean busy;

    PurpleFinder(AccessibilityService service) {
        this.service = service;
    }

    static boolean available() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /** Ms until a screenshot may be taken (0 = now). */
    long waitMs() {
        if (busy) return MIN_GAP;
        return Math.max(0, lastShot + MIN_GAP - SystemClock.uptimeMillis());
    }

    /**
     * Takes a screenshot and hands the pop-up button's place on screen to {@code done} - null
     * when there is none, or when no screenshot could be taken (then {@code failed} is told).
     */
    void find(Consumer<Rect> done, Consumer<String> failed) {
        find(done, failed, null);
    }

    /**
     * As {@link #find(Consumer, Consumer)}; with {@code before} (the page before the tick) only
     * the page's buttons that were there before are left out - a button the pop-up brought is
     * taken whatever its words, also when the page reports it.
     */
    void find(Consumer<Rect> done, Consumer<String> failed, Page.Before before) {
        if (!available()) {
            failed.accept("screenshots need Android 11+");
            done.accept(null);
            return;
        }
        if (busy) {
            done.accept(null);
            return;
        }
        busy = true;
        lastShot = SystemClock.uptimeMillis();
        // What the page reports as tappable, and our own windows: read before the picture.
        List<Rect> exclude = excluded(before);
        try {
            // The picture is searched off the main thread, so taps and checks aren't held up.
            service.takeScreenshot(Display.DEFAULT_DISPLAY, WORKER,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            Rect found = null;
                            HardwareBuffer hb = result.getHardwareBuffer();
                            try {
                                Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                                if (hw != null) {
                                    Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                                    hw.recycle();
                                    if (bmp != null) {
                                        found = scan(bmp, exclude);
                                        bmp.recycle();
                                    }
                                }
                            } catch (RuntimeException e) {
                                String why = "couldn't read the screenshot: " + e.getMessage();
                                MAIN.post(() -> failed.accept(why));
                            } finally {
                                hb.close();
                            }
                            Rect result2 = found;
                            MAIN.post(() -> {
                                busy = false;
                                done.accept(result2);
                            });
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            MAIN.post(() -> {
                                busy = false;
                                failed.accept(errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS
                                        ? "no screenshot permission - turn the Inspector off and on in Accessibility"
                                        : "screenshot failed (" + errorCode + ")");
                                done.accept(null);
                            });
                        }
                    });
        } catch (RuntimeException e) {
            busy = false;
            failed.accept("screenshot failed: " + e.getMessage());
            done.accept(null);
        }
    }

    /** The page's own small tappable elements and our overlay windows: never the pop-up's button. */
    private List<Rect> excluded(Page.Before before) {
        List<Rect> out = new ArrayList<>();
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        long quarter = (long) dm.widthPixels * dm.heightPixels / 4;
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            if (!n.isClickable() || !n.isVisibleToUser()) continue;
            Rect r = Page.bounds(n);
            if (r.width() <= 0 || r.height() <= 0 || (long) r.width() * r.height() >= quarter) continue;
            if (before != null && !before.clickables.contains(Page.key(n))) continue; // came with the pop-up
            out.add(r);
        }
        try {
            for (AccessibilityWindowInfo w : service.getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                out.add(r);
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    // ---- the picture ---------------------------------------------------------------------

    private static final int STEP = 4;

    /** The best pill-shaped purple button with white text, not excluded; null if none. */
    static Rect scan(Bitmap bmp, List<Rect> exclude) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int gw = w / STEP, gh = h / STEP;
        int[] row = new int[w];
        int[] grid = new int[gw * gh];
        for (int gy = 0; gy < gh; gy++) {
            bmp.getPixels(row, 0, w, 0, gy * STEP, w, 1);
            for (int gx = 0; gx < gw; gx++) grid[gy * gw + gx] = row[gx * STEP];
        }
        return scanGrid(grid, gw, gh, w, h, exclude);
    }

    /** {@code grid}: every STEP-th pixel of a w x h screen, gw x gh of them. */
    static Rect scanGrid(int[] grid, int gw, int gh, int w, int h, List<Rect> exclude) {
        boolean[] purple = new boolean[gw * gh];
        boolean[] white = new boolean[gw * gh];
        for (int i = 0; i < grid.length; i++) {
            purple[i] = isPurple(grid[i]);
            white[i] = isWhite(grid[i]);
        }
        boolean[] seen = new boolean[gw * gh];
        int[] queue = new int[gw * gh];
        Rect best = null;
        int bestCount = 0;
        for (int start = 0; start < purple.length; start++) {
            if (!purple[start] || seen[start]) continue;
            // One purple blob (flood fill on the grid).
            int head = 0, tail = 0, count = 0;
            int minX = gw, minY = gh, maxX = -1, maxY = -1;
            queue[tail++] = start;
            seen[start] = true;
            while (head < tail) {
                int i = queue[head++];
                count++;
                int x = i % gw, y = i / gw;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                int[] next = {x > 0 ? i - 1 : -1, x < gw - 1 ? i + 1 : -1, y > 0 ? i - gw : -1, y < gh - 1 ? i + gw : -1};
                for (int j : next) {
                    if (j >= 0 && purple[j] && !seen[j]) {
                        seen[j] = true;
                        queue[tail++] = j;
                    }
                }
            }
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            // A button: wide enough, not a strip or a page band, pill-shaped, mostly filled.
            if (bw * STEP < w * 12 / 100 || bw * STEP > w * 95 / 100) continue;
            if (bh * STEP < h * 2 / 100 || bh * STEP > h * 12 / 100) continue;
            double aspect = (double) bw / bh;
            if (aspect < 1.6 || aspect > 9) continue;
            if (count < bw * bh * 0.5) continue;
            // Its words: white pixels inside it (the "OK").
            int whites = 0;
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) if (white[y * gw + x]) whites++;
            }
            // Any purple button counts - with words on it (OK, Proceed ...) or an icon or none;
            // a box mostly white inside is a frame, not a button.
            if (whites > bw * bh * 0.45) continue;
            Rect r = new Rect(minX * STEP, minY * STEP, (maxX + 1) * STEP, (maxY + 1) * STEP);
            // A pop-up's button sits in the middle of the screen: centred across (its middle in
            // the middle 40%), and not at the top or the bottom edge (where the page's own bars
            // and buttons are).
            if (r.centerX() < w * 30 / 100 || r.centerX() > w * 70 / 100) continue;
            if (r.centerY() < h * 20 / 100 || r.centerY() > h * 85 / 100) continue;
            boolean out = false;
            for (Rect e : exclude) {
                if (e.contains(r.centerX(), r.centerY())) {
                    out = true;
                    break;
                }
            }
            if (out) continue;
            if (count > bestCount) {
                best = r;
                bestCount = count;
            }
        }
        return best;
    }

    /** Purple / violet: hue 255°-300°, clearly coloured, neither too dark nor too pale. */
    static boolean isPurple(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max < 70 || max > 235) return false;
        int d = max - min;
        if (d * 100 < max * 35) return false; // saturation under 35%
        float hue;
        if (max == r) hue = 60f * (((g - b) / (float) d) % 6);
        else if (max == g) hue = 60f * ((b - r) / (float) d + 2);
        else hue = 60f * ((r - g) / (float) d + 4);
        if (hue < 0) hue += 360;
        return hue >= 255 && hue <= 300;
    }

    private static boolean isWhite(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return r > 215 && g > 215 && b > 215;
    }
}
