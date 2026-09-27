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
 * pop-up that came up (a new OK / Yes / Close ... button, or a dialog's button). Then the next
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
    private final ColourPatches colours;
    /** Every box ticked this run: purple too, so never taken for a pop-up's button. */
    private final List<Rect> tickedBoxes = new ArrayList<>();

    Ticker(AccessibilityService service, Listener listener) {
        this.service = service;
        this.listener = listener;
        this.colours = new ColourPatches(service);
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
        tickedBoxes.clear();
        tickedRows.clear();
        failedRows.clear();
        ticked = notTicked = popups = stillScreens = 0;
        lastScreen = "";
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
            later(this::next, 900);
            return;
        }
        tried.add(b.node);
        Set<String> before = clickableKeys();
        // The page's own purple before the tick (ticked boxes, headers): not a pop-up.
        colours.find(base -> {
            if (!running) return;
            tickBox(b, before, base == null ? new ArrayList<>() : base);
        });
    }

    /** Pop-up taps after the current box - at most 3, so a patch that won't go can't loop. */
    private int popupTaps;

    private void tickBox(Box b, Set<String> before, List<Rect> base) {
        popupTaps = 0;
        log("row " + b.row + ": clicking its checkbox (box at " + b.box.centerX() + ","
                + b.box.centerY() + ")");
        boolean sent = b.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        later(() -> {
            if (isChecked(b.node)) {
                done(b, "ticked ✓ by a direct click", before, base);
            } else {
                // The click didn't take (or was refused): a real tap on the box you see.
                log("row " + b.row + ": " + (sent ? "click didn't tick it" : "click refused")
                        + " - tapping the box at " + b.box.centerX() + "," + b.box.centerY());
                tap(b.box.centerX(), b.box.centerY());
                later(() -> {
                    if (isChecked(b.node)) done(b, "ticked ✓ by a tap", before, base);
                    else {
                        notTicked++;
                        failedRows.add(b.row);
                        log("row " + b.row + ": still empty ✗");
                        tickedBoxes.add(new Rect(b.box));
                        clearPopups(before, base, 3, this::next);
                    }
                }, 500);
            }
        }, 450);
    }

    private void done(Box b, String how, Set<String> before, List<Rect> base) {
        ticked++;
        tickedRows.add(b.row);
        tickedBoxes.add(new Rect(b.box));
        log("row " + b.row + ": " + how);
        clearPopups(before, base, 3, this::next);
    }

    // ---- pop-ups ---------------------------------------------------------------

    /**
     * Looks (a few times, 300 ms apart) for a pop-up that came up after the tick: a new
     * clickable with an OK / Yes / Close ... text, or any button inside a dialog. Taps it, then
     * looks again (a second pop-up); with none, carries on.
     */
    private void clearPopups(Set<String> before, List<Rect> base, int looksLeft, Runnable then) {
        if (popupTaps >= 3) {
            log("pop-up: 3 taps after this box already - going on");
            later(then, 300);
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
                clearPopups(clickableKeys(), base, 3, then); // a second pop-up may follow
                return;
            }
            // 2) A pop-up drawn but not reported: a new patch of its purple on a screenshot.
            colours.find(now -> {
                if (!running) return;
                Rect patch = now == null ? null : newPatch(now, base);
                if (patch != null) {
                    log("pop-up: tapping its purple button at " + patch.centerX() + ","
                            + patch.centerY() + " (" + patch.width() + "x" + patch.height() + ")");
                    tap(patch.centerX(), patch.centerY());
                    popups++;
                    popupTaps++;
                    clearPopups(before, base, 3, then); // a second pop-up may follow
                } else if (looksLeft > 1) {
                    clearPopups(before, base, looksLeft - 1, then);
                } else {
                    if (now == null) log("pop-up: no screenshot (Android 11+ needed) - not checked");
                    else log("pop-up: no new purple button found");
                    then.run();
                }
            });
        }, 300);
    }

    /**
     * The pop-up's button: the biggest patch of purple that the page didn't have before the
     * tick and that isn't a ticked box. Button-shaped (wider than tall) patches first; a
     * squarer one only if clearly bigger than a checkbox.
     */
    private Rect newPatch(List<Rect> now, List<Rect> base) {
        int near = dp(8), pad = dp(12);
        Rect best = null, bestSquare = null;
        int boxArea = dp(30) * dp(30);
        Rect screen = screen();
        int topBar = barHeight("status_bar_height") + dp(6);
        int bottomBar = screen.height() - barHeight("navigation_bar_height") - dp(6);
        StringBuilder seenNew = new StringBuilder();
        for (Rect r : now) {
            // Not a bar: the status bar (the app colours it when a pop-up opens), the
            // navigation strip, or anything nearly the full width of the screen (a header).
            if (r.bottom <= topBar || r.top < topBar / 2 || r.top >= bottomBar) continue;
            if (r.width() >= screen.width() * 9 / 10) continue;
            boolean old = false;
            for (Rect b : base) {
                if (Math.abs(b.centerX() - r.centerX()) <= near && Math.abs(b.centerY() - r.centerY()) <= near
                        && Math.abs(b.width() - r.width()) <= near && Math.abs(b.height() - r.height()) <= near) {
                    old = true;
                    break;
                }
            }
            if (old) continue;
            boolean isBox = false;
            for (Rect t : tickedBoxes) {
                Rect grown = new Rect(t);
                grown.inset(-pad, -pad);
                if (Rect.intersects(grown, r)) {
                    isBox = true;
                    break;
                }
            }
            if (isBox) continue;
            seenNew.append(' ').append(r.centerX()).append(',').append(r.centerY())
                    .append(" (").append(r.width()).append('x').append(r.height()).append(')');
            long area = (long) r.width() * r.height();
            if (r.width() >= r.height() * 3 / 2) {
                if (best == null || area > (long) best.width() * best.height()) best = r;
            } else if (area > boxArea * 2L) {
                if (bestSquare == null || area > (long) bestSquare.width() * bestSquare.height()) bestSquare = r;
            }
        }
        if (seenNew.length() > 0) log("pop-up: new purple patches:" + seenNew);
        return best != null ? best : bestSquare;
    }

    private int barHeight(String name) {
        int id = service.getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? service.getResources().getDimensionPixelSize(id) : dp(24);
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
