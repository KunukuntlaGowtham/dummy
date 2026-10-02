package com.kunukuntla.a11yinspector;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * How a page reacted to a Tick run - its checkbox, the way it took the ticks, the pop-up after
 * each tick (how Tick saw it and closed it, how late it came), refused rows, its list, its
 * Continue - kept for each app (the last run), and compared with the last run in another app.
 * So a run on the practice app and one on the real app say whether both reacted the same.
 */
final class Behaviour {

    /** The facts compared, in this order, with what they're called in the report. */
    private static final String[][] FACTS = {
            {"box", "Checkbox"},
            {"label", "Around the box"},
            {"tickWay", "Ticked by"},
            {"popupWhen", "Pop-up after a tick"},
            {"popupSeen", "Pop-up seen as"},
            {"popupClose", "Pop-up closed by"},
            {"popupDelay", "Pop-up comes"},
            {"refuse", "Refused row"},
            {"rebuilt", "List after an add"},
            {"continue", "Continue"},
    };

    private final String app;
    private final TreeSet<String> ways = new TreeSet<>(), seen = new TreeSet<>(), closes = new TreeSet<>(),
            refusals = new TreeSet<>();
    private final List<Long> delays = new ArrayList<>();
    private int ticks, withPopup;
    private boolean rebuilt;
    private String box = "", label = "", continueStart = "";

    Behaviour(String app) {
        this.app = app == null ? "?" : app;
    }

    // ---- what Tick saw, as it happens -------------------------------------------------

    /** The page as read at the start: its first checkbox, and Continue. */
    void page(AccessibilityNodeInfo firstBox, AccessibilityNodeInfo cont) {
        if (firstBox != null) {
            Rect r = Page.bounds(firstBox);
            boolean hidden = r.width() <= 1 || r.height() <= 1;
            box = (hidden ? "hidden (0 wide)" : "shown") + ", " + (Page.label(firstBox).isEmpty() ? "no name" : "named");
            AccessibilityNodeInfo p = firstBox.getParent();
            String role = p == null ? "" : Page.role(p).toLowerCase(Locale.ROOT);
            if (p != null && role.contains("label")) {
                label = "a label" + (p.isClickable() ? ", tappable" : ", not tappable");
            } else {
                label = "no label" + (p != null && p.isClickable() ? " (tappable box around it)" : "");
            }
        }
        continueStart = cont == null ? "none" : cont.isEnabled() ? "on" : "off";
    }

    /** A box ticked ("click", "label", "tap") - or not ("not ticked"). */
    void ticked(String way) {
        ticks++;
        ways.add(way);
    }

    /** A pop-up came {@code delay} ms after the tick (-1: not known), seen and closed so. */
    void popup(String seenAs, String closedBy, long delay) {
        withPopup++;
        seen.add(seenAs);
        closes.add(closedBy);
        if (delay >= 0) delays.add(delay);
    }

    /** A row not added: how ("unticks it after its pop-up", "won't tick"). */
    void refused(String how) {
        refusals.add(how);
    }

    /** The page replaced its checkboxes (a box Tick held went from the page). */
    void rebuilt() {
        rebuilt = true;
    }

    // ---- the profile ------------------------------------------------------------------

    private Map<String, String> facts(AccessibilityNodeInfo contEnd) {
        Map<String, String> f = new LinkedHashMap<>();
        if (!box.isEmpty()) f.put("box", box);
        if (!label.isEmpty()) f.put("label", label);
        if (!ways.isEmpty()) f.put("tickWay", String.join(" / ", ways));
        f.put("popupWhen", withPopup == 0 ? "never" : withPopup >= ticks ? "every tick" : "some ticks");
        if (!seen.isEmpty()) f.put("popupSeen", String.join(" / ", seen));
        if (!closes.isEmpty()) f.put("popupClose", String.join(" / ", closes));
        if (!delays.isEmpty()) {
            List<Long> d = new ArrayList<>(delays);
            Collections.sort(d);
            long median = d.get(d.size() / 2);
            f.put("popupDelay", (median < 300 ? "at once (under 0.3 s)" : median < 1000 ? "after 0.3-1 s"
                    : median < 3000 ? "after 1-3 s" : "after over 3 s"));
            f.put("popupDelayMs", String.valueOf(median));
        }
        f.put("refuse", refusals.isEmpty() ? "none refused" : String.join(" / ", refusals));
        f.put("rebuilt", rebuilt ? "rebuilt" : "kept");
        String end = contEnd == null ? "none" : contEnd.isEnabled() ? "on" : "off";
        String begin = continueStart.isEmpty() ? end : continueStart;
        f.put("continue", begin.equals("none") && end.equals("none") ? "none"
                : begin.equals("off") && end.equals("on") ? "off, on once ticked"
                : begin.equals("on") ? "on from the start" : "stayed " + end);
        return f;
    }

    /**
     * Saves this run as the app's profile and returns the report section: the profile, and how
     * it compares with the last run in each other app. {@code summary} gets one line per app.
     */
    String finish(Context c, AccessibilityNodeInfo contEnd, StringBuilder summary) {
        Map<String, String> mine = facts(contEnd);
        SharedPreferences sp = c.getSharedPreferences("behaviour", Context.MODE_PRIVATE);
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date());
        StringBuilder out = new StringBuilder("\n\nHOW THE PAGE REACTED (this run, " + app + ")\n");
        for (String[] k : FACTS) {
            String v = mine.get(k[0]);
            if (v == null) continue;
            out.append("  ").append(k[1]).append(": ").append(v);
            if (k[0].equals("popupDelay")) out.append(" (").append(mine.get("popupDelayMs")).append(" ms)");
            out.append('\n');
        }
        // Compared with the last run in every other app.
        for (String other : sp.getStringSet("apps", Collections.emptySet())) {
            if (other.equals(app)) continue;
            Map<String, String> theirs = decode(sp.getString("p_" + other, ""));
            if (theirs.isEmpty()) continue;
            out.append("\nCOMPARED WITH ").append(other).append(" (its last Tick run, ")
                    .append(sp.getString("t_" + other, "?")).append(")\n");
            int same = 0, differ = 0;
            String timing = "";
            List<String> differs = new ArrayList<>();
            for (String[] k : FACTS) {
                String a = mine.get(k[0]), b = theirs.get(k[0]);
                if (a == null && b == null) continue;
                String mark;
                if (a == null || b == null) {
                    mark = "·  not seen in " + (a == null ? "this run" : "that run");
                } else if (k[0].equals("refuse") && (a.equals("none refused") || b.equals("none refused"))
                        && !a.equals(b)) {
                    mark = "·  not compared (no row was refused in one run)";
                } else if (a.equals(b)) {
                    mark = "✓ same";
                    same++;
                } else if (k[0].equals("popupDelay")) {
                    // The real pop-up waits for its server: timing is said apart, not as a difference.
                    mark = "⚠ timing differs";
                    timing = " - pop-up timing differs (" + mine.get("popupDelayMs") + " ms here, "
                            + theirs.get("popupDelayMs") + " ms there)";
                } else {
                    mark = "✗ DIFFERS";
                    differ++;
                    differs.add(k[1]);
                }
                out.append("  ").append(mark).append(" - ").append(k[1]).append(": ")
                        .append(a == null ? "-" : a)
                        .append(a != null && b != null && a.equals(b) ? "" : "  |  there: " + (b == null ? "-" : b))
                        .append('\n');
            }
            String line = (differ == 0 ? "🔁 Reacted the same as " : "🔁 Differs from ") + other + ": "
                    + same + " same" + (differ == 0 ? "" : ", " + differ + " differ (" + String.join(", ", differs) + ")")
                    + timing;
            out.append("  ").append(line).append('\n');
            summary.append('\n').append(line);
        }
        // Kept as this app's profile, for the next run in another app.
        java.util.Set<String> apps = new java.util.HashSet<>(sp.getStringSet("apps", Collections.emptySet()));
        apps.add(app);
        sp.edit().putStringSet("apps", apps).putString("p_" + app, encode(mine)).putString("t_" + app, when).apply();
        return out.toString();
    }

    private static String encode(Map<String, String> f) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : f.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue().replace('\n', ' ')).append('\n');
        }
        return sb.toString();
    }

    private static Map<String, String> decode(String s) {
        Map<String, String> f = new LinkedHashMap<>();
        for (String line : s.split("\n")) {
            int t = line.indexOf('\t');
            if (t > 0) f.put(line.substring(0, t), line.substring(t + 1));
        }
        return f;
    }
}
