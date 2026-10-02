package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The deepest look: the app's own details (from the phone's package list) and every
 * accessibility window and element with EVERY property the phone gives - flags, all actions
 * with their ids, the extra data a web page attaches (HTML tag, link address, hint ...),
 * list and range info, labels, traversal order, drawing order, unique ids. Also a recorder of
 * the accessibility events an app sends while you use it. Reads only.
 */
final class RawScan {

    private RawScan() {
    }

    private static final int MAX_NODES = 3000;

    // ---- the app's own details -------------------------------------------------------------

    static String appInfo(Context c, String pkg, String currentScreen) {
        StringBuilder sb = new StringBuilder();
        sb.append("APP\n");
        PackageManager pm = c.getPackageManager();
        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS | PackageManager.GET_META_DATA;
        PackageInfo p;
        try {
            p = pm.getPackageInfo(pkg, flags);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return sb.append("  ").append(pkg).append(" - details not available (").append(e.getClass().getSimpleName())
                    .append(")\n").toString();
        }
        ApplicationInfo ai = p.applicationInfo;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
        sb.append("  Name: ").append(ai == null ? "?" : pm.getApplicationLabel(ai)).append('\n');
        sb.append("  Package: ").append(pkg).append('\n');
        sb.append("  Version: ").append(p.versionName).append(" (").append(Build.VERSION.SDK_INT >= 28
                ? p.getLongVersionCode() : p.versionCode).append(")\n");
        if (ai != null) {
            sb.append("  Target / min Android API: ").append(ai.targetSdkVersion)
                    .append(Build.VERSION.SDK_INT >= 24 ? " / " + ai.minSdkVersion : "").append('\n');
            sb.append("  Debuggable: ").append((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0 ? "yes" : "no")
                    .append(" · System app: ").append((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? "yes" : "no").append('\n');
        }
        sb.append("  Installed: ").append(f.format(new Date(p.firstInstallTime)))
                .append(" · Updated: ").append(f.format(new Date(p.lastUpdateTime))).append('\n');
        if (currentScreen != null) sb.append("  Screen open now: ").append(currentScreen).append('\n');
        sb.append("  Built with: ").append(framework(p)).append('\n');
        if (p.activities != null) {
            sb.append("  Screens (activities): ").append(p.activities.length).append('\n');
            for (ActivityInfo a : p.activities) sb.append("    ").append(shortName(a.name, pkg)).append(a.exported ? " [open to other apps]" : "").append('\n');
        }
        if (p.services != null) {
            sb.append("  Services: ").append(p.services.length).append('\n');
            for (ServiceInfo s : p.services) sb.append("    ").append(shortName(s.name, pkg)).append('\n');
        }
        if (p.receivers != null) sb.append("  Receivers: ").append(p.receivers.length).append('\n');
        if (p.providers != null) sb.append("  Providers: ").append(p.providers.length).append('\n');
        if (p.requestedPermissions != null) {
            sb.append("  Permissions asked: ").append(p.requestedPermissions.length).append('\n');
            for (int i = 0; i < p.requestedPermissions.length; i++) {
                boolean granted = p.requestedPermissionsFlags != null
                        && (p.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                sb.append("    ").append(granted ? "✓ " : "  ").append(p.requestedPermissions[i].replace("android.permission.", "")).append('\n');
            }
        }
        return sb.toString();
    }

    /** What the app is built with, from the names of its parts. */
    static String framework(PackageInfo p) {
        StringBuilder names = new StringBuilder();
        if (p.activities != null) for (ActivityInfo a : p.activities) names.append(a.name).append(' ');
        if (p.services != null) for (ServiceInfo s : p.services) names.append(s.name).append(' ');
        if (p.applicationInfo != null && p.applicationInfo.metaData != null) {
            for (String k : p.applicationInfo.metaData.keySet()) names.append(k).append(' ');
        }
        String n = names.toString().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (n.contains("com.facebook.react") || n.contains("expo.") || n.contains("reactnative")
                || n.contains("react_native") || n.contains("com.swmansion") || n.contains("com.proyecto26")) out.add("React Native");
        if (n.contains("io.flutter")) out.add("Flutter");
        if (n.contains("capacitor") || n.contains("cordova")) out.add("Capacitor/Cordova (web app)");
        if (n.contains("xamarin") || n.contains("mono.")) out.add("Xamarin");
        if (n.contains("firebase")) out.add("Firebase");
        if (n.contains("google.android.gms")) out.add("Google Play services");
        return out.isEmpty() ? "plain Android (nothing special found)" : String.join(", ", out);
    }

    private static String shortName(String name, String pkg) {
        return name.startsWith(pkg) ? name.substring(pkg.length()) : name;
    }

    // ---- every window and element, every property ------------------------------------------

    static String rawTree(AccessibilityService service) {
        StringBuilder sb = new StringBuilder();
        String own = service.getPackageName();
        List<AccessibilityWindowInfo> windows;
        try {
            windows = service.getWindows();
        } catch (RuntimeException e) {
            windows = new ArrayList<>();
        }
        int total = 0;
        sb.append("WINDOWS (").append(windows.size()).append(")\n");
        for (AccessibilityWindowInfo w : windows) {
            Rect r = new Rect();
            w.getBoundsInScreen(r);
            sb.append("  #").append(w.getId()).append(' ').append(windowType(w.getType()))
                    .append(" layer=").append(w.getLayer())
                    .append(w.isActive() ? " [active]" : "").append(w.isFocused() ? " [focused]" : "")
                    .append(w.isAccessibilityFocused() ? " [a11y-focus]" : "");
            if (Build.VERSION.SDK_INT >= 24 && w.getTitle() != null) sb.append(" \"").append(w.getTitle()).append('"');
            if (Build.VERSION.SDK_INT >= 26 && w.isInPictureInPictureMode()) sb.append(" [picture-in-picture]");
            if (Build.VERSION.SDK_INT >= 30) sb.append(" display=").append(w.getDisplayId());
            sb.append(" @").append(r.toShortString()).append('\n');
        }
        for (AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            if (own.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
            sb.append("\nWINDOW #").append(w.getId()).append(' ').append(windowType(w.getType()))
                    .append(" - ").append(root.getPackageName()).append('\n');
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            List<String> paths = new ArrayList<>();
            stack.add(root);
            paths.add("0");
            while (!stack.isEmpty() && total < MAX_NODES) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                String path = paths.remove(paths.size() - 1);
                if (n == null) continue;
                total++;
                node(sb, n, path);
                for (int i = n.getChildCount() - 1; i >= 0; i--) {
                    stack.add(n.getChild(i));
                    paths.add(path + "." + i);
                }
            }
        }
        if (total >= MAX_NODES) sb.append("\n(cut short at ").append(MAX_NODES).append(" elements)\n");
        return "RAW ACCESSIBILITY TREE - every property (" + total + " elements)\n" + sb;
    }

    /** One element: its path (0.2.1 = root's 3rd child's 2nd child) and every property. */
    private static void node(StringBuilder sb, AccessibilityNodeInfo n, String path) {
        StringBuilder padding = new StringBuilder();
        for (int i = Math.min(path.split("\\.").length - 1, 25); i > 0; i--) padding.append("  ");
        String pad = padding.toString();
        Rect screen = new Rect(), parent = new Rect();
        n.getBoundsInScreen(screen);
        n.getBoundsInParent(parent);
        sb.append(pad).append("• [").append(path).append("] ").append(n.getClassName());
        String id = n.getViewIdResourceName();
        if (id != null) sb.append(" id=").append(id);
        if (Build.VERSION.SDK_INT >= 33 && n.getUniqueId() != null) sb.append(" uid=").append(n.getUniqueId());
        sb.append('\n');
        String in = pad + "    ";
        prop(sb, in, "text", n.getText());
        prop(sb, in, "description", n.getContentDescription());
        if (Build.VERSION.SDK_INT >= 26) prop(sb, in, "hint", n.getHintText());
        prop(sb, in, "error", n.getError());
        if (Build.VERSION.SDK_INT >= 30) prop(sb, in, "state", n.getStateDescription());
        if (Build.VERSION.SDK_INT >= 28) {
            prop(sb, in, "tooltip", n.getTooltipText());
            prop(sb, in, "pane title", n.getPaneTitle());
        }
        if (Build.VERSION.SDK_INT >= 34) prop(sb, in, "container title", n.getContainerTitle());
        sb.append(in).append("where: screen ").append(screen.toShortString()).append(" · in parent ")
                .append(parent.toShortString()).append(" · window #").append(n.getWindowId());
        if (Build.VERSION.SDK_INT >= 24) sb.append(" · drawing order ").append(n.getDrawingOrder());
        sb.append(" · children ").append(n.getChildCount()).append('\n');

        List<String> fl = new ArrayList<>();
        flag(fl, n.isVisibleToUser(), "visible", "NOT visible");
        flag(fl, n.isEnabled(), "enabled", "DISABLED");
        if (n.isClickable()) fl.add("clickable");
        if (n.isLongClickable()) fl.add("long-clickable");
        if (n.isContextClickable()) fl.add("context-clickable");
        if (n.isCheckable()) fl.add(n.isChecked() ? "checkable ☑" : "checkable ☐");
        if (n.isEditable()) fl.add("editable");
        if (n.isFocusable()) fl.add("focusable");
        if (n.isFocused()) fl.add("FOCUSED");
        if (n.isAccessibilityFocused()) fl.add("a11y-focused");
        if (n.isScrollable()) fl.add("scrollable");
        if (n.isSelected()) fl.add("selected");
        if (n.isPassword()) fl.add("password");
        if (n.isMultiLine()) fl.add("multi-line");
        if (n.isDismissable()) fl.add("dismissable");
        if (Build.VERSION.SDK_INT >= 24 && n.isImportantForAccessibility()) fl.add("important");
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText()) fl.add("showing-hint");
        if (Build.VERSION.SDK_INT >= 28) {
            if (n.isHeading()) fl.add("heading");
            if (n.isScreenReaderFocusable()) fl.add("screen-reader-focusable");
        }
        if (Build.VERSION.SDK_INT >= 29 && n.isTextEntryKey()) fl.add("keyboard key");
        if (Build.VERSION.SDK_INT >= 33 && n.isTextSelectable()) fl.add("text-selectable");
        if (n.getLiveRegion() != 0) fl.add("live region " + (n.getLiveRegion() == 1 ? "polite" : "assertive"));
        sb.append(in).append("flags: ").append(String.join(", ", fl)).append('\n');

        if (n.getInputType() != 0) sb.append(in).append("input type: ").append(n.getInputType()).append('\n');
        if (n.getMaxTextLength() > 0) sb.append(in).append("max length: ").append(n.getMaxTextLength()).append('\n');
        if (n.getTextSelectionStart() >= 0) {
            sb.append(in).append("text selection: ").append(n.getTextSelectionStart()).append("..")
                    .append(n.getTextSelectionEnd()).append('\n');
        }
        AccessibilityNodeInfo.CollectionInfo ci = n.getCollectionInfo();
        if (ci != null) {
            sb.append(in).append("list: ").append(ci.getRowCount()).append(" rows x ").append(ci.getColumnCount())
                    .append(" cols").append(ci.isHierarchical() ? ", tree" : "").append('\n');
        }
        AccessibilityNodeInfo.CollectionItemInfo ii = n.getCollectionItemInfo();
        if (ii != null) {
            sb.append(in).append("list item: row ").append(ii.getRowIndex()).append(" col ").append(ii.getColumnIndex())
                    .append(ii.isHeading() ? ", header" : "").append(ii.isSelected() ? ", selected" : "").append('\n');
        }
        AccessibilityNodeInfo.RangeInfo ri = n.getRangeInfo();
        if (ri != null) {
            sb.append(in).append("range: ").append(ri.getMin()).append("..").append(ri.getMax())
                    .append(" now ").append(ri.getCurrent()).append('\n');
        }
        related(sb, in, "labelled by", n.getLabeledBy());
        related(sb, in, "label for", n.getLabelFor());
        if (Build.VERSION.SDK_INT >= 22) {
            related(sb, in, "read before", n.getTraversalBefore());
            related(sb, in, "read after", n.getTraversalAfter());
        }

        List<String> acts = new ArrayList<>();
        for (AccessibilityAction a : n.getActionList()) {
            String name = actionName(a.getId());
            acts.add(name + (a.getLabel() != null ? " \"" + a.getLabel() + "\"" : "") + " (" + a.getId() + ")");
        }
        if (!acts.isEmpty()) sb.append(in).append("actions: ").append(String.join(", ", acts)).append('\n');
        if (Build.VERSION.SDK_INT >= 26) {
            List<String> extraData = n.getAvailableExtraData();
            if (extraData != null && !extraData.isEmpty()) sb.append(in).append("extra data on request: ")
                    .append(String.join(", ", extraData)).append('\n');
        }
        int granularity = n.getMovementGranularities();
        if (granularity != 0) sb.append(in).append("text movement: ").append(granularities(granularity)).append('\n');

        // Every extra the app (or the web page) attached: HTML tag, link, role, hint ...
        Bundle extras = n.getExtras();
        if (extras != null && !extras.isEmpty()) {
            Map<String, String> sorted = new TreeMap<>();
            for (String k : extras.keySet()) {
                Object v;
                try {
                    v = extras.get(k);
                } catch (RuntimeException e) {
                    v = "?";
                }
                sorted.put(k.replace("AccessibilityNodeInfo.", ""), cut(String.valueOf(v), 120));
            }
            sb.append(in).append("extras: ");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, String> e : sorted.entrySet()) parts.add(e.getKey() + "=" + e.getValue());
            sb.append(String.join(" · ", parts)).append('\n');
        }
    }

    private static void prop(StringBuilder sb, String in, String name, CharSequence v) {
        if (v == null || v.length() == 0) return;
        sb.append(in).append(name).append(": \"").append(cut(v.toString().replace('\n', ' '), 200)).append("\"\n");
    }

    private static void flag(List<String> fl, boolean on, String yes, String no) {
        fl.add(on ? yes : no);
    }

    private static void related(StringBuilder sb, String in, String what, AccessibilityNodeInfo other) {
        if (other == null) return;
        CharSequence t = other.getText();
        if (t == null || t.length() == 0) t = other.getContentDescription();
        Rect r = new Rect();
        other.getBoundsInScreen(r);
        sb.append(in).append(what).append(": ").append(other.getClassName())
                .append(t == null ? "" : " \"" + cut(t.toString(), 40) + "\"").append(" @").append(r.toShortString()).append('\n');
    }

    private static String granularities(int g) {
        List<String> out = new ArrayList<>();
        if ((g & AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER) != 0) out.add("character");
        if ((g & AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD) != 0) out.add("word");
        if ((g & AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE) != 0) out.add("line");
        if ((g & AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH) != 0) out.add("paragraph");
        if ((g & AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE) != 0) out.add("page");
        return String.join(", ", out);
    }

    private static String actionName(int id) {
        switch (id) {
            case AccessibilityNodeInfo.ACTION_FOCUS: return "focus";
            case AccessibilityNodeInfo.ACTION_CLEAR_FOCUS: return "clear-focus";
            case AccessibilityNodeInfo.ACTION_SELECT: return "select";
            case AccessibilityNodeInfo.ACTION_CLEAR_SELECTION: return "clear-selection";
            case AccessibilityNodeInfo.ACTION_CLICK: return "click";
            case AccessibilityNodeInfo.ACTION_LONG_CLICK: return "long-click";
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS: return "a11y-focus";
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS: return "clear-a11y-focus";
            case AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY: return "next-granularity";
            case AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY: return "previous-granularity";
            case AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT: return "next-html";
            case AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT: return "previous-html";
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD: return "scroll-forward";
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD: return "scroll-backward";
            case AccessibilityNodeInfo.ACTION_COPY: return "copy";
            case AccessibilityNodeInfo.ACTION_PASTE: return "paste";
            case AccessibilityNodeInfo.ACTION_CUT: return "cut";
            case AccessibilityNodeInfo.ACTION_SET_SELECTION: return "set-selection";
            case AccessibilityNodeInfo.ACTION_EXPAND: return "expand";
            case AccessibilityNodeInfo.ACTION_COLLAPSE: return "collapse";
            case AccessibilityNodeInfo.ACTION_DISMISS: return "dismiss";
            case AccessibilityNodeInfo.ACTION_SET_TEXT: return "set-text";
            default: break;
        }
        if (id == AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId()) return "show-on-screen";
        if (id == AccessibilityAction.ACTION_SCROLL_TO_POSITION.getId()) return "scroll-to-position";
        if (id == AccessibilityAction.ACTION_SCROLL_UP.getId()) return "scroll-up";
        if (id == AccessibilityAction.ACTION_SCROLL_DOWN.getId()) return "scroll-down";
        if (id == AccessibilityAction.ACTION_SCROLL_LEFT.getId()) return "scroll-left";
        if (id == AccessibilityAction.ACTION_SCROLL_RIGHT.getId()) return "scroll-right";
        if (id == AccessibilityAction.ACTION_CONTEXT_CLICK.getId()) return "context-click";
        if (id == AccessibilityAction.ACTION_SET_PROGRESS.getId()) return "set-progress";
        if (Build.VERSION.SDK_INT >= 26 && id == AccessibilityAction.ACTION_MOVE_WINDOW.getId()) return "move-window";
        if (Build.VERSION.SDK_INT >= 28) {
            if (id == AccessibilityAction.ACTION_SHOW_TOOLTIP.getId()) return "show-tooltip";
            if (id == AccessibilityAction.ACTION_HIDE_TOOLTIP.getId()) return "hide-tooltip";
        }
        if (Build.VERSION.SDK_INT >= 30) {
            if (id == AccessibilityAction.ACTION_PRESS_AND_HOLD.getId()) return "press-and-hold";
            if (id == AccessibilityAction.ACTION_IME_ENTER.getId()) return "ime-enter";
        }
        return "custom";
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

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ---- the events an app sends -------------------------------------------------------------

    /** Records the accessibility events of other apps while on. */
    static final class Recorder {
        private final List<String> lines = new ArrayList<>();
        private final Map<String, Integer> counts = new TreeMap<>();
        private long start;
        boolean on;

        void start() {
            lines.clear();
            counts.clear();
            start = android.os.SystemClock.uptimeMillis();
            on = true;
        }

        void add(AccessibilityEvent e) {
            if (!on) return;
            String type = AccessibilityEvent.eventTypeToString(e.getEventType()).replace("TYPE_", "");
            counts.merge(type, 1, Integer::sum);
            if (lines.size() >= 400) return;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "%6d ms  ", android.os.SystemClock.uptimeMillis() - start)).append(type);
            if (e.getClassName() != null) {
                String c = e.getClassName().toString();
                sb.append(" ").append(c.substring(c.lastIndexOf('.') + 1));
            }
            if (e.getContentChangeTypes() != 0) sb.append(" changes=").append(e.getContentChangeTypes());
            if (Build.VERSION.SDK_INT >= 28 && e.getWindowChanges() != 0) sb.append(" window-changes=").append(e.getWindowChanges());
            if (!e.getText().isEmpty()) sb.append(" \"").append(cut(String.valueOf(e.getText()), 80)).append('"');
            if (e.getContentDescription() != null) sb.append(" desc=\"").append(cut(e.getContentDescription().toString(), 40)).append('"');
            AccessibilityNodeInfo src = e.getSource();
            if (src != null) {
                Rect r = new Rect();
                src.getBoundsInScreen(r);
                sb.append(" @").append(r.toShortString());
            }
            lines.add(sb.toString());
        }

        String stop(String pkg) {
            on = false;
            StringBuilder sb = new StringBuilder();
            sb.append("A11y Inspector - events recorded (").append(pkg).append(")\n")
                    .append("================================================\n");
            int total = 0;
            for (int c : counts.values()) total += c;
            sb.append(total).append(" events in ").append((android.os.SystemClock.uptimeMillis() - start) / 1000).append(" s\n");
            for (Map.Entry<String, Integer> e : counts.entrySet()) sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            sb.append("\nEVENTS (first 400)\n");
            for (String l : lines) sb.append(l).append('\n');
            return sb.toString();
        }

        String summary() {
            StringBuilder sb = new StringBuilder("🎙 Events recorded:\n");
            for (Map.Entry<String, Integer> e : counts.entrySet()) sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            return counts.isEmpty() ? "🎙 No events from the app while recording.\n" : sb.toString();
        }
    }
}
