package com.kunukuntla.binpress;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the sevak profiles on the page. Each profile card starts with its number ("2") and has,
 * after it, its name and its delete bin (a picture; the page does not mark it as tappable, so it is
 * tapped where it is drawn). Your own profile (1) has no bin. Reads only - nothing is pressed here.
 */
final class Cards {

    static final class Card {
        final int number;
        final String name;
        final AccessibilityNodeInfo bin; // null: none found

        Card(int number, String name, AccessibilityNodeInfo bin) {
            this.number = number;
            this.name = name;
            this.bin = bin;
        }
    }

    private Cards() {
    }

    /** Every node of the page in front (not our own windows), depth first. */
    static List<AccessibilityNodeInfo> nodes(AccessibilityService service) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        List<AccessibilityWindowInfo> windows;
        try {
            windows = service.getWindows();
        } catch (RuntimeException e) {
            return out;
        }
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    || w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM
                    || w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            stack.add(root);
            int seen = 0;
            while (!stack.isEmpty() && seen++ < 6000) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                if (n == null) continue;
                out.add(n);
                for (int i = n.getChildCount() - 1; i >= 0; i--) stack.add(n.getChild(i));
            }
        }
        return out;
    }

    static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) return t.toString().trim();
        CharSequence d = n.getContentDescription();
        return d == null ? "" : d.toString().trim();
    }

    /** Every profile card on the page, in page order. */
    static List<Card> read(AccessibilityService service) {
        List<Card> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : nodes(service)) {
            String label = label(n);
            if (!label.matches("\\d{1,2}") || n.getChildCount() > 0) continue;
            AccessibilityNodeInfo card = n.getParent();
            if (card == null) continue;
            String name = "";
            AccessibilityNodeInfo bin = null, firstPicture = null;
            boolean after = false;
            for (int i = 0; i < card.getChildCount(); i++) {
                AccessibilityNodeInfo c = card.getChild(i);
                if (c == null) continue;
                if (c.equals(n)) {
                    after = true;
                    continue;
                }
                if (!after) continue;
                String t = label(c);
                if (t.matches("\\d{1,2}")) break; // the next card's number
                if (isPicture(c)) {
                    if (isBin(c)) bin = c;
                    else if (firstPicture == null && !looksLikeChevron(c)) firstPicture = c;
                } else if (name.isEmpty() && t.matches(".*[A-Za-z]{3,}.*")) {
                    name = t;
                }
            }
            if (name.isEmpty()) continue; // a bare number, not a profile card
            if (bin == null) bin = firstPicture; // the bin is not named like a bin: the first picture that is no arrow
            out.add(new Card(Integer.parseInt(label), name, bin));
        }
        return out;
    }

    static Card find(AccessibilityService service, int number) {
        for (Card c : read(service)) if (c.number == number) return c;
        return null;
    }

    private static String role(AccessibilityNodeInfo n) {
        try {
            Bundle ex = n.getExtras();
            CharSequence r = ex == null ? null : ex.getCharSequence("AccessibilityNodeInfo.chromeRole");
            return r == null ? "" : r.toString().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String url(AccessibilityNodeInfo n) {
        try {
            Bundle ex = n.getExtras();
            CharSequence u = ex == null ? null : ex.getCharSequence("AccessibilityNodeInfo.targetUrl");
            return u == null ? "" : u.toString().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    static boolean isPicture(AccessibilityNodeInfo n) {
        return String.valueOf(n.getClassName()).endsWith("Image") || role(n).contains("image");
    }

    /** The bin picture: its address or its name says bin / trash / delete / remove. */
    static boolean isBin(AccessibilityNodeInfo n) {
        String u = url(n);
        if (u.contains("bin.") || u.contains("trash") || u.contains("delete") || u.contains("remove")) return true;
        String l = label(n).toLowerCase(Locale.ROOT);
        return l.contains("delete") || l.contains("remove") || l.contains("trash") || l.equals("bin");
    }

    private static boolean looksLikeChevron(AccessibilityNodeInfo n) {
        String s = url(n) + " " + label(n).toLowerCase(Locale.ROOT);
        return s.contains("arrow") || s.contains("chevron") || s.contains("down") || s.contains("expand")
                || s.contains("dropdown") || s.contains("collapse");
    }

    /** Where to tap: the bin as drawn on screen, or null when it is off the screen. */
    static Rect where(AccessibilityNodeInfo bin) {
        Rect r = new Rect();
        bin.getBoundsInScreen(r);
        return r.width() > 4 && r.height() > 4 && bin.isVisibleToUser() ? r : null;
    }

    /** A cheap "has the page changed" sign: how many items, and how many of them can be pressed. */
    static String signature(AccessibilityService service) {
        int all = 0, clickable = 0;
        for (AccessibilityNodeInfo n : nodes(service)) {
            all++;
            if (n.isClickable()) clickable++;
        }
        return all + "/" + clickable;
    }
}
