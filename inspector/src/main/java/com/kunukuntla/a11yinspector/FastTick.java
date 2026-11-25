package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * ⚡ Fast tick: ticks every empty checkbox one after another WITHOUT clearing pop-ups - you
 * clear them yourself. Each box is clicked through accessibility (works behind a pop-up and
 * off screen), then read until it shows ☑; if it doesn't, its label is clicked once. A box
 * that never showed ☑ is tried again in up to 2 more passes. A box that showed ☑ once is never
 * clicked again (a second click would untick it, and a row the page refuses unticks itself).
 * Nothing else is done: no pop-up clearing, no Continue, no delete list.
 */
final class FastTick {

    interface Listener {
        void done(String summary, String log);
    }

    private static final long WAIT_MS = 500;   // per click, for the box to read ☑
    private static final long LOOK_MS = 30;    // how often it is read meanwhile
    private static final int PASSES = 3;       // first pass + 2 more for boxes that didn't tick

    private final AccessibilityService service;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private boolean running;
    private int gen;
    private long start;

    /** Places (0 = first checkbox on the page) seen ☑ in this run: never clicked again. */
    private final java.util.Set<Integer> done = new java.util.TreeSet<>();
    /** Places this pass still has to try, in page order. */
    private final List<Integer> todo = new ArrayList<>();
    /** Places that never read ☑ in this pass: tried again in the next one. */
    private final List<Integer> missed = new ArrayList<>();
    private int pass, boxesAtStart, alreadyTicked;

    FastTick(AccessibilityService service, Listener listener) {
        this.service = service;
        this.listener = listener;
    }

    // ---- its own ⚡ button (nothing in the rest of the app is changed for it) ----------

    private static FastTick shown;
    private static TextView button;
    private static String lastReport = "";

    /**
     * Puts the ⚡ Fast button on the screen, left of the other buttons at the bottom right.
     * Tap: start / stop. Long press: copies the last run's report. Drag: move it.
     */
    @SuppressLint("ClickableViewAccessibility")
    static void showButton(AccessibilityService service) {
        // The service started again (Accessibility turned off and on): the old button goes first.
        if (shown != null) shown.stop("⚡ Stopped");
        if (button != null) {
            try {
                button.getContext().getSystemService(WindowManager.class).removeView(button);
            } catch (RuntimeException ignored) {
            }
            button = null;
        }
        WindowManager wm = service.getSystemService(WindowManager.class);
        float d = service.getResources().getDisplayMetrics().density;
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        TextView b = new TextView(service);
        b.setText("⚡\nFast");
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setLineSpacing(0, 0.9f);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription("Fast tick");
        b.setBackground(Glass.button(0xEEEF6C00, d));
        b.setElevation(8 * d);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams((int) (56 * d), (int) (56 * d),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dm.widthPixels - (int) ((56 + 14 + 70) * d);
        lp.y = dm.heightPixels - (int) ((56 + 128) * d);
        Handler h = new Handler(Looper.getMainLooper());
        shown = new FastTick(service, (summary, log) -> {
            lastReport = log;
            b.setText("⚡\nFast");
            String[] lines = summary.split("\n");
            Toast.makeText(service, lines[0] + (lines.length > 1 ? " · " + lines[1] : "")
                    + " - long-press ⚡ to copy the report", Toast.LENGTH_LONG).show();
        });
        int slop = ViewConfiguration.get(service).getScaledTouchSlop();
        float[] down = new float[2];
        int[] start = new int[2];
        boolean[] dragged = {false}, longDone = {false};
        Runnable longPress = () -> {
            longDone[0] = true;
            if (lastReport.isEmpty()) {
                Toast.makeText(service, "⚡ No Fast tick run yet", Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.ClipboardManager cb = service.getSystemService(android.content.ClipboardManager.class);
            if (cb != null) cb.setPrimaryClip(android.content.ClipData.newPlainText("Fast tick report", lastReport));
            Toast.makeText(service, "⚡ Report copied - paste it in a chat", Toast.LENGTH_SHORT).show();
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    start[0] = lp.x;
                    start[1] = lp.y;
                    dragged[0] = false;
                    longDone[0] = false;
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start();
                    h.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (!dragged[0] && Math.hypot(dx, dy) > slop) {
                        dragged[0] = true;
                        h.removeCallbacks(longPress);
                    }
                    if (dragged[0]) {
                        lp.x = start[0] + (int) dx;
                        lp.y = start[1] + (int) dy;
                        try {
                            wm.updateViewLayout(b, lp);
                        } catch (RuntimeException ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                    h.removeCallbacks(longPress);
                    if (!dragged[0] && !longDone[0]) tapped(service, b);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                    h.removeCallbacks(longPress);
                    return true;
                default:
                    return true;
            }
        });
        try {
            wm.addView(b, lp);
            button = b;
        } catch (RuntimeException ignored) {
        }
    }

    private static void tapped(AccessibilityService service, TextView b) {
        if (shown.isRunning()) {
            shown.stop("⚡ Stopped");
            return;
        }
        // The same lock as the other buttons: signed in with a licence.
        if (!Auth.signedIn(service)) {
            Toast.makeText(service, "🔒 Sign in first - open the app", Toast.LENGTH_LONG).show();
            return;
        }
        b.setText("■\nStop");
        shown.start();
    }

    boolean isRunning() {
        return running;
    }

    void start() {
        if (running) return;
        running = true;
        gen++;
        start = SystemClock.uptimeMillis();
        log.setLength(0);
        done.clear();
        todo.clear();
        missed.clear();
        pass = 1;
        log("⚡ Fast tick: every empty box, one after another - pop-ups are left for you to clear");
        List<AccessibilityNodeInfo> boxes = boxes();
        boxesAtStart = boxes.size();
        alreadyTicked = 0;
        for (int i = 0; i < boxes.size(); i++) {
            AccessibilityNodeInfo n = boxes.get(i);
            if (n.isChecked()) {
                alreadyTicked++;
                continue;
            }
            if (!n.isEnabled()) {
                log("row " + (i + 1) + ": switched off by the page - left alone");
                continue;
            }
            todo.add(i);
        }
        log("page read: " + boxesAtStart + " checkbox(es), " + todo.size() + " to tick"
                + (alreadyTicked > 0 ? ", " + alreadyTicked + " already ☑" : ""));
        if (boxesAtStart == 0) {
            stop("⚡ No checkboxes found here");
            return;
        }
        later(this::nextBox, 0);
    }

    void stop(String why) {
        if (!running) return;
        running = false;
        gen++;
        handler.removeCallbacksAndMessages(null);
        StringBuilder s = new StringBuilder(why);
        if (!why.startsWith("⚡ No")) {
            s.append("\n").append(done.size()).append(" ticked");
            if (!missed.isEmpty() || !todo.isEmpty()) {
                List<Integer> left = new ArrayList<>(missed);
                left.addAll(todo);
                s.append(", didn't tick: row ").append(rows(left));
            }
            s.append(" · ").append(SystemClock.uptimeMillis() - start).append(" ms");
        }
        log("— " + s.toString().replace('\n', ' '));
        String report = "⚡ FAST TICK\n" + s + "\n\nSTEPS\n" + log;
        listener.done(s.toString(), report);
    }

    // ---- one box after another ------------------------------------------------------------

    private void nextBox() {
        if (todo.isEmpty()) {
            endOfPass();
            return;
        }
        int idx = todo.remove(0);
        String row = String.valueOf(idx + 1);
        AccessibilityNodeInfo n = boxAt(idx);
        if (n == null) {
            log("row " + row + ": not on the page now - skipped");
            missed.add(idx);
            later(this::nextBox, 0);
            return;
        }
        if (n.isChecked()) {
            log("row " + row + ": already ☑");
            done.add(idx);
            later(this::nextBox, 0);
            return;
        }
        long t0 = SystemClock.uptimeMillis();
        boolean sent = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        log("row " + row + ": box clicked" + (sent ? "" : " (the page didn't take the click)")
                + (n.isVisibleToUser() ? "" : " (off screen)"));
        waitTicked(idx, t0 + WAIT_MS, on -> {
            if (on) {
                ticked(idx, t0, "box click");
                return;
            }
            // Read once more: a late tick must not get a second click (it would untick it).
            AccessibilityNodeInfo now = boxAt(idx);
            if (now != null && now.isChecked()) {
                ticked(idx, t0, "box click, late");
                return;
            }
            AccessibilityNodeInfo label = now == null ? null : clickableParent(now);
            if (label == null) {
                log("row " + row + ": didn't tick - " + (now == null ? "the box went from the page" : "no label to click"));
                missed.add(idx);
                later(this::nextBox, 0);
                return;
            }
            label.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            log("row " + row + ": the box click didn't tick it - its label clicked once");
            waitTicked(idx, SystemClock.uptimeMillis() + WAIT_MS, byLabel -> {
                if (byLabel) {
                    ticked(idx, t0, "label click");
                } else {
                    log("row " + row + ": still ☐ - tried again later");
                    missed.add(idx);
                    later(this::nextBox, 0);
                }
            });
        });
    }

    private void ticked(int idx, long t0, String how) {
        done.add(idx);
        log("row " + (idx + 1) + ": ticked ✓ by a " + how + " (" + (SystemClock.uptimeMillis() - t0) + " ms)");
        later(this::nextBox, 0);
    }

    private void endOfPass() {
        if (missed.isEmpty()) {
            stop("⚡ All ticked - clear the pop-ups");
            return;
        }
        if (pass >= PASSES) {
            stop("⚡ Done - " + missed.size() + " box(es) wouldn't tick");
            return;
        }
        pass++;
        // Only boxes that never read ☑: a box that read ☑ and is ☐ now was unticked by the page
        // (refused) and is not clicked again.
        for (int idx : missed) if (!done.contains(idx)) todo.add(idx);
        missed.clear();
        log("pass " + pass + ": trying again row " + rows(todo));
        later(this::nextBox, 150);
    }

    // ---- reading the page --------------------------------------------------------------

    private List<AccessibilityNodeInfo> boxes() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : Page.nodes(service)) if (Page.isCheckbox(n)) out.add(n);
        return out;
    }

    /** The checkbox at place {@code idx} now (the page may redraw its list after each add). */
    private AccessibilityNodeInfo boxAt(int idx) {
        List<AccessibilityNodeInfo> b = boxes();
        return idx < b.size() ? b.get(idx) : null;
    }

    private void waitTicked(int idx, long until, java.util.function.Consumer<Boolean> then) {
        later(() -> {
            AccessibilityNodeInfo n = boxAt(idx);
            boolean on = n != null && n.isChecked();
            if (on || SystemClock.uptimeMillis() >= until) then.accept(on);
            else waitTicked(idx, until, then);
        }, LOOK_MS);
    }

    private static AccessibilityNodeInfo clickableParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n.getParent();
        for (int i = 0; p != null && i < 3; i++, p = p.getParent()) {
            if (p.isClickable()) return p;
        }
        return null;
    }

    private static String rows(List<Integer> places) {
        StringBuilder s = new StringBuilder();
        for (int i : places) {
            if (s.length() > 0) s.append(", ");
            s.append(i + 1);
        }
        return s.toString();
    }

    private void later(Runnable r, long ms) {
        int g = gen;
        handler.postDelayed(() -> {
            if (!running || g != gen) return;
            try {
                r.run();
            } catch (RuntimeException e) {
                stop("⚡ Error: " + e);
            }
        }, ms);
    }

    private void log(String line) {
        log.append(SystemClock.uptimeMillis() - start).append(" ms  ").append(line).append('\n');
    }
}
