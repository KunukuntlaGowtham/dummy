package com.kunukuntla.engine;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.kunukuntla.engineapi.SecureEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The real fast-tick logic, delivered from the server and loaded at runtime. Finds every empty
 * checkbox on the page and clicks it; with "burst" it clicks them all straight after the other,
 * else it waits for each to read ☑ before the next. Returns a short result.
 */
public final class EngineImpl implements SecureEngine {

    @Override
    public String run(AccessibilityService service, String arg, Log log) {
        boolean burst = arg != null && arg.contains("burst");
        long start = SystemClock.uptimeMillis();
        List<AccessibilityNodeInfo> boxes = checkboxes(service);
        int total = 0, ticked = 0;
        for (AccessibilityNodeInfo n : boxes) if (!n.isChecked() && n.isEnabled()) total++;
        log.line("engine: " + boxes.size() + " checkbox(es), " + total + " to tick" + (burst ? " (burst)" : ""));
        for (int i = 0; i < boxes.size(); i++) {
            AccessibilityNodeInfo n = boxes.get(i);
            if (n.isChecked() || !n.isEnabled()) continue;
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!burst) {
                long until = SystemClock.uptimeMillis() + 500;
                while (SystemClock.uptimeMillis() < until) {
                    AccessibilityNodeInfo now = boxAt(service, i);
                    if (now != null && now.isChecked()) break;
                    SystemClock.sleep(15);
                }
            }
            AccessibilityNodeInfo now = boxAt(service, i);
            if (now != null && now.isChecked()) {
                ticked++;
                log.line("row " + (i + 1) + ": ticked");
            }
        }
        return String.format(Locale.ROOT, "engine ticked %d/%d in %d ms", ticked, total,
                SystemClock.uptimeMillis() - start);
    }

    private static List<AccessibilityNodeInfo> checkboxes(AccessibilityService service) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : service.getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                        || w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null) walk(root, out);
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private static void walk(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null || out.size() > 6000) return;
        if (isCheckbox(n)) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out);
    }

    private static boolean isCheckbox(AccessibilityNodeInfo n) {
        String cls = String.valueOf(n.getClassName());
        return cls.endsWith("CheckBox") || n.isCheckable();
    }

    private static AccessibilityNodeInfo boxAt(AccessibilityService service, int idx) {
        List<AccessibilityNodeInfo> b = checkboxes(service);
        return idx < b.size() ? b.get(idx) : null;
    }
}
