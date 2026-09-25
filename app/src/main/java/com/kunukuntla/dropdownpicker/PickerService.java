package com.kunukuntla.dropdownpicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.ClipData;
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
 * Accessibility service that shows floating Start and See buttons. Start takes
 * a screenshot, taps the top-most dropdown once and selects its first option
 * (or taps half a centimetre below its line if no option text can be read).
 * Long-pressing it shows the screenshot the app analysed with what it found
 * marked on it, and lets the user share that picture.
 */
public class PickerService extends AccessibilityService {

    private static final long OPEN_WAIT_MS = 700;
    private static final int PICK_ATTEMPTS = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    /** Holds the Start and See buttons; this is the floating window. */
    private LinearLayout controls;
    private TextView button;
    private WindowManager.LayoutParams buttonParams;
    /** Why the last screenshot failed, or null. */
    private String screenshotError;
    private boolean busy;
    private boolean running;
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
        if (controls != null) windowManager.removeView(controls);
        removeHighlight();
        closePreview();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- Floating button ------------------------------------------------------

    private void showButton() {
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);

        button = roundButton("▶\nStart", 0xDD6A3FA0, dp(56));
        button.setContentDescription("Start selecting dropdowns");
        TextView see = roundButton("👁\nSee", 0xDD00897B, dp(48));
        see.setContentDescription("See what the app sees");

        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        LinearLayout.LayoutParams seeLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        seeLp.gravity = Gravity.CENTER_HORIZONTAL;
        seeLp.topMargin = dp(8);
        controls.addView(button, startLp);
        controls.addView(see, seeLp);

        buttonParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        buttonParams.gravity = Gravity.TOP | Gravity.END;
        buttonParams.x = dp(12);
        buttonParams.y = dp(160);

        // Both buttons drag the pair around; a plain tap runs the button's action,
        // and a long-press on Start also shows what the app sees.
        button.setOnTouchListener(new DragOrTap(this::run, this::showWhatISee));
        see.setOnTouchListener(new DragOrTap(this::showWhatISee, null));
        windowManager.addView(controls, buttonParams);
    }

    private TextView roundButton(String text, int color, int size) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        b.setBackground(bg);
        b.setMinimumWidth(size);
        b.setMinimumHeight(size);
        return b;
    }

    /** Drags the floating buttons, or runs {@code onTap} / {@code onLongPress}. */
    private final class DragOrTap implements View.OnTouchListener {
        private final Runnable onTap;
        private final Runnable longPress;
        private final int slop = ViewConfiguration.get(PickerService.this).getScaledTouchSlop();
        private float downX, downY;
        private int startX, startY;
        private boolean dragging, longPressed;
        private final Runnable onLongPress;

        DragOrTap(Runnable onTap, Runnable longPress) {
            this.onTap = onTap;
            this.longPress = longPress;
            this.onLongPress = () -> {
                longPressed = true;
                longPress.run();
            };
        }

        @SuppressLint("ClickableViewAccessibility")
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
                    if (longPress != null) {
                        handler.postDelayed(onLongPress, ViewConfiguration.getLongPressTimeout());
                    }
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
                        windowManager.updateViewLayout(controls, buttonParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(onLongPress);
                    if (!dragging && !longPressed) onTap.run();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(onLongPress);
                    return true;
                default:
                    return false;
            }
        }
    }

    private void setButtonVisible(boolean visible) {
        if (controls != null) controls.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
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
            stop("Stopped");
            return;
        }
        if (busy) return;
        busy = true;
        running = true;
        button.setText("■\nStop");
        findAndOpen();
    }

    private void stop(String message) {
        running = false;
        busy = false;
        handler.removeCallbacksAndMessages(null);
        // Leave the last outline up briefly so you can see what was tapped.
        handler.postDelayed(this::removeHighlight, 1200);
        button.setText("▶\nStart");
        setButtonVisible(true);
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** Takes a screenshot and opens the top-most dropdown in it. */
    private void findAndOpen() {
        // Keep the button and old outlines out of the screenshot.
        removeHighlight();
        setButtonVisible(false);
        handler.postDelayed(() -> capture(bmp -> {
            if (bmp == null) {
                setButtonVisible(true);
                open(nodeTargets());
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
                open(targets.isEmpty() ? nodeTargets() : targets);
            });
        }), 250);
    }

    private void open(List<Target> targets) {
        if (!running) return;
        if (targets.isEmpty()) {
            stop(screenshotError != null
                    ? "Couldn't take a screenshot: " + screenshotError
                    : "No dropdown found. Tap See to see what the app sees.");
            return;
        }
        Target t = targets.get(0);
        showHighlight(t.line, t.arrow, null, t.tapX, t.tapY);
        handler.postDelayed(() -> openAndPick(t), 400);
    }

    /**
     * Takes a screenshot; passes null if that isn't possible and sets
     * {@link #screenshotError} to the reason.
     */
    private void capture(Consumer<Bitmap> done) {
        capture(done, true);
    }

    private void capture(Consumer<Bitmap> done, boolean retry) {
        screenshotError = null;
        // Screen shared from the app: use its latest frame.
        Bitmap shared = ScreenCaptureService.grab();
        if (shared != null) {
            done.accept(shared);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            screenshotError = "open Dropdown Picker and tap Share screen first";
            done.accept(null);
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap bmp = null;
                try {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hw != null) bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                } catch (RuntimeException e) {
                    bmp = null;
                } finally {
                    buffer.close();
                }
                if (bmp == null) {
                    screenshotError = "couldn't read the picture - open Dropdown Picker and tap "
                            + "Share screen";
                }
                done.accept(bmp);
            }

            @Override
            public void onFailure(int errorCode) {
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && retry) {
                    // Android allows only a few screenshots per second; wait and try again.
                    handler.postDelayed(() -> capture(done, false), 500);
                    return;
                }
                switch (errorCode) {
                    case ERROR_TAKE_SCREENSHOT_SECURE_WINDOW:
                        screenshotError = "this app blocks screenshots (secure screen) - try "
                                + "Share screen in Dropdown Picker";
                        break;
                    case ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS:
                        screenshotError = "screenshot permission missing - turn the app off and on "
                                + "again in accessibility settings";
                        break;
                    case ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT:
                        screenshotError = "too many screenshots, try again in a second";
                        break;
                    default:
                        screenshotError = "Android error " + errorCode + " - open Dropdown "
                                + "Picker and tap Share screen";
                }
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

    /** Taps the dropdown once, then selects its first option. */
    private void openAndPick(Target t) {
        if (!running) return;
        Set<String> before = new HashSet<>();
        for (Clickable c : clickables()) before.add(c.key);

        boolean clicked = t.node != null && t.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) tap(t.tapX, t.tapY);
        handler.postDelayed(() -> pickFirst(t, before, PICK_ATTEMPTS), OPEN_WAIT_MS);
    }

    /**
     * Clicks the option that appeared after the dropdown opened: the one
     * matching the user's first keyword that matches anything, else the
     * top-most option with text. If none can be read, taps half a centimetre
     * below the line, where the first option normally is.
     */
    private void pickFirst(Target t, Set<String> before, int attemptsLeft) {
        if (!running) return;
        Rect screen = screenBounds();
        long screenArea = (long) screen.width() * screen.height();
        List<String> keywords = Keywords.list(this);
        Clickable first = null;
        String firstLabel = "";
        Clickable match = null;
        String matchLabel = "";
        int matchRank = Integer.MAX_VALUE;
        for (Clickable c : clickables()) {
            if (before.contains(c.key)) continue;
            if (c.bounds.contains(t.tapX, t.tapY)) continue;
            // Skip full-screen backdrops that close the menu when tapped.
            if ((long) c.bounds.width() * c.bounds.height() > screenArea / 2) continue;
            String label = label(c.node);
            if (label.isEmpty()) continue;

            // Earlier keywords win; for the same keyword, the higher option wins.
            String lower = label.toLowerCase(java.util.Locale.ROOT);
            for (int k = 0; k < keywords.size() && k <= matchRank; k++) {
                if (!lower.contains(keywords.get(k))) continue;
                if (k < matchRank || c.bounds.top < match.bounds.top) {
                    match = c;
                    matchLabel = label;
                    matchRank = k;
                }
                break;
            }
            if (first == null || c.bounds.top < first.bounds.top
                    || (c.bounds.top == first.bounds.top && c.bounds.left < first.bounds.left)) {
                first = c;
                firstLabel = label;
            }
        }

        if (first == null) {
            if (attemptsLeft > 1) {
                handler.postDelayed(() -> pickFirst(t, before, attemptsLeft - 1), 400);
                return;
            }
            // No option text found: tap half a centimetre below the line.
            int x = t.line.centerX();
            int y = t.line.bottom + halfCm();
            showHighlight(null, null, null, x, y);
            tap(x, y);
            stop("Tapped half a cm below the line");
            return;
        }

        String note = "";
        if (match != null) {
            first = match;
            firstLabel = matchLabel;
            note = " (keyword \"" + keywords.get(matchRank) + "\")";
        } else if (!keywords.isEmpty()) {
            note = " (no keyword matched, took the first option)";
        }
        showHighlight(null, null, first.bounds, -1, -1);
        if (!first.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            tap(first.bounds.centerX(), first.bounds.centerY());
        }
        stop("Selected: " + firstLabel + note);
    }

    /** Half a centimetre in screen pixels. */
    private int halfCm() {
        return Math.round(getResources().getDisplayMetrics().ydpi / 2.54f * 0.5f);
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
            if (!ScreenCaptureService.isSharing()) {
                Toast.makeText(this, "Open Dropdown Picker and tap Share screen first",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        busy = true;
        setButtonVisible(false);
        handler.postDelayed(() -> capture(bmp -> {
            if (bmp == null) {
                stop("Couldn't take a screenshot: " + screenshotError);
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
            markup.backupX = hit.line.centerX();
            markup.backupY = hit.line.bottom + halfCm();
            markup.caption = result.hits.size() + " dropdown(s) found!\n"
                    + "Red = underline, green = arrow,\n"
                    + "pink = where Start taps to open it,\n"
                    + "blue dot = backup tap, half a cm below.\n"
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/DropdownPicker");
            }
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    out.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't save the picture: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            closePreview();
            return;
        } finally {
            out.recycle();
        }
        closePreview();
        if (uri == null) {
            Toast.makeText(this, "Couldn't save the picture", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Saved to Pictures/DropdownPicker", Toast.LENGTH_SHORT).show();
        // ClipData + the grant flag let the app you share to read the picture.
        ClipData clip = ClipData.newRawUri("screenshot", uri);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(clip);
        Intent chooser = Intent.createChooser(send, "Share what the app sees");
        chooser.setClipData(clip);
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(chooser);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Couldn't open the share menu. The picture is in your Gallery "
                    + "under Pictures/DropdownPicker.", Toast.LENGTH_LONG).show();
        }
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
