package com.kunukuntla.ttdfiller;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reading the screen in front straight from accessibility: its windows, its elements in page order. */
final class Nodes {

    private Nodes() {
    }

    /** The windows of the app in front and its dialogs - not overlays, not the status bar, not the keyboard, not us. */
    static List<AccessibilityWindowInfo> windows(AccessibilityService s) {
        List<AccessibilityWindowInfo> out = new ArrayList<>();
        String own = s.getPackageName();
        List<AccessibilityWindowInfo> all;
        try {
            all = s.getWindows();
        } catch (RuntimeException e) {
            all = new ArrayList<>();
        }
        for (AccessibilityWindowInfo w : all) {
            int t = w.getType();
            if (t == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || t == AccessibilityWindowInfo.TYPE_SYSTEM
                    || t == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence p = root.getPackageName();
            if (p != null && own.contentEquals(p)) continue;
            out.add(w);
        }
        return out;
    }

    /** Every element of those windows, in page order (top to bottom of the page, also out of view). */
    static List<AccessibilityNodeInfo> all(AccessibilityService s) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityWindowInfo w : windows(s)) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            stack.add(root);
            while (!stack.isEmpty() && out.size() < 8000) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                if (n == null) continue;
                out.add(n);
                for (int i = n.getChildCount() - 1; i >= 0; i--) stack.add(n.getChild(i));
            }
        }
        return out;
    }

    /** The package of the app in front (its active window, else the top one); "" when none. */
    static String frontPackage(AccessibilityService s) {
        List<AccessibilityWindowInfo> all;
        try {
            all = s.getWindows();
        } catch (RuntimeException e) {
            return "";
        }
        String first = "";
        for (AccessibilityWindowInfo w : all) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null || r.getPackageName() == null) continue;
            String p = r.getPackageName().toString();
            if (w.isActive() || w.isFocused()) return p;
            if (first.isEmpty()) first = p;
        }
        return first;
    }

    static String role(AccessibilityNodeInfo n) {
        try {
            CharSequence c = n.getExtras().getCharSequence("AccessibilityNodeInfo.chromeRole");
            if (c != null && c.length() > 0) return c.toString().toLowerCase(Locale.ROOT);
            CharSequence d = n.getExtras().getCharSequence("AccessibilityNodeInfo.roleDescription");
            return d == null ? "" : d.toString().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Its words: its text, else what it is called. */
    static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString().replace('\n', ' ').trim();
    }

    static String cls(AccessibilityNodeInfo n) {
        return n.getClassName() == null ? "" : n.getClassName().toString();
    }

    /** A box you type into or choose in (web pages report both as text fields). */
    static boolean isField(AccessibilityNodeInfo n) {
        if (cls(n).endsWith("EditText")) return true;
        String r = role(n);
        return r.contains("textfield") || r.contains("combobox") || r.contains("spinbutton");
    }

    /** One line of an open dropdown list (a web list, or Android's own list in a dialog). */
    static boolean isOption(AccessibilityNodeInfo n) {
        String r = role(n);
        if (r.contains("listitem") || r.equals("option") || r.contains("listboxoption") || r.contains("menuitem")) return true;
        return cls(n).endsWith("CheckedTextView");
    }

    static boolean isButton(AccessibilityNodeInfo n) {
        return cls(n).endsWith("Button") || role(n).equals("button");
    }

    static Rect bounds(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r;
    }

    /** Where it is on screen (or, when it has no size, its nearest sized parent); null when nowhere. */
    static Rect visible(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        for (int i = 0; p != null && i < 4; i++, p = p.getParent()) {
            Rect r = bounds(p);
            if (r.width() > 4 && r.height() > 4) return r;
        }
        return null;
    }

    /**
     * Where it really is on screen. Chrome cuts each element's box to the part it thinks is in
     * view and keeps the whole box in extras; on the TTD pilgrim page it thinks nothing is in view
     * (every box comes as 0 high), so the whole box is the one to go by there.
     */
    static Rect realRect(AccessibilityNodeInfo n) {
        Rect r = bounds(n);
        if (r.width() > 4 && r.height() > 4) return r;
        try {
            android.os.Bundle e = n.getExtras();
            String k = "AccessibilityNodeInfo.unclipped";
            if (e != null && e.containsKey(k + "Top")) {
                int top = e.getInt(k + "Top", r.top), left = e.getInt(k + "Left", r.left);
                int bottom = e.containsKey(k + "Bottom") ? e.getInt(k + "Bottom") : top + e.getInt(k + "Height", 0);
                int right = e.containsKey(k + "Right") ? e.getInt(k + "Right") : left + e.getInt(k + "Width", 0);
                if (right - left > 4 && bottom - top > 4) return new Rect(left, top, right, bottom);
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /** Its real place is on the screen, clear of the status bar and the bottom edge. */
    static boolean onScreen(AccessibilityService s, Rect r) {
        if (r == null) return false;
        android.util.DisplayMetrics dm = s.getResources().getDisplayMetrics();
        int y = r.centerY(), x = r.centerX();
        return y > Math.round(40 * dm.density) && y < dm.heightPixels - Math.round(30 * dm.density) && x > 0 && x < dm.widthPixels;
    }

    static boolean tapAt(AccessibilityService s, int x, int y) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        return s.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50)).build(), null, null);
    }

    /** Clicked through accessibility (itself, or its nearest clickable parent); a tap on it when that is refused. */
    static void click(AccessibilityService s, AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; c != null && i < 3 && !c.isClickable(); i++) c = c.getParent();
        if (c == null) c = n;
        if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        if (c != n && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        tapOn(s, n);
    }

    /**
     * One line of an open list clicked: itself through accessibility (never its list - that would
     * choose nothing), else a tap on it when it is on screen, else its clickable parent.
     */
    static void clickOption(AccessibilityService s, AccessibilityNodeInfo n) {
        if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        Rect r = bounds(n);
        if (r.width() > 4 && r.height() > 4 && tapOn(s, n)) return;
        click(s, n);
    }

    /** A real finger tap in its middle (when it is on screen). */
    static boolean tapOn(AccessibilityService s, AccessibilityNodeInfo n) {
        Rect r = visible(n);
        if (r == null) return false;
        int h = s.getResources().getDisplayMetrics().heightPixels;
        if (r.centerY() <= 0 || r.centerY() >= h) return false;
        Path p = new Path();
        p.moveTo(Math.max(0, r.centerX()), r.centerY());
        return s.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }
}
