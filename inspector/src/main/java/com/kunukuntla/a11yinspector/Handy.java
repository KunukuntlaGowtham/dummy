package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The two handy buttons, one step at a time instead of a whole run:
 * <ul>
 *   <li>☑ Next - ticks the next unticked checkbox, once (a pop-up then comes);</li>
 *   <li>🧹 OK - clears the pop-up (the existing Clear: its OK, or the purple button found on the screen),
 *       then points at the next checkbox to tick: it is brought on screen and ringed.</li>
 * </ul>
 * A row the page refused (it was ticked and read unticked after its pop-up) is not offered again
 * until you long-press ☑ Next.
 */
final class Handy {

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View marker;
    /** The boxes (by their order on the page) the page refused. */
    private final Set<Integer> refused = new HashSet<>();
    /** The box just ticked by ☑ Next (-1: none): read at the next step - unticked then means refused. */
    private int pending = -1;

    Handy(AccessibilityService service, WindowManager windowManager) {
        this.service = service;
        this.windowManager = windowManager;
    }

    private List<AccessibilityNodeInfo> boxes() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : Page.nodes(service)) if (Page.isCheckbox(n)) out.add(n);
        return out;
    }

    /** The row just ticked, read now: unticked means the page refused it. */
    private void settlePending(List<AccessibilityNodeInfo> boxes) {
        if (pending >= 0 && pending < boxes.size() && !Page.isChecked(boxes.get(pending))) refused.add(pending);
        pending = -1;
    }

    private int next(List<AccessibilityNodeInfo> boxes) {
        for (int i = 0; i < boxes.size(); i++) {
            if (!Page.isChecked(boxes.get(i)) && !refused.contains(i)) return i;
        }
        return -1;
    }

    /** ☑ Next: the next unticked box is pressed once. Returns what to say. */
    String tick() {
        hideMarker();
        List<AccessibilityNodeInfo> boxes = boxes();
        if (boxes.isEmpty()) return "No checkboxes on this page";
        settlePending(boxes);
        int i = next(boxes);
        if (i < 0) {
            return refused.isEmpty() ? "Every box is ticked ✓"
                    : "Every other box is ticked ✓ - the page refused " + refused.size()
                    + " (long-press ☑ to try those again)";
        }
        AccessibilityNodeInfo box = boxes.get(i);
        boolean ok = box.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        AccessibilityNodeInfo p = box.getParent();
        for (int up = 0; !ok && up < 3 && p != null; up++, p = p.getParent()) {
            if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ok = true;
        }
        if (!ok) {
            Rect r = place(box);
            if (r != null) {
                tap(r.centerX(), r.centerY());
                ok = true;
            }
        }
        if (!ok) return "Couldn't press box " + (i + 1);
        pending = i;
        return "☑ Box " + (i + 1) + " of " + boxes.size() + " pressed - pop-up? tap 🧹";
    }

    /** 🧹 OK has cleared the pop-up: the next box is brought on screen and ringed. Returns what to say. */
    String point() {
        hideMarker();
        List<AccessibilityNodeInfo> boxes = boxes();
        if (boxes.isEmpty()) return "No checkboxes on this page";
        settlePending(boxes);
        int i = next(boxes);
        if (i < 0) {
            return refused.isEmpty() ? "Every box is ticked ✓"
                    : "Every other box is ticked ✓ - the page refused " + refused.size();
        }
        AccessibilityNodeInfo box = boxes.get(i);
        box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        handler.postDelayed(() -> {
            try {
                box.refresh();
            } catch (RuntimeException ignored) {
            }
            Rect r = place(box);
            if (r != null) showMarker(r);
        }, 300);
        return "👉 Next: box " + (i + 1) + " of " + boxes.size() + " - tap ☑";
    }

    /** Long press on ☑ Next: the rows the page refused are offered again. */
    void reset() {
        refused.clear();
        pending = -1;
        hideMarker();
    }

    void hideMarker() {
        if (marker == null) return;
        try {
            windowManager.removeView(marker);
        } catch (RuntimeException ignored) {
        }
        marker = null;
    }

    // ---- the ring ---------------------------------------------------------------------------

    private void showMarker(Rect r) {
        hideMarker();
        float d = service.getResources().getDisplayMetrics().density;
        int pad = Math.round(6 * d);
        View v = new View(service);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(12 * d);
        ring.setColor(0x33FFC107);
        ring.setStroke(Math.round(4 * d), 0xFFFFC107);
        v.setBackground(ring);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(r.width() + 2 * pad, r.height() + 2 * pad,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = r.left - pad;
        lp.y = r.top - pad;
        try {
            windowManager.addView(v, lp);
            marker = v;
            handler.postDelayed(() -> {
                if (marker == v) hideMarker();
            }, 20000);
        } catch (RuntimeException ignored) {
        }
    }

    /** Where the box is on screen: the box itself, or (hidden, 0 wide) the nearest parent with a real size. */
    private Rect place(AccessibilityNodeInfo box) {
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        long limit = (long) dm.widthPixels * dm.heightPixels / 8;
        AccessibilityNodeInfo p = box;
        for (int depth = 0; p != null && depth < 4; depth++, p = p.getParent()) {
            Rect r = new Rect();
            p.getBoundsInScreen(r);
            if (r.width() > 4 && r.height() > 4 && (long) r.width() * r.height() < limit
                    && r.top >= 0 && r.bottom <= dm.heightPixels) return r;
        }
        return null;
    }

    private void tap(int x, int y) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }
}
