package com.kunukuntla.dropdownpicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Accessibility service that shows a floating Start button. Tapping it goes
 * through the dropdowns on screen one after another, top to bottom: take a
 * screenshot, find the next dropdown, open it and select its first option.
 * Long-pressing it shows the screenshot the app analysed with what it found
 * marked on it, and lets the user share that picture.
 */
public class PickerService extends AccessibilityService {

    private static final long OPEN_WAIT_MS = 700;
    private static final int PICK_ATTEMPTS = 3;
    private static final int MAX_STEPS = 30;
    private static final long STEP_PAUSE_MS = 900;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private TextView button;
    private WindowManager.LayoutParams buttonParams;
    private boolean busy;
    private boolean running;
    private int picked, steps;
    private final List<Integer> doneYs = new ArrayList<>();
    private View highlight;
    private View preview;

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
        removeHighlight();
        closePreview();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- Floating button ------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private void showButton() {
        button = new TextView(this);
        button.setText("▶\nStart");
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setGravity(Gravity.CENTER);
        int size = dp(56);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xDD6A3FA0);
        button.setBackground(bg);
        button.setContentDescription("Start selecting dropdowns. Long-press to see what the app sees.");

        buttonParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        buttonParams.gravity = Gravity.TOP | Gravity.END;
        buttonParams.x = dp(12);
        buttonParams.y = dp(160);

        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        long longPressMs = ViewConfiguration.getLongPressTimeout();
        button.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragging, longPressed;
            final Runnable onLongPress = () -> {
                longPressed = true;
                showWhatISee();
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = buttonParams.x;
                        startY = buttonParams.y;
                        dragging = false;
                        longPressed = false;
                        handler.postDelayed(onLongPress, longPressMs);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > slop) {
                            dragging = true;
                            handler.removeCallbacks(onLongPress);
                        }
                        if (dragging) {
                            // Gravity is END, so x grows to the left.
                            buttonParams.x = startX - (int) dx;
                            buttonParams.y = startY + (int) dy;
                            windowManager.updateViewLayout(button, buttonParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(onLongPress);
                        if (!dragging && !longPressed) run();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(onLongPress);
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

    /** A dropdown to open: where it is on screen, and its node if it came from the accessibility tree. */
    private static final class Target {
        final Rect line, arrow;
        final int tapX, tapY;
        final AccessibilityNodeInfo node;

        Target(Rect line, Rect arrow, int tapX, int tapY, AccessibilityNodeInfo node) {
            this.line = line;
            this.arrow = arrow;
            this.tapX = tapX;
            this.tapY = tapY;
            this.node = node;
        }
    }

    /** Button tap: Start, or Stop if already running. */
    private void run() {
        if (running) {
            stop("Stopped. Selected " + picked + " dropdown(s).");
            return;
        }
        if (busy) return;
        busy = true;
        running = true;
        picked = 0;
        steps = 0;
        doneYs.clear();
        button.setText("■\nStop");
        nextStep();
    }

    private void stop(String message) {
        running = false;
        busy = false;
        handler.removeCallbacksAndMessages(null);
        removeHighlight();
        button.setText("▶\nStart");
        setButtonVisible(true);
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** Takes a fresh screenshot, then opens the next dropdown that hasn't been done yet. */
    private void nextStep() {
        if (!running) return;
        if (++steps > MAX_STEPS) {
            stop("Done. Selected " + picked + " dropdown(s).");
            return;
        }
        // Keep the button and the outlines out of the screenshot.
        removeHighlight();
        setButtonVisible(false);
        handler.postDelayed(() -> capture(bmp -> {
            if (bmp == null) {
                setButtonVisible(true);
                openNext(nodeTargets());
                return;
            }
            analyze(bmp, result -> {
                bmp.recycle();
                setButtonVisible(true);
                List<Target> targets = new ArrayList<>();
                for (DropdownDetector.Hit h : result.hits) {
                    targets.add(new Target(h.line, h.arrow, h.tapX, h.tapY, null));
                }
                // Nothing seen in the picture: try native dropdown widgets instead.
                openNext(targets.isEmpty() ? nodeTargets() : targets);
            });
        }), 250);
    }

    private void openNext(List<Target> targets) {
        if (!running) return;
        Target next = null;
        for (Target t : targets) {
            if (!isDone(t)) {
                next = t;
                break;
            }
        }
        if (next == null) {
            stop(picked == 0
                    ? "No dropdown found. Long-press the button to see what the app sees."
                    : "Done. Selected " + picked + " dropdown(s).");
            return;
        }
        doneYs.add(next.line.centerY());
        Target t = next;
        showHighlight(t.line, t.arrow, null, t.tapX, t.tapY);
        handler.postDelayed(() -> openAndPick(t), 400);
    }

    /** A dropdown counts as done if one at about the same height was already handled. */
    private boolean isDone(Target t) {
        int tolerance = dp(16);
        for (int y : doneYs) {
            if (Math.abs(y - t.line.centerY()) <= tolerance) return true;
        }
        return false;
    }

    /** Takes a screenshot; passes null if that isn't possible (Android 10 and older). */
    private void capture(Consumer<Bitmap> done) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            done.accept(null);
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                Bitmap bmp = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                buffer.close();
                done.accept(bmp);
            }

            @Override
            public void onFailure(int errorCode) {
                done.accept(null);
            }
        });
    }

    /** Runs the detector off the main thread and delivers the result on it. */
    private void analyze(Bitmap bmp, Consumer<DropdownDetector.Result> done) {
        new Thread(() -> {
            DropdownDetector.Result result = DropdownDetector.analyze(bmp);
            handler.post(() -> done.accept(result));
        }).start();
    }

    /** Native dropdown widgets (Spinner, HTML select, combobox), top to bottom. */
    private List<Target> nodeTargets() {
        List<Target> out = new ArrayList<>();
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
                    if (!r.isEmpty()) out.add(new Target(r, null, r.centerX(), r.centerY(), n));
                }
                for (int i = 0; i < n.getChildCount(); i++) stack.add(n.getChild(i));
            }
        }
        out.sort((a, b) -> Integer.compare(a.line.top, b.line.top));
        return out;
    }

    private void openAndPick(Target t) {
        if (!running) return;
        Set<String> before = new HashSet<>();
        for (Clickable c : clickables()) before.add(c.key);

        boolean clicked = t.node != null && t.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) tap(t.tapX, t.tapY);
        handler.postDelayed(() -> pickFirst(before, t.tapX, t.tapY, PICK_ATTEMPTS), OPEN_WAIT_MS);
    }

    /** Clicks the top-most element that appeared after the dropdown opened, then moves on. */
    private void pickFirst(Set<String> before, int openX, int openY, int attemptsLeft) {
        if (!running) return;
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
                // Couldn't see the options: close whatever opened and carry on with the next one.
                Toast.makeText(this, "Couldn't find the options of a dropdown, skipping it",
                        Toast.LENGTH_SHORT).show();
                performGlobalAction(GLOBAL_ACTION_BACK);
                handler.postDelayed(this::nextStep, STEP_PAUSE_MS);
            }
            return;
        }

        showHighlight(null, null, first.bounds, -1, -1);
        if (!first.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            tap(first.bounds.centerX(), first.bounds.centerY());
        }
        picked++;
        String label = label(first.node);
        Toast.makeText(this, label.isEmpty() ? "Selected first option" : "Selected: " + label,
                Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::nextStep, STEP_PAUSE_MS);
    }

    // ---- Showing what the app sees ------------------------------------------------

    /** Briefly outlines things on the real screen. Touches pass through. */
    private void showHighlight(Rect line, Rect arrow, Rect option, int tapX, int tapY) {
        removeHighlight();
        MarkupView v = new MarkupView(this);
        v.line = line;
        v.arrow = arrow;
        v.option = option;
        v.tapX = tapX;
        v.tapY = tapY;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        windowManager.addView(v, lp);
        highlight = v;
        handler.postDelayed(() -> {
            if (highlight == v) removeHighlight();
        }, 1200);
    }

    private void removeHighlight() {
        if (highlight != null) {
            windowManager.removeView(highlight);
            highlight = null;
        }
    }

    /** Long-press: show the screenshot with what the detector found, without tapping anything. */
    private void showWhatISee() {
        if (busy || running) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "Seeing the screen needs Android 11 or newer", Toast.LENGTH_LONG).show();
            return;
        }
        busy = true;
        setButtonVisible(false);
        handler.postDelayed(() -> capture(bmp -> {
            if (bmp == null) {
                stop("Couldn't take a screenshot");
                return;
            }
            analyze(bmp, result -> {
                busy = false;
                showPreview(bmp, result);
            });
        }), 200);
    }

    private void showPreview(Bitmap bmp, DropdownDetector.Result result) {
        closePreview();
        MarkupView markup = new MarkupView(this);
        markup.shot = bmp;
        markup.lines = result.lines;
        markup.hits = result.hits;
        DropdownDetector.Hit hit = result.hit;
        if (hit != null) {
            markup.line = hit.line;
            markup.arrow = hit.arrow;
            markup.tapX = hit.tapX;
            markup.tapY = hit.tapY;
            markup.caption = result.hits.size() + " dropdown(s) found!\n"
                    + "Red = underline, green = arrow,\n"
                    + "pink = where Start taps first.\n"
                    + "Yellow = other long lines.";
        } else {
            markup.caption = "No dropdown found.\n"
                    + "Yellow = long lines seen (" + result.lines.size() + ").\n"
                    + "None had a down arrow just above\n"
                    + "its right end.";
        }

        FrameLayout root = new FrameLayout(this);
        root.addView(markup, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        Button share = new Button(this);
        share.setText("Share");
        share.setOnClickListener(v -> share(markup));
        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(v -> closePreview());
        buttons.addView(share);
        buttons.addView(close);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        blp.bottomMargin = dp(48);
        root.addView(buttons, blp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        windowManager.addView(root, lp);
        preview = root;
        preview.setTag(bmp);
    }

    private void closePreview() {
        if (preview != null) {
            windowManager.removeView(preview);
            Object bmp = preview.getTag();
            if (bmp instanceof Bitmap) ((Bitmap) bmp).recycle();
            preview = null;
        }
        setButtonVisible(true);
    }

    /** Saves the marked-up screenshot to Pictures/DropdownPicker and opens the share sheet. */
    private void share(MarkupView markup) {
        Uri uri = null;
        Bitmap out = markup.render();
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "dropdown-picker-" + System.currentTimeMillis() + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/DropdownPicker");
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    out.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
            }
        } catch (Exception e) {
            uri = null;
        } finally {
            out.recycle();
        }
        closePreview();
        if (uri == null) {
            Toast.makeText(this, "Couldn't save the picture", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Saved to Pictures/DropdownPicker", Toast.LENGTH_SHORT).show();
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, "Share what the app sees");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(chooser);
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
