package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Walks every window the phone reports to accessibility and writes down what an automation
 * app could read and control there: each kind of control, how many accept a click (or typing,
 * scrolling ...), every controllable element with its actions, and the whole element tree.
 * Reads only - nothing is tapped or changed.
 */
final class Scanner {

    private Scanner() {
    }

    /** At most this many elements are read (a huge page stays quick). */
    private static final int MAX_NODES = 6000;

    /** The kinds of control counted, in the order they are listed. */
    private static final String[] KINDS = {"Checkboxes", "Switches / toggles", "Radio buttons",
            "Dropdowns / pickers", "Text fields", "Date / time fields", "Buttons", "Links",
            "Images / icons", "Scrollable lists", "Web views", "Other tappable items", "Plain text"};

    private static final class Tally {
        int found, onScreen, usable, ticked;
        final List<String> examples = new ArrayList<>();
    }

    /** A scan: a short summary (for the pop-up card) and the full report. */
    static final class Result {
        final String summary;
        final String report;

        Result(String summary, String report) {
            this.summary = summary;
            this.report = report;
        }
    }

    static Result scan(AccessibilityService service, boolean deep) {
        String own = service.getPackageName();
        StringBuilder tree = new StringBuilder();
        StringBuilder controls = new StringBuilder();
        Map<String, Tally> kinds = new LinkedHashMap<>();
        for (String k : KINDS) kinds.put(k, new Tally());
        Map<String, Integer> actionCounts = new TreeMap<>();
        int nodes = 0, controllable = 0, webViews = 0, webNodes = 0;

        StringBuilder windows = new StringBuilder();
        String app = "?";
        AccessibilityNodeInfo active = service.getRootInActiveWindow();
        if (active != null && active.getPackageName() != null) app = active.getPackageName().toString();

        List<AccessibilityWindowInfo> list;
        try {
            list = service.getWindows();
        } catch (RuntimeException e) {
            list = new ArrayList<>();
        }
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        for (AccessibilityWindowInfo w : list) {
            AccessibilityNodeInfo root = w.getRoot();
            String pkg = root == null || root.getPackageName() == null ? "?" : root.getPackageName().toString();
            windows.append("  • ").append(windowType(w.getType()))
                    .append(w.isActive() ? " (active)" : "")
                    .append(" - ").append(pkg);
            CharSequence title = Build.VERSION.SDK_INT >= 24 ? w.getTitle() : null;
            if (title != null && title.length() > 0) windows.append(" \"").append(cut(title.toString(), 30)).append('"');
            windows.append(root == null ? "  [shows nothing to accessibility]" : "").append('\n');
            if (root == null || pkg.equals(own)) continue;
            roots.add(root);
        }
        if (roots.isEmpty() && active != null) roots.add(active);

        for (AccessibilityNodeInfo root : roots) {
            // Depth-first, keeping each node's depth for the tree.
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            List<Integer> depths = new ArrayList<>();
            List<Boolean> inWeb = new ArrayList<>();
            stack.add(root);
            depths.add(0);
            inWeb.add(false);
            while (!stack.isEmpty() && nodes < MAX_NODES) {
                int last = stack.size() - 1;
                AccessibilityNodeInfo n = stack.remove(last);
                int depth = depths.remove(last);
                boolean web = inWeb.remove(last);
                if (n == null) continue;
                nodes++;
                String cls = n.getClassName() == null ? "" : n.getClassName().toString();
                boolean isWebView = cls.contains("WebView");
                if (isWebView) webViews++;
                if (web) webNodes++;
                for (int i = n.getChildCount() - 1; i >= 0; i--) {
                    stack.add(n.getChild(i));
                    depths.add(depth + 1);
                    inWeb.add(web || isWebView);
                }

                String role = webRole(n);
                String kind = kindOf(n, cls, role.toLowerCase(Locale.ROOT));
                List<String> actions = actions(n);
                for (String a : actions) actionCounts.merge(a, 1, Integer::sum);
                boolean clickable = n.isClickable() || actions.contains("click");
                boolean usable = clickable || n.isEditable() || n.isScrollable()
                        || actions.contains("set-text") || actions.contains("expand");
                String text = text(n);
                Rect r = new Rect();
                n.getBoundsInScreen(r);

                if (kind != null) {
                    Tally t = kinds.get(kind);
                    t.found++;
                    if (n.isVisibleToUser()) t.onScreen++;
                    if (usable) t.usable++;
                    if (n.isChecked()) t.ticked++;
                    if (!text.isEmpty() && t.examples.size() < 4) t.examples.add(cut(text, 18));
                }

                String line = describe(n, cls, role, text, actions, r);
                indent(tree, depth).append(line).append('\n');
                if (usable || n.isCheckable() || n.isEditable()) {
                    controllable++;
                    controls.append(String.format(Locale.ROOT, "%4d. ", controllable))
                            .append(kind == null ? "Element" : singular(kind)).append(": ")
                            .append(line).append('\n');
                }
            }
        }

        // ---- the report ----
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        StringBuilder sum = new StringBuilder();
        sum.append("App: ").append(app).append("  (").append(deep ? "deep scan" : "scan").append(")\n");
        sum.append(nodes).append(" elements, ").append(controllable).append(" controllable")
                .append(webViews > 0 ? ", " + webViews + " web view(s) with " + webNodes + " elements" : "")
                .append('\n');
        for (Map.Entry<String, Tally> e : kinds.entrySet()) {
            Tally t = e.getValue();
            if (t.found == 0) continue;
            sum.append(t.usable > 0 ? "✅ " : "⚠️ ").append(e.getKey()).append(": ").append(t.found);
            if (t.found != t.onScreen) sum.append(" (").append(t.onScreen).append(" on screen)");
            if (!e.getKey().equals("Plain text")) sum.append(", ").append(t.usable).append(" usable");
            if (e.getKey().equals("Checkboxes") || e.getKey().startsWith("Switches")
                    || e.getKey().startsWith("Radio")) {
                sum.append(", ").append(t.ticked).append(" on");
            }
            if (!t.examples.isEmpty()) sum.append("  - ").append(String.join(", ", t.examples));
            sum.append('\n');
        }
        List<String> none = new ArrayList<>();
        for (Map.Entry<String, Tally> e : kinds.entrySet()) if (e.getValue().found == 0) none.add(e.getKey());
        if (!none.isEmpty()) sum.append("❌ Not reported: ").append(String.join(", ", none)).append('\n');
        if (webViews > 0 && webNodes < 40) {
            sum.append("⚠️ The web view reports very little - its page may hide its controls from "
                    + "accessibility (then only screenshots and taps can work there).\n");
        }

        StringBuilder rep = new StringBuilder();
        rep.append("A11y Inspector - ").append(when).append('\n');
        rep.append("=================================================\n");
        rep.append(sum).append('\n');
        rep.append("WINDOWS\n").append(windows).append('\n');
        rep.append("ACTIONS ACCEPTED (how many elements accept each)\n");
        if (actionCounts.isEmpty()) rep.append("  none\n");
        for (Map.Entry<String, Integer> e : actionCounts.entrySet()) {
            rep.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        rep.append("\nALWAYS AVAILABLE TO AN ACCESSIBILITY APP\n")
                .append("  Back, Home, Recents, Notifications, Quick settings, Power menu")
                .append(Build.VERSION.SDK_INT >= 28 ? ", Lock screen, Screenshot" : "").append('\n')
                .append("  Taps, long presses and swipes anywhere on the screen (gestures)\n")
                .append(Build.VERSION.SDK_INT >= 30 ? "  Screenshots of the screen\n" : "")
                .append('\n');
        rep.append("CONTROLLABLE ELEMENTS (").append(controllable).append(")\n");
        rep.append(controls.length() == 0 ? "  none\n" : controls.toString()).append('\n');
        rep.append("FULL ELEMENT TREE (").append(nodes).append(nodes >= MAX_NODES ? ", cut short" : "")
                .append(")\n");
        rep.append("  legend: [clk] clickable  [long] long-clickable  [chk ☐/☑] checkable  [edit] "
                + "editable  [scroll] scrollable  [off] disabled  [hidden] not on screen\n");
        rep.append(tree);
        return new Result(sum.toString(), rep.toString());
    }

    // ---- what an element is -------------------------------------------------------

    private static String kindOf(AccessibilityNodeInfo n, String cls, String role) {
        boolean switchy = cls.endsWith("Switch") || cls.endsWith("SwitchCompat")
                || cls.endsWith("SwitchMaterial") || cls.endsWith("ToggleButton")
                || role.contains("switch") || role.contains("togglebutton");
        boolean radio = cls.endsWith("RadioButton") || role.contains("radio");
        if (switchy) return "Switches / toggles";
        if (radio) return "Radio buttons";
        if (cls.endsWith("CheckBox") || cls.endsWith("CheckedTextView") || role.contains("checkbox")
                || n.isCheckable()) return "Checkboxes";
        if (cls.contains("Spinner") || cls.contains("ComboBox") || cls.contains("AutoComplete")
                || role.contains("combobox") || role.contains("popupbutton") || role.contains("listbox")
                || n.getActionList().contains(AccessibilityAction.ACTION_EXPAND)) return "Dropdowns / pickers";
        if (role.contains("date") || role.contains("time") || cls.contains("DatePicker")
                || cls.contains("TimePicker")) return "Date / time fields";
        if (n.isEditable() || cls.endsWith("EditText") || role.contains("textfield")
                || role.contains("searchbox")) return "Text fields";
        if (cls.contains("WebView")) return "Web views";
        if (cls.endsWith("Button") || role.equals("button")) return "Buttons";
        if (role.contains("link")) return "Links";
        if (cls.endsWith("ImageView") || role.contains("image") || role.equals("img")) return "Images / icons";
        if (n.isScrollable()) return "Scrollable lists";
        if (n.isClickable()) return "Other tappable items";
        if (!text(n).isEmpty()) return "Plain text";
        return null;
    }

    private static String singular(String kind) {
        switch (kind) {
            case "Checkboxes": return "Checkbox";
            case "Switches / toggles": return "Switch";
            case "Radio buttons": return "Radio";
            case "Dropdowns / pickers": return "Dropdown";
            case "Text fields": return "Text field";
            case "Date / time fields": return "Date";
            case "Buttons": return "Button";
            case "Links": return "Link";
            case "Images / icons": return "Image";
            case "Scrollable lists": return "List";
            case "Web views": return "Web view";
            case "Other tappable items": return "Tappable";
            default: return "Text";
        }
    }

    /** Chrome's own name for a web element ("checkBox", "button", ...), or "". */
    private static String webRole(AccessibilityNodeInfo n) {
        try {
            CharSequence r = n.getExtras().getCharSequence("AccessibilityNodeInfo.chromeRole");
            if (r != null && r.length() > 0) return r.toString();
            CharSequence d = n.getExtras().getCharSequence("AccessibilityNodeInfo.roleDescription");
            return d == null ? "" : d.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String text(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        if ((t == null || t.length() == 0) && Build.VERSION.SDK_INT >= 26) t = n.getHintText();
        return t == null ? "" : t.toString().replace('\n', ' ').trim();
    }

    /** The actions an element accepts, by name (focus-moving ones left out as noise). */
    private static List<String> actions(AccessibilityNodeInfo n) {
        List<String> out = new ArrayList<>();
        for (AccessibilityAction a : n.getActionList()) {
            String name = actionName(a);
            if (name != null && !out.contains(name)) out.add(name);
        }
        return out;
    }

    private static String actionName(AccessibilityAction a) {
        int id = a.getId();
        if (id == AccessibilityNodeInfo.ACTION_CLICK) return "click";
        if (id == AccessibilityNodeInfo.ACTION_LONG_CLICK) return "long-click";
        if (id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return "scroll-forward";
        if (id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return "scroll-backward";
        if (id == AccessibilityNodeInfo.ACTION_SET_TEXT) return "set-text";
        if (id == AccessibilityNodeInfo.ACTION_EXPAND) return "expand";
        if (id == AccessibilityNodeInfo.ACTION_COLLAPSE) return "collapse";
        if (id == AccessibilityNodeInfo.ACTION_SELECT) return "select";
        if (id == AccessibilityNodeInfo.ACTION_FOCUS) return "focus";
        if (id == AccessibilityNodeInfo.ACTION_SET_SELECTION) return "set-selection";
        if (id == AccessibilityNodeInfo.ACTION_COPY) return "copy";
        if (id == AccessibilityNodeInfo.ACTION_PASTE) return "paste";
        if (id == AccessibilityNodeInfo.ACTION_CUT) return "cut";
        if (id == AccessibilityNodeInfo.ACTION_DISMISS) return "dismiss";
        if (id == AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId()) return "show-on-screen";
        if (id == AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId()) return "scroll-to-position";
        if (id == AccessibilityAction.ACTION_SCROLL_UP.getId()) return "scroll-up";
        if (id == AccessibilityAction.ACTION_SCROLL_DOWN.getId()) return "scroll-down";
        if (id == AccessibilityAction.ACTION_SCROLL_LEFT.getId()) return "scroll-left";
        if (id == AccessibilityAction.ACTION_SCROLL_RIGHT.getId()) return "scroll-right";
        if (id == AccessibilityAction.ACTION_CONTEXT_CLICK.getId()) return "context-click";
        if (id == AccessibilityAction.ACTION_SET_PROGRESS.getId()) return "set-progress";
        // Moving the accessibility focus around is available everywhere: not worth listing.
        if (id == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                || id == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                || id == AccessibilityNodeInfo.ACTION_CLEAR_FOCUS
                || id == AccessibilityNodeInfo.ACTION_CLEAR_SELECTION
                || id == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                || id == AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
                || id == AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT
                || id == AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT) return null;
        CharSequence label = a.getLabel();
        return label != null && label.length() > 0 ? "\"" + label + "\"" : "action-" + id;
    }

    /** One line for an element: type, role, text, state, flags, actions, where. */
    private static String describe(AccessibilityNodeInfo n, String cls, String role, String text,
                                   List<String> actions, Rect r) {
        StringBuilder sb = new StringBuilder();
        sb.append(cls.isEmpty() ? "?" : cls.substring(cls.lastIndexOf('.') + 1));
        if (!role.isEmpty()) sb.append(" role=").append(role);
        if (!text.isEmpty()) sb.append(" \"").append(cut(text, 40)).append('"');
        CharSequence state = Build.VERSION.SDK_INT >= 30 ? n.getStateDescription() : null;
        if (state != null && state.length() > 0) sb.append(" state=").append(cut(state.toString(), 20));
        String id = n.getViewIdResourceName();
        if (id != null && !id.isEmpty()) sb.append(" id=").append(id.substring(id.indexOf('/') + 1));
        if (n.isClickable()) sb.append(" [clk]");
        if (n.isLongClickable()) sb.append(" [long]");
        if (n.isCheckable()) sb.append(n.isChecked() ? " [chk ☑]" : " [chk ☐]");
        if (n.isEditable()) sb.append(" [edit]");
        if (n.isScrollable()) sb.append(" [scroll]");
        if (n.isPassword()) sb.append(" [password]");
        if (n.isSelected()) sb.append(" [selected]");
        if (!n.isEnabled()) sb.append(" [off]");
        if (!n.isVisibleToUser()) sb.append(" [hidden]");
        if (!actions.isEmpty()) sb.append(" {").append(String.join(", ", actions)).append('}');
        sb.append(" @").append(r.left).append(',').append(r.top).append(' ')
                .append(r.width()).append('x').append(r.height());
        return sb.toString();
    }

    private static StringBuilder indent(StringBuilder sb, int depth) {
        for (int i = 0; i < Math.min(depth, 30); i++) sb.append("  ");
        return sb;
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String windowType(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "App window";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "Keyboard";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "System window";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "Accessibility overlay";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "Split-screen divider";
            default: return "Window type " + type;
        }
    }
}
