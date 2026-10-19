package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds a sevak profile's delete (bin) icon by the profile's number on the sevaks list.
 * Each profile card starts with its number ("2"); the bin is a picture of bin.png in the
 * card (the page names it "Edit"), next to the card's chevron. The page doesn't mark the
 * bin as tappable, so it is tapped where it is drawn. Reads only - nothing is pressed here.
 */
final class SevakDeleter {

    /** A profile card: its number, name, and its bin icon (null when it has none, like your own profile). */
    static final class Card {
        final int number;
        final String name;
        final AccessibilityNodeInfo bin;

        Card(int number, String name, AccessibilityNodeInfo bin) {
            this.number = number;
            this.name = name;
            this.bin = bin;
        }
    }

    /** Every profile card on the page, in page order. */
    static List<Card> cards(AccessibilityService service) {
        List<Card> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            String label = Page.label(n);
            if (!label.matches("\\d{1,2}") || n.getChildCount() > 0) continue;
            AccessibilityNodeInfo card = n.getParent();
            if (card == null) continue;
            String name = "";
            AccessibilityNodeInfo bin = null;
            boolean nameNext = false;
            for (int i = 0; i < card.getChildCount(); i++) {
                AccessibilityNodeInfo c = card.getChild(i);
                if (c == null) continue;
                if (c.equals(n)) {
                    nameNext = true;
                    continue;
                }
                if (!nameNext) continue;
                String t = Page.label(c);
                if (isBin(c)) {
                    bin = c;
                } else if (name.isEmpty() && t.matches(".*[A-Za-z]{3,}.*") && !isPicture(c)) {
                    name = t;
                }
                // The next card's number: this card ends here (cards side by side in one box).
                if (t.matches("\\d{1,2}") && !c.equals(n)) break;
            }
            if (name.isEmpty()) continue; // a bare number, not a profile card (e.g. the "10" field)
            out.add(new Card(Integer.parseInt(label), name, bin));
        }
        return out;
    }

    static Card card(AccessibilityService service, int number) {
        for (Card c : cards(service)) if (c.number == number) return c;
        return null;
    }

    private static boolean isPicture(AccessibilityNodeInfo n) {
        return String.valueOf(n.getClassName()).endsWith("Image") || Page.role(n).toLowerCase(Locale.ROOT).contains("image");
    }

    /** The bin picture: its address names a bin / trash / delete, or its name says delete / remove. */
    static boolean isBin(AccessibilityNodeInfo n) {
        if (!isPicture(n)) return false;
        String url = targetUrl(n).toLowerCase(Locale.ROOT);
        if (url.contains("bin.") || url.contains("trash") || url.contains("delete")) return true;
        String l = Page.label(n).toLowerCase(Locale.ROOT);
        return l.contains("delete") || l.contains("remove") || l.contains("trash");
    }

    static String targetUrl(AccessibilityNodeInfo n) {
        try {
            Bundle ex = n.getExtras();
            CharSequence u = ex == null ? null : ex.getCharSequence("AccessibilityNodeInfo.targetUrl");
            return u == null ? "" : u.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Where to tap the bin: its drawn place on screen, or null when it is off screen. */
    static Rect where(AccessibilityNodeInfo bin) {
        Rect r = Page.bounds(bin);
        return r.width() > 4 && r.height() > 4 && bin.isVisibleToUser() ? r : null;
    }
}
