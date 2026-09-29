package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * A read-only accessibility inspector.
 *
 * When another app is in the foreground this service walks the accessibility
 * tree that the app publishes for the current screen and builds a plain-text
 * report: every node, its text/description/id/bounds/state, and the list of
 * accessibility actions the node advertises. It NEVER performs any of those
 * actions and never touches another app - it only reads and reports what the
 * app itself exposes to the accessibility framework, so you can see what an
 * automation tool would be able to read and control there.
 *
 * The report for the most recent non-self screen is held in {@link Latest};
 * MainActivity reads it when you return to this app and tap Refresh.
 */
public class InspectorService extends AccessibilityService {

    /** Guard so a huge or looping tree can never produce an unbounded report. */
    private static final int MAX_NODES = 1500;
    private static final int MAX_DEPTH = 60;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        CharSequence pkg = root.getPackageName();
        // Skip our own screens - we only report on other apps.
        if (pkg == null || getPackageName().contentEquals(pkg)) {
            root.recycle();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("App package: ").append(pkg).append('\n');
        int[] count = {0};
        walk(root, 0, sb, count);
        sb.append("\nTotal elements reported: ").append(count[0]);
        if (count[0] >= MAX_NODES) {
            sb.append(" (truncated at ").append(MAX_NODES).append(')');
        }

        Latest.set(pkg.toString(), sb.toString());
        root.recycle();
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt: this service only reads.
    }

    private void walk(AccessibilityNodeInfo node, int depth, StringBuilder sb, int[] count) {
        if (node == null || count[0] >= MAX_NODES || depth > MAX_DEPTH) {
            return;
        }
        count[0]++;
        describe(node, depth, sb);

        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                walk(child, depth + 1, sb, count);
                child.recycle();
            }
        }
    }

    private void describe(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(simpleName(n.getClassName()));

        CharSequence text = n.getText();
        if (!TextUtils.isEmpty(text)) {
            sb.append(" \"").append(oneLine(text)).append('"');
        }
        CharSequence desc = n.getContentDescription();
        if (!TextUtils.isEmpty(desc)) {
            sb.append(" desc=\"").append(oneLine(desc)).append('"');
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
        add(flags, n.isFocusable(), "focusable");
        add(flags, !n.isEnabled(), "disabled");
        add(flags, n.isPassword(), "password");
        if (flags.length() > 0) {
            sb.append(" {").append(flags).append('}');
        }

        // The actions the node advertises. Reported only - never performed.
        String actions = actionNames(n);
        if (!actions.isEmpty()) {
            sb.append(" actions=[").append(actions).append(']');
        }
        sb.append('\n');
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
