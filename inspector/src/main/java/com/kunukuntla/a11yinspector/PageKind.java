package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/**
 * Which page of the booking the screen shows, read from what the page reports:
 * <ol>
 *   <li>Sevaks list - the sevaks (no calendar, no checkboxes to tick yet);</li>
 *   <li>Calendar - the dropdown, the calendar, the checkbox and the radio (slot) buttons;</li>
 *   <li>Ticking - the sevak rows, each with its checkbox, and Continue.</li>
 * </ol>
 * Reads only - nothing is pressed.
 */
final class PageKind {

    enum Kind {
        SEVAK_LIST(1, "Sevaks list"),
        CALENDAR(2, "Calendar & slot"),
        TICKING(3, "Ticking sevaks"),
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
        boolean cont, contOn;

        String describe() {
            StringBuilder sb = new StringBuilder();
            if (days > 0) sb.append(days).append(" calendar days, ");
            if (dropdowns > 0) sb.append(dropdowns).append(" dropdown, ");
            if (radios > 0) sb.append(radios).append(" radio, ");
            if (boxes > 0) sb.append(boxes).append(" checkbox").append(boxes == 1 ? "" : "es")
                    .append(" (").append(ticked).append(" ☑), ");
            if (sevakWords > 0) sb.append("\"sevak\" ×").append(sevakWords).append(", ");
            if (cont) sb.append("Continue ").append(contOn ? "on" : "off").append(", ");
            return sb.length() == 0 ? "nothing to book with" : sb.substring(0, sb.length() - 2);
        }
    }

    /** At least this many day cells make a calendar (a date in the text is not one). */
    static final int MIN_DAYS = 7;

    /** The page these facts point to (the rules, kept apart so they can be tested). */
    static Kind decide(Facts f) {
        // A calendar on screen: the date / slot page, even with its one checkbox and radios.
        if (f.days >= MIN_DAYS) return Kind.CALENDAR;
        if (f.dropdowns > 0 && f.radios > 0) return Kind.CALENDAR;
        // No calendar, checkboxes to tick: the sevak rows.
        if (f.boxes >= 2 || f.boxes == 1 && (f.sevakWords > 0 || f.cont)) return Kind.TICKING;
        // Sevaks named, nothing to tick or pick: the sevaks list.
        if (f.boxes == 0 && f.sevakWords > 0) return Kind.SEVAK_LIST;
        return Kind.OTHER;
    }

    /** Reads the page in front now. */
    static Facts read(AccessibilityService service) {
        Facts f = new Facts();
        List<AccessibilityNodeInfo> nodes = Page.nodes(service);
        for (AccessibilityNodeInfo n : nodes) {
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
            if (n.isClickable() && low.equals("continue")) {
                f.cont = true;
                f.contOn = n.isEnabled();
            }
        }
        f.days = Booker.dayCells(service).size();
        return f;
    }

    static Kind detect(AccessibilityService service) {
        return decide(read(service));
    }
}
