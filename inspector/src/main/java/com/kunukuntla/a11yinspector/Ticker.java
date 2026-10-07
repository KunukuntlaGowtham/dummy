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

    /** Reads the page once to say what is there, then starts. */
    private void scanOnce() {
        attempted.clear();
        lastIdx = -1;
        List<AccessibilityNodeInfo> all = Page.nodes(service);
        int boxes = 0, empty = 0;
        AccessibilityNodeInfo first = null;
        for (AccessibilityNodeInfo n : all) {
            if (!Page.isCheckbox(n)) continue;
            if (first == null) first = n;
            boxes++;
            if (!n.isChecked() && n.isEnabled()) empty++;
        }
        log("environment: " + app() + " - " + environment(all));
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
        if (lastIdx >= 0) {
            int left = 0;
            for (AccessibilityNodeInfo m : boxes) if (!m.isChecked() && m.isEnabled()) left++;
            AccessibilityNodeInfo cont = continueButton();
            log("row " + lastRow + " final page state: box " + (lastIdx < boxes.size() && boxes.get(lastIdx).isChecked() ? "☑" : "☐")
                    + ", Continue " + (cont == null ? "none" : cont.isEnabled() ? "on" : "off")
                    + ", empty boxes left " + left);
        }
        if (lastIdx >= 0 && lastIdx < boxes.size() && !boxes.get(lastIdx).isChecked()) {
            refused(lastRow, "the page unticked it again (not added)");
        }
        lastIdx = -1;
        for (int i = 0; i < boxes.size(); i++) {
            if (attempted.contains(i)) continue;
            AccessibilityNodeInfo n = boxes.get(i);
            if (n.isChecked() || !n.isEnabled()) continue;
            attempted.add(i);
            int idx = i;
            String row = String.valueOf(i + 1);
            Box b = new Box(n, Page.bounds(n), row);
            Page.Before before = new Page.Before(service, all);
            String name = Page.label(n);
            lastSignals = "";
            lastShot = "not taken yet";
            lastChance = false;
            log("row " + row + " [" + app() + "]: checkbox found - " + describeBox(n) + ", state before ☐");
            log("row " + row + ": clicking its checkbox" + (name.isEmpty() ? "" : " \"" + name + "\"")
                    + (n.isVisibleToUser() ? "" : " (off screen)") + " - click method: ACTION_CLICK on the box");
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            // The page answers every tap on a box - tick or untick - with a pop-up (its cover
            // comes within ~0.5 s), and the box may read ☑ only a moment later. So once the page
            // has answered, the box gets no second click: that would untick it.
            whenTickedOrAnswered(n, idx, before, 700, r -> {
                if (r == TICKED) {
                    fastTicked(n, idx, row, "click", before);
                    return;
                }
                if (r == ANSWERED) {
                    answeredNotTicked(n, idx, row, "click", before);
                    return;
                }
                // No answer at all: the click didn't reach the box - its label, once.
                AccessibilityNodeInfo label = clickableParent(n);
                log("row " + row + ": state after the click ☐, and the page didn't answer - " + (label != null
                        ? "click method: ACTION_CLICK on its label" : "no tappable label around it"));
                if (label != null) label.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                whenTickedOrAnswered(n, idx, before, label != null ? 700 : 0, r2 -> {
                    if (r2 == TICKED) {
                        fastTicked(n, idx, row, "label", before);
                        return;
                    }
                    if (r2 == ANSWERED) {
                        answeredNotTicked(n, idx, row, "label", before);
                        return;
                    }
                    // Not ticked: if a pop-up comes the page has answered; either way, on to the next.
                    log("row " + row + ": state after ☐ - not ticked");
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

    private static final int NOTHING = 0, TICKED = 1, ANSWERED = 2;

    /**
     * Waits (every 60 ms, up to {@code ms}) until the box reads ☑ (TICKED) or the page has
     * answered the click without it - a pop-up or its cover came (ANSWERED); else NOTHING.
     */
    private void whenTickedOrAnswered(AccessibilityNodeInfo n, int idx, Page.Before before, long ms,
                                      java.util.function.Consumer<Integer> then) {
        if (checkedAt(n, idx)) {
            then.accept(TICKED);
            return;
        }
        if (answered(before)) {
            then.accept(ANSWERED);
            return;
        }
        if (ms <= 0) {
            then.accept(NOTHING);
            return;
        }
        later(() -> whenTickedOrAnswered(n, idx, before, ms - 60, then), 60);
    }

    /** The page answered since {@code before}: its pop-up's cover, or a pop-up accessibility shows. */
    private boolean answered(Page.Before before) {
        return Page.coverCame(service, before) || Page.popup(service, before) != null;
    }

    /** The box reads ☑ after the click ({@code way}): counted, then its pop-up is cleared. */
    private void fastTicked(AccessibilityNodeInfo n, int idx, String row, String way, Page.Before before) {
        lastNode = n;
        lastIdx = idx;
        lastRow = row;
        ticked++;
        tickedRows.add(row);
        log("row " + row + ": state after ☑ - ticked ✓ by a click" + (way.equals("label") ? " on its label" : ""));
        note(way);
        tickTime = SystemClock.uptimeMillis();
        waitUntil = tickTime + waitMs();
        hintUntil = tickTime + 3000;
        clearPopups(before, looks(), this::next);
    }

    /**
     * The page answered the click (its pop-up came) but the box doesn't read ☑ yet: no second
     * click - it would untick the box. The pop-up is cleared, then the box is read once more.
     */
    private void answeredNotTicked(AccessibilityNodeInfo n, int idx, String row, String way, Page.Before before) {
        log("row " + row + ": the page answered the " + way + " (its pop-up came) but the box still reads ☐ -"
                + " no second click (it would untick it); clearing the pop-up, then reading the box");
        tickTime = SystemClock.uptimeMillis();
        waitUntil = tickTime + waitMs();
        hintUntil = tickTime + 3000;
        clearPopups(before, looks(), () -> {
            if (checkedAt(n, idx)) {
                lastNode = n;
                lastIdx = idx;
                lastRow = row;
                ticked++;
                tickedRows.add(row);
                log("row " + row + ": state after ☑ - ticked ✓ by the " + way + " (read after its pop-up)");
                note(way);
            } else {
                log("row " + row + ": state after ☐ - the page answered but didn't tick it");
                note("not ticked");
                refused(row, "not ticked - the page answered with a pop-up");
            }
            next();
        });
    }

    /**
     * Whether the box at place {@code idx} is ticked: its element when it is still on the page,
     * else (the page rebuilt its list) the box now at that place.
     */
    private boolean checkedAt(AccessibilityNodeInfo n, int idx) {
        try {
            if (n.refresh()) return n.isChecked();
        } catch (RuntimeException ignored) {
        }
        if (behaviour != null) behaviour.rebuilt();
        int i = 0;
        for (AccessibilityNodeInfo m : Page.nodes(service)) {
            if (!Page.isCheckbox(m)) continue;
            if (i++ == idx) return m.isChecked();
        }
        return false;
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
        later(() -> clearPopups(null, 20, () -> stop(popups == 0
                ? "No accessible or matching purple popup button found - use a deep scan if one is visible"
                : "Pop-up clearing finished; see verification in the report")), 50);
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
        coverApp = service.getSharedPreferences("popup", android.content.Context.MODE_PRIVATE)
                .getBoolean("cover_" + (spotKey.startsWith("ok_spot_") ? spotKey.substring(8) : "?"), false);
        okSpot = spot == null ? null : Rect.unflattenFromString(spot);
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
        lastSignals = "";
        lastShot = "not taken yet";
        lastChance = false;
        log("row " + b.row + " [" + app() + "]: checkbox found - " + describeBox(b.node) + ", state before ☐");
        log("row " + b.row + ": clicking its checkbox (at " + b.box.centerX() + "," + b.box.centerY() + ") - click method: ACTION_CLICK on the box");
        boolean sent = b.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        whenChecked(b.node, 700, checked -> {
            if (checked) {
                note("click");
                done(b, "ticked ✓ by a click", before);
                return;
            }
            // The page answered (its pop-up came): no second click - it would untick the box.
            if (answered(before)) {
                carefulAnswered(b, "click", before);
                return;
            }
            AccessibilityNodeInfo label = clickableParent(b.node);
            if (label != null) {
                log("row " + b.row + ": " + (sent ? "click didn't tick it" : "click refused")
                        + " - clicking its label");
                label.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            whenChecked(b.node, label != null ? 700 : 0, byLabel -> {
                if (byLabel) {
                    note("label");
                    done(b, "ticked ✓ by a click on its label", before);
                    return;
                }
                if (label != null && answered(before)) {
                    carefulAnswered(b, "label", before);
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

    /** The careful way: the page answered but the box reads ☐ - pop-up cleared, box read again. */
    private void carefulAnswered(Box b, String way, Page.Before before) {
        log("row " + b.row + ": the page answered the " + way + " (its pop-up came) but the box still reads ☐ -"
                + " no second click (it would untick it); clearing the pop-up, then reading the box");
        tickTime = SystemClock.uptimeMillis();
        waitUntil = tickTime + waitMs();
        hintUntil = tickTime + 3000;
        clearPopups(before, looks(), () -> {
            if (Page.isChecked(b.node)) {
                ticked++;
                tickedRows.add(b.row);
                note(way);
                log("row " + b.row + ": state after ☑ - ticked ✓ by the " + way + " (read after its pop-up)");
            } else {
                note("not ticked");
                notTicked++;
                failedRows.add(b.row);
                log("row " + b.row + ": state after ☐ - the page answered but didn't tick it ✗");
                missedChanged();
            }
            next();
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
        hintUntil = tickTime + 3000;
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

    // ---- after a tick: is there a pop-up, and which kind -------------------------------------
    //
    //   A  no pop-up: nothing came within the short watch after the tick - go on.
    //   B  a pop-up accessibility shows: a dialog, a new window, a titled pane, or a new
    //      OK-like button - closed with its button (ACTION_CLICK) or "dismiss".
    //   C  a pop-up you see that accessibility doesn't show - Chrome reports no node for its
    //      OK, not even its cover - found by its purple button on a screenshot, tapped there.
    //
    // Accessibility is read first on every look (semantic actions first); a screenshot is
    // taken on every look it is allowed (~3 a second, only in this short watch), because in
    // Chrome nothing in the tree says a pop-up is up. Every way of closing is checked after:
    // the run goes on only once the pop-up has gone (else it stops after 3 tries).
    // The Next.js route announcer (a hidden role=alert, 0x0) is never taken for a pop-up.

    /** What one look at the page saw. */
    private static final class Signals {
        /** B: the pop-up accessibility shows, with its button (or "dismiss"). */
        Page.Popup a11y;
        /** A visible, meaningful alert / dialog that came (never the route announcer). */
        AccessibilityNodeInfo alert;
        /** An empty cover over the page came; a new window came. */
        boolean cover, newWindow;
        /** Clickable elements that weren't there before the tick. */
        int newClickables;
        /** The screenshot: not taken, none in the middle, or where the purple button is. */
        String shot = "not taken";

        /** Accessibility hints that a pop-up is up, without giving a button to close it. */
        boolean hinted() {
            return cover || alert != null || newWindow;
        }

        String text() {
            return "accessibility pop-up " + (a11y == null ? "none" : a11y.how)
                    + " · alert " + (alert == null ? "none" : "\"" + Page.label(alert) + "\" " + Page.bounds(alert).toShortString())
                    + " · cover " + (cover ? "came" : "none")
                    + " · new window " + (newWindow ? "yes" : "no")
                    + " · new clickables " + newClickables
                    + " · screenshot " + shot;
        }
    }

    /**
     * This app reports its pop-up's cover (the TTD app's web view does, Chrome doesn't): then a
     * screenshot is taken only once the cover came - an early one, before the pop-up is drawn,
     * would only hold the next one back (~3 screenshots a second). Kept per app.
     */
    private boolean coverApp;
    /** One screenshot at the end of a quiet watch was taken for this box (cover apps). */
    private boolean lastChance;

    /** The signals last logged for this box: a look is logged only when they change. */
    private String lastSignals = "";
    /** The last screenshot's verdict for this box (looks between screenshots carry it on). */
    private String lastShot = "not taken yet";
    /** Up to when a pop-up accessibility hints at (cover / alert / window) is waited for. */
    private long hintUntil;

    private void logSignals(Signals s) {
        String t = s.text();
        if (t.equals(lastSignals)) return;
        lastSignals = t;
        log("pop-up signals: " + t);
    }

    /** One look through accessibility: what came since {@code before} (null: Clear, no baseline). */
    private Signals look(Page.Before before) {
        Signals s = new Signals();
        s.a11y = Page.popup(service, before);
        if (before == null) return s;
        List<AccessibilityNodeInfo> all = Page.nodes(service);
        for (AccessibilityNodeInfo n : all) {
            if (n.isClickable() && n.isVisibleToUser() && !before.clickables.contains(Page.key(n))) s.newClickables++;
            if (s.alert == null && Page.isMeaningfulAlert(service, n) && !before.alerts.contains(Page.key(n))) s.alert = n;
        }
        for (String k : Page.coverKeys(service, all)) {
            if (!before.covers.contains(k)) {
                s.cover = true;
                break;
            }
        }
        for (int id : Page.windowIds(service)) {
            if (!before.windows.contains(id)) {
                s.newWindow = true;
                break;
            }
        }
        return s;
    }

    /**
     * Watches for a pop-up that came up since {@code before} (any pop-up, when null): every
     * look reads accessibility (B) and, when allowed, a screenshot (C); with neither, looks
     * again until the watch ends (A). {@code looksLeft}: looks still to take.
     */
    private void clearPopups(Page.Before before, int looksLeft, Runnable then) {
        if (popupTaps >= 3) {
            stillUpAfterTries(before, then);
            return;
        }
        later(() -> {
            Signals s = look(before);
            if (s.a11y != null) {
                logSignals(s);
                closeB(s, before, then);
                return;
            }
            if (s.cover && before != null && !coverApp) {
                coverApp = true;
                service.getSharedPreferences("popup", android.content.Context.MODE_PRIVATE).edit()
                        .putBoolean("cover_" + app(), true).apply();
                log("this app reports its pop-up's cover - from now on a screenshot only once the cover comes");
            }
            Runnable lookOn = () -> {
                long now = SystemClock.uptimeMillis();
                // Accessibility hints at a pop-up (cover, alert, window) but no button showed
                // yet: keep looking (up to 3 s after the tick) for it on a screenshot.
                boolean hintWait = s.hinted() && now < hintUntil;
                if ((looksLeft > 1 || hintWait) && (tickTime == 0 || now < waitUntil || hintWait)) {
                    clearPopups(before, Math.max(1, looksLeft - 1), then);
                } else {
                    // A cover app, no cover came: one screenshot still, so a pop-up without a
                    // cover isn't missed (only when nothing came - the rare case there).
                    if (coverApp && shots && popupTaps == 0 && !lastChance && before != null) {
                        lastChance = true;
                        later(() -> {
                            int g2 = gen;
                            finder.find(r -> {
                                if (!running || g2 != gen) return;
                                if (r != null) {
                                    s.shot = "purple button at " + r.centerX() + "," + r.centerY();
                                    logSignals(s);
                                    pressPurple(r, s, before, then);
                                } else {
                                    log("pop-up: state A - none came (no cover, none on the last screenshot)");
                                    quietBoxes++;
                                    then.run();
                                }
                            }, why -> {
                                shots = false;
                                log("pop-up: no screenshots - " + why);
                            }, before);
                        }, finder.waitMs());
                        return;
                    }
                    if (popupTaps == 0) {
                        if (s.hinted()) {
                            log("pop-up: accessibility hints at one (" + s.text()
                                    + ") but no button to close it was found");
                            stop("Possible popup still covers the page - stopped; take a deep scan");
                            return;
                        } else {
                            log("pop-up: state A - none came (watched " + waitMs() + " ms)");
                        }
                        quietBoxes++;
                    }
                    then.run();
                }
            };
            // Wait out the screenshot cooldown instead of treating a skipped image as absence.
            if (clearOnly && shots && finder.waitMs() > 0) {
                later(() -> clearPopups(before, looksLeft, then), finder.waitMs());
                return;
            }
            // C: a pop-up drawn but not in the tree - its purple button on a screenshot, on every
            // look the screenshot rate allows (Chrome reports no cover, so none is waited for).
            // In an app that reports the cover, only once it (or another hint) came.
            boolean shotWanted = !coverApp || before == null || s.hinted();
            if (shots && shotWanted && finder.waitMs() == 0) {
                int g = gen;
                finder.find(r -> {
                    if (!running || g != gen) return;
                    if (r != null) s.shot = "purple button at " + r.centerX() + "," + r.centerY();
                    else if (shots) s.shot = "no purple button in the middle";
                    lastShot = s.shot;
                    logSignals(s);
                    if (r != null) pressPurple(r, s, before, then);
                    else lookOn.run();
                }, why -> {
                    shots = false;
                    s.shot = "unavailable (" + why + ")";
                    log("pop-up: no screenshots - " + why);
                }, before);
                return;
            }
            s.shot = shots ? lastShot : "unavailable";
            logSignals(s);
            // No screenshots at all: a pop-up accessibility hints at is closed at the OK's
            // remembered place - only as the last resort.
            if (!shots && s.hinted() && okSpot != null) {
                pressSpot(before, then);
                return;
            }
            lookOn.run();
        }, 50);
    }

    /** B: the pop-up accessibility shows - its button pressed (or dismissed), then checked gone. */
    private void closeB(Signals s, Page.Before before, Runnable then) {
        Page.Popup p = s.a11y;
        log("pop-up: state B - accessibility shows it (" + p.how + ")");
        popupCame();
        notePopup("reported (" + p.how + ")", p.button != null ? "pressing its button" : "dismissing it");
        popups++;
        popupTaps++;
        popupsSeen = true;
        quietBoxes = 0;
        AccessibilityNodeInfo gone;
        if (p.button != null) {
            Rect r = Page.bounds(p.button);
            log("pop-up candidate: button \"" + Page.label(p.button) + "\" " + r.toShortString());
            boolean sent = p.button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            log("dismiss: ACTION_CLICK on \"" + Page.label(p.button) + "\" - "
                    + (sent ? "sent" : "refused, tapping it where it is (" + r.centerX() + "," + r.centerY() + ")"));
            if (!sent) tap(r.centerX(), r.centerY());
            gone = p.button;
        } else {
            log("pop-up candidate: a dismissable " + String.valueOf(p.dismiss.getClassName()));
            boolean sent = p.dismiss.performAction(AccessibilityNodeInfo.ACTION_DISMISS);
            log("dismiss: ACTION_DISMISS - " + (sent ? "sent" : "refused"));
            gone = p.dismiss;
        }
        // Sending the action isn't enough: wait for its button to go, then check it really went.
        waitGone(gone, 0, () -> verifyGone(before, then));
    }

    /**
     * After closing: the pop-up is gone only when accessibility no longer shows it (nor its
     * cover) and - with screenshots - no purple button is left in the middle. Else it is looked
     * at again (up to 3 tries in all).
     */
    private void verifyGone(Page.Before before, Runnable then) {
        later(() -> {
            Signals v = look(before);
            if (v.a11y != null || v.cover) {
                log("dismiss result: still up (" + (v.a11y != null ? "accessibility still shows " + v.a11y.how
                        : "its cover is still there") + ")");
                clearPopups(before, 10, then);
                return;
            }
            if (!shots) {
                log("dismiss result: gone ✓ (accessibility)");
                secondLook(before, then);
                return;
            }
            int g = gen;
            finder.find(r -> {
                if (!running || g != gen) return;
                if (r != null) {
                    log("dismiss result: accessibility shows nothing, but a purple button is still in the middle at "
                            + r.centerX() + "," + r.centerY() + " - state C");
                    v.shot = "purple button at " + r.centerX() + "," + r.centerY();
                    pressPurple(r, v, before, then);
                } else {
                    if (!shots) {
                        stop("Popup close could not be visually verified - take a deep scan");
                        return;
                    }
                    log("dismiss result: gone ✓ (accessibility and screenshot)");
                    secondLook(before, then);
                }
            }, why -> {
                shots = false;
                log("dismiss result: screenshot unavailable - " + why);
                // PurpleFinder calls the result callback once with null after this failure.

            }, before);
        }, Math.max(80, finder.waitMs()));
    }

    /** 3 presses after this box already: go on only if the pop-up has really gone, else stop. */
    private void stillUpAfterTries(Page.Before before, Runnable then) {
        Signals s = look(before);
        if (s.a11y != null || s.cover) {
            stuck(s.a11y != null ? "accessibility still shows " + s.a11y.how : "its cover is still there");
            return;
        }
        if (!shots) {
            log("pop-up: 3 presses after this box - accessibility shows none up, going on");
            later(then, 100);
            return;
        }
        int g = gen;
        later(() -> finder.find(r -> {
            if (!running || g != gen) return;
            if (r != null) stuck("its purple button is still on the screen at " + r.centerX() + "," + r.centerY());
            else if (!shots) stop("Popup close could not be visually verified - take a deep scan");
            else {
                log("pop-up: 3 presses after this box - none left (accessibility and screenshot), going on");
                then.run();
            }
        }, why -> {
            shots = false;
            log("verification screenshot unavailable: " + why);
        }, before), finder.waitMs());
    }

    /** A pop-up that won't close: stop, so no tick lands under it. */
    private void stuck(String why) {
        log("pop-up: still up after 3 tries (" + why + ") ✗");
        stop("✗ A pop-up stayed up after 3 tries - stopped, so no tick lands under it");
    }

    /** Where this app's OK place is kept ("ok_spot_<app>"). */
    private String spotKey = "ok_spot";

    /** Where the pop-up's OK is (learned from a screenshot once, kept for next time), or null. */
    private Rect okSpot;

    /** The pop-up's purple button, seen on the screenshot: tapped, then checked that it went. */
    private void pressPurple(Rect r, Signals s, Page.Before before, Runnable then) {
        boolean cover = Page.coverCame(service, before);
        log("pop-up: state C - seen on the screenshot, not in accessibility ("
                + (cover ? "its cover is in the tree, its words and OK aren't" : "nothing of it in the tree") + ")");
        log("pop-up candidate: purple button " + r.toShortString() + " (found on this screenshot)");
        if (popupTaps == 0) {
            popupCame();
            notePopup(cover ? "cover only (words and OK hidden)" : "drawn, not reported (no cover)",
                    "its purple button on a screenshot");
            popups++;
            popupsSeen = true;
        }
        popupTaps++;
        quietBoxes = 0;
        okSpot = new Rect(r);
        service.getSharedPreferences("popup", android.content.Context.MODE_PRIVATE).edit()
                .putString(spotKey, r.flattenToString()).putString("ok_spot", r.flattenToString()).apply();
        log("dismiss: tap on its purple button at " + r.centerX() + "," + r.centerY()
                + " (where this screenshot shows it; also remembered for when no screenshot can be taken)");
        tap(r.centerX(), r.centerY());
        Runnable after = () -> secondLook(before, then);
        if (cover) waitCoverGone(before, 0, r, after);
        else later(() -> checkPurpleGone(r, after), Math.max(350, finder.waitMs()));
    }

    /** The pop-up's cover is in the tree and its OK's place is known: tap it straight away. */
    private void pressSpot(Page.Before before, Runnable then) {
        popupCame();
        notePopup("cover only (words and OK hidden)", "its remembered OK place");
        popups++;
        popupTaps++;
        popupsSeen = true;
        quietBoxes = 0;
        log("pop-up: accessibility hints at one and no screenshot can be taken - dismiss: tap at the OK's"
                + " remembered place " + okSpot.centerX() + "," + okSpot.centerY() + " (last resort)");
        tap(okSpot.centerX(), okSpot.centerY());
        waitCoverGone(before, 0, null, () -> secondLook(before, then));
    }

    /** Goes on the moment the pop-up's cover has gone; if it stays, finds the OK again on a screenshot. */
    private void waitCoverGone(Page.Before before, long waited, Rect tapped, Runnable then) {
        later(() -> {
            if (!Page.coverCame(service, before)) {
                // The cover went with the pop-up: that is the check - no extra screenshot (speed).
                log("dismiss result: gone ✓ - its cover went (" + waited + " ms)");
                then.run();
            } else if (waited >= 1200) {
                log("dismiss result: still up after 1.2 s - finding its OK again on a screenshot");
                okSpot = null;
                clearPopups(before, 20, then);
            } else {
                waitCoverGone(before, waited + 40, tapped, then);
            }
        }, 40);
    }

    private void checkPurpleGone(Rect was, Runnable then) {
        if (finder.waitMs() > 0) {
            later(() -> checkPurpleGone(was, then), finder.waitMs());
            return;
        }
        int g = gen;
        finder.find(r -> {
            if (!running || g != gen) return;
            boolean still = r != null && Math.abs(r.centerX() - was.centerX()) < was.width() / 2
                    && Math.abs(r.centerY() - was.centerY()) < was.height();
            if (still && popupTaps < 3) {
                log("dismiss result: its purple button is still there - tapping again at " + r.centerX() + "," + r.centerY());
                popupTaps++;
                tap(r.centerX(), r.centerY());
                later(() -> checkPurpleGone(r, then), Math.max(350, finder.waitMs()));
            } else if (still) {
                stuck("its purple button is still on the screen at " + r.centerX() + "," + r.centerY());
            } else {
                log("dismiss result: gone ✓ (the screenshot shows no purple button there)");
                then.run();
            }
        }, why -> {
            shots = false;
            log("dismiss result unknown: " + why);
            stop("Couldn't verify the popup closed - take a deep scan");
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

    /** The app in front (its package). */
    private String app() {
        return spotKey.startsWith("ok_spot_") ? spotKey.substring(8) : "?";
    }

    /** Where the page comes from: a web view in it (Chrome, an app's own web view) or not. */
    private static String environment(List<AccessibilityNodeInfo> all) {
        int web = 0;
        boolean webView = false;
        for (AccessibilityNodeInfo n : all) {
            if (String.valueOf(n.getClassName()).contains("WebView")) webView = true;
            if (!Page.role(n).isEmpty()) web++;
        }
        return (webView ? "a web view" : "no web view") + ", " + web + " web element(s) reported";
    }

    /** A checkbox in a few words: hidden or shown, its place, its label. */
    private static String describeBox(AccessibilityNodeInfo n) {
        Rect r = Page.bounds(n);
        boolean hidden = r.width() <= 4 || r.height() <= 4;
        AccessibilityNodeInfo p = n.getParent();
        String role = p == null ? "" : Page.role(p);
        return (hidden ? "hidden (" + r.width() + "x" + r.height() + ")" : "shown " + r.width() + "x" + r.height())
                + " @" + r.toShortString()
                + (role.toLowerCase(java.util.Locale.ROOT).contains("label") ? ", in a label" + (p.isClickable() ? " (tappable)" : "") : "")
                + (Page.label(n).isEmpty() ? ", no name" : ", \"" + Page.label(n) + "\"");
    }

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
