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
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Which calendar days are open. When the page marks closed days (not enabled), that says it;
 * when it marks none, a screenshot shows which days are drawn green. {@code done} gets
 * day (days since 1970) -> open, or null when the page doesn't tell them apart (no day
 * off, no green day found, no screenshot).
 */
final class DayColours {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Handler worker;

    private DayColours() {
    }

    /** {@code hide}: our own buttons off the screen (true) and back (false) around the screenshot. */
    static void open(AccessibilityService service, Map<AccessibilityNodeInfo, int[]> cells,
                     Consumer<Boolean> hide, Consumer<Map<Long, Boolean>> done) {
        Map<Long, Boolean> byFlag = new HashMap<>();
        boolean anyOff = false;
        for (Map.Entry<AccessibilityNodeInfo, int[]> e : cells.entrySet()) {
            int[] d = e.getValue();
            boolean on = e.getKey().isEnabled();
            if (!on) anyOff = true;
            byFlag.put(DayPick.epochDay(d[0], d[1], d[2]), on);
        }
        if (anyOff) {
            done.accept(byFlag);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            done.accept(null);
            return;
        }
        Map<Long, Rect> where = new HashMap<>();
        for (Map.Entry<AccessibilityNodeInfo, int[]> e : cells.entrySet()) {
            Rect r = Page.bounds(e.getKey());
            if (r.width() < 8 || r.height() < 8 || !e.getKey().isVisibleToUser()) continue;
            int[] d = e.getValue();
            where.put(DayPick.epochDay(d[0], d[1], d[2]), r);
        }
        if (where.isEmpty()) {
            done.accept(null);
            return;
        }
        if (worker == null) {
            HandlerThread t = new HandlerThread("day-colours");
            t.start();
            worker = new Handler(t.getLooper());
        }
        hide.accept(true);
        MAIN.postDelayed(() -> {
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, worker::post,
                        new AccessibilityService.TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(AccessibilityService.ScreenshotResult result) {
                                Map<Long, Boolean> out = null;
                                HardwareBuffer hb = result.getHardwareBuffer();
                                try {
                                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                                    if (hw != null) {
                                        Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                                        hw.recycle();
                                        if (bmp != null) {
                                            out = read(bmp, where);
                                            bmp.recycle();
                                        }
                                    }
                                } catch (RuntimeException ignored) {
                                } finally {
                                    hb.close();
                                }
                                Map<Long, Boolean> result2 = out;
                                MAIN.post(() -> {
                                    hide.accept(false);
                                    done.accept(result2);
                                });
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                MAIN.post(() -> {
                                    hide.accept(false);
                                    done.accept(null);
                                });
                            }
                        });
            } catch (RuntimeException e) {
                hide.accept(false);
                done.accept(null);
            }
        }, 80);
    }

    /** What the last look saw: each day's colour (for the report). */
    static String lastNote = "";

    /**
     * Each day: drawn green or yellow (open)? Null when no day is either (the calendar doesn't
     * colour its days). {@link #lastNote} gets each day's colour.
     */
    private static Map<Long, Boolean> read(Bitmap bmp, Map<Long, Rect> where) {
        Map<Long, Boolean> out = new HashMap<>();
        boolean anyOpen = false;
        StringBuilder note = new StringBuilder();
        for (long day : new java.util.TreeSet<>(where.keySet())) {
            Rect r = where.get(day);
            int[] seen = colours(bmp, r);
            // Green or yellow: open; the day already chosen (drawn purple) counts as open too.
            boolean open = seen[0] >= 4 || seen[1] >= 4 || seen[3] >= 10;
            anyOpen |= open;
            out.put(day, open);
            int[] d = DayPick.date(day);
            note.append(note.length() == 0 ? "" : ", ").append(d[0]).append('/').append(d[1]).append(' ')
                    .append(seen[0] >= 4 ? "green" : seen[1] >= 4 ? "yellow" : seen[3] >= 10 ? "chosen (purple)"
                            : String.format("#%06X", seen[2]));
        }
        lastNote = note.toString();
        return anyOpen ? out : null;
    }

    /**
     * Across the day's box (81 points): {green points, yellow points, the box's middle colour,
     * purple points}.
     * At least 4 green or yellow points make an open day (its fill, ring or dot).
     */
    static int[] colours(Bitmap bmp, Rect r) {
        int green = 0, yellow = 0, purple = 0;
        for (int i = 0; i < 9; i++) {
            for (int j = 0; j < 9; j++) {
                int x = r.left + r.width() * (5 + i * 90 / 8) / 100;
                int y = r.top + r.height() * (5 + j * 90 / 8) / 100;
                if (x < 0 || y < 0 || x >= bmp.getWidth() || y >= bmp.getHeight()) continue;
                int c = bmp.getPixel(x, y);
                int cr = (c >> 16) & 0xFF, cg = (c >> 8) & 0xFF, cb = c & 0xFF;
                if (DayPick.isGreen(cr, cg, cb)) green++;
                else if (DayPick.isYellow(cr, cg, cb)) yellow++;
                else if (DayPick.isPurple(cr, cg, cb)) purple++;
            }
        }
        int mx = Math.min(Math.max(r.centerX(), 0), bmp.getWidth() - 1);
        int my = Math.min(Math.max(r.top + r.height() / 4, 0), bmp.getHeight() - 1);
        return new int[] {green, yellow, bmp.getPixel(mx, my) & 0xFFFFFF, purple};
    }
}
