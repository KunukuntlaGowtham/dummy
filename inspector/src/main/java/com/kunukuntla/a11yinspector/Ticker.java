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

    /** Finds a pop-up's purple button on a screenshot, for pop-ups the page doesn't report. */
    private final PurpleFinder finder;
    /** Screenshots work here (turned off after the first failure of a run). */
    private boolean shots;

    Ticker(AccessibilityService service, Listener listener) {
        this.service = service;
        this.listener = listener;
        this.finder = new PurpleFinder(service);
    }

    boolean isRunning() {
        return running;
    }

    void start() {
        begin(false);
        log("Tick: clicking every empty checkbox through accessibility, clearing pop-ups");
        later(this::scanOnce, 50);
    }

    // ---- the fast way: scan the page once, then click each box straight away ------------

    /** The empty checkboxes found by the one scan at the start, in page order, and their rows. */
    private final List<AccessibilityNodeInfo> plan = new ArrayList<>();
    private final java.util.Map<AccessibilityNodeInfo, String> planRows = new java.util.HashMap<>();
    /** Boxes the quick click didn't tick: left for the careful way (on screen, label, tap). */
    private final List<AccessibilityNodeInfo> slow = new ArrayList<>();
    /** The page as it was at the start, with no pop-up: what a pop-up adds is seen against it. */
    private Page.Before baseline;

    /**
     * Reads the page once: every empty checkbox (also those below the screen - a web page
     * reports them all) with its row number, and the page without a pop-up.
     */
    private void scanOnce() {
        plan.clear();
        planRows.clear();
        slow.clear();
        List<AccessibilityNodeInfo> all = Page.nodes(service);
        for (AccessibilityNodeInfo n : all) {
            if (!Page.isCheckbox(n) || n.isChecked() || !n.isEnabled()) continue;
            plan.add(n);
            Rect seen = Page.visible(n);
            planRows.put(n, rowOf(seen == null ? Page.bounds(n) : seen, all));
        }
        baseline = new Page.Before(service);
        List<String> rows = new ArrayList<>();
        for (AccessibilityNodeInfo n : plan) rows.add(planRows.get(n));
        log("scanned once: " + plan.size() + " empty checkbox(es)" + (rows.isEmpty() ? "" : ", rows " + String.join(", ", rows))
                + " - each is clicked where it is, on screen or not");
        next();
    }

    /** The next box of the one scan, clicked straight away (no scrolling); false when none is left. */
    private boolean nextPlanned() {
        while (!plan.isEmpty()) {
            AccessibilityNodeInfo n = plan.remove(0);
            try {
                if (!n.refresh()) continue; // gone from the page
            } catch (RuntimeException e) {
                continue;
            }
            if (n.isChecked() || !n.isEnabled()) continue;
            String row = planRows.get(n);
            Rect r = Page.bounds(n);
            Box b = new Box(n, r, row == null ? "?" : row);
            log("row " + b.row + ": clicking its checkbox" + (n.isVisibleToUser() ? "" : " (below / above the screen)"));
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            whenChecked(n, 450, ok -> {
                if (ok) {
                    tried.add(n);
                    done(b, "ticked ✓ by a click", baseline);
                } else {
                    log("row " + b.row + ": the click didn't tick it - tried again the careful way at the end");
                    slow.add(n);
                    clearPopups(baseline, 3, this::next);
                }
            });
            return true;
        }
        return false;
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
        shots = PurpleFinder.available();
        tickTime = popDelay = waitUntil = 0;
        spotKey = "ok_spot";
        for (android.view.accessibility.AccessibilityWindowInfo w : Page.windows(service)) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root != null && root.getPackageName() != null) {
                spotKey = "ok_spot_" + root.getPackageName(); // each app's pop-up sits elsewhere
                break;
            }
        }
        String spot = service.getSharedPreferences("popup", android.content.Context.MODE_PRIVATE).getString(spotKey, null);
        okSpot = spot == null ? null : Rect.unflattenFromString(spot);
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
        popupTaps = 0;
        if (nextPlanned()) return;
        // The careful way: boxes the quick click missed, and any the page added since (some
        // pages only add rows once scrolled to) - brought on screen, then label / tap.
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

    /** When the last box was ticked (0 once its pop-up came), and how late pop-ups come here. */
    private long tickTime, popDelay, waitUntil;

    /**
     * How long to watch for a pop-up after a tick: once one has come, as long as it took plus
     * 0.7 s (the page is learned); before that up to 3 s; 0.8 s once none came for 2 boxes.
     */
    private long waitMs() {
        if (popDelay > 0) return Math.min(3000, popDelay + 700);
        return quietBoxes >= 2 ? 800 : 3000;
    }

    private int looks() {
        return (int) (waitMs() / 100);
    }

    private void done(Box b, String how, Page.Before before) {
        ticked++;
        tickedRows.add(b.row);
        log("row " + b.row + ": " + how);
        tickTime = SystemClock.uptimeMillis();
        waitUntil = tickTime + waitMs();
        clearPopups(before, looks(), this::next);
    }

    /** A pop-up came: note how long after the tick, to wait just that long next time. */
    private void popupCame() {
        if (tickTime == 0) return;
        long d = SystemClock.uptimeMillis() - tickTime;
        tickTime = 0;
        popDelay = Math.max(popDelay, d);
        log("pop-up came " + d + " ms after the tick - next boxes watch " + waitMs() + " ms");
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
                // The page hides the pop-up's words and buttons but reports its cover over the
                // page: with the OK's place known, tap it now - no screenshot needed.
                boolean cover = Page.coverCame(service, before);
                // Pop-ups differ (OK, Proceed, Close ... in other places): with screenshots, the
                // purple button is found on the screen each time; the remembered place is used
                // only when no screenshot can be taken.
                if (cover && okSpot != null && !shots) {
                    pressSpot(before, then);
                    return;
                }
                Runnable lookOn = () -> {
                    if (looksLeft > 1 && (tickTime == 0 || SystemClock.uptimeMillis() < waitUntil)) {
                        clearPopups(before, looksLeft - 1, then);
                    } else {
                        if (popupTaps == 0) {
                            log("pop-up: none came");
                            quietBoxes++;
                        }
                        then.run();
                    }
                };
                // Not in the tree: the page may draw it without reporting it - look for its purple
                // button on a screenshot (only for pop-ups; ~3 screenshots a second at most).
                // With the cover in the tree a screenshot is needed only once it has come; pages
                // that report no cover get one every second at most (screenshots slow things down).
                long now = SystemClock.uptimeMillis();
                boolean shotDue = cover || before == null || now - lastBlindShot >= 1000;
                if (shots && shotDue && finder.waitMs() == 0) {
                    if (!cover) lastBlindShot = now;
                    int g = gen;
                    finder.find(r -> {
                        if (!running || g != gen) return;
                        if (r != null) pressPurple(r, before, then);
                        else lookOn.run();
                    }, why -> {
                        shots = false;
                        log("pop-up: no screenshots - " + why);
                    });
                    return;
                }
                lookOn.run();
                return;
            }
            popupCame();
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
        }, 50);
    }

    /** Where this app's OK place is kept ("ok_spot_<app>"). */
    private String spotKey = "ok_spot";

    /** When the last screenshot was taken without a cover in the tree. */
    private long lastBlindShot;

    /** Where the pop-up's OK is (learned from a screenshot once, kept for next time), or null. */
    private Rect okSpot;

    /** The pop-up's purple button, seen on the screenshot: tapped, then checked that it went. */
    private void pressPurple(Rect r, Page.Before before, Runnable then) {
        popupCame();
        popups++;
        popupTaps++;
        popupsSeen = true;
        quietBoxes = 0;
        okSpot = new Rect(r);
        service.getSharedPreferences("popup", android.content.Context.MODE_PRIVATE).edit()
                .putString(spotKey, r.flattenToString()).putString("ok_spot", r.flattenToString()).apply();
        log("pop-up (drawn, not reported): tapping its purple button at " + r.centerX() + "," + r.centerY()
                + " - its place is remembered");
        tap(r.centerX(), r.centerY());
        if (Page.coverCame(service, before)) waitCoverGone(before, 0, then);
        else later(() -> checkPurpleGone(r, then), Math.max(350, finder.waitMs()));
    }

    /** The pop-up's cover is in the tree and its OK's place is known: tap it straight away. */
    private void pressSpot(Page.Before before, Runnable then) {
        popupCame();
        popups++;
        popupTaps++;
        popupsSeen = true;
        quietBoxes = 0;
        log("pop-up: its cover came - tapping OK at " + okSpot.centerX() + "," + okSpot.centerY());
        tap(okSpot.centerX(), okSpot.centerY());
        waitCoverGone(before, 0, then);
    }

    /** Goes on the moment the pop-up's cover has gone; if it stays, finds the OK again on a screenshot. */
    private void waitCoverGone(Page.Before before, long waited, Runnable then) {
        later(() -> {
            if (!Page.coverCame(service, before)) {
                log("pop-up: gone (" + waited + " ms)");
                then.run();
            } else if (waited >= 1200) {
                log("pop-up: still up - finding its OK again on a screenshot");
                okSpot = null;
                clearPopups(before, 20, then);
            } else {
                waitCoverGone(before, waited + 40, then);
            }
        }, 40);
    }

    private void checkPurpleGone(Rect was, Runnable then) {
        int g = gen;
        finder.find(r -> {
            if (!running || g != gen) return;
            boolean still = r != null && Math.abs(r.centerX() - was.centerX()) < was.width() / 2
                    && Math.abs(r.centerY() - was.centerY()) < was.height();
            if (still && popupTaps < 3) {
                log("pop-up: still there - tapping again");
                popupTaps++;
                tap(r.centerX(), r.centerY());
                later(() -> checkPurpleGone(r, then), Math.max(350, finder.waitMs()));
            } else {
                if (still) log("pop-up: still there after 3 taps - going on");
                then.run();
            }
        }, why -> {
        });
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
