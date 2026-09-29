package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * A read-only accessibility inspector.
 *
 * When another app is in the foreground this service reads the accessibility
 * information the system exposes and builds a plain-text report: every window
 * on screen (dialogs, keyboard, status bar, split-screen), and within each
 * window every node - its text/description/id/bounds/state and the accessibility
 * actions the node advertises, plus richer detail (hint, error, input type,
 * range, grid position, tooltip, live region, drawing order, heading).
 *
 * It NEVER performs any of those actions and never touches another app - it
 * only reads and reports what apps expose to the accessibility framework, so
 * you can see what an automation tool would be able to read and control.
 *
 * The report for the most recent non-self screen is held in {@link Latest};
 * MainActivity reads it when you return to this app and tap Refresh. The node
 * budget is configurable from the app (see {@link Limits}); depth is bounded
 * only as a guard against pathological/looping trees.
 */
public class InspectorService extends AccessibilityService {

    /** Safety guard against a looping tree - not the user-facing limit. */
    private static final int MAX_DEPTH = 400;
    /** Content-changed events can fire many times a second; coalesce them. */
    private static final long MIN_INTERVAL_MS = 400;

    private long lastRun;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRun < MIN_INTERVAL_MS) {
            return;
        }
        lastRun = now;

        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active == null) {
            return;
        }
        CharSequence fgPkg = active.getPackageName();
        active.recycle();
        // Only report on other apps, never our own screens.
        if (fgPkg == null || getPackageName().contentEquals(fgPkg)) {
            return;
        }

        int maxNodes = Limits.maxNodes(this);
        int[] count = {0};
        StringBuilder sb = new StringBuilder();
        sb.append("Foreground app: ").append(fgPkg).append('\n');
        sb.append("Node budget: ").append(maxNodes).append('\n');

        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null && !windows.isEmpty()) {
            sb.append("Windows on screen: ").append(windows.size()).append('\n');
            for (int i = 0; i < windows.size(); i++) {
                AccessibilityWindowInfo w = windows.get(i);
                if (w == null) {
                    continue;
                }
                describeWindow(w, i, sb);
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null) {
                    walk(root, 1, sb, count, maxNodes);
                    root.recycle();
                }
                w.recycle();
                if (count[0] >= maxNodes) {
                    break;
                }
            }
        } else {
            // Fallback: some devices return no window list to a11y services.
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                sb.append("\n== Active window ==\n");
                walk(root, 1, sb, count, maxNodes);
                root.recycle();
            }
        }

        sb.append("\nTotal elements reported: ").append(count[0]);
        if (count[0] >= maxNodes) {
            sb.append(" (node budget reached; raise it in the app to go deeper)");
        }
        Latest.set(fgPkg.toString(), sb.toString());
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt: this service only reads.
    }

    private void describeWindow(AccessibilityWindowInfo w, int index, StringBuilder sb) {
        sb.append("\n== Window ").append(index)
          .append(" [").append(windowType(w.getType())).append(']');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            CharSequence title = w.getTitle();
            if (!TextUtils.isEmpty(title)) {
                sb.append(" \"").append(oneLine(title)).append('"');
            }
        }
        sb.append(" layer=").append(w.getLayer());
        if (w.isActive()) {
            sb.append(" active");
        }
        if (w.isFocused()) {
            sb.append(" focused");
        }
        Rect b = new Rect();
        w.getBoundsInScreen(b);
        sb.append(" [").append(b.left).append(',').append(b.top)
          .append('-').append(b.right).append(',').append(b.bottom).append("] ==\n");
    }

    private void walk(AccessibilityNodeInfo node, int depth, StringBuilder sb,
                      int[] count, int maxNodes) {
        if (node == null || count[0] >= maxNodes || depth > MAX_DEPTH) {
            return;
        }
        count[0]++;
        describe(node, depth, sb);

        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                walk(child, depth + 1, sb, count, maxNodes);
                child.recycle();
            }
        }
    }

    private void describe(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(simpleName(n.getClassName()));

        appendQuoted(sb, "", n.getText());
        appendQuoted(sb, "desc=", n.getContentDescription());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appendQuoted(sb, "hint=", n.getHintText());
        }
        appendQuoted(sb, "error=", n.getError());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appendQuoted(sb, "tooltip=", n.getTooltipText());
        }
        String id = n.getViewIdResourceName();
        if (!TextUtils.isEmpty(id)) {
            sb.append(" id=").append(id);
        }

        Rect b = new Rect();
        n.getBoundsInScreen(b);
        sb.append(" [").append(b.left).append(',').append(b.top)
          .append('-').append(b.right).append(',').append(b.bottom).append(']');

        // State flags: what the element says about itself.
        StringBuilder flags = new StringBuilder();
        add(flags, n.isClickable(), "clickable");
        add(flags, n.isLongClickable(), "long-clickable");
        add(flags, n.isScrollable(), "scrollable");
        add(flags, n.isEditable(), "editable");
        add(flags, n.isCheckable(), "checkable");
        add(flags, n.isChecked(), "checked");
        add(flags, n.isSelected(), "selected");
        add(flags, n.isFocusable(), "focusable");
        add(flags, n.isFocused(), "focused");
        add(flags, !n.isEnabled(), "disabled");
        add(flags, n.isPassword(), "password");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && n.isHeading()) {
            add(flags, true, "heading");
        }
        if (flags.length() > 0) {
            sb.append(" {").append(flags).append('}');
        }

        appendExtras(n, sb);

        // The actions the node advertises. Reported only - never performed.
        String actions = actionNames(n);
        if (!actions.isEmpty()) {
            sb.append(" actions=[").append(actions).append(']');
        }
        sb.append('\n');
    }

    /** Input type, range/progress, grid position, live region, drawing order. */
    private void appendExtras(AccessibilityNodeInfo n, StringBuilder sb) {
        int it = n.getInputType();
        if (it != 0) {
            sb.append(" input=").append(inputType(it));
        }

        AccessibilityNodeInfo.RangeInfo ri = n.getRangeInfo();
        if (ri != null) {
            sb.append(" range(").append(ri.getMin()).append("..").append(ri.getMax())
              .append("=").append(ri.getCurrent()).append(')');
        }

        AccessibilityNodeInfo.CollectionInfo ci = n.getCollectionInfo();
        if (ci != null) {
            sb.append(" grid(").append(ci.getRowCount()).append('x')
              .append(ci.getColumnCount()).append(')');
        }
        AccessibilityNodeInfo.CollectionItemInfo cii = n.getCollectionItemInfo();
        if (cii != null) {
            sb.append(" cell(r").append(cii.getRowIndex())
              .append(",c").append(cii.getColumnIndex()).append(')');
        }

        int live = n.getLiveRegion();
        if (live == View.ACCESSIBILITY_LIVE_REGION_POLITE) {
            sb.append(" live=polite");
        } else if (live == View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE) {
            sb.append(" live=assertive");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sb.append(" draw=").append(n.getDrawingOrder());
        }
    }

    private static String inputType(int it) {
        StringBuilder s = new StringBuilder();
        switch (it & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_TEXT: s.append("text"); break;
            case InputType.TYPE_CLASS_NUMBER: s.append("number"); break;
            case InputType.TYPE_CLASS_PHONE: s.append("phone"); break;
            case InputType.TYPE_CLASS_DATETIME: s.append("datetime"); break;
            default: s.append("0x").append(Integer.toHexString(it)); return s.toString();
        }
        int var = it & InputType.TYPE_MASK_VARIATION;
        if (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || var == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            s.append("/password");
        } else if (var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) {
            s.append("/email");
        } else if (var == InputType.TYPE_TEXT_VARIATION_URI) {
            s.append("/uri");
        }
        return s.toString();
    }

    private static String actionNames(AccessibilityNodeInfo n) {
        List<AccessibilityNodeInfo.AccessibilityAction> list = n.getActionList();
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AccessibilityNodeInfo.AccessibilityAction a : list) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            CharSequence label = a.getLabel();
            if (!TextUtils.isEmpty(label)) {
                sb.append(oneLine(label));
            } else {
                sb.append(actionName(a.getId()));
            }
        }
        return sb.toString();
    }

    private static String actionName(int id) {
        switch (id) {
            case AccessibilityNodeInfo.ACTION_CLICK: return "CLICK";
            case AccessibilityNodeInfo.ACTION_LONG_CLICK: return "LONG_CLICK";
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD: return "SCROLL_FORWARD";
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD: return "SCROLL_BACKWARD";
            case AccessibilityNodeInfo.ACTION_FOCUS: return "FOCUS";
            case AccessibilityNodeInfo.ACTION_CLEAR_FOCUS: return "CLEAR_FOCUS";
            case AccessibilityNodeInfo.ACTION_SET_TEXT: return "SET_TEXT";
            case AccessibilityNodeInfo.ACTION_EXPAND: return "EXPAND";
            case AccessibilityNodeInfo.ACTION_COLLAPSE: return "COLLAPSE";
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS: return "A11Y_FOCUS";
            default: return "0x" + Integer.toHexString(id);
        }
    }

    private static String windowType(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "application";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "keyboard";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "system";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "a11y-overlay";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "split-divider";
            default: return "type" + type;
        }
    }

    private static void appendQuoted(StringBuilder sb, String prefix, CharSequence value) {
        if (!TextUtils.isEmpty(value)) {
            sb.append(' ').append(prefix).append('"').append(oneLine(value)).append('"');
        }
    }

    private static void add(StringBuilder sb, boolean on, String name) {
        if (on) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(name);
        }
    }

    private static String simpleName(CharSequence cls) {
        if (cls == null) {
            return "View";
        }
        String s = cls.toString();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private static String oneLine(CharSequence cs) {
        String s = cs.toString().replace('\n', ' ').replace('\r', ' ');
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
