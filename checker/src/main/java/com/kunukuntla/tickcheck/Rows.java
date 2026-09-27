package com.kunukuntla.tickcheck;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the page through accessibility (no screenshots, nothing tapped): every checkbox in
 * page order - also those further down, off screen - with the row number and name next to
 * it, and whether it is ticked.
 */
final class Rows {

    static final class Row {
        final String number;
        final String name;
        final boolean ticked;

        Row(String number, String name, boolean ticked) {
            this.number = number;
            this.name = name;
            this.ticked = ticked;
        }

        String show() {
            return (number.isEmpty() ? "?" : number) + (name.isEmpty() ? "" : " " + name);
        }
    }

    private static final String ROW_NUMBER = "\\(?\\d{1,4}[.)]?";

    private Rows() {
    }

    /** Every checkbox on the page, top to bottom. */
    static List<Row> read(AccessibilityService service) {
        List<Row> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : nodes(service)) {
            if (!isCheckbox(n)) continue;
            String[] numberAndName = rowOf(n);
            out.add(new Row(numberAndName[0], numberAndName[1], n.isChecked()));
        }
        return out;
    }

    private static boolean isCheckbox(AccessibilityNodeInfo n) {
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        if (cls.endsWith("RadioButton") || cls.endsWith("Switch")) return false;
        String role = role(n).toLowerCase(Locale.ROOT);
        if (role.contains("radio") || role.contains("switch")) return false;
        return cls.endsWith("CheckBox") || role.contains("checkbox") || n.isCheckable();
    }

    /**
     * The row the checkbox is on: going up from it, the first part of the page holding
     * exactly one row number - that number, and the first name-like text in it.
     */
    private static String[] rowOf(AccessibilityNodeInfo box) {
        AccessibilityNodeInfo p = box.getParent();
        for (int depth = 0; p != null && depth < 8; depth++, p = p.getParent()) {
            List<AccessibilityNodeInfo> inside = subtree(p, 300);
            String number = null;
            int numbers = 0;
            for (AccessibilityNodeInfo n : inside) {
                String l = label(n);
                if (l.matches(ROW_NUMBER) && !n.isCheckable() && !n.isEditable()) {
                    numbers++;
                    if (number == null) number = l.replaceAll("\\D", "");
                }
            }
            if (numbers == 1) return new String[] {number, nameIn(inside, number)};
            if (numbers > 1) break;
        }
        return new String[] {sameLine(box), ""};
    }

    private static String nameIn(List<AccessibilityNodeInfo> inside, String number) {
        for (AccessibilityNodeInfo n : inside) {
            String l = label(n);
            if (l.equals(number) || !l.matches(".*[A-Za-z]{3,}.*")) continue;
            String low = l.toLowerCase(Locale.ROOT);
            if (low.equals("male") || low.equals("female") || low.startsWith("dob") || low.equals("edit")) continue;
            return l.length() > 30 ? l.substring(0, 30) + "…" : l;
        }
        return "";
    }

    /** No row box around it: the number on the same line (only for boxes on screen). */
    private static String sameLine(AccessibilityNodeInfo box) {
        Rect b = visible(box);
        if (b == null) return "";
        AccessibilityNodeInfo root = box;
        while (root.getParent() != null) root = root.getParent();
        String best = "";
        int bestGap = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : subtree(root, 6000)) {
            String l = label(n);
            if (!l.matches(ROW_NUMBER)) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.height() <= 0 || Math.abs(r.centerY() - b.centerY()) > Math.max(b.height(), r.height()) / 2 + 4) continue;
            int gap = r.left >= b.right ? r.left - b.right : Math.max(0, b.left - r.right);
            if (gap < bestGap) {
                bestGap = gap;
                best = l.replaceAll("\\D", "");
            }
        }
        return best;
    }

    private static Rect visible(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        for (int i = 0; p != null && i < 4; i++, p = p.getParent()) {
            Rect r = new Rect();
            p.getBoundsInScreen(r);
            if (r.width() > 4 && r.height() > 4) return r;
        }
        return null;
    }

    private static List<AccessibilityNodeInfo> subtree(AccessibilityNodeInfo root, int max) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty() && out.size() < max) {
            AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
            if (n == null) continue;
            out.add(n);
            for (int i = n.getChildCount() - 1; i >= 0; i--) stack.add(n.getChild(i));
        }
        return out;
    }

    /** The page's nodes in page order (not our own window, not the status bar). */
    private static List<AccessibilityNodeInfo> nodes(AccessibilityService service) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        String own = service.getPackageName();
        List<AccessibilityWindowInfo> windows;
        try {
            windows = service.getWindows();
        } catch (RuntimeException e) {
            windows = new ArrayList<>();
        }
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || own.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
            out.addAll(subtree(root, 8000));
        }
        return out;
    }

    private static String role(AccessibilityNodeInfo n) {
        try {
            CharSequence c = n.getExtras().getCharSequence("AccessibilityNodeInfo.chromeRole");
            return c == null ? "" : c.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString().replace('\n', ' ').trim();
    }
}
