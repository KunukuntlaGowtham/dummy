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

    /** The page's nodes (not our own windows, not the status bar), in page order. */
    static List<AccessibilityNodeInfo> nodes(AccessibilityService service) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityWindowInfo w : windows(service)) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            stack.add(root);
            while (!stack.isEmpty() && out.size() < 6000) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                if (n == null) continue;
                out.add(n);
                for (int i = n.getChildCount() - 1; i >= 0; i--) stack.add(n.getChild(i));
            }
        }
        return out;
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
        Set<Integer> out = new HashSet<>();
        for (AccessibilityWindowInfo w : windows(service)) out.add(w.getId());
        return out;
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

        Before(AccessibilityService service) {
            this(service, nodes(service));
        }

        /** From the page's nodes already read (no second read of the page). */
        Before(AccessibilityService service, List<AccessibilityNodeInfo> all) {
            for (AccessibilityNodeInfo n : all) if (n.isClickable()) clickables.add(key(n));
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
        List<AccessibilityWindowInfo> ws = windows(service);
        AccessibilityNodeInfo best = null, dismiss = null;
        int bestRank = Integer.MAX_VALUE;
        boolean bestCross = false;
        String how = "";
        for (AccessibilityWindowInfo w : ws) {
            boolean newWindow = before != null && !before.windows.contains(w.getId()) && ws.size() > 1;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            // Per node: 2 inside a dialog (or a new window), 1 inside a titled pane, 0 neither.
            List<Integer> dialog = new ArrayList<>();
            stack.add(root);
            dialog.add(newWindow ? 2 : 0);
            int seen = 0;
            while (!stack.isEmpty() && seen++ < 6000) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                int inDialog = dialog.remove(dialog.size() - 1);
                if (n == null) continue;
                int d = Math.max(inDialog, isDialog(n) ? 2 : isPane(n) ? 1 : 0);
                for (int i = n.getChildCount() - 1; i >= 0; i--) {
                    stack.add(n.getChild(i));
                    dialog.add(d);
                }
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
