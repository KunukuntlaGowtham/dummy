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

        /** The rows not added so far this run (in the order they failed); empty when a run starts. */
        default void notAdded(List<String> rows) {
        }
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

    /** How the page reacted this run (null for Clear): kept per app and compared with another app's. */
    private Behaviour behaviour;

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

    // ---- the fast way: boxes by their place in the list, each clicked where it is ----------

    /**
     * The boxes already tried, by their place among the page's checkboxes (1st, 2nd ...): the
     * page may rebuild its list after each add, so its elements change but the places don't.
     * A box is tried once - never again, also when the page refuses it.
     */
    private final java.util.Set<Integer> attempted = new java.util.HashSet<>();
    /** The box ticked last (its place) and its row: checked on the next read of the page. */
    private int lastIdx = -1;
    /** The element of the box ticked last, to see whether the page rebuilt its list. */
    private AccessibilityNodeInfo lastNode;
    private String lastRow = "";

    /** The page at the start of the run (no pop-up up): a cover not in it is a pop-up still up. */
    private Page.Before runStart;
    /** Pop-ups cleared before the next click (one left up), for this click: at most 2. */
    private int lingerTries;
    /** The box ticked last read ☐ once and is being read again (the page may be redrawing it). */
    private boolean recheckedLast;
    /** Which click ticks the boxes here (box or label), learned in this run only. */
    private final ClickMethod clicks = new ClickMethod();
    /** The learned label click was used for a row already this run (said once in the log). */
    private boolean learnedUsed;

    /** Reads the page once to say what is there, then starts. */
    private void scanOnce() {
        attempted.clear();
        lastIdx = -1;
        lingerTries = 0;
        recheckedLast = false;
        clicks.reset(); // every run starts with the direct box click; nothing kept from before
        learnedUsed = false;
        List<AccessibilityNodeInfo> all = Page.nodes(service);
        runStart = new Page.Before(service, all);
        int boxes = 0, empty = 0;
        AccessibilityNodeInfo first = null;
        for (AccessibilityNodeInfo n : all) {
            if (!Page.isCheckbox(n)) continue;
            if (first == null) first = n;
            boxes++;
            if (!n.isChecked() && n.isEnabled()) empty++;
        }
        log("page read: " + boxes + " checkbox(es), " + empty + " empty - each is clicked where it is, on screen or not");
        if (behaviour != null) behaviour.page(first, continueButton());
        next();
    }

    /**
     * One read of the page: the verdict on the box ticked last (the page may have unticked it -
     * "Unable to add"), then the next empty box not tried yet, clicked straight away.
     * False when none is left.
     */
    private boolean nextPlanned() {
        List<AccessibilityNodeInfo> all = Page.nodes(service);
        List<AccessibilityNodeInfo> boxes = new ArrayList<>();
        for (AccessibilityNodeInfo n : all) if (Page.isCheckbox(n)) boxes.add(n);
        // (2) A pop-up still up (it came late, after the watch ended): its cover is in this read
        // and wasn't there at the start - clear it before the next click, else that click would
        // land under it. From this read already made: nothing extra to read.
        if (runStart != null && lingerTries < 2) {
            for (String k : Page.coverKeys(service, all)) {
                if (runStart.covers.contains(k)) continue;
                lingerTries++;
                log("pop-up still up before the next click (it came late) - clearing it first");
                tickTime = 0;
                String cover = k;
                clearPopups(runStart, looks(), () -> {
                    // No pop-up there after all: that element is part of the page now - not
                    // looked at again before every click.
                    if (popupTaps == 0 && runStart != null) runStart.covers.add(cover);
                    next();
                });
                return true;
            }
        }
        // The box ticked last, gone from the page: the page rebuilt its list after the add.
        if (lastNode != null && behaviour != null) {
            boolean still;
            try {
                still = lastNode.refresh();
            } catch (RuntimeException e) {
                still = false;
            }
            if (!still) behaviour.rebuilt();
        }
        lastNode = null;
        if (lastIdx >= 0 && lastIdx < boxes.size() && !boxes.get(lastIdx).isChecked()) {
            // (1) After OK the page redraws the row (~80 ms): a box just added can read ☐ for a
            // moment. Read it once more 150 ms later before calling it not added.
            if (!recheckedLast) {
                recheckedLast = true;
                log("row " + lastRow + " reads ☐ - reading it again in 150 ms (the page may be redrawing it)");
                later(this::next, 150);
                return true;
            }
            refused(lastRow, "the page unticked it again (not added)");
        } else if (recheckedLast && lastIdx >= 0) {
            log("row " + lastRow + " reads ☑ on the second read - added ✓");
        }
        recheckedLast = false;
        lastIdx = -1;
        for (int i = 0; i < boxes.size(); i++) {
            if (attempted.contains(i)) continue;
            AccessibilityNodeInfo n = boxes.get(i);
            if (n.isChecked() || !n.isEnabled()) continue;
            attempted.add(i);
            lingerTries = 0;
            int idx = i;
            String row = String.valueOf(i + 1);
            Box b = new Box(n, Page.bounds(n), row);
            Page.Before before = new Page.Before(service, all);
            String name = Page.label(n);
            log("row " + row + ": clicking its checkbox" + (name.isEmpty() ? "" : " \"" + name + "\"")
                    + (n.isVisibleToUser() ? "" : " (off screen)"));
            lastChance = false;
            // This row's own label (never one kept from an earlier row - the page redraws).
            AccessibilityNodeInfo label = clickableParent(n);
            ClickMethod.Way first = clicks.first(label != null);
            if (first == ClickMethod.Way.LABEL) {
                log("row " + row + ": clicking its label first (a label click ticked an earlier row this run)"
                        + (learnedUsed ? "" : " - the learned way, used from now on"));
                learnedUsed = true;
                label.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } else {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            whenCheckedAt(n, idx, 450, ok -> {
                if (ok) {
                    fastTicked(n, idx, row, first, label != null, false, before);
                    return;
                }
                // The other way, once. Re-read the box and its own label first: the page may
                // have redrawn the row, and a late tick by the first click must not get a
                // second click (it would untick the box).
                AccessibilityNodeInfo current = checkboxAt(n, idx);
                boolean nowChecked = current != null && current.isChecked();
                AccessibilityNodeInfo currentLabel = current == null ? null : clickableParent(current);
                ClickMethod.Way second = current == null ? null
                        : clicks.fallback(first, currentLabel != null, nowChecked);
                if (second != null) {
                    log("row " + row + ": the " + way(first) + " didn't tick it - " + way(second) + " once");
                    (second == ClickMethod.Way.LABEL ? currentLabel : current)
                            .performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } else if (nowChecked) {
                    log("row " + row + ": reads ☑ now (a late tick by the " + way(first) + ") - no second click");
                } else {
                    log("row " + row + ": the " + way(first) + " didn't tick it - "
                            + (current == null ? "the box went from the page" : "no label to click"));
                }
                whenCheckedAt(n, idx, second != null ? 450 : 0, byFallback -> {
                    if (byFallback) {
                        fastTicked(n, idx, row, second != null ? second : first, currentLabel != null,
                                second != null, before);
                        return;
                    }
                    // Not ticked: if a pop-up comes the page has answered; either way, on to the next.
                    note("not ticked");
                    clearPopups(before, looks(), () -> {
                        refused(row, popupTaps > 0 ? "not ticked - the page answered with a pop-up"
                                : "the click didn't tick it");
                        next();
                    });
                });
            });
            return true;
        }
        return false;
    }

    /**
     * The row's box read ☑ after {@code way}: counted, the way learned (only now - verified,
     * not because the click was sent), its pop-up cleared.
     */
    private void fastTicked(AccessibilityNodeInfo n, int idx, String row, ClickMethod.Way way,
                            boolean rowHadLabel, boolean byFallback, Page.Before before) {
        lastNode = n;
        lastIdx = idx;
        lastRow = row;
        ticked++;
        tickedRows.add(row);
        log("row " + row + ": ticked ✓ by a " + way(way) + (byFallback ? " (the other way)" : ""));
        note(way == ClickMethod.Way.LABEL ? "label" : "click");
        if (clicks.verified(way, rowHadLabel)) {
            log(way == ClickMethod.Way.LABEL
                    ? "learned for this run: a label click ticks the boxes here - the label is clicked first from the next row"
                    : "learned for this run: a click on the box ticks it again - the box is clicked first from the next row");
        }
        tickTime = SystemClock.uptimeMillis();
        waitUntil = tickTime + waitMs();
        clearPopups(before, looks(), this::next);
    }

    private static String way(ClickMethod.Way w) {
        return w == ClickMethod.Way.LABEL ? "click on its label" : "click on the box";
    }

    /**
     * Whether the box at place {@code idx} is ticked: its element when it is still on the page,
     * else (the page rebuilt its list) the box now at that place.
     */
    private boolean checkedAt(AccessibilityNodeInfo n, int idx) {
        AccessibilityNodeInfo current = checkboxAt(n, idx);
        return current != null && current.isChecked();
    }

    /** The box at place {@code idx} now: its element refreshed, else the one now at that place. */
    private AccessibilityNodeInfo checkboxAt(AccessibilityNodeInfo n, int idx) {
        try {
            if (n.refresh()) return n;
        } catch (RuntimeException ignored) {
        }
        if (behaviour != null) behaviour.rebuilt();
        int i = 0;
        for (AccessibilityNodeInfo m : Page.nodes(service)) {
            if (!Page.isCheckbox(m)) continue;
            if (i++ == idx) return m;
        }
        return null;
    }

    /** Checks every 40 ms (up to {@code ms}) whether the box at {@code idx} turned ☑. */
    private void whenCheckedAt(AccessibilityNodeInfo n, int idx, long ms, java.util.function.Consumer<Boolean> then) {
        if (ms <= 0) {
            then.accept(checkedAt(n, idx));
            return;
        }
        whenCheckedUntil(n, idx, SystemClock.uptimeMillis() + ms, then);
    }

    /**
     * Until {@code until} (the clock, not a count of looks: a look the page's events wake
     * early must not use up the wait - the label click after it could untick the box).
     */
    private void whenCheckedUntil(AccessibilityNodeInfo n, int idx, long until, java.util.function.Consumer<Boolean> then) {
        poll(() -> {
            boolean on = checkedAt(n, idx);
            if (on || SystemClock.uptimeMillis() >= until - 5) then.accept(on);
            else whenCheckedUntil(n, idx, until, then);
        }, 40);
    }

    /** A row not added: noted, said on screen, never tried again. */
    private void refused(String row, String why) {
        if (behaviour != null) {
            behaviour.refused(why.contains("unticked") ? "unticks it after its pop-up" : "won't tick");
        }
        if (tickedRows.remove(row)) ticked--;
        notTicked++;
        failedRows.add(row);
        log("row " + row + ": " + why + " - going on, not tried again ✗");
        notAdded(row);
    }

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
        behaviour = clear ? null : new Behaviour(spotKey.startsWith("ok_spot_") ? spotKey.substring(8) : null);
        String spot = service.getSharedPreferences("popup", android.content.Context.MODE_PRIVATE).getString(spotKey, null);
        okSpot = spot == null ? null : Rect.unflattenFromString(spot);
        coverApp = service.getSharedPreferences("popup", android.content.Context.MODE_PRIVATE)
                .getBoolean("cover_" + spotKey, false);
        runSpot = null;
        if (!clear) missedChanged(); // a new run: nothing missed yet
    }

    /** Tells the listener the rows not added so far (for the box in the corner). */
    private void missedChanged() {
        listener.notAdded(new ArrayList<>(failedRows));
    }

    void stop(String why) {
        if (!running) return;
        running = false;
        gen++;
        log("END: " + why);
        String summary = clearOnly ? why + "\nPop-ups cleared " + popups : why + "\n"
                + (failedRows.isEmpty() ? "✅ All added" : "❌ Not added: row" + (failedRows.size() > 1 ? "s " : " ")
                        + String.join(", ", failedRows))
                + "\n☑ Ticked " + ticked
                + (tickedRows.isEmpty() ? "" : " (rows " + String.join(", ", tickedRows) + ")")
                + "\nNot ticked " + notTicked
                + "\nPop-ups cleared " + popups;
        String reacted = "";
        if (behaviour != null && ticked + notTicked > 0) {
            StringBuilder lines = new StringBuilder();
            try {
                reacted = behaviour.finish(service, continueButton(), lines);
            } catch (RuntimeException e) {
                reacted = "\n\nHOW THE PAGE REACTED: not saved (" + e + ")";
            }
            summary += lines;
        }
        behaviour = null;
        listener.done(summary, "A11y Inspector - " + (clearOnly ? "Clear pop-ups" : "Tick run")
                + "\n=========================\n" + summary + reacted + "\n\nSTEPS\n" + log);
    }

    // ---- one box after another ------------------------------------------------------

    private void next() {
        popupTaps = 0;
        if (!clearOnly) {
            if (nextPlanned()) return;
            if (endChecks++ >= 1) {
                stop("No empty checkbox left on the page");
            } else {
                // Some pages only add rows once scrolled to: one scroll, then read again.
                log("no empty checkbox left - one scroll to be sure");
                scroll(true);
                later(this::next, 450);
            }
            return;
        }
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
                missedChanged();
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
                note("click");
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
                    note("label");
                    done(b, "ticked ✓ by a click on its label", before);
                    return;
                }
                log("row " + b.row + ": tapping it at " + b.box.centerX() + "," + b.box.centerY());
                tap(b.box.centerX(), b.box.centerY());
                whenChecked(b.node, 500, ok -> {
                    if (ok) {
                        note("tap");
                        done(b, "ticked ✓ by a tap", before);
                    } else {
                        note("not ticked");
                        notTicked++;
                        failedRows.add(b.row);
                        log("row " + b.row + ": still empty ✗");
                        missedChanged();
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

    /** Says on screen, as it happens, that a row wasn't added (Tick goes on to the next). */
    private void notAdded(String row) {
        missedChanged();
        android.widget.Toast.makeText(service, "Row " + row + " not added - going on to the next",
                android.widget.Toast.LENGTH_SHORT).show();
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
        clearPopups(before, looks(), () -> afterPopup(b));
    }

    /**
     * After the pop-up: a page that couldn't take the tick ("Unable to add ...") unticks the
     * box again - counted as not ticked, and never tried again (no loop): on to the next box.
     */
    private void afterPopup(Box b) {
        if (!Page.isChecked(b.node)) {
            ticked--;
            tickedRows.remove(b.row);
            notTicked++;
            failedRows.add(b.row);
            log("row " + b.row + ": the page unticked it again (not added) - going on, not tried again ✗");
            if (behaviour != null) behaviour.refused("unticks it after its pop-up");
            notAdded(b.row);
        }
        next();
    }

    /** A pop-up came: note how long after the tick, to wait just that long next time. */
    private void popupCame() {
        lastDelay = -1;
        if (tickTime == 0) return;
        long d = SystemClock.uptimeMillis() - tickTime;
        lastDelay = d;
        tickTime = 0;
        popDelay = Math.max(popDelay, d);
        log("pop-up came " + d + " ms after the tick - next boxes watch " + waitMs() + " ms");
    }

    /** How late the pop-up just seen came after its tick (-1: not after a tick). */
    private long lastDelay = -1;

    private void note(String way) {
        if (behaviour != null) behaviour.ticked(way);
    }

    private void notePopup(String seenAs, String closedBy) {
        if (behaviour != null) behaviour.popup(seenAs, closedBy, lastDelay);
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
        poll(() -> {
            boolean woken = pollRanWoken; // woken early by the page: doesn't count as a look
            Page.Popup p = Page.popup(service, before);
            if (p == null) {
                // The page hides the pop-up's words and buttons but reports its cover over the
                // page: with the OK's place known, tap it now - no screenshot needed.
                boolean cover = Page.coverCame(service, before);
                if (cover && before != null && !coverApp) {
                    coverApp = true;
                    service.getSharedPreferences("popup", android.content.Context.MODE_PRIVATE).edit()
                            .putBoolean("cover_" + spotKey, true).apply();
                    log("this app reports its pop-up's cover - from now on a screenshot only once the cover comes");
                }
                // The OK place confirmed this run (a tap there closed the last pop-up): tap it now.
                if (cover && runSpot != null && before != null) {
                    pressRunSpot(before, then);
                    return;
                }
                // Pop-ups differ (OK, Proceed, Close ... in other places): with screenshots, the
                // purple button is found on the screen each time; the remembered place is used
                // only when no screenshot can be taken.
                if (cover && okSpot != null && !shots) {
                    pressSpot(before, then);
                    return;
                }
                Runnable lookOn = () -> {
                    if (looksLeft > 1 && (tickTime == 0 || SystemClock.uptimeMillis() < waitUntil)) {
                        clearPopups(before, woken ? looksLeft : looksLeft - 1, then);
                    } else {
                        // A cover app and no cover came: one screenshot still, so a pop-up drawn
                        // without its cover isn't missed (only rows where nothing came pay for it).
                        if (coverApp && shots && popupTaps == 0 && before != null && !lastChance) {
                            lastChance = true;
                            int g2 = gen;
                            later(() -> finder.find(r -> {
                                if (!running || g2 != gen) return;
                                if (r != null) pressPurple(r, before, then);
                                else {
                                    log("pop-up: none came (no cover, none on the last screenshot)");
                                    quietBoxes++;
                                    then.run();
                                }
                            }, why -> {
                                shots = false;
                                log("pop-up: no screenshots - " + why);
                            }, before), finder.waitMs());
                            return;
                        }
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
                boolean shotDue = cover || before == null || (!coverApp && now - lastBlindShot >= 1000);
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
                    }, before);
                    return;
                }
                lookOn.run();
                return;
            }
            popupCame();
            notePopup("reported (" + p.how + ")", p.button != null ? "pressing its button" : "dismissing it");
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
            else waitGone(gone, 0, () -> secondLook(before, then));
        }, 50);
    }

    /** Where this app's OK place is kept ("ok_spot_<app>"). */
    private String spotKey = "ok_spot";

    /** When the last screenshot was taken without a cover in the tree. */
    private long lastBlindShot;

    /**
     * This app reports its pop-up's cover in the tree (the TTD app's web view does; kept per
     * app): no screenshot before the cover comes - an early one, before the pop-up is drawn,
     * only holds the next one back (~3 screenshots a second).
     */
    private boolean coverApp;
    /** One screenshot at the end of a quiet watch was taken for this row (cover apps). */
    private boolean lastChance;
    /**
     * Where OK was tapped this run and the pop-up's cover then went: the next cover gets a tap
     * there straight away, no screenshot. Found again on a screenshot when a tap there doesn't
     * close it. Not kept across runs.
     */
    private Rect runSpot;

    /** Where the pop-up's OK is (learned from a screenshot once, kept for next time), or null. */
    private Rect okSpot;

    /** The pop-up's purple button, seen on the screenshot: tapped, then checked that it went. */
    private void pressPurple(Rect r, Page.Before before, Runnable then) {
        popupCame();
        notePopup(Page.coverCame(service, before) ? "cover only (words and OK hidden)" : "drawn, not reported (no cover)",
                "its purple button on a screenshot");
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
        if (Page.coverCame(service, before)) {
            waitCoverGoneSince(before, SystemClock.uptimeMillis(), r, then);
        } else {
            later(() -> checkPurpleGone(r, then), Math.max(350, finder.waitMs()));
        }
    }

    /** The cover came and OK's place is confirmed this run: tap it straight away, check it went. */
    private void pressRunSpot(Page.Before before, Runnable then) {
        popupCame();
        notePopup("cover only (words and OK hidden)", "the OK place found earlier this run");
        popups++;
        popupTaps++;
        popupsSeen = true;
        quietBoxes = 0;
        log("pop-up: its cover came - tapping OK where it was this run (" + runSpot.centerX() + ","
                + runSpot.centerY() + "), no screenshot");
        tap(runSpot.centerX(), runSpot.centerY());
        waitSpotGone(before, SystemClock.uptimeMillis(), 1, then);
    }

    /**
     * After a tap at the run's OK place: on the moment the cover goes. Still up after 350 ms:
     * one more tap there (the pop-up may still have been drawing); then OK is found on a
     * screenshot again (another pop-up, OK elsewhere) and the kept place is dropped.
     */
    private void waitSpotGone(Page.Before before, long since, int taps, Runnable then) {
        poll(() -> {
            long waited = SystemClock.uptimeMillis() - since;
            if (!Page.coverCame(service, before)) {
                log("pop-up: gone (" + waited + " ms after the tap)");
                then.run();
            } else if (waited < 350) {
                waitSpotGone(before, since, taps, then);
            } else if (taps < 2 && runSpot != null) {
                log("pop-up: still up - tapping its OK place once more");
                popupTaps++;
                tap(runSpot.centerX(), runSpot.centerY());
                waitSpotGone(before, SystemClock.uptimeMillis(), taps + 1, then);
            } else {
                log("pop-up: still up after taps at the kept place - finding its OK on a screenshot");
                runSpot = null;
                clearPopups(before, 20, then);
            }
        }, 40);
    }

    /** The pop-up's cover is in the tree and its OK's place is known: tap it straight away. */
    private void pressSpot(Page.Before before, Runnable then) {
        popupCame();
        notePopup("cover only (words and OK hidden)", "its remembered OK place");
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
        waitCoverGoneSince(before, SystemClock.uptimeMillis() - waited, then);
    }

    /** As above, the time since {@code since} by the clock (woken looks don't count as 40 ms). */
    private void waitCoverGoneSince(Page.Before before, long since, Runnable then) {
        waitCoverGoneSince(before, since, null, then);
    }

    /**
     * {@code tapped}: where OK was just tapped (found on a screenshot) - when the cover goes,
     * that place is kept for this run (only then: the tap there is what closed it).
     */
    private void waitCoverGoneSince(Page.Before before, long since, Rect tapped, Runnable then) {
        poll(() -> {
            long waited = SystemClock.uptimeMillis() - since;
            if (!Page.coverCame(service, before)) {
                log("pop-up: gone (" + waited + " ms)");
                if (tapped != null) {
                    if (runSpot == null) log("pop-up: its OK place is kept for this run - next pop-ups get a tap there, no screenshot");
                    runSpot = new Rect(tapped);
                }
                then.run();
            } else if (waited >= 1200) {
                log("pop-up: still up - finding its OK again on a screenshot");
                okSpot = null;
                clearPopups(before, 20, then);
            } else {
                waitCoverGoneSince(before, since, tapped, then);
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

    /** One quick look for a second pop-up after the first went (no screenshot). */
    private void secondLook(Page.Before before, Runnable then) {
        later(() -> {
            Page.Popup p = Page.popup(service, before);
            if (p != null && p.button != null && !p.crossOnly && popupTaps < 3) clearPopups(before, 1, then);
            else then.run();
        }, 60);
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

    /** The page's Continue button, or null. */
    private AccessibilityNodeInfo continueButton() {
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            if (n.isClickable() && Page.label(n).trim().equalsIgnoreCase("continue")) return n;
        }
        return null;
    }

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

    // ---- waits woken by the page's own change events -------------------------------------

    /** The look waiting for its timer (a box turning ☑, a pop-up's cover coming / going). */
    private Runnable pollTask;
    /** The page already woke the waiting look: further events of the same burst don't. */
    private boolean pollWoken;
    /** The look running now was woken early by the page (not by its timer). */
    private boolean pollRanWoken;

    /**
     * Like {@link #later}, for a look that waits for the page to change: it also runs as soon
     * as the page says it changed ({@link #onPageEvent}), not only when its timer ends.
     */
    private void poll(Runnable r, long ms) {
        int g = gen;
        Runnable[] self = new Runnable[1];
        self[0] = () -> {
            pollRanWoken = pollTask == self[0] && pollWoken;
            if (pollTask == self[0]) pollTask = null;
            if (!running || g != gen) return;
            try {
                r.run();
            } catch (RuntimeException e) {
                stop("Error: " + e);
            }
        };
        pollTask = self[0];
        pollWoken = false;
        handler.postDelayed(self[0], ms);
    }

    /**
     * The page's accessibility event (from the service): the box changed, the pop-up's cover
     * came or went ... - the waiting look runs now (10 ms later, so a burst of events counts
     * once) instead of at its 40 / 50 ms timer.
     */
    void onPageEvent(android.view.accessibility.AccessibilityEvent e) {
        if (!running || pollTask == null || pollWoken) return;
        int t = e.getEventType();
        if (t != android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && t != android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && t != android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg != null && service.getPackageName().contentEquals(pkg)) return; // our own windows
        pollWoken = true;
        Runnable task = pollTask;
        handler.removeCallbacks(task);
        handler.postDelayed(task, 10);
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
