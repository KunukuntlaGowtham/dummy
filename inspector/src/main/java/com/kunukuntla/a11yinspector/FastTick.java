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

        /** Boxes ticked so far this run, of the boxes it set out to tick. */
        default void progress(int ticked, int total) {
        }
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
    private int pass, boxesAtStart, alreadyTicked, toTick, tickedCount;

    FastTick(AccessibilityService service, Listener listener) {
        this.service = service;
        this.listener = listener;
    }

    // ---- its own ⚡ button (nothing in the rest of the app is changed for it) ----------

    private static FastTick shown;
    private static TextView button;
    private static String lastReport = "";
    /** The app's last reports (Tick, Delete, the trip ...), oldest first, at most 5. */
    private static final List<String> REPORTS = new ArrayList<>();

    static void keepReport(String text) {
        if (text == null) return;
        synchronized (REPORTS) {
            REPORTS.add(text.length() > 30000 ? text.substring(0, 30000) + "\n…(cut)" : text);
            while (REPORTS.size() > 5) REPORTS.remove(0);
        }
    }
    /** "ticked/total" of the last run, shown on the button. */
    private static String lastCount = "";

    /**
     * Puts the ⚡ Fast button on the screen, left of the other buttons at the bottom right.
     * Tap: start / stop. Long press: copies the last run's report. Drag: move it.
     */
    @SuppressLint("ClickableViewAccessibility")
    static void showButton(AccessibilityService service) {
        // The service started again (Accessibility turned off and on): the old button goes first.
        if (shown != null) {
            shown.stop("⚡ Stopped");
            shown.stopWatch();
        }
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
        shown = new FastTick(service, new Listener() {
            @Override
            public void done(String summary, String log) {
                lastReport = log;
                // The count stays on the button until the next run: ⚡ and how many ticked.
                b.setText(lastCount.isEmpty() ? "⚡\nFast" : "⚡\n" + lastCount);
                String[] lines = summary.split("\n");
                Toast.makeText(service, lines[0] + (lines.length > 1 ? " · " + lines[1] : "")
                        + " - long-press ⚡ to copy the report", Toast.LENGTH_LONG).show();
            }

            @Override
            public void progress(int ticked, int total) {
                // Live, on the button: ticked so far / to tick (■ = tap to stop).
                lastCount = ticked + "/" + total;
                b.setText("■\n" + lastCount);
            }
        });
        int slop = ViewConfiguration.get(service).getScaledTouchSlop();
        float[] down = new float[2];
        int[] start = new int[2];
        boolean[] dragged = {false}, longDone = {false};
        Runnable longPress = () -> {
            longDone[0] = true;
            // Everything to find a fault: what Auto / Delete hold now, the last reports (Tick,
            // the trip, Delete ... newest first) and ⚡'s own last run.
            StringBuilder all = new StringBuilder("GOWTHAM - FULL REPORT (long-press ⚡)\n=====================================\n");
            if (service instanceof InspectorService) {
                try {
                    all.append(((InspectorService) service).fastState());
                } catch (RuntimeException e) {
                    all.append("state not readable: ").append(e).append('\n');
                }
            }
            synchronized (REPORTS) {
                for (int i = REPORTS.size() - 1; i >= 0; i--) {
                    all.append("\n\n----- REPORT ").append(REPORTS.size() - i).append(" (newest first) -----\n")
                            .append(REPORTS.get(i));
                }
            }
            if (!lastReport.isEmpty()) all.append("\n\n----- ⚡ LAST RUN -----\n").append(lastReport);
            android.content.ClipboardManager cb = service.getSystemService(android.content.ClipboardManager.class);
            if (cb != null) cb.setPrimaryClip(android.content.ClipData.newPlainText("Gowtham report", all.toString()));
            Toast.makeText(service, "⚡ Full report copied - paste it in a chat", Toast.LENGTH_SHORT).show();
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

    /** ⚡ was tapped and starts in a moment (what it stopped lets go of the page first). */
    private static Runnable starting;

    private static void tapped(AccessibilityService service, TextView b) {
        Handler h = new Handler(Looper.getMainLooper());
        if (starting != null) {
            // Tapped again in the moment before it starts (the button already says ■): that's Stop.
            h.removeCallbacks(starting);
            starting = null;
            b.setText(lastCount.isEmpty() ? "⚡\nFast" : "⚡\n" + lastCount);
            Toast.makeText(service, "⚡ Not started", Toast.LENGTH_SHORT).show();
            return;
        }
        if (shown.isRunning()) {
            shown.stop("⚡ Stopped");
            return;
        }
        // The same lock as the other buttons: signed in with a licence.
        if (!Auth.signedIn(service)) {
            Toast.makeText(service, "🔒 Sign in first - open the app", Toast.LENGTH_LONG).show();
            return;
        }
        // Only on page 3: on page 1, 2 or 4 nothing is stopped, ticked or cleared (page 2's
        // declaration box is never ticked, 🗑's saved list is never wiped). A page that can't be
        // told (still drawing, a pop-up) is let through: it may be page 3.
        PageKind.Kind k;
        try {
            k = PageKind.decide(PageKind.read(service));
        } catch (RuntimeException e) {
            k = PageKind.Kind.OTHER;
        }
        if (k == PageKind.Kind.CALENDAR || k == PageKind.Kind.SEVAK_LIST || k == PageKind.Kind.CONFIRM) {
            Toast.makeText(service, "⚡ This is " + k.title() + " - ⚡ ticks on page 3 only", Toast.LENGTH_SHORT).show();
            return;
        }
        lastCount = "";
        b.setText("■\nStop");
        // Everything else stops first (Tick, Go, Delete, retry; Auto off for now) - then Fast.
        InspectorService s = service instanceof InspectorService ? (InspectorService) service : null;
        boolean autoWasOwed = s != null && s.fastOwesAuto();
        boolean stopped = s != null && s.stopAllForFast();
        if (stopped) {
            boolean autoOff = s.fastOwesAuto() && !autoWasOwed;
            Toast.makeText(service, "⚡ Everything else stopped" + (autoOff ? " - 🤖 Auto off until page 1 or 2" : "")
                    + " - Fast tick starting", Toast.LENGTH_SHORT).show();
            starting = () -> {
                starting = null;
                if (!shown.isRunning()) shown.start();
            };
            h.postDelayed(starting, 300);
        } else {
            shown.start();
        }
    }

    /** The calendar's screenshot is taken (▶ Go, 📅 Book): ⚡ hidden meanwhile, then as it was. */
    private static int visibleBeforeShot = View.VISIBLE;

    static void hideForShot(boolean hide) {
        if (button == null) return;
        if (hide) {
            visibleBeforeShot = button.getVisibility();
            button.setVisibility(View.INVISIBLE);
        } else {
            button.setVisibility(visibleBeforeShot);
        }
    }

    boolean isRunning() {
        return running;
    }

    void start() {
        if (running) return;
        stopWatch();
        autoBackGen++; // looked for again when this run (and its look at page 3) ends
        // A new booking's ticking, as ☑ Tick does: the red box and the saved delete list start empty.
        if (service instanceof InspectorService) ((InspectorService) service).fastStarted();
        running = true;
        gen++;
        start = SystemClock.uptimeMillis();
        log.setLength(0);
        done.clear();
        tickedCount = 0;
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
        toTick = todo.size();
        listener.progress(0, toTick);
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
        // Not when you stopped it, nor with no boxes: then nothing is noted for 🗑 Delete.
        if (!why.startsWith("⚡ Stopped") && !why.startsWith("⚡ No")) {
            startWatch();
            // The pop-ups cleared, then Continue and Confirm when every box is ticked.
            if (service instanceof InspectorService) ((InspectorService) service).fastFinish();
        } else {
            startAutoBack();
        }
    }

    // ---- after the run: the rows not ticked go to the red box and 🗑 Delete's list ------

    /*
     * A row the page refuses unticks itself - often only when you clear its pop-up. So after the
     * run the page is looked at every 0.5 s: every row not ☑ (never ticked, or unticked by the
     * page) goes to the red box and the saved delete list, the same way ☑ Tick and 👁 Watch
     * note them - 🗑 Delete then deletes them as before. The look ends when page 3 is left
     * (no checkboxes any more), after 5 minutes, when 🗑 Delete / ☑ Tick runs, or at the next ⚡.
     */
    private static final long WATCH_EVERY_MS = 500;
    private static final long WATCH_FOR_MS = 5 * 60 * 1000;
    private int watchGen;
    private long watchUntil;
    private int noBoxLooks;

    private void startWatch() {
        watchGen++;
        watchUntil = SystemClock.uptimeMillis() + WATCH_FOR_MS;
        noBoxLooks = 0;
        watchLook(watchGen);
    }

    private void stopWatch() {
        watchGen++;
    }

    private void watchLook(int g) {
        if (g != watchGen || running) return;
        if (!(service instanceof InspectorService)) return;
        if (!watchOnce(g)) startAutoBack(); // the look at page 3 ended
    }

    /** One look; false when the look at page 3 ends. */
    private boolean watchOnce(int g) {
        List<String> rows = new ArrayList<>();
        int i = 0;
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            if (!Page.isCheckbox(n)) continue;
            i++;
            if (!n.isChecked()) rows.add(String.valueOf(i));
        }
        if (i == 0) {
            // Page 3 left (Continue, Back ...): the list noted last is kept, the look ends.
            if (++noBoxLooks >= 2) return false;
        } else {
            noBoxLooks = 0;
            if (!((InspectorService) service).fastRows(rows)) return false; // Delete / Tick took over, or not page 3
        }
        if (SystemClock.uptimeMillis() >= watchUntil) return false;
        handler.postDelayed(() -> watchLook(g), WATCH_EVERY_MS);
        return true;
    }

    // ---- 🤖 Auto back on, if ⚡ turned it off --------------------------------------------

    /*
     * ⚡ turns 🤖 Auto off so it can't start the normal Tick meanwhile. It is turned back on when
     * page 1 or page 2 is reached afterwards (the next booking, or 🗑's trip back) - pages where
     * Auto presses nothing by itself. Looked at once a second, for up to 30 minutes.
     */
    private static final long AUTO_BACK_EVERY_MS = 1000;
    private static final long AUTO_BACK_FOR_MS = 30 * 60 * 1000;
    private int autoBackGen;

    private void startAutoBack() {
        if (!(service instanceof InspectorService) || !((InspectorService) service).fastOwesAuto()) return;
        int g = ++autoBackGen;
        long until = SystemClock.uptimeMillis() + AUTO_BACK_FOR_MS;
        handler.postDelayed(() -> autoBackLook(g, until), AUTO_BACK_EVERY_MS);
    }

    private void autoBackLook(int g, long until) {
        if (g != autoBackGen || running) return;
        InspectorService s = (InspectorService) service;
        if (!s.fastOwesAuto()) return;
        PageKind.Kind k;
        try {
            k = PageKind.decide(PageKind.read(service));
        } catch (RuntimeException e) {
            k = PageKind.Kind.OTHER;
        }
        if (k == PageKind.Kind.CALENDAR || k == PageKind.Kind.SEVAK_LIST) {
            s.fastAutoBack();
            return;
        }
        if (SystemClock.uptimeMillis() >= until) return;
        handler.postDelayed(() -> autoBackLook(g, until), AUTO_BACK_EVERY_MS);
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
        tickedCount++; // only boxes this run ticked (not ones found ☑ already)
        listener.progress(Math.min(tickedCount, toTick), toTick);
        log("row " + (idx + 1) + ": ticked ✓ by a " + how + " (" + (SystemClock.uptimeMillis() - t0) + " ms)");
        later(this::nextBox, 0);
    }

    private void endOfPass() {
        if (missed.isEmpty()) {
            stop("⚡ All ticked - clearing the pop-ups, then Continue");
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
