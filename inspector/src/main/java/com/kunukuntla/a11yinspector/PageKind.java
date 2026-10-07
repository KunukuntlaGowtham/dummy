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

        /** "Page 2 · Calendar & slot", or "Not a booking page". */
        String title() {
            return number == 0 ? name : "Page " + number + " · " + name;
        }
    }

    /** What the page has, counted. */
    static final class Facts {
        int days, radios, boxes, ticked, dropdowns, sevakWords;
        boolean cont, contOn, confirm, confirmOn;
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
        if (f.confirm) return Kind.CONFIRM;
        // No calendar, checkboxes to tick: the sevak rows.
        if (f.boxes >= 2 || f.boxes == 1 && (f.sevakWords > 0 || f.cont)) return Kind.TICKING;
        return Kind.OTHER;
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
            if (label.length() >= 2 && !label.matches("[\\d\\s/:.,-]+") && f.texts.size() < 300) f.texts.add(label);
            if (label.isEmpty() || !n.isVisibleToUser()) continue;
            AccessibilityNodeInfo button = pressable(n);
            if (button == null) continue;
            if (isContinue(label)) {
                f.cont = true;
                f.contOn = button.isEnabled() && n.isEnabled();
            } else if (isConfirm(label)) {
                f.confirm = true;
                f.confirmOn = f.confirmOn || button.isEnabled() && n.isEnabled();
            }
        }
        f.days = Booker.dayCells(service).size();
        return f;
    }
}
