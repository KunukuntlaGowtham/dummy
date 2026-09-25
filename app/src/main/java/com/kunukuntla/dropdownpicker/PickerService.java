package com.kunukuntla.dropdownpicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Accessibility service that shows a floating button. Tapping it finds the
 * top-most dropdown on screen, opens it and selects its first option.
 */
public class PickerService extends AccessibilityService {

    private static final long OPEN_WAIT_MS = 700;
    private static final int PICK_ATTEMPTS = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private TextView button;
    private WindowManager.LayoutParams buttonParams;
    private boolean busy;

    /** A clickable element on screen, used to spot what the dropdown adds when it opens. */
    private static final class Clickable {
        final AccessibilityNodeInfo node;
        final Rect bounds;
        final String key;

        Clickable(AccessibilityNodeInfo node, Rect bounds, String label) {
            this.node = node;
            this.bounds = bounds;
            this.key = bounds.toShortString() + "|" + label;
        }
    }

    @Override
    protected void onServiceConnected() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showButton();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (button != null) windowManager.removeView(button);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- Floating button ------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private void showButton() {
        button = new TextView(this);
        button.setText("▼1");
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setGravity(Gravity.CENTER);
        int size = dp(52);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xDD6A3FA0);
        button.setBackground(bg);
        button.setContentDescription("Select first dropdown option");

        buttonParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        buttonParams.gravity = Gravity.TOP | Gravity.END;
        buttonParams.x = dp(12);
        buttonParams.y = dp(160);

        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        button.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = buttonParams.x;
                        startY = buttonParams.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                        if (dragging) {
                            // Gravity is END, so x grows to the left.
                            buttonParams.x = startX - (int) dx;
                            buttonParams.y = startY + (int) dy;
                            windowManager.updateViewLayout(button, buttonParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging) run();
                        return true;
                    default:
                        return false;
                }
            }
        });
        windowManager.addView(button, buttonParams);
    }

    private void setButtonVisible(boolean visible) {
        if (button != null) button.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    // ---- Main flow -----------------------------------------------------------

    private void run() {
        if (busy) return;
        busy = true;
        // Hide the button so it doesn't show up in the screenshot or get tapped.
        setButtonVisible(false);
        handler.postDelayed(this::detect, 200);
    }

    private void finish(String message) {
        busy = false;
        setButtonVisible(true);
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void detect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            detectByNodes();
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                Bitmap bmp = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                buffer.close();
                if (bmp == null) {
                    detectByNodes();
                    return;
                }
                new Thread(() -> {
                    DropdownDetector.Hit hit = DropdownDetector.find(bmp);
                    bmp.recycle();
                    handler.post(() -> {
                        if (hit != null) {
                            openAndPick(null, hit.tapX, hit.tapY);
                        } else {
                            detectByNodes();
                        }
                    });
                }).start();
            }

            @Override
            public void onFailure(int errorCode) {
                detectByNodes();
            }
        });
    }

    /** Fallback: look for native dropdown widgets (Spinner, HTML select, combobox). */
    private void detectByNodes() {
        AccessibilityNodeInfo best = null;
        Rect bestBounds = null;
        for (AccessibilityNodeInfo root : roots()) {
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            stack.add(root);
            while (!stack.isEmpty()) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                if (n == null) continue;
                CharSequence cls = n.getClassName();
                String c = cls == null ? "" : cls.toString();
                if (n.isVisibleToUser() && (c.contains("Spinner") || c.contains("ComboBox")
                        || c.contains("AutoCompleteTextView"))) {
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    if (!r.isEmpty() && (bestBounds == null || r.top < bestBounds.top)) {
                        best = n;
                        bestBounds = r;
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) stack.add(n.getChild(i));
            }
        }
        if (best == null) {
            finish("No dropdown found on screen");
            return;
        }
        openAndPick(best, bestBounds.centerX(), bestBounds.centerY());
    }

    private void openAndPick(AccessibilityNodeInfo node, int x, int y) {
        Set<String> before = new HashSet<>();
        for (Clickable c : clickables()) before.add(c.key);

        boolean clicked = node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) tap(x, y);
        handler.postDelayed(() -> pickFirst(before, x, y, PICK_ATTEMPTS), OPEN_WAIT_MS);
    }

    /** Clicks the top-most element that appeared after the dropdown opened. */
    private void pickFirst(Set<String> before, int openX, int openY, int attemptsLeft) {
        Rect screen = screenBounds();
        long screenArea = (long) screen.width() * screen.height();
        Clickable first = null;
        for (Clickable c : clickables()) {
            if (before.contains(c.key)) continue;
            if (c.bounds.contains(openX, openY)) continue;
            // Skip full-screen backdrops that close the menu when tapped.
            if ((long) c.bounds.width() * c.bounds.height() > screenArea / 2) continue;
            if (first == null || c.bounds.top < first.bounds.top
                    || (c.bounds.top == first.bounds.top && c.bounds.left < first.bounds.left)) {
                first = c;
            }
        }

        if (first == null) {
            if (attemptsLeft > 1) {
                handler.postDelayed(() -> pickFirst(before, openX, openY, attemptsLeft - 1), 500);
            } else {
                finish("Opened the dropdown but couldn't find its options");
            }
            return;
        }

        if (!first.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            tap(first.bounds.centerX(), first.bounds.centerY());
        }
        String label = label(first.node);
        finish(label.isEmpty() ? "Selected first option" : "Selected: " + label);
    }

    // ---- Helpers ---------------------------------------------------------------

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null) roots.add(r);
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) roots.add(r);
        }
        return roots;
    }

    private List<Clickable> clickables() {
        List<Clickable> out = new ArrayList<>();
        for (AccessibilityNodeInfo root : roots()) {
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            stack.add(root);
            while (!stack.isEmpty()) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                if (n == null) continue;
                if (n.isClickable() && n.isVisibleToUser()) {
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    if (!r.isEmpty()) out.add(new Clickable(n, r, label(n)));
                }
                for (int i = 0; i < n.getChildCount(); i++) stack.add(n.getChild(i));
            }
        }
        return out;
    }

    /** The node's own text, or the text of its first descendant that has some. */
    private static String label(AccessibilityNodeInfo n) {
        if (n == null) return "";
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        if (t != null && t.length() > 0) return t.toString().trim();
        for (int i = 0; i < n.getChildCount(); i++) {
            String s = label(n.getChild(i));
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    private void tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        dispatchGesture(gesture, null, null);
    }

    private Rect screenBounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.getCurrentWindowMetrics().getBounds();
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
