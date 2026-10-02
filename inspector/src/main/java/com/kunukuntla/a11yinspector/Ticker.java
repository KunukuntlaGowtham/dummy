package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Ticks every empty checkbox the page reports, only through accessibility (no screenshots):
 * clicks it (ACTION_CLICK; a hidden web checkbox is clicked through its label, and as a last
 * try tapped where the page says it is), checks that the page now reports it ☑, then clears the
 * pop-up that came up - a new window, a dialog, or a new OK / Yes / Close ... button - by
 * clicking that button (or dismissing the dialog). Then the next box, in page order; when none
 * is left on screen the page is brought on / scrolled, until no empty checkbox is left.
 * Also clears the pop-ups up now on their own ({@link #clearNow}).
 */
final class Ticker {

    interface Listener {
        void done(String summary, String log);
    }

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final StringBuilder log = new StringBuilder();
    private final List<AccessibilityNodeInfo> tried = new ArrayList<>();
    private boolean running;
    private int gen;
    private long start;
    private int ticked, notTicked, popups, stillScreens;
    private final List<String> tickedRows = new ArrayList<>();
    private final List<String> failedRows = new ArrayList<>();
    private String lastScreen = "";
    /** A pop-up came after an earlier tick this run: wait a little longer for the next one. */
    private boolean popupsSeen;
    /** Boxes in a row after which no pop-up came: stop waiting long for one. */
    private int quietBoxes;
    /** Only clearing the pop-ups up now, no ticking. */
    private boolean clearOnly;

    Ticker(AccessibilityService service, Listener listener) {
        this.service = service;
        this.listener = listener;
    }

    boolean isRunning() {
        return running;
    }

    void start() {
        begin(false);
        log("Tick: clicking every empty checkbox through accessibility, clearing pop-ups");
        later(this::next, 100);
    }

    /** Clears the pop-ups that are up now (a dialog's OK, a new window's button), then stops. */
    void clearNow() {
        begin(true);
        log("Clear: pressing the OK / Close of every pop-up up now");
        later(() -> clearPopups(null, 1, () -> stop(popups == 0 ? "No pop-up up" : "Pop-ups cleared")), 50);
    }

    private void begin(boolean clear) {
        if (running) return;
        running = true;
        clearOnly = clear;
        gen++;
        start = SystemClock.uptimeMillis();
        log.setLength(0);
        tried.clear();
        tickedRows.clear();
        failedRows.clear();
        ticked = notTicked = popups = stillScreens = 0;
        lastScreen = "";
        showTries = endChecks = 0;
        popupsSeen = false;
        quietBoxes = 0;
    }

    void stop(String why) {
        if (!running) return;
        running = false;
        gen++;
        log("END: " + why);
        String summary = clearOnly ? why + "\nPop-ups cleared " + popups : why + "\n" + "Ticked " + ticked
                + (tickedRows.isEmpty() ? "" : " (rows " + String.join(", ", tickedRows) + ")")
                + "\nNot ticked " + notTicked
                + (failedRows.isEmpty() ? "" : " (rows " + String.join(", ", failedRows) + ")")
                + "\nPop-ups cleared " + popups;
        listener.done(summary, "A11y Inspector - " + (clearOnly ? "Clear pop-ups" : "Tick run")
                + "\n=========================\n" + summary + "\n\nSTEPS\n" + log);
    }

    // ---- one box after another ------------------------------------------------------

    private void next() {
        // A pop-up left open (it came late) is cleared before the next box.
        Page.Popup left = Page.popup(service, null);
        if (left != null && left.button != null && !left.crossOnly && popupTaps < 3) {
            clearPopups(null, 1, this::next);
            return;
        }
        popupTaps = 0;
        // Strictly in page order: the first empty checkbox on the whole page (the page
        // reports those further down too, 0 high while off screen), brought on screen first.
        AccessibilityNodeInfo further = anyEmptyBox();
        Box b = further == null ? null : boxFor(further);
        if (b == null) {
            if (further == null) {
                if (endChecks++ >= 1) {
                    stop("No empty checkbox left on the page");
                } else {
                    log("no empty checkbox left on the page - one scroll to be sure");
                    scroll(true);
                    later(this::next, 450);
                }
                return;
            }
            if (showTries++ < 2) {
                log("next empty checkbox is off screen - asking the page to show it");
                further.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                later(this::next, 300);
                return;
            }
            String screen = screenKey();
            if (screen.equals(lastScreen) && ++stillScreens >= 2) {
                // It won't come on screen: skip it, go on with the next one.
                tried.add(further);
                notTicked++;
                failedRows.add("?");
                log("a checkbox never came on screen - skipped ✗");
                stillScreens = 0;
                showTries = 0;
                later(this::next, 50);
                return;
            }
            if (!screen.equals(lastScreen)) stillScreens = 0;
            lastScreen = screen;
            Rect r = new Rect();
            further.getBoundsInScreen(r);
            boolean up = r.bottom <= screen().height() / 3;
            log("next empty checkbox is off screen - scrolling " + (up ? "up" : "down") + " to it");
            scroll(!up);
            showTries = 0;
            later(this::next, 450);
            return;
        }
        showTries = 0;
        endChecks = 0;
        tried.add(b.node);
        tickBox(b);
    }

    private int showTries, endChecks;

    /** The first (in page order) empty, enabled checkbox not tried yet, on screen or not. */
    private AccessibilityNodeInfo anyEmptyBox() {
        for (AccessibilityNodeInfo node : Page.nodes(service)) {
            if (Page.isCheckbox(node) && !node.isChecked() && node.isEnabled() && !tried.contains(node)) return node;
        }
        return null;
    }

    /** Pop-up presses after the current box - at most 3, so an OK that won't go can't loop. */
    private int popupTaps;

    /**
     * Ticks the box through accessibility, one way after another until the page reports it ☑:
     * a click on the box, a click on its label (a hidden web checkbox), a tap where it is.
     */
    private void tickBox(Box b) {
        Page.Before before = new Page.Before(service);
        log("row " + b.row + ": clicking its checkbox (at " + b.box.centerX() + "," + b.box.centerY() + ")");
        boolean sent = b.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        whenChecked(b.node, 450, checked -> {
            if (checked) {
                done(b, "ticked ✓ by a click", before);
                return;
            }
            AccessibilityNodeInfo label = clickableParent(b.node);
            if (label != null) {
                log("row " + b.row + ": " + (sent ? "click didn't tick it" : "click refused")
                        + " - clicking its label");
                label.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            whenChecked(b.node, label != null ? 450 : 0, byLabel -> {
                if (byLabel) {
                    done(b, "ticked ✓ by a click on its label", before);
                    return;
                }
                log("row " + b.row + ": tapping it at " + b.box.centerX() + "," + b.box.centerY());
                tap(b.box.centerX(), b.box.centerY());
                whenChecked(b.node, 500, ok -> {
                    if (ok) done(b, "ticked ✓ by a tap", before);
                    else {
                        notTicked++;
                        failedRows.add(b.row);
                        log("row " + b.row + ": still empty ✗");
                        clearPopups(before, looks(), this::next);
                    }
                });
            });
        });
    }

    /** The nearest clickable parent (up to 3 levels): a web checkbox's label. */
    private static AccessibilityNodeInfo clickableParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n.getParent();
        for (int i = 0; p != null && i < 3; i++, p = p.getParent()) {
            if (p.isClickable()) return p;
        }
        return null;
    }

    /** Checks every 40 ms (up to {@code ms}) whether the box turned ☑. */
    private void whenChecked(AccessibilityNodeInfo node, long ms, java.util.function.Consumer<Boolean> then) {
        if (ms <= 0) {
            then.accept(Page.isChecked(node));
            return;
        }
        later(() -> {
            boolean on = Page.isChecked(node);
            if (on || ms <= 40) then.accept(on);
            else whenChecked(node, ms - 40, then);
        }, 40);
    }

    /** How many 100 ms looks for a pop-up after a tick: longer once pop-ups have been coming. */
    private int looks() {
        return popupsSeen ? 15 : quietBoxes >= 3 ? 5 : 10;
    }

    private void done(Box b, String how, Page.Before before) {
        ticked++;
        tickedRows.add(b.row);
        log("row " + b.row + ": " + how);
        clearPopups(before, looks(), this::next);
    }

    // ---- pop-ups ---------------------------------------------------------------

    /**
     * Looks (every 100 ms, {@code looksLeft} times) for a pop-up that came up since
     * {@code before} (any pop-up up, when null): presses its OK / Yes / Close ... button, or
     * dismisses it, waits for it to go, and looks again for a second one. With none, carries on.
     */
    private void clearPopups(Page.Before before, int looksLeft, Runnable then) {
        if (popupTaps >= 3) {
            log("pop-up: 3 presses after this box already - going on");
            later(then, 100);
            return;
        }
        later(() -> {
            Page.Popup p = Page.popup(service, before);
            if (p == null) {
                if (looksLeft > 1) {
                    clearPopups(before, looksLeft - 1, then);
                } else {
                    if (popupTaps == 0) {
                        log("pop-up: none came");
                        quietBoxes++;
                    }
                    then.run();
                }
                return;
            }
            popups++;
            popupTaps++;
            popupsSeen = true;
            quietBoxes = 0;
            AccessibilityNodeInfo gone;
            if (p.button != null) {
                Rect r = Page.bounds(p.button);
                log("pop-up (" + p.how + "): pressing \"" + Page.label(p.button) + "\" at "
                        + r.centerX() + "," + r.centerY());
                if (!p.button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(r.centerX(), r.centerY());
                gone = p.button;
            } else {
                log("pop-up (" + p.how + "): dismissing it");
                p.dismiss.performAction(AccessibilityNodeInfo.ACTION_DISMISS);
                gone = p.dismiss;
            }
            // Wait for it to go (up to 1.5 s), then look once more: a second pop-up may follow.
            // A lone ✕ closes one box: done. A dialog may be followed by a second one: look again.
            if (p.crossOnly) waitGone(gone, 0, then);
            else waitGone(gone, 0, () -> clearPopups(before == null ? null : new Page.Before(service), 3, then));
        }, 100);
    }

    private void waitGone(AccessibilityNodeInfo n, long waited, Runnable then) {
        later(() -> {
            boolean still;
            try {
                still = n.refresh() && n.isVisibleToUser();
            } catch (RuntimeException e) {
                still = false;
            }
            if (!still || waited >= 1500) {
                if (still) log("pop-up: its button is still there after 1.5 s");
                then.run();
            } else {
                waitGone(n, waited + 50, then);
            }
        }, 50);
    }

    // ---- finding boxes and reading the page ----------------------------------------

    private static final class Box {
        final AccessibilityNodeInfo node;
        final Rect box;
        final String row;

        Box(AccessibilityNodeInfo node, Rect box, String row) {
            this.node = node;
            this.box = box;
            this.row = row;
        }
    }

    /** The checkbox as a box on screen with its row number, or null when it is off screen. */
    private Box boxFor(AccessibilityNodeInfo node) {
        Rect box = visibleBox(node);
        if (box == null) return null;
        Rect s = screen();
        int bottom = s.height() - barHeight("navigation_bar_height");
        if (box.top < barHeight("status_bar_height") || box.bottom > bottom) return null;
        return new Box(node, box, rowOf(box, Page.nodes(service)));
    }

    private int barHeight(String name) {
        int id = service.getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? service.getResources().getDimensionPixelSize(id) : dp(24);
    }

    /**
     * Where the box you see is: the checkbox itself, or (when it is hidden, 0 wide) the
     * nearest parent with a real size - its label. Null when nothing is on screen.
     */
    private Rect visibleBox(AccessibilityNodeInfo n) {
        Rect screen = screen();
        long limit = (long) screen.width() * screen.height() / 8;
        AccessibilityNodeInfo p = n;
        for (int depth = 0; p != null && depth < 4; depth++, p = p.getParent()) {
            Rect r = new Rect();
            p.getBoundsInScreen(r);
            if (r.width() > 4 && r.height() > 4 && (long) r.width() * r.height() < limit) return r;
        }
        return null;
    }

    /** The number printed on the same line as the box (the row), or "?". */
    private String rowOf(Rect box, List<AccessibilityNodeInfo> nodes) {
        String best = "?";
        int bestGap = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            String t = Page.label(n);
            if (!t.matches("\\(?\\d{1,4}[.)]?")) continue;
            Rect r = Page.bounds(n);
            if (Math.abs(r.centerY() - box.centerY()) > Math.max(box.height(), r.height()) / 2 + 4) continue;
            if (Rect.intersects(r, box)) continue;
            int gap = r.left >= box.right ? r.left - box.right : box.left - r.right;
            if (gap < bestGap) {
                bestGap = gap;
                best = t.replaceAll("\\D", "");
            }
        }
        return best;
    }

    private String screenKey() {
        StringBuilder sb = new StringBuilder();
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            String t = Page.label(n);
            if (t.isEmpty()) continue;
            Rect r = Page.bounds(n);
            sb.append(t).append('@').append(r.top / 8).append('|');
        }
        return sb.toString();
    }

    // ---- gestures ----------------------------------------------------------------

    private void tap(int x, int y) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    /**
     * Scrolls the page: the page's own scroll action on its biggest scrolling list first;
     * when none takes it, a steady drag (no fling) of about half a screen.
     * {@code down}: show what is further down.
     */
    private void scroll(boolean down) {
        AccessibilityNodeInfo list = null;
        long biggest = 0;
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            if (!n.isScrollable() || !n.isVisibleToUser()) continue;
            Rect r = Page.bounds(n);
            long area = (long) r.width() * r.height();
            if (area > biggest) {
                list = n;
                biggest = area;
            }
        }
        if (list != null && list.performAction(down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return;
        drag(down);
    }

    private void drag(boolean down) {
        Rect s = screen();
        float a = s.height() * 3 / 4f, b = s.height() * 3 / 10f;
        float from = down ? a : b, to = down ? b : a;
        Path p = new Path();
        p.moveTo(s.centerX(), from);
        p.lineTo(s.centerX(), to);
        GestureDescription.StrokeDescription drag = new GestureDescription.StrokeDescription(p, 0, 450, true);
        service.dispatchGesture(new GestureDescription.Builder().addStroke(drag).build(),
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription g) {
                        Path hold = new Path();
                        hold.moveTo(s.centerX(), to);
                        service.dispatchGesture(new GestureDescription.Builder()
                                .addStroke(drag.continueStroke(hold, 0, 150, false)).build(), null, null);
                    }
                }, null);
    }

    private Rect screen() {
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
    }

    // ---- helpers -----------------------------------------------------------------

    private int dp(int v) {
        return Math.round(v * service.getResources().getDisplayMetrics().density);
    }

    private void later(Runnable r, long ms) {
        int g = gen;
        handler.postDelayed(() -> {
            if (!running || g != gen) return;
            try {
                r.run();
            } catch (RuntimeException e) {
                stop("Error: " + e);
            }
        }, ms);
    }

    private void log(String line) {
        log.append(SystemClock.uptimeMillis() - start).append(" ms  ").append(line).append('\n');
    }
}
