package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The in-depth part of a scan: what Tick, Clear and Book would find and do on this page, read
 * from the accessibility tree only. Pop-ups up now and the button that clears each; every
 * checkbox with its row, state and how it can be ticked; the calendar (days, month shown,
 * closed days, arrows); the dropdown and its options; radio buttons; the Continue / Submit
 * buttons; and the messages the page shows (errors, live regions). Reads only.
 */
final class Automation {

    private Automation() {
    }

    /** A scan's in-depth part: a few lines for the card and the full section for the report. */
    static final class Result {
        final String summary;
        final String report;

        Result(String summary, String report) {
            this.summary = summary;
            this.report = report;
        }
    }

    static Result look(AccessibilityService service) {
        List<AccessibilityNodeInfo> nodes = Page.nodes(service);
        StringBuilder sum = new StringBuilder(), rep = new StringBuilder();
        rep.append("WHAT TICK, CLEAR AND BOOK SEE HERE (accessibility only)\n");
        popups(service, nodes, sum, rep);
        checkboxes(nodes, sum, rep);
        calendar(service, sum, rep);
        dropdown(service, nodes, sum, rep);
        radios(nodes, sum, rep);
        buttons(nodes, sum, rep);
        messages(nodes, sum, rep);
        return new Result(sum.toString(), rep.toString());
    }

    // ---- pop-ups -----------------------------------------------------------------------------

    private static void popups(AccessibilityService service, List<AccessibilityNodeInfo> nodes,
                               StringBuilder sum, StringBuilder rep) {
        rep.append("\n  POP-UPS\n");
        List<AccessibilityWindowInfo> ws = Page.windows(service);
        for (AccessibilityWindowInfo w : ws) {
            AccessibilityNodeInfo root = w.getRoot();
            CharSequence title = Build.VERSION.SDK_INT >= 24 ? w.getTitle() : null;
            rep.append("    window #").append(w.getId()).append(w.isActive() ? " (active)" : "")
                    .append(" layer ").append(w.getLayer()).append(" - ")
                    .append(root == null ? "?" : root.getPackageName())
                    .append(title == null ? "" : " \"" + cut(title.toString(), 40) + "\"").append('\n');
        }
        if (ws.size() > 1) rep.append("    ").append(ws.size()).append(" app windows: the top one may be a pop-up\n");
        int dialogs = 0;
        for (AccessibilityNodeInfo n : nodes) {
            boolean dialog = Page.isDialog(n), pane = Page.isPane(n);
            if (!dialog && !pane) continue;
            dialogs++;
            Rect r = Page.bounds(n);
            rep.append("    ").append(dialog ? "dialog" : "pane")
                    .append(pane ? " \"" + cut(String.valueOf(n.getPaneTitle()), 40) + "\"" : "")
                    .append(" ").append(shortClass(n)).append(n.isVisibleToUser() ? "" : " [hidden]")
                    .append(n.isDismissable() ? " [dismissable]" : "")
                    .append(" @").append(r.toShortString()).append('\n');
            List<String> inside = new ArrayList<>();
            buttonsIn(n, inside, 0);
            if (!inside.isEmpty()) rep.append("      buttons: ").append(String.join(", ", inside)).append('\n');
        }
        Page.Popup p = Page.popup(service, null);
        if (p == null) {
            rep.append("    Clear would press: nothing (no pop-up up)\n");
        } else {
            String what = p.button != null ? "\"" + Page.label(p.button) + "\" at " + center(Page.bounds(p.button))
                    : "dismiss on " + shortClass(p.dismiss);
            rep.append("    Clear would press: ").append(what).append(" (").append(p.how).append(")\n");
            sum.append("🪟 Pop-up up: Clear would press ").append(what).append('\n');
        }
        if (dialogs == 0 && p == null && ws.size() <= 1) rep.append("    none up\n");
        rep.append("    Tick sees a pop-up as: a new window, a dialog, or a new OK / Yes / Close ... button after "
                + "a tick - and presses that button through accessibility.\n");
    }

    private static void buttonsIn(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 12 || out.size() >= 12) return;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            if (c.isClickable()) {
                String l = Page.label(c);
                out.add((l.isEmpty() ? shortClass(c) : "\"" + cut(l, 20) + "\"")
                        + (Page.popupRank(l) < Integer.MAX_VALUE ? "✓" : ""));
            }
            buttonsIn(c, out, depth + 1);
        }
    }

    // ---- checkboxes --------------------------------------------------------------------------

    private static void checkboxes(List<AccessibilityNodeInfo> nodes, StringBuilder sum, StringBuilder rep) {
        rep.append("\n  CHECKBOXES (page order)\n");
        int total = 0, empty = 0, off = 0, hidden = 0, noClick = 0;
        for (AccessibilityNodeInfo n : nodes) {
            if (!Page.isCheckbox(n)) continue;
            total++;
            Rect own = Page.bounds(n);
            Rect seen = Page.visible(n);
            boolean zero = own.width() <= 4 || own.height() <= 4;
            boolean click = n.isClickable() || n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            if (!n.isChecked()) empty++;
            if (!n.isEnabled()) off++;
            if (zero) hidden++;
            if (!click) noClick++;
            String row = rowOf(seen == null ? own : seen, nodes);
            String label = labelFor(n);
            rep.append(String.format(Locale.ROOT, "    %3d. ", total)).append(n.isChecked() ? "☑" : "☐")
                    .append(" row ").append(row)
                    .append(label.isEmpty() ? "" : " \"" + cut(label, 40) + "\"")
                    .append(n.isEnabled() ? "" : " [off]")
                    .append(n.isVisibleToUser() ? "" : " [not on screen]")
                    .append(zero ? " [hidden box: ticked through its label" + (seen == null ? ", none sized]" : "]") : "")
                    .append(click ? " [click]" : " [no click action: tapped]")
                    .append(" @").append(seen == null ? "-" : center(seen)).append('\n');
        }
        if (total == 0) {
            rep.append("    none reported - Tick can't find checkboxes here\n");
            return;
        }
        sum.append("☑ Checkboxes: ").append(total).append(", ").append(empty).append(" empty")
                .append(off > 0 ? ", " + off + " off" : "").append(hidden > 0 ? ", " + hidden + " hidden (label clicked)" : "")
                .append(noClick > 0 ? ", " + noClick + " without click" : "").append('\n');
    }

    /** The checkbox's own words, else the words beside it (its parent's). */
    private static String labelFor(AccessibilityNodeInfo n) {
        String l = Page.label(n);
        if (!l.isEmpty()) return l;
        AccessibilityNodeInfo by = n.getLabeledBy();
        if (by != null && !Page.label(by).isEmpty()) return Page.label(by);
        AccessibilityNodeInfo p = n.getParent();
        if (p == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.getChildCount() && sb.length() < 60; i++) {
            AccessibilityNodeInfo c = p.getChild(i);
            if (c != null && !c.equals(n)) sb.append(Page.label(c)).append(' ');
        }
        return sb.toString().trim();
    }

    /** The number printed on the same line as the box (its row), or "?". */
    private static String rowOf(Rect box, List<AccessibilityNodeInfo> nodes) {
        String best = "?";
        int bestGap = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            String t = Page.label(n);
            if (!t.matches("\\(?\\d{1,4}[.)]?")) continue;
            Rect r = Page.bounds(n);
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

    // ---- the calendar ------------------------------------------------------------------------

    private static void calendar(AccessibilityService service, StringBuilder sum, StringBuilder rep) {
        rep.append("\n  CALENDAR\n");
        Map<AccessibilityNodeInfo, int[]> cells = Booker.dayCells(service);
        if (cells.isEmpty()) {
            rep.append("    no day cells found - Book can't pick a date here\n");
            return;
        }
        int shown = Booker.shownMonth(cells);
        List<String> closed = new ArrayList<>(), open = new ArrayList<>(), chosen = new ArrayList<>();
        List<int[]> days = new ArrayList<>(cells.values());
        days.sort((a, b) -> a[2] != b[2] ? a[2] - b[2] : a[1] != b[1] ? a[1] - b[1] : a[0] - b[0]);
        for (int[] d : days) {
            for (Map.Entry<AccessibilityNodeInfo, int[]> e : cells.entrySet()) {
                if (e.getValue() != d) continue;
                AccessibilityNodeInfo n = e.getKey();
                String t = d[0] + "/" + d[1];
                if (!n.isEnabled()) closed.add(t);
                else open.add(t);
                if (n.isSelected() || n.isChecked()) chosen.add(t);
            }
        }
        AccessibilityNodeInfo next = Booker.arrow(service, true), prev = Booker.arrow(service, false);
        rep.append("    month shown: ").append(Booker.monthText(shown)).append(" · ").append(cells.size()).append(" day cells\n");
        rep.append("    open days (").append(open.size()).append("): ").append(String.join(" ", open)).append('\n');
        rep.append("    closed days (").append(closed.size()).append("): ")
                .append(closed.isEmpty() ? "none" : String.join(" ", closed)).append('\n');
        if (!chosen.isEmpty()) rep.append("    chosen: ").append(String.join(" ", chosen)).append('\n');
        rep.append("    next-month arrow: ").append(next == null ? "not found" : "\"" + Page.label(next) + "\" at " + center(Page.bounds(next)))
                .append("\n    previous-month arrow: ").append(prev == null ? "not found" : "\"" + Page.label(prev) + "\" at " + center(Page.bounds(prev)))
                .append('\n');
        sum.append("📅 Calendar: ").append(Booker.monthText(shown)).append(", ").append(open.size()).append(" open / ")
                .append(closed.size()).append(" closed days").append(next == null ? ", no next arrow" : "").append('\n');
    }

    // ---- the dropdown, radios, buttons ---------------------------------------------------------

    private static void dropdown(AccessibilityService service, List<AccessibilityNodeInfo> nodes,
                                 StringBuilder sum, StringBuilder rep) {
        rep.append("\n  DROPDOWN / TEXT FIELDS\n");
        int fields = 0;
        for (AccessibilityNodeInfo n : nodes) {
            String cls = String.valueOf(n.getClassName());
            String role = Page.role(n).toLowerCase(Locale.ROOT);
            if (!cls.endsWith("EditText") && !role.contains("combobox") && !role.contains("textfield")
                    && !cls.contains("Spinner") && !n.isEditable()) continue;
            fields++;
            CharSequence hint = Build.VERSION.SDK_INT >= 26 ? n.getHintText() : null;
            rep.append("    ").append(shortClass(n)).append(role.isEmpty() ? "" : " role=" + role)
                    .append(" \"").append(cut(Page.label(n), 40)).append('"')
                    .append(hint == null ? "" : " hint=\"" + cut(hint.toString(), 30) + "\"")
                    .append(n.isEnabled() ? "" : " [off]").append(" @").append(center(Page.bounds(n))).append('\n');
        }
        List<String> options = Booker.optionTexts(service);
        if (!options.isEmpty()) {
            rep.append("    options open now (").append(options.size()).append("):\n");
            for (String o : options) rep.append("      • ").append(cut(o, 60)).append('\n');
        } else if (fields > 0) {
            rep.append("    options: the list is closed (Book opens it to read them)\n");
        }
        if (fields == 0) rep.append("    none\n");
        if (fields > 0) {
            sum.append("🔽 Fields: ").append(fields).append(options.isEmpty() ? "" : ", " + options.size() + " options open").append('\n');
        }
    }

    private static void radios(List<AccessibilityNodeInfo> nodes, StringBuilder sum, StringBuilder rep) {
        rep.append("\n  RADIO BUTTONS\n");
        int count = 0;
        for (AccessibilityNodeInfo n : nodes) {
            if (!Booker.isRadio(n) || Page.role(n).toLowerCase(Locale.ROOT).contains("radiogroup")) continue;
            count++;
            rep.append("    ").append(n.isChecked() ? "◉" : "○").append(" \"").append(cut(Booker.radioText(n), 50))
                    .append('"').append(n.isEnabled() ? "" : " [off]").append(" @").append(center(Page.bounds(n))).append('\n');
        }
        if (count == 0) rep.append("    none reported (Book then looks for small round buttons in the cards)\n");
        else sum.append("◉ Radio buttons: ").append(count).append('\n');
    }

    private static void buttons(List<AccessibilityNodeInfo> nodes, StringBuilder sum, StringBuilder rep) {
        rep.append("\n  CONTINUE / SUBMIT / OK BUTTONS\n");
        int count = 0;
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isClickable()) continue;
            String l = Page.label(n).toLowerCase(Locale.ROOT).trim();
            boolean go = l.equals("continue") || l.startsWith("submit") || l.startsWith("next") || l.startsWith("book")
                    || l.startsWith("proceed") || l.startsWith("confirm") || l.startsWith("pay") || Page.popupRank(l) < 12;
            if (!go) continue;
            count++;
            rep.append("    \"").append(cut(Page.label(n), 40)).append('"').append(n.isEnabled() ? " [on]" : " [OFF - something missing?]")
                    .append(n.isVisibleToUser() ? "" : " [not on screen]").append(" @").append(center(Page.bounds(n))).append('\n');
            if (l.equals("continue")) {
                sum.append("➡ Continue: ").append(n.isEnabled() ? "on" : "off").append('\n');
            }
        }
        if (count == 0) rep.append("    none\n");
    }

    /** What the page says: fields' errors, live regions (messages read out), alert roles. */
    private static void messages(List<AccessibilityNodeInfo> nodes, StringBuilder sum, StringBuilder rep) {
        rep.append("\n  MESSAGES THE PAGE SHOWS\n");
        int count = 0;
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence err = n.getError();
            String role = Page.role(n).toLowerCase(Locale.ROOT);
            String l = Page.label(n);
            String what = null;
            if (err != null && err.length() > 0) what = "error on \"" + cut(l, 30) + "\": " + err;
            else if (n.getLiveRegion() != 0 && !l.isEmpty()) what = "live: " + l;
            else if ((role.contains("alert") || role.contains("status")) && !l.isEmpty()) what = role + ": " + l;
            if (what == null) continue;
            count++;
            rep.append("    ").append(cut(what, 120)).append('\n');
            if (count == 1) sum.append("💬 ").append(cut(what, 60)).append('\n');
        }
        if (count == 0) rep.append("    none\n");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static String center(Rect r) {
        return r.centerX() + "," + r.centerY();
    }

    private static String shortClass(AccessibilityNodeInfo n) {
        String c = String.valueOf(n.getClassName());
        return c.substring(c.lastIndexOf('.') + 1);
    }

    private static String cut(String s, int max) {
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
