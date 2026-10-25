package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which page of the booking the screen shows, read from what the page reports:
 * <ol>
 *   <li>Sevaks list - whatever page comes just before page 2 (learned: the page left for
 *       page 2, or reached by Back from it);</li>
 *   <li>Calendar - the dropdown, the calendar, the checkbox and the radio (slot) buttons;</li>
 *   <li>Ticking - the sevak rows, each with its checkbox, and Continue;</li>
 *   <li>Confirm - a Confirm button.</li>
 * </ol>
 * Reads only - nothing is pressed.
 */
final class PageKind {

    enum Kind {
        SEVAK_LIST(1, "Sevaks list"),
        CALENDAR(2, "Calendar & slot"),
        TICKING(3, "Ticking sevaks"),
        CONFIRM(4, "Confirm"),
        OTHER(0, "Not a booking page");

        final int number;
        final String name;

        Kind(int number, String name) {
            this.number = number;
            this.name = name;
        }

        /** The page with this number (1-4), OTHER for any other. */
        static Kind of(int number) {
            for (Kind k : values()) if (k.number == number && number > 0) return k;
            return OTHER;
        }

        /** "Page 2 · Calendar & slot", or "Not a booking page". */
        String title() {
            return number == 0 ? name : "Page " + number + " · " + name;
        }
    }

    /** What the page has, counted. */
    static final class Facts {
        int days, radios, boxes, ticked, dropdowns, sevakWords, fields;
        /** Page 2's declaration ("I hereby declare ...") is on the page. */
        boolean declare;
        boolean cont, contOn, confirm, confirmOn, confirmShown;
        String pkg = "";
        /** The page's words (not bare numbers): what page 1 is known by. */
        final Set<String> texts = new HashSet<>();

        int empty() {
            return boxes - ticked;
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            if (days > 0) sb.append(days).append(" calendar days, ");
            if (dropdowns > 0) sb.append(dropdowns).append(" dropdown, ");
            if (radios > 0) sb.append(radios).append(" radio, ");
            if (boxes > 0) sb.append(boxes).append(" checkbox").append(boxes == 1 ? "" : "es")
                    .append(" (").append(ticked).append(" ☑), ");
            if (sevakWords > 0) sb.append("\"sevak\" ×").append(sevakWords).append(", ");
            if (cont) sb.append("Continue ").append(contOn ? "on" : "off").append(", ");
            if (confirm) sb.append("Confirm ").append(confirmOn ? "on" : "off").append(", ");
            return sb.length() == 0 ? "nothing to book with" : sb.substring(0, sb.length() - 2);
        }
    }

    /** At least this many day cells make a calendar (a date in the text is not one). */
    static final int MIN_DAYS = 7;

    /**
     * The page these facts point to (the rules, kept apart so they can be tested). Page 1 is
     * not decided here: it is known by its words ({@link #samePage}), not by what it has.
     */
    static Kind decide(Facts f) {
        // A calendar on screen: the date / slot page, even with its one checkbox and radios.
        if (f.days >= MIN_DAYS) return Kind.CALENDAR;
        if (f.dropdowns > 0 && f.radios > 0) return Kind.CALENDAR;
        // A Confirm button: the last page.
        // (A Confirm below the screen counts only on a page with no checkboxes: never page 3.)
        if (f.confirm && (f.confirmShown || f.boxes == 0)) return Kind.CONFIRM;
        // No calendar, checkboxes to tick: the sevak rows.
        // Page 2 still drawing its calendar: its one checkbox is the declaration, or it has the
        // dropdown's text field - never page 3.
        if (f.boxes <= 1 && (f.declare || f.fields > 0 && f.boxes == 1)) return Kind.CALENDAR;
        if (f.boxes >= 2 || f.boxes == 1 && (f.sevakWords > 0 || f.cont)) return Kind.TICKING;
        // The sevaks list by its own words (known also on first use, before page 2 was ever seen).
        if (f.boxes == 0 && isPage1(f.texts)) return Kind.SEVAK_LIST;
        return Kind.OTHER;
    }

    /** Page 1's own words: "Sevaks Profiles" / "Add Sevak" (any case). */
    static boolean isPage1(Set<String> texts) {
        for (String t : texts) {
            String l = t.toLowerCase(Locale.ROOT).trim();
            if (l.equals("sevaks profiles") || l.equals("add sevak") || l.equals("+ add sevak")) return true;
        }
        return false;
    }

    /** Two readings of the same page: most of their words shared. */
    static boolean samePage(Set<String> a, Set<String> b) {
        if (a.size() < 3 || b.size() < 3) return false;
        int shared = 0;
        for (String s : a) if (b.contains(s)) shared++;
        return shared * 10 >= Math.max(a.size(), b.size()) * 6;
    }

    /** "Confirm", "Confirm Booking", "CONFIRM →" - a button's words, lower-case letters only. */
    static boolean isConfirm(String label) {
        String w = norm(label);
        return w.equals("confirm") || w.startsWith("confirm ") && w.length() <= 24;
    }

    static boolean isContinue(String label) {
        return norm(label).equals("continue");
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace("…", "").replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Inside a dialog / pop-up (up to 10 levels up): its buttons are never a page's. */
    static boolean inDialog(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo a = n.getParent();
        for (int i = 0; i < 10 && a != null; i++, a = a.getParent()) {
            if (Page.isDialog(a)) return true;
        }
        return false;
    }

    /** The button with these words that can be pressed (itself or the box around it), or null. */
    static AccessibilityNodeInfo pressable(AccessibilityNodeInfo n) {
        if (n.isClickable()) return n;
        AccessibilityNodeInfo p = n.getParent();
        return p != null && p.isClickable() ? p : null;
    }

    /** Reads the page in front now. */
    static Facts read(AccessibilityService service) {
        Facts f = new Facts();
        List<AccessibilityNodeInfo> nodes = Page.nodes(service);
        for (AccessibilityNodeInfo n : nodes) {
            if (f.pkg.isEmpty() && n.getPackageName() != null) f.pkg = n.getPackageName().toString();
            String cls = String.valueOf(n.getClassName());
            String role = Page.role(n).toLowerCase(Locale.ROOT);
            String label = Page.label(n);
            String low = label.toLowerCase(Locale.ROOT);
            if (Booker.isRadio(n) && !role.contains("radiogroup")) {
                f.radios++;
            } else if (Page.isCheckbox(n)) {
                f.boxes++;
                if (n.isChecked()) f.ticked++;
            }
            if (cls.endsWith("Spinner") || role.contains("combobox") || role.contains("combo box")
                    || role.contains("popupbutton") || role.contains("pop up button") || role.equals("listbox")) {
                f.dropdowns++;
            }
            if (low.contains("sevak")) f.sevakWords++;
            if (low.contains("hereby declare")) f.declare = true;
            if (cls.endsWith("EditText") || role.contains("textfield")) f.fields++;
            if (label.length() >= 2 && !label.matches("[\\d\\s/:.,-]+") && f.texts.size() < 300) f.texts.add(label);
            // Continue / Confirm count also below the screen (pressed through accessibility).
            if (label.isEmpty()) continue;
            AccessibilityNodeInfo button = pressable(n);
            if (button == null) continue;
            if (isContinue(label)) {
                f.cont = true;
                f.contOn = button.isEnabled() && n.isEnabled();
            } else if (isConfirm(label) && !inDialog(n)) {
                f.confirm = true;
                f.confirmOn = f.confirmOn || button.isEnabled() && n.isEnabled();
                f.confirmShown = f.confirmShown || n.isVisibleToUser();
            }
        }
        f.days = Booker.dayCells(service).size();
        return f;
    }
}
