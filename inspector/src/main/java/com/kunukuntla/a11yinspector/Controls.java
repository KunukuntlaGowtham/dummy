package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks every controllable element as it is scanned and says what would stop an automation
 * app (or a screen-reader user) using it: no name, disabled, off screen, too small to tap,
 * a hidden 0-size box (a web checkbox drawn by its label), a "clickable" that has no click
 * action (or the other way round), a field that can't be typed into, a name used twice.
 * Reads only - nothing is pressed.
 */
final class Controls {

    private final int minSize;
    private final Map<String, Integer> problemCounts = new TreeMap<>();
    private final Map<String, Integer> names = new LinkedHashMap<>();
    private final List<String> duplicates = new ArrayList<>();
    private int checked, ok;

    Controls(AccessibilityService service) {
        // Android's smallest comfortable touch target: 48 dp (40 dp counted as fine here).
        minSize = Math.round(40 * service.getResources().getDisplayMetrics().density);
    }

    /** What is wrong with this controllable, or an empty list. */
    List<String> problems(AccessibilityNodeInfo n, String text, List<String> actions, Rect r, String kind) {
        checked++;
        List<String> out = new ArrayList<>();
        String name = text;
        if (name.isEmpty() && n.getLabeledBy() != null) name = Page.label(n.getLabeledBy());
        boolean field = n.isEditable() || String.valueOf(n.getClassName()).endsWith("EditText");
        if (name.isEmpty() && !field && !n.isScrollable()) out.add("no name (nothing says what it is)");
        if (!n.isEnabled()) out.add("disabled");
        if (!n.isVisibleToUser()) out.add("not on screen now");
        boolean zero = r.width() <= 4 || r.height() <= 4;
        if (zero) {
            out.add("0-size box (hidden; use its label / parent)");
        } else if (n.isVisibleToUser() && !n.isScrollable() && (r.width() < minSize || r.height() < minSize)) {
            out.add("small target " + r.width() + "x" + r.height() + " px");
        }
        boolean clickAction = actions.contains("click");
        if (n.isClickable() && !clickAction) out.add("says clickable but has no click action");
        if (!n.isClickable() && clickAction && !field) out.add("click action but not marked clickable");
        if (field && !actions.contains("set-text")) out.add("field can't be typed into (no set-text)");
        if (n.isPassword()) out.add("password field");
        if (n.isCheckable() && !clickAction && !n.isClickable()) out.add("checkbox with no way to tick it");
        if (!name.isEmpty()) {
            String key = (kind == null ? "" : kind) + "|" + name.toLowerCase(java.util.Locale.ROOT);
            names.merge(key, 1, Integer::sum);
        }
        for (String p : out) {
            String k = p.startsWith("small target") ? "small target" : p;
            if (!p.equals("password field")) problemCounts.merge(k, 1, Integer::sum);
        }
        boolean fine = true;
        for (String p : out) if (!p.equals("password field")) fine = false;
        if (fine) ok++;
        return out;
    }

    /** After the last element: the names used more than once. */
    void finish() {
        for (Map.Entry<String, Integer> e : names.entrySet()) {
            if (e.getValue() < 2) continue;
            String[] parts = e.getKey().split("\\|", 2);
            duplicates.add("\"" + parts[1] + "\"" + (parts[0].isEmpty() ? "" : " (" + parts[0] + ")") + " x" + e.getValue());
        }
    }

    String summary() {
        if (checked == 0) return "🔎 No controllable elements reported\n";
        StringBuilder sb = new StringBuilder("🔎 Controllables checked: ").append(checked).append(", ")
                .append(ok).append(" ok");
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : problemCounts.entrySet()) parts.add(e.getValue() + " " + shortName(e.getKey()));
        if (!duplicates.isEmpty()) parts.add(duplicates.size() + " names used twice");
        if (!parts.isEmpty()) sb.append(" · ⚠ ").append(String.join(", ", parts));
        return sb.append('\n').toString();
    }

    String report() {
        StringBuilder sb = new StringBuilder("CONTROLLABLES CHECK (").append(checked).append(" checked, ")
                .append(ok).append(" with nothing wrong)\n");
        if (problemCounts.isEmpty()) sb.append("  no problems found\n");
        for (Map.Entry<String, Integer> e : problemCounts.entrySet()) {
            sb.append("  ⚠ ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        if (!duplicates.isEmpty()) {
            sb.append("  ⚠ same name on more than one control (an app can't tell them apart by name):\n");
            for (String d : duplicates) sb.append("    ").append(d).append('\n');
        }
        sb.append("  Each controllable, with its own ✓ / ⚠, is listed under CONTROLLABLE ELEMENTS below.\n");
        return sb.toString();
    }

    private static String shortName(String problem) {
        int i = problem.indexOf(" (");
        return i > 0 ? problem.substring(0, i) : problem;
    }
}
