package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reading the page in front straight from accessibility (no screenshots): its elements in page
 * order, their roles and words, and the pop-ups that are up - a dialog, a new window, a pane
 * with a title, or a new OK / Yes / Close ... button.
 */
final class Page {

    private Page() {
    }

    /** The words of the buttons that close a pop-up, best first. */
    static final String[] POPUP_WORDS = {"ok", "okay", "yes", "confirm", "agree", "i agree",
            "accept", "proceed", "done", "got it", "close", "continue", "submit", "understood",
            "dismiss", "allow", "sure", "fine", "x", "✕", "×"};

    // ---- one read of the page, shared for a moment (speed on slower phones) ----------

    /**
     * One read of the page: its nodes in page order and, for each, the window it is in and
     * whether it is inside a dialog (2) or a titled pane (1) - so finding a pop-up needs no
     * second walk of the page.
     */
    static final class Snapshot {
        final List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        final List<Integer> windowOf = new ArrayList<>();
        final List<Integer> level = new ArrayList<>();
        final Set<Integer> windows = new HashSet<>();
    }

    /** Bumped by every change the page reports: a kept read is never used after one. */
    private static volatile int changes;
    private static Snapshot lastRead;
    private static long lastReadAt;
    private static int lastReadChanges = -1;
    /** A read is shared this long (when the page reported no change since). */
    private static final long SHARE_MS = 40;

    /** The page reported a change (its content, a window): the next read is a fresh one. */
    static void changed() {
        changes++;
    }

    /**
     * The page's nodes (not our own windows, not the status bar), in page order. Several looks
     * within 40 ms with no change from the page share one read - each read walks hundreds of
     * elements, slow on a slower phone.
     */
    static List<AccessibilityNodeInfo> nodes(AccessibilityService service) {
        return new ArrayList<>(snapshot(service).nodes);
    }

    static Snapshot snapshot(AccessibilityService service) {
        long now = android.os.SystemClock.uptimeMillis();
        int c = changes;
        Snapshot kept = lastRead;
        if (kept != null && c == lastReadChanges && now - lastReadAt < SHARE_MS) return kept;
        Snapshot s = read(service);
        lastRead = s;
        lastReadAt = now;
        lastReadChanges = c;
        return s;
    }

    private static Snapshot read(AccessibilityService service) {
        Snapshot s = new Snapshot();
        // Android 13+: each call brings up to 50 elements at once (else one call per element).
        boolean batch = android.os.Build.VERSION.SDK_INT >= 33;
        String own = service.getPackageName();
        List<AccessibilityWindowInfo> all;
        try {
            all = service.getWindows();
        } catch (RuntimeException e) {
            return s;
        }
        for (AccessibilityWindowInfo w : all) {
            int t = w.getType();
            if (t == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || t == AccessibilityWindowInfo.TYPE_SYSTEM
                    || t == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = batch ? rootBatch(w) : w.getRoot();
            if (root == null || own.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
            int id = w.getId();
            s.windows.add(id);
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            List<Integer> levels = new ArrayList<>();
            stack.add(root);
            levels.add(0);
            while (!stack.isEmpty() && s.nodes.size() < 6000) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                int up = levels.remove(levels.size() - 1);
                if (n == null) continue;
                int d = Math.max(up, isDialog(n) ? 2 : isPane(n) ? 1 : 0);
                s.nodes.add(n);
                s.windowOf.add(id);
                s.level.add(d);
                for (int i = n.getChildCount() - 1; i >= 0; i--) {
                    stack.add(batch ? childBatch(n, i) : n.getChild(i));
                    levels.add(d);
                }
            }
        }
        return s;
    }

    @android.annotation.TargetApi(33)
    private static AccessibilityNodeInfo childBatch(AccessibilityNodeInfo n, int i) {
        return n.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID);
    }

    @android.annotation.TargetApi(33)
    private static AccessibilityNodeInfo rootBatch(AccessibilityWindowInfo w) {
        return w.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID);
    }

    /** The windows of the page in front: apps and dialogs, not ours, not the status bar. */
    static List<AccessibilityWindowInfo> windows(AccessibilityService service) {
        List<AccessibilityWindowInfo> out = new ArrayList<>();
        String own = service.getPackageName();
        List<AccessibilityWindowInfo> all;
        try {
            all = service.getWindows();
        } catch (RuntimeException e) {
            all = new ArrayList<>();
        }
        for (AccessibilityWindowInfo w : all) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) continue;
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || own.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
            out.add(w);
        }
        return out;
    }

    /** The ids of the page's windows now: a pop-up often comes as a window of its own. */
    static Set<Integer> windowIds(AccessibilityService service) {
        return new HashSet<>(snapshot(service).windows);
    }

    static String role(AccessibilityNodeInfo n) {
        try {
            CharSequence c = n.getExtras().getCharSequence("AccessibilityNodeInfo.chromeRole");
            if (c != null && c.length() > 0) return c.toString();
            CharSequence d = n.getExtras().getCharSequence("AccessibilityNodeInfo.roleDescription");
            return d == null ? "" : d.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString().replace('\n', ' ').trim();
    }

    static Rect bounds(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r;
    }

    /** Where the element (or, when it is hidden, its nearest sized parent) is on screen. */
    static Rect visible(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        for (int i = 0; p != null && i < 4; i++, p = p.getParent()) {
            Rect r = bounds(p);
            if (r.width() > 4 && r.height() > 4) return r;
        }
        return null;
    }

    static boolean isCheckbox(AccessibilityNodeInfo n) {
        String cls = String.valueOf(n.getClassName());
        String role = role(n).toLowerCase(Locale.ROOT);
        if (cls.endsWith("RadioButton") || role.contains("radio") || cls.endsWith("Switch")
                || role.contains("switch")) return false;
        return cls.endsWith("CheckBox") || role.contains("checkbox") || n.isCheckable();
    }

    static boolean isChecked(AccessibilityNodeInfo n) {
        try {
            n.refresh();
        } catch (RuntimeException ignored) {
        }
        return n.isChecked();
    }

    /** The node is a dialog: its role or class says so. */
    static boolean isDialog(AccessibilityNodeInfo n) {
        String role = role(n).toLowerCase(Locale.ROOT);
        String cls = String.valueOf(n.getClassName()).toLowerCase(Locale.ROOT);
        return role.contains("dialog") || cls.contains("dialog");
    }

    /** The node is a pane with a title (a sheet, a panel, often a pop-up). */
    static boolean isPane(AccessibilityNodeInfo n) {
        return Build.VERSION.SDK_INT >= 28 && n.getPaneTitle() != null && n.getPaneTitle().length() > 0;
    }

    /** How good a pop-up button this label is: 0 best, MAX_VALUE not one. */
    static int popupRank(String label) {
        String t = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z✕× ]", " ").replaceAll("\\s+", " ").trim();
        for (int i = 0; i < POPUP_WORDS.length; i++) {
            if (t.equals(POPUP_WORDS[i]) || t.startsWith(POPUP_WORDS[i] + " ")) return i;
        }
        return Integer.MAX_VALUE;
    }

    /** The element is a checkbox, holds one (a label around it) or sits in one. */
    static boolean checkboxPart(AccessibilityNodeInfo n) {
        if (n.isCheckable() || isCheckbox(n)) return true;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            if (c.isCheckable() || isCheckbox(c)) return true;
            for (int j = 0; j < c.getChildCount(); j++) {
                AccessibilityNodeInfo g = c.getChild(j);
                if (g != null && (g.isCheckable() || isCheckbox(g))) return true;
            }
        }
        AccessibilityNodeInfo p = n.getParent();
        return p != null && (p.isCheckable() || isCheckbox(p));
    }

    /** A button that only acknowledges a message: OK, Okay, Got it, Close, Done, Understood, Dismiss. */
    static boolean isAcknowledge(String label) {
        String t = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        return t.equals("ok") || t.equals("okay") || t.equals("got it") || t.equals("close")
                || t.equals("done") || t.equals("understood") || t.equals("dismiss") || t.equals("ok got it");
    }

    /**
     * The label names a close icon: "✕", "×", "x", or words like cross / close / cancel /
     * dismiss in it ("purple_cross_icon", "close-button", "Close dialog").
     */
    static boolean isCloseIcon(String label) {
        String t = label.trim().toLowerCase(Locale.ROOT);
        if (t.equals("x") || t.equals("✕") || t.equals("×") || t.equals("✖")) return true;
        for (String w : t.split("[^a-z]+")) {
            if (w.equals("cross") || w.equals("close") || w.equals("cancel") || w.equals("dismiss")) return true;
        }
        return false;
    }

    /** A clickable element's key: its words and place (to tell new ones from old ones). */
    static String key(AccessibilityNodeInfo n) {
        return label(n) + "@" + bounds(n).toShortString();
    }

    static Set<String> clickableKeys(AccessibilityService service) {
        Set<String> out = new HashSet<>();
        for (AccessibilityNodeInfo n : nodes(service)) if (n.isClickable()) out.add(key(n));
        return out;
    }

    /** What the page is now, before an action: to see what a pop-up added after it. */
    static final class Before {
        final Set<String> clickables = new HashSet<>();
        final Set<String> covers;
        final Set<Integer> windows;
        /** The visible, meaningful alerts / dialogs already up (see {@link #isMeaningfulAlert}). */
        final Set<String> alerts = new HashSet<>();

        Before(AccessibilityService service) {
            this(service, nodes(service));
        }

        /** From the page's nodes already read (no second read of the page). */
        Before(AccessibilityService service, List<AccessibilityNodeInfo> all) {
            for (AccessibilityNodeInfo n : all) {
                if (n.isClickable()) clickables.add(key(n));
                if (isMeaningfulAlert(service, n)) alerts.add(key(n));
            }
            covers = coverKeys(service, all);
            windows = windowIds(service);
        }
    }

    /**
     * Elements that cover most of the page and are empty (no words, no children): the dark
     * cover a web pop-up puts over the page - also when the pop-up hides its own text and
     * buttons from accessibility.
     */
    static Set<String> coverKeys(AccessibilityService service, List<AccessibilityNodeInfo> all) {
        Set<String> out = new HashSet<>();
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        long big = (long) dm.widthPixels * dm.heightPixels * 6 / 10;
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (AccessibilityNodeInfo n : all) {
            if (n.getChildCount() != 0 || !label(n).isEmpty() || !n.isVisibleToUser()) continue;
            Rect r = bounds(n);
            if ((long) r.width() * r.height() < big) continue;
            String k = r.toShortString();
            int times = seen.merge(k, 1, Integer::sum);
            out.add(times == 1 ? k : k + "#" + times);
        }
        return out;
    }

    /**
     * The hidden element Next.js puts on every page to read out the page's name
     * ({@code __next-route-announcer__}, role alert, 0x0): never a pop-up.
     */
    static boolean isRouteAnnouncer(AccessibilityNodeInfo n) {
        String id = n.getViewIdResourceName();
        if (id != null && id.contains("route-announcer")) return true;
        AccessibilityNodeInfo p = n.getParent();
        String pid = p == null ? null : p.getViewIdResourceName();
        return pid != null && pid.contains("route-announcer");
    }

    /**
     * An alert or dialog that means something on screen: its role says alert / dialog, it is
     * visible, has a real size (at least 2% of the screen) and isn't the Next.js route
     * announcer. "role=alert" alone is not a pop-up.
     */
    static boolean isMeaningfulAlert(AccessibilityService service, AccessibilityNodeInfo n) {
        String role = role(n).toLowerCase(Locale.ROOT);
        if (!role.contains("alert") && !isDialog(n)) return false;
        if (!n.isVisibleToUser() || isRouteAnnouncer(n)) return false;
        Rect r = bounds(n);
        if (r.width() <= 4 || r.height() <= 4) return false;
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        return (long) r.width() * r.height() * 50 >= (long) dm.widthPixels * dm.heightPixels;
    }

    /** A cover (see {@link #coverKeys}) that wasn't there in {@code before}: a pop-up is up. */
    static boolean coverCame(AccessibilityService service, Before before) {
        if (before == null) return false;
        for (String k : coverKeys(service, nodes(service))) if (!before.covers.contains(k)) return true;
        return false;
    }

    /**
     * An OK / Got it / Close / Done ... button on screen now that was not in {@code baseline}:
     * the button of a pop-up that just came up. Null when there is none.
     */
    static AccessibilityNodeInfo newOk(AccessibilityService service, Before baseline) {
        for (AccessibilityNodeInfo n : nodes(service)) {
            if (!n.isClickable() || !n.isEnabled() || !n.isVisibleToUser()) continue;
            Rect r = bounds(n);
            if (r.width() <= 0 || r.height() <= 0) continue;
            if (!isAcknowledge(label(n))) continue;
            if (baseline != null && baseline.clickables.contains(key(n))) continue;
            return n;
        }
        return null;
    }

    /** A pop-up that is up: how it shows, and the button that closes it (or null). */
    static final class Popup {
        String how = "";
        AccessibilityNodeInfo button;
        /** When it has no button: a node that accepts "dismiss". */
        AccessibilityNodeInfo dismiss;
        /** Only a lone ✕ / close icon on the page (no dialog): pressed only when you ask (Clear). */
        boolean crossOnly;
    }

    /**
     * A pop-up that came up since {@code before} (or any pop-up, when {@code before} is null):
     * a new window, a dialog, or a new OK-like button. Null when there is none.
     */
    static Popup popup(AccessibilityService service, Before before) {
        // From the shared read (no walk of its own): each node's window and dialog level.
        Snapshot snap = snapshot(service);
        AccessibilityNodeInfo best = null, dismiss = null;
        int bestRank = Integer.MAX_VALUE;
        boolean bestCross = false;
        String how = "";
        int windowCount = snap.windows.size();
        for (int idx = 0; idx < snap.nodes.size(); idx++) {
            AccessibilityNodeInfo n = snap.nodes.get(idx);
            boolean newWindow = before != null && !before.windows.contains(snap.windowOf.get(idx)) && windowCount > 1;
            int d = newWindow ? 2 : snap.level.get(idx);
            {
                if (d == 2 && dismiss == null && n.isDismissable()
                        && (before == null || !before.clickables.contains(key(n)))) dismiss = n;
                if (!n.isClickable() || !n.isEnabled() || !n.isVisibleToUser()) continue;
                Rect r = bounds(n);
                if (r.width() <= 0 || r.height() <= 0) continue;
                // After an action only what it brought counts; the page's own buttons never do.
                if (before != null && before.clickables.contains(key(n))) continue;
                String l = label(n);
                int rank = popupRank(l);
                // A ✕ / cross / close icon is never a pop-up's button: in this app a pop-up is
                // closed with its purple button in the middle (a ✕ closes a note on the page).
                boolean cross = rank == Integer.MAX_VALUE && isCloseIcon(l);
                if (cross) continue;
                if (rank == Integer.MAX_VALUE && d == 2) rank = 100; // any button in a dialog
                if (rank == Integer.MAX_VALUE) continue;
                // With no dialog marked (a web page often draws its pop-up as a plain box), only a
                // button that just acknowledges counts - OK, Got it, Close ... or a ✕ icon - never
                // one that commits something (Continue, Submit, Yes, Confirm ...).
                boolean ack = isAcknowledge(l) || l.trim().equalsIgnoreCase("yes");
                // Outside a dialog / pane / new window, only a button whose whole name just
                // answers the message counts (OK, Yes, Close, Done ... or a ✕) - never one that
                // only starts with such a word ("Confirm sevak 1" is a checkbox's label).
                if (d == 0 && !newWindow && !cross && !ack) continue;
                // A checkbox, or part of one (its label), is never a pop-up's button.
                if (checkboxPart(n)) continue;
                if (rank < bestRank) {
                    best = n;
                    bestRank = rank;
                    bestCross = cross && d == 0 && !newWindow;
                    how = newWindow ? "a new window" : d == 2 ? "a dialog" : d == 1 ? "a pane"
                            : cross ? "a box with a ✕ close button"
                            : before == null ? "a box with \"" + l + "\"" : "a new button";
                }
            }
        }
        if (best == null && dismiss == null) return null;
        Popup p = new Popup();
        p.button = best;
        p.dismiss = dismiss;
        p.crossOnly = best != null && bestCross;
        p.how = best != null ? how : "a dismissable dialog";
        return p;
    }
}
