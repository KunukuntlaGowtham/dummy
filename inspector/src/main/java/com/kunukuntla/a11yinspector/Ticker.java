package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ticks every empty checkbox the page reports - also a hidden one (a web page often hides the
 * real checkbox and draws a styled label instead): clicks it straight through accessibility,
 * checks it turned ☑, and if not taps the box you see (its label). After each tick it clears a
 * pop-up that came up by pressing its OK (a new OK / Yes / Close ... button the page reports, or
 * a new "OK" read off a screenshot when the pop-up isn't reported). Then the next
 * box; when none is left on screen it scrolls on, until the page stops moving.
 */
final class Ticker {

    interface Listener {
        void done(String summary, String log);
    }

    private static final String[] POPUP_WORDS = {"ok", "okay", "yes", "confirm", "agree", "i agree",
            "accept", "proceed", "done", "got it", "close", "continue", "submit", "understood"};

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
    private final ScreenWords words;
    /** Where the page shows "OK" by itself (read once per screen): not a pop-up's. */
    private List<Rect> pageOks;
    /** A pop-up came after an earlier tick this run: wait a little longer for the next one. */
    private boolean popupsSeen;
    /** Boxes in a row after which no pop-up came: stop waiting long for one. */
    private int quietBoxes;

    Ticker(AccessibilityService service, Listener listener) {
        this.service = service;
        this.listener = listener;
        this.words = new ScreenWords(service);
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
        tried.clear();
        tickedRows.clear();
        failedRows.clear();
        ticked = notTicked = popups = stillScreens = 0;
        lastScreen = "";
        pageOks = null;
        popupsSeen = false;
        quietBoxes = 0;
        okSpot = null;
        okLook = okGone = null;
        log("Tick: clicking every empty checkbox directly, clearing pop-ups");
        later(this::next, 100);
    }

    void stop(String why) {
        if (!running) return;
        running = false;
        gen++;
        log("END: " + why);
        String summary = why + "\n" + "Ticked " + ticked
                + (tickedRows.isEmpty() ? "" : " (rows " + String.join(", ", tickedRows) + ")")
                + "\nNot ticked " + notTicked
                + (failedRows.isEmpty() ? "" : " (rows " + String.join(", ", failedRows) + ")")
                + "\nPop-ups cleared " + popups;
        listener.done(summary, "A11y Inspector - Tick run\n=========================\n" + summary
                + "\n\nSTEPS\n" + log);
    }

    // ---- one box after another ------------------------------------------------------

    private void next() {
        Box b = firstEmptyBox();
        if (b == null) {
            String screen = screenKey();
            if (screen.equals(lastScreen) && ++stillScreens >= 2) {
                stop("No empty checkbox left (the page no longer scrolls)");
                return;
            }
            if (!screen.equals(lastScreen)) stillScreens = 0;
            lastScreen = screen;
            log("no empty checkbox on this screen - scrolling on");
            scroll();
            pageOks = null; // a new screen: read its words again
            later(this::next, 600);
            return;
        }
        tried.add(b.node);
        Set<String> before = clickableKeys();
        // Any OK already on the page before the tick is not a pop-up's (read once a screen).
        if (pageOks != null) {
            tickBox(b, before, pageOks);
            return;
        }
        words.read(seen -> {
            if (!running) return;
            pageOks = okWords(seen);
            tickBox(b, before, pageOks);
        });
    }

    /** Pop-up taps after the current box - at most 3, so an OK that won't go can't loop. */
    private int popupTaps;

    private void tickBox(Box b, Set<String> before, List<Rect> oldOks) {
        popupTaps = 0;
        log("row " + b.row + ": clicking its checkbox (box at " + b.box.centerX() + ","
                + b.box.centerY() + ")");
        boolean sent = b.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        whenChecked(b.node, 450, checked -> {
            if (checked) {
                done(b, "ticked ✓ by a direct click", before, oldOks);
            } else {
                // The click didn't take (or was refused): a real tap on the box you see.
                log("row " + b.row + ": " + (sent ? "click didn't tick it" : "click refused")
                        + " - tapping the box at " + b.box.centerX() + "," + b.box.centerY());
                tap(b.box.centerX(), b.box.centerY());
                whenChecked(b.node, 500, ok -> {
                    if (ok) done(b, "ticked ✓ by a tap", before, oldOks);
                    else {
                        notTicked++;
                        failedRows.add(b.row);
                        log("row " + b.row + ": still empty ✗");
                        clearPopups(before, oldOks, firstLooks(), this::next);
                    }
                });
            }
        });
    }

    /** Checks every 90 ms (up to {@code ms}) whether the box turned ☑. */
    private void whenChecked(AccessibilityNodeInfo node, long ms, java.util.function.Consumer<Boolean> then) {
        later(() -> {
            boolean on = isChecked(node);
            if (on || ms <= 90) then.accept(on);
            else whenChecked(node, ms - 90, then);
        }, 90);
    }

    /** How often to look for a pop-up after a tick: longer once pop-ups have been coming. */
    private int firstLooks() {
        return popupsSeen ? 6 : quietBoxes >= 3 ? 2 : 4;
    }

    private void done(Box b, String how, Set<String> before, List<Rect> oldOks) {
        ticked++;
        tickedRows.add(b.row);
        log("row " + b.row + ": " + how);
        clearPopups(before, oldOks, firstLooks(), this::next);
    }

    // ---- pop-ups ---------------------------------------------------------------

    /**
     * Looks (a few times, 300 ms apart) for a pop-up that came up after the tick: a new
     * clickable with an OK / Yes / Close ... text, or any button inside a dialog. Taps it, then
     * looks again (a second pop-up); with none, carries on.
     */
    private void clearPopups(Set<String> before, List<Rect> oldOks, int looksLeft, Runnable then) {
        if (popupTaps >= 3) {
            log("pop-up: 3 taps after this box already - going on");
            later(then, 150);
            return;
        }
        later(() -> {
            // 1) A pop-up the page reports: its OK / Yes / Close ... button.
            AccessibilityNodeInfo button = popupButton(before);
            if (button != null) {
                Rect r = new Rect();
                button.getBoundsInScreen(r);
                String what = label(button);
                log("pop-up: tapping \"" + what + "\" at " + r.centerX() + "," + r.centerY());
                if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(r.centerX(), r.centerY());
                popups++;
                popupTaps++;
                popupsSeen = true;
                clearPopups(clickableKeys(), oldOks, 1, then); // a second pop-up may follow
                return;
            }
            // 2) A pop-up drawn but not reported. Once its OK has been read off a screenshot,
            //    its look is remembered: later pop-ups are spotted from the pixels alone (fast);
            //    the words are read (slower) only on the last look, or until an OK is learned.
            boolean quick = okSpot != null && looksLeft > 1;
            words.shot(!quick, shot -> {
                if (!running) return;
                Rect ok = null;
                String how = "";
                if (shot != null && okSpot != null && looksLikeOk(shot)) {
                    ok = okSpot;
                    how = " (spotted by its look)";
                }
                if (ok == null && shot != null && shot.words != null) {
                    ok = newOk(shot.words, oldOks);
                    if (ok != null) {
                        okSpot = grow(ok);
                        okLook = shot.grid(okSpot);
                        okGone = null;
                        if (okLook == null) okSpot = null;
                    }
                }
                if (ok != null) {
                    log("pop-up: pressing OK at " + ok.centerX() + "," + ok.centerY() + how);
                    tap(ok.centerX(), ok.centerY());
                    popups++;
                    popupTaps++;
                    popupsSeen = true;
                    if (okSpot != null && okGone == null) {
                        // First time: see how that spot looks with the pop-up gone.
                        later(() -> words.shot(false, after -> {
                            if (!running) return;
                            double[] g = after == null || okSpot == null ? null : after.grid(okSpot);
                            if (g != null && okLook != null && BinFinder.similarity(g, okLook) < 0.97) okGone = g;
                            clearPopups(clickableKeys(), oldOks, 1, then); // a second pop-up may follow
                        }), 150);
                    } else {
                        // Known pop-up: straight on; one left open is found at the next box.
                        later(then, 120);
                    }
                } else if (looksLeft > 1) {
                    clearPopups(before, oldOks, looksLeft - 1, then);
                } else {
                    if (shot == null) log("pop-up: couldn't read the screen (Android 11+ needed) - not checked");
                    else log("pop-up: no new OK on screen - none to clear");
                    if (popupTaps == 0) quietBoxes++;
                    then.run();
                }
            });
        }, 60);
    }

    /** Where the pop-up's OK was, a little bigger (its button), and how it looked. */
    private Rect okSpot;
    private double[] okLook, okGone;

    private Rect grow(Rect r) {
        Rect g = new Rect(r);
        g.inset(-Math.max(dp(6), r.width() / 4), -Math.max(dp(4), r.height() / 3));
        return g;
    }

    /** The remembered OK is on screen again: its spot looks like the OK, not like the page. */
    private boolean looksLikeOk(ScreenWords.Shot shot) {
        double[] g = shot.grid(okSpot);
        if (g == null || okLook == null) return false;
        double like = BinFinder.similarity(g, okLook);
        if (like < 0.95) return false;
        return okGone == null || BinFinder.similarity(g, okGone) < like - 0.02;
    }

    /** Where the screen shows "OK" (or "Okay"), each as screen pixels. */
    private static List<Rect> okWords(List<ScreenWords.Word> seen) {
        List<Rect> out = new ArrayList<>();
        if (seen == null) return out;
        for (ScreenWords.Word w : seen) {
            String t = w.text.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (t.equals("ok") || t.equals("okay")) out.add(w.box);
        }
        return out;
    }

    /** An OK on screen now that wasn't there before the tick (the pop-up's), or null. */
    private Rect newOk(List<ScreenWords.Word> seen, List<Rect> oldOks) {
        int near = dp(12);
        for (Rect r : okWords(seen)) {
            boolean old = false;
            for (Rect o : oldOks) {
                if (Math.abs(o.centerX() - r.centerX()) <= near && Math.abs(o.centerY() - r.centerY()) <= near) {
                    old = true;
                    break;
                }
            }
            if (!old) return r;
        }
        return null;
    }

    private int dp(int v) {
        return Math.round(v * service.getResources().getDisplayMetrics().density);
    }

    private AccessibilityNodeInfo popupButton(Set<String> before) {
        AccessibilityNodeInfo best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Node n : all()) {
            AccessibilityNodeInfo node = n.node;
            if (!node.isVisibleToUser() || !node.isEnabled()) continue;
            if (!node.isClickable()) continue;
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) continue;
            if (before.contains(key(node, r))) continue; // it was there before the tick
            String t = label(node).toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").trim();
            int rank = Integer.MAX_VALUE;
            for (int i = 0; i < POPUP_WORDS.length; i++) {
                if (t.equals(POPUP_WORDS[i]) || t.startsWith(POPUP_WORDS[i] + " ")) {
                    rank = i;
                    break;
                }
            }
            if (rank == Integer.MAX_VALUE && n.inDialog) rank = 100; // any button in a dialog
            if (rank < bestRank) {
                best = node;
                bestRank = rank;
            }
        }
        return best;
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

    private static final class Node {
        final AccessibilityNodeInfo node;
        final boolean inDialog;

        Node(AccessibilityNodeInfo node, boolean inDialog) {
            this.node = node;
            this.inDialog = inDialog;
        }
    }

    /** The top-most empty, enabled checkbox not tried yet (hidden ones too), or null. */
    private Box firstEmptyBox() {
        List<Node> nodes = all();
        Box best = null;
        for (Node n : nodes) {
            AccessibilityNodeInfo node = n.node;
            if (!isCheckbox(node) || node.isChecked() || !node.isEnabled()) continue;
            if (tried.contains(node)) continue;
            Rect box = visibleBox(node);
            if (box == null) continue;
            if (best == null || box.top < best.box.top) best = new Box(node, box, "?");
        }
        if (best == null) return null;
        return new Box(best.node, best.box, rowOf(best.box, nodes));
    }

    private static boolean isCheckbox(AccessibilityNodeInfo n) {
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        if (cls.endsWith("RadioButton") || cls.endsWith("Switch")) return false;
        String role = "";
        try {
            CharSequence r = n.getExtras().getCharSequence("AccessibilityNodeInfo.chromeRole");
            if (r != null) role = r.toString().toLowerCase(Locale.ROOT);
        } catch (RuntimeException ignored) {
        }
        return cls.endsWith("CheckBox") || role.contains("checkbox") || n.isCheckable();
    }

    private boolean isChecked(AccessibilityNodeInfo n) {
        try {
            n.refresh();
        } catch (RuntimeException ignored) {
        }
        return n.isChecked();
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
    private String rowOf(Rect box, List<Node> nodes) {
        String best = "?";
        int bestGap = Integer.MAX_VALUE;
        for (Node n : nodes) {
            String t = label(n.node).trim();
            if (!t.matches("\\(?\\d{1,4}[.)]?")) continue;
            Rect r = new Rect();
            n.node.getBoundsInScreen(r);
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

    /** Every node of the page (not our own windows), noting which sit inside a dialog. */
    private List<Node> all() {
        List<Node> out = new ArrayList<>();
        String own = service.getPackageName();
        List<AccessibilityWindowInfo> windows;
        try {
            windows = service.getWindows();
        } catch (RuntimeException e) {
            windows = new ArrayList<>();
        }
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || own.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            List<Boolean> dialog = new ArrayList<>();
            stack.add(root);
            dialog.add(false);
            while (!stack.isEmpty() && out.size() < 6000) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                boolean inDialog = dialog.remove(dialog.size() - 1);
                if (n == null) continue;
                String role = "";
                try {
                    CharSequence r = n.getExtras().getCharSequence("AccessibilityNodeInfo.chromeRole");
                    if (r != null) role = r.toString().toLowerCase(Locale.ROOT);
                } catch (RuntimeException ignored) {
                }
                boolean d = inDialog || role.contains("dialog") || role.contains("alertdialog");
                out.add(new Node(n, d));
                for (int i = 0; i < n.getChildCount(); i++) {
                    stack.add(n.getChild(i));
                    dialog.add(d);
                }
            }
        }
        return out;
    }

    private Set<String> clickableKeys() {
        Set<String> out = new HashSet<>();
        for (Node n : all()) {
            if (!n.node.isClickable()) continue;
            Rect r = new Rect();
            n.node.getBoundsInScreen(r);
            out.add(key(n.node, r));
        }
        return out;
    }

    private static String key(AccessibilityNodeInfo n, Rect r) {
        return label(n) + "@" + r.toShortString();
    }

    private String screenKey() {
        StringBuilder sb = new StringBuilder();
        for (Node n : all()) {
            String t = label(n.node);
            if (t.isEmpty()) continue;
            Rect r = new Rect();
            n.node.getBoundsInScreen(r);
            sb.append(t).append('@').append(r.top / 8).append('|');
        }
        return sb.toString();
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString().replace('\n', ' ').trim();
    }

    // ---- gestures ----------------------------------------------------------------

    private void tap(int x, int y) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    /** A steady drag up (no fling), about half a screen. */
    private void scroll() {
        Rect s = screen();
        Path p = new Path();
        p.moveTo(s.centerX(), s.height() * 3 / 4f);
        p.lineTo(s.centerX(), s.height() * 3 / 10f);
        GestureDescription.StrokeDescription drag = new GestureDescription.StrokeDescription(p, 0, 450, true);
        service.dispatchGesture(new GestureDescription.Builder().addStroke(drag).build(),
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription g) {
                        Path hold = new Path();
                        hold.moveTo(s.centerX(), s.height() * 3 / 10f);
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
