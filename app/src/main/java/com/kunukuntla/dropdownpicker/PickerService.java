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
import java.time.LocalDate;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Accessibility service that shows floating Start and See buttons. Start takes
 * a screenshot, finds the top-most dropdown's line and taps once 4 mm above it
 * to open it. Then it taps 4 mm below the line (the first option), or, when
 * keywords are set, searches the options for them, scrolling the list.
 * Long-pressing it shows the screenshot the app analysed with what it found
 * marked on it, and lets the user share that picture.
 */
public class PickerService extends AccessibilityService {

    private static final long OPEN_WAIT_MS = 450;
    private static final int MAX_MONTH_CHANGES = 12;
    private static final int OPEN_CHECKS = 3;
    private static final int MAX_SCROLLS = 40;
    private static final int FORM_SCROLLS = 40;
    private static final int PAGE_SCROLLS = 40;

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
    private static final int STEP_DROP = 0, STEP_CAL = 1, STEP_CHECK = 2;
    private static final String[] STEP_LABELS = {"▼\nDrop", "📅\nCal", "☑\nCheck"};
    private TextView[] stepButtons;
    /** Which button started the current run. */
    private int stepMode = STEP_DROP;
    private final ScreenReader reader = new ScreenReader();
    /** Text read in the dropdown's column before it was opened. */
    private List<ScreenReader.Found> beforeOcr;
    /** What happened during the last Start, shown on the app's main screen. */
    private final StringBuilder runLog = new StringBuilder();
    private View highlight;
    private View preview;

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
        reader.close();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- Floating button ------------------------------------------------------

    private void showButton() {
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);

        // One button per step, to test each on its own: Drop, Cal, Check (+ Continue), See.
        stepButtons = new TextView[] {
                roundButton(STEP_LABELS[STEP_DROP], 0xDD6A3FA0, dp(52)),
                roundButton(STEP_LABELS[STEP_CAL], 0xDD1565C0, dp(52)),
                roundButton(STEP_LABELS[STEP_CHECK], 0xDDEF6C00, dp(52))};
        TextView see = roundButton("👁\nSee", 0xDD00897B, dp(52));
        see.setContentDescription("See what the app sees");

        for (int i = 0; i < stepButtons.length; i++) {
            int step = i;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
            lp.bottomMargin = dp(6);
            controls.addView(stepButtons[i], lp);
            stepButtons[i].setOnTouchListener(new DragOrTap(() -> run(step), null));
        }
        controls.addView(see, new LinearLayout.LayoutParams(dp(52), dp(52)));
        button = stepButtons[STEP_DROP];

        buttonParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        buttonParams.gravity = Gravity.TOP | Gravity.END;
        buttonParams.x = dp(12);
        buttonParams.y = dp(120);

        // Every button drags the column around; a plain tap runs the button's action.
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

    /** A step button was tapped: run that step alone, or stop if something is running. */
    private void run(int step) {
        if (running) {
            stop("Stopped");
            return;
        }
        if (busy) return;
        busy = true;
        running = true;
        stepMode = step;
        button = stepButtons[step];
        button.setText("■\nStop");
        runLog.setLength(0);
        log("Start: " + STEP_LABELS[step].replace('\n', ' '));
        if (step == STEP_DROP) {
            findAndOpen();
        } else if (step == STEP_CAL) {
            startCalendar("Calendar test");
        } else {
            formStep(new boolean[3], FORM_SCROLLS, "Check test");
        }
    }

    private void stop(String message) {
        if (message != null) log(message);
        Keywords.saveLastRun(this, runLog.toString());
        running = false;
        busy = false;
        handler.removeCallbacksAndMessages(null);
        // Leave the last outline up briefly so you can see what was tapped.
        handler.postDelayed(this::removeHighlight, 1200);
        for (int i = 0; i < stepButtons.length; i++) stepButtons[i].setText(STEP_LABELS[i]);
        setButtonVisible(true);
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** Takes a screenshot and opens the top-most dropdown in it. */
    private void findAndOpen() {
        // Keep the button and old outlines out of the screenshot.
        removeHighlight();
        setButtonVisible(false);
        handler.postDelayed(safe(() -> capture(bmp -> {
            if (bmp == null) {
                setButtonVisible(true);
                log("No screenshot (" + screenshotError + "), looking for native dropdowns");
                open(nodeTargets());
                return;
            }
            analyze(bmp, result -> {
                setButtonVisible(true);
                List<Target> targets = new ArrayList<>();
                for (DropdownDetector.Hit h : result.hits) {
                    // Open it with one tap 4 mm above the line (not on the arrow).
                    targets.add(new Target(h.line, h.arrow, h.line.centerX(),
                            h.line.top - mm(4), null));
                }
                log("Screenshot " + result.width + "x" + result.height + ": "
                        + result.lines.size() + " long line(s), " + targets.size() + " dropdown(s)");
                beforeOcr = null;
                if (targets.isEmpty() || Keywords.loadPickMode(this) == Keywords.PICK_BELOW) {
                    bmp.recycle();
                    // Nothing seen in the picture: try native dropdown widgets instead.
                    open(targets.isEmpty() ? nodeTargets() : targets);
                    return;
                }
                // Remember the text in the dropdown's column before opening it (the field's
                // label and value), so it isn't mistaken for an option afterwards.
                Rect line = targets.get(0).line;
                int left = Math.max(0, line.left);
                int right = Math.min(bmp.getWidth(), line.right + 1);
                Bitmap column = Bitmap.createBitmap(bmp, left, 0, Math.max(1, right - left),
                        bmp.getHeight());
                if (column != bmp) bmp.recycle();
                reader.readAll(column, left, 0, lines -> safe(() -> {
                    beforeOcr = lines;
                    open(targets);
                }).run());
            });
        })), 120);
    }

    private void open(List<Target> targets) {
        if (!running) return;
        if (targets.isEmpty()) {
            afterDropdown(screenshotError != null
                    ? "Couldn't take a screenshot: " + screenshotError
                    : "No dropdown found");
            return;
        }
        Target t = targets.get(0);
        log("Dropdown line " + t.line.toShortString() + ", opening it at " + t.tapX + "," + t.tapY);
        showHighlight(t.line, t.arrow, null, t.tapX, t.tapY);
        openAndPick(t);
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
                Bitmap shot = bmp;
                safe(() -> done.accept(shot)).run();
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
                safe(() -> done.accept(null)).run();
            }
        });
    }

    /** Runs the detector off the main thread and delivers the result on it. */
    private void analyze(Bitmap bmp, Consumer<DropdownDetector.Result> done) {
        new Thread(() -> {
            try {
                DropdownDetector.Result result = DropdownDetector.analyze(bmp);
                handler.post(safe(() -> done.accept(result)));
            } catch (Throwable e) {
                handler.post(() -> fail(e));
            }
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

    /** A piece of text on screen (a possible option). */
    private static final class TextNode {
        final AccessibilityNodeInfo node;
        final Rect bounds;
        final String text;

        TextNode(AccessibilityNodeInfo node, Rect bounds, String text) {
            this.node = node;
            this.bounds = bounds;
            this.text = text;
        }

        String key() {
            return text + "@" + bounds.toShortString();
        }
    }

    /** Taps the dropdown once, then picks an option. */
    private void openAndPick(Target t) {
        if (!running) return;
        // Remember the text already on screen and where it is; options are text that shows
        // up (or moves) after opening. Text that stays put, like the field's own value, is not.
        Set<String> before = new HashSet<>();
        for (TextNode n : texts(true)) before.add(n.key());

        boolean clicked = t.node != null && t.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) tap(t.tapX, t.tapY);

        handler.postDelayed(safe(() -> ensureOpen(t, before)), OPEN_WAIT_MS);
    }

    /** If nothing new showed up, the dropdown didn't open: tap it once more (on the arrow). */
    private void ensureOpen(Target t, Set<String> before) {
        if (!running) return;
        if (options(t, before, true).isEmpty() && t.arrow != null) {
            log("The dropdown didn't seem to open, tapping its arrow");
            tap(t.arrow.centerX(), t.arrow.centerY());
            handler.postDelayed(safe(() -> pick(t, before)), OPEN_WAIT_MS);
            return;
        }
        pick(t, before);
    }

    /** The dropdown is open: tap 4 mm below, or search for a keyword. */
    private void pick(Target t, Set<String> before) {
        if (!running) return;
        List<String> keywords = Keywords.list(this);
        int pickMode = Keywords.loadPickMode(this);
        if (pickMode == Keywords.PICK_BELOW || keywords.isEmpty()) {
            // The first option sits just below the line.
            tapBelow(t);
        } else {
            search(t, before, keywords, pickMode == Keywords.PICK_EXACT, OPEN_CHECKS,
                    MAX_SCROLLS, 0, null);
        }
    }

    /** Taps 4 mm below the line (the blue dot), where the first option is. */
    private void tapBelow(Target t) {
        if (!running) return;
        int x = t.line.centerX();
        int y = t.line.bottom + mm(4);
        log("Tapping 4 mm below the line at " + x + "," + y);
        showHighlight(null, null, null, x, y);
        tap(x, y);
        afterDropdown("Tapped 4 mm below the line");
    }

    /**
     * Looks for an option matching one of the keywords (earliest keyword wins),
     * including options scrolled out of view, and taps it. Scrolls the list
     * 15 mm at a time until the match is on screen or the list stops moving.
     */
    private void search(Target t, Set<String> before, List<String> keywords, boolean exact,
                        int checksLeft, int scrollsLeft, int stuck, String lastSeen) {
        if (!running) return;
        List<TextNode> all = options(t, before, true);
        List<TextNode> visible = new ArrayList<>();
        for (TextNode o : all) if (o.node.isVisibleToUser()) visible.add(o);

        TextNode match = null;
        String matched = null;
        search:
        for (String k : keywords) {
            for (TextNode o : all) {
                String text = Keywords.norm(o.text);
                if (exact ? text.equals(k) : text.contains(k)) {
                    match = o;
                    matched = k;
                    break search;
                }
            }
        }

        if (match != null && inView(match)) {
            log("Found \"" + match.text + "\" for keyword \"" + matched + "\", tapping it");
            showHighlight(null, null, match.bounds, -1, -1);
            tap(match.bounds.centerX(), match.bounds.centerY());
            String picked = match.text, key = matched;
            handler.postDelayed(safe(() -> confirmPick(t, before, key, exact, picked)), 400);
            return;
        }

        if (match != null) {
            if (checksLeft > 0) {
                // The page knows the match is further down the list: bring it into view, fast.
                log("\"" + match.text + "\" is out of view, bringing it into view");
                match.node.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                handler.postDelayed(safe(() -> search(t, before, keywords, exact, checksLeft - 1,
                        scrollsLeft, stuck, lastSeen)), 200);
                return;
            }
            // Still not in view: click the option directly through the page.
            AccessibilityNodeInfo target = clickableSelfOrParent(match.node);
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                log("Clicked \"" + match.text + "\" directly");
                afterDropdown("Selected: " + match.text + " (keyword \"" + matched + "\")");
                return;
            }
        }

        StringBuilder seenBuilder = new StringBuilder();
        // Text and positions: the page may call every option "visible", but they move when
        // the list scrolls.
        for (TextNode o : all) seenBuilder.append(o.key()).append('|');
        String pageSeen = seenBuilder.toString();

        // Take a screenshot and read the list from it.
        ocrLook(t, keywords, exact, (found, ocrText, error) -> {
            if (!running) return;
            if (found != null) {
                log("Read \"" + found.text + "\" on screen for keyword \"" + found.keyword
                        + "\" at " + found.box.toShortString() + ", tapping it");
                showHighlight(null, null, found.box, -1, -1);
                tap(found.box.centerX(), found.box.centerY());
                String picked = found.text, key = found.keyword;
                handler.postDelayed(safe(() -> confirmPick(t, before, key, exact, picked)), 400);
                return;
            }
            if (error != null) log("Couldn't read the screen: " + error);
            scrollOn(t, before, keywords, exact, checksLeft, scrollsLeft, stuck, lastSeen,
                    visible, pageSeen + "#" + ocrText);
        });
    }

    /**
     * After tapping an option: if the list is still open with that option in view, the tap
     * didn't take, so click the option directly through the page.
     */
    private void confirmPick(Target t, Set<String> before, String keyword, boolean exact, String picked) {
        if (!running) return;
        for (TextNode o : options(t, before, true)) {
            String text = Keywords.norm(o.text);
            if (!(exact ? text.equals(keyword) : text.contains(keyword)) || !inView(o)) continue;
            log("The list is still open, clicking \"" + o.text + "\" directly");
            AccessibilityNodeInfo target = clickableSelfOrParent(o.node);
            if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                tap(o.bounds.centerX(), o.bounds.centerY());
            }
            break;
        }
        afterDropdown("Selected: " + picked + " (keyword \"" + keyword + "\")");
    }

    /** Swipes the list once more, or gives up when it has stopped moving. */
    private void scrollOn(Target t, Set<String> before, List<String> keywords, boolean exact,
                          int checksLeft, int scrollsLeft, int stuck, String lastSeen,
                          List<TextNode> visible, String seen) {
        int nowStuck = seen.equals(lastSeen) ? stuck + 1 : 0;
        if (scrollsLeft <= 0 || nowStuck >= 3) {
            afterDropdown("No option matching your keywords found (list stopped scrolling)");
            return;
        }
        swipeList(t, visible);
        // Let the list settle before looking again.
        handler.postDelayed(safe(() -> search(t, before, keywords, exact, checksLeft,
                scrollsLeft - 1, nowStuck, seen)), 500);
    }

    /**
     * Screenshots the dropdown's column (the whole height: the list can open over the field,
     * above the line as well as below it) and looks for a keyword in it.
     */
    private void ocrLook(Target t, List<String> keywords, boolean exact, ScreenReader.Callback cb) {
        removeHighlight();
        setButtonVisible(false);
        handler.postDelayed(safe(() -> capture(bmp -> {
            setButtonVisible(true);
            if (bmp == null) {
                cb.done(null, "", "no screenshot (" + screenshotError + ")");
                return;
            }
            int left = Math.max(0, t.line.left);
            int top = 0;
            int right = Math.min(bmp.getWidth(), t.line.right + 1);
            Bitmap crop = Bitmap.createBitmap(bmp, left, top, Math.max(1, right - left),
                    bmp.getHeight() - top);
            if (crop != bmp) bmp.recycle();
            reader.find(crop, left, top, keywords, exact, beforeOcr, mm(3), (found, text, error) ->
                    safe(() -> cb.done(found, text, error)).run());
        })), 80);
    }

    /** A medium-speed 30 mm drag up inside the open list. */
    private void swipeList(Target t, List<TextNode> visible) {
        // Swipe inside the list itself when we can find it.
        Rect list = null;
        Rect screen = screenBounds();
        if (!visible.isEmpty()) {
            for (AccessibilityNodeInfo p = visible.get(0).node.getParent(); p != null; p = p.getParent()) {
                if (!p.isScrollable()) continue;
                Rect r = new Rect();
                p.getBoundsInScreen(r);
                if ((long) r.width() * r.height() <= (long) screen.width() * screen.height() / 2
                        && r.height() > mm(8)) {
                    list = r;
                }
                break;
            }
        }
        int x, from, to;
        if (list != null) {
            x = list.centerX();
            from = Math.min(list.bottom - mm(1), list.centerY() + mm(15));
            to = Math.max(list.top + mm(1), from - mm(30));
        } else {
            // The list sits over the field, around the line.
            x = t.line.centerX();
            from = t.line.bottom + mm(15);
            to = from - mm(30);
        }
        log("Dragging the list up 30 mm (" + from + " -> " + to + ")");
        // A medium-speed drag that holds before lifting, so the list moves 30 mm and stops.
        drag(x, from, x, to, 350);
    }

    /** On screen and inside the box of the list it scrolls in (not hidden by scrolling). */
    private boolean inView(TextNode o) {
        if (!o.node.isVisibleToUser() || !onScreen(o.bounds)) return false;
        for (AccessibilityNodeInfo p = o.node.getParent(); p != null; p = p.getParent()) {
            if (!p.isScrollable()) continue;
            Rect r = new Rect();
            p.getBoundsInScreen(r);
            return r.contains(o.bounds.centerX(), o.bounds.centerY());
        }
        return true;
    }

    private boolean onScreen(Rect r) {
        Rect screen = screenBounds();
        return r.top >= 0 && r.bottom <= screen.bottom && r.left >= 0 && r.right <= screen.right;
    }

    /** Text that appeared after the dropdown opened, in the dropdown's columns, top to bottom. */
    private List<TextNode> options(Target t, Set<String> before, boolean includeHidden) {
        List<TextNode> out = new ArrayList<>();
        for (TextNode n : texts(includeHidden)) {
            if (before.contains(n.key())) continue;
            if (n.bounds.contains(t.tapX, t.tapY)) continue;
            if (n.bounds.right < t.line.left || n.bounds.left > t.line.right) continue;
            out.add(n);
        }
        out.sort((a, b) -> Integer.compare(a.bounds.top, b.bounds.top));
        return out;
    }

    private List<TextNode> texts() {
        return texts(false);
    }

    /** Every piece of text on screen (a node's own text or description); hidden ones if asked. */
    private List<TextNode> texts(boolean includeHidden) {
        List<TextNode> out = new ArrayList<>();
        for (AccessibilityNodeInfo root : roots()) {
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            stack.add(root);
            while (!stack.isEmpty()) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                if (n == null) continue;
                CharSequence txt = n.getText();
                if (txt == null || txt.length() == 0) txt = n.getContentDescription();
                if (txt != null && (includeHidden || n.isVisibleToUser())) {
                    String s = txt.toString().trim();
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    if (!s.isEmpty() && !r.isEmpty()) out.add(new TextNode(n, r, s));
                }
                for (int i = 0; i < n.getChildCount(); i++) stack.add(n.getChild(i));
            }
        }
        return out;
    }

    // ---- Calendar day --------------------------------------------------------

    /** Dropdown step finished: go on to the calendar if a day is set, else stop. */
    private void afterDropdown(String message) {
        if (!running) return;
        if (stepMode == STEP_DROP) {
            // Testing the dropdown on its own.
            stop(message);
            return;
        }
        startCalendar(message);
    }

    private void startCalendar(String message) {
        log(message);
        int mode = DayChoice.loadMode(this);
        if (mode == DayChoice.MODE_OFF) {
            if (stepMode != STEP_CAL) {
                afterCalendar(message);
                return;
            }
            mode = DayChoice.MODE_EXACT; // the Cal button always tries the calendar
        }
        int calMode = mode;
        LocalDate date = DayChoice.parse(DayChoice.loadDate(this));
        if (date == null) {
            stop(message + ". Calendar skipped: enter the day as DD/MM/YYYY in the app");
            return;
        }
        log("Calendar: want " + date + (calMode == DayChoice.MODE_EXACT ? " (exact)" : " (or best)"));
        handler.postDelayed(safe(() -> calendarStep(date, calMode, MAX_MONTH_CHANGES, PAGE_SCROLLS)),
                stepMode == STEP_CAL ? 0 : 500);
    }

    /**
     * Moves the calendar to the wanted month, scrolling the page 10 mm at a
     * time until the calendar is on screen, then reads the day colours and
     * taps a day.
     */
    private void calendarStep(LocalDate date, int mode, int changesLeft, int scrollsLeft) {
        if (!running) return;
        List<TextNode> all = texts(true);
        List<TextNode> headers = monthHeaders(all);
        if (headers.isEmpty()) {
            if (scrollsLeft <= 0) {
                stop("Couldn't find the calendar's month name (like \"October 2026\")");
                return;
            }
            log("No month name yet, scrolling down 10 mm");
            pageSwipe(true);
            handler.postDelayed(safe(() -> calendarStep(date, mode, changesLeft, scrollsLeft - 1)), 350);
            return;
        }
        int want = DayChoice.monthOf(date);

        TextNode header = null;
        for (TextNode h : headers) if (DayChoice.monthOf(h.text) == want) header = h;
        TextNode shown = header != null ? header : headers.get(0);
        TextNode[] days = days(shown, headers, all);
        Rect grid = gridBounds(days);

        // Bring the calendar fully on screen first.
        Rect area = new Rect(shown.bounds);
        if (grid != null) area.union(grid);
        Rect screen = screenBounds();
        boolean fits = area.height() < screen.height() * 4 / 5;
        boolean below = fits ? area.bottom > screen.bottom - mm(8) : area.top > screen.centerY();
        boolean above = area.top < 0;
        if ((below || above) && scrollsLeft > 0) {
            log("Calendar is " + (below ? "below" : "above") + " the screen, scrolling 10 mm");
            pageSwipe(below);
            handler.postDelayed(safe(() -> calendarStep(date, mode, changesLeft, scrollsLeft - 1)), 350);
            return;
        }

        if (header == null) {
            int diff = want - DayChoice.monthOf(shown.text);
            if (changesLeft <= 0) {
                stop("Couldn't reach " + date.getMonth() + " " + date.getYear() + " in the calendar");
                return;
            }
            log("Calendar shows " + shown.text + ", tapping " + (diff > 0 ? "next" : "previous"));
            tapArrow(shown, grid, diff > 0);
            handler.postDelayed(safe(() -> calendarStep(date, mode, changesLeft - 1, scrollsLeft)), 600);
            return;
        }

        if (days[date.getDayOfMonth()] == null) {
            stop("Couldn't find day " + date.getDayOfMonth() + " under " + header.text);
            return;
        }
        // Read the colours from a fresh screenshot.
        removeHighlight();
        setButtonVisible(false);
        handler.postDelayed(safe(() -> capture(bmp -> {
            setButtonVisible(true);
            if (bmp == null) {
                stop("Couldn't take a screenshot to read the day colours: " + screenshotError);
                return;
            }
            String[] colours = new String[32];
            StringBuilder seen = new StringBuilder();
            for (int d = 1; d <= 31; d++) {
                if (days[d] == null) continue;
                colours[d] = cellColour(bmp, days[d].bounds);
                seen.append(d).append(colours[d].charAt(0)).append(' ');
            }
            bmp.recycle();
            log("Day colours: " + seen.toString().trim());
            chooseDay(date.getDayOfMonth(), mode, days, colours);
        })), 120);
    }

    private void chooseDay(int want, int mode, TextNode[] days, String[] colours) {
        int pick = -1;
        if (DayChoice.isOpen(this, colours[want])) {
            pick = want;
        } else if (mode == DayChoice.MODE_BEST) {
            // Nearest open day; on a tie, the earlier one.
            for (int dist = 1; dist <= 31 && pick < 0; dist++) {
                for (int d : new int[] {want - dist, want + dist}) {
                    if (d >= 1 && d <= 31 && days[d] != null && DayChoice.isOpen(this, colours[d])) {
                        pick = d;
                        break;
                    }
                }
            }
        }
        if (pick < 0) {
            stop("Day " + want + " is " + colours[want].toLowerCase(Locale.ROOT)
                    + (mode == DayChoice.MODE_BEST ? " and no day this month has an allowed colour "
                    + DayChoice.loadOpenColours(this)
                    : ", not available"));
            return;
        }
        TextNode cell = days[pick];
        showHighlight(null, null, cell.bounds, -1, -1);
        // A real tap on the day's box, like a finger.
        log("Tapping day " + pick + " at " + cell.bounds.centerX() + "," + cell.bounds.centerY());
        tap(cell.bounds.centerX(), cell.bounds.centerY());
        afterCalendar(pick == want ? "Selected day " + pick + " (" + colours[pick].toLowerCase(Locale.ROOT) + ")"
                : "Day " + want + " is " + colours[want].toLowerCase(Locale.ROOT)
                + ", selected nearest open day " + pick + " ("
                + colours[pick].toLowerCase(Locale.ROOT) + ")");
    }

    /**
     * Month names like "October 2026", also when the month and the year are
     * separate pieces of text on the same row. Top to bottom.
     */
    private List<TextNode> monthHeaders(List<TextNode> all) {
        List<TextNode> out = new ArrayList<>();
        for (TextNode n : all) {
            if (DayChoice.monthOf(n.text) >= 0) {
                out.add(n);
                continue;
            }
            if (!n.text.matches("[A-Za-z]{3,9}\\.?")) continue;
            for (TextNode y : all) {
                if (y == n || !y.text.matches("\\d{4}")) continue;
                if (Math.abs(y.bounds.centerY() - n.bounds.centerY()) > n.bounds.height()) continue;
                if (y.bounds.left < n.bounds.left || y.bounds.left - n.bounds.right > mm(15)) continue;
                String joined = n.text + " " + y.text;
                if (DayChoice.monthOf(joined) < 0) continue;
                Rect r = new Rect(n.bounds);
                r.union(y.bounds);
                out.add(new TextNode(n.node, r, joined));
                break;
            }
        }
        out.sort((a, b) -> Integer.compare(a.bounds.top, b.bounds.top));
        return out;
    }

    /** Scrolls the page 10 mm: down (content moves up) or up. */
    private void pageSwipe(boolean down) {
        Rect screen = screenBounds();
        int x = screen.centerX();
        int y = screen.height() * 3 / 5;
        if (down) swipe(x, y, x, y - mm(10), 250);
        else swipe(x, y - mm(10), x, y, 250);
    }

    /** The day-number cells (index 1-31) under a month header, before the next header. */
    private TextNode[] days(TextNode header, List<TextNode> headers, List<TextNode> all) {
        int limit = header.bounds.top + screenBounds().height() * 3 / 4;
        for (TextNode h : headers) {
            if (h.bounds.top > header.bounds.top) {
                limit = Math.min(limit, h.bounds.top);
                break;
            }
        }
        TextNode[] out = new TextNode[32];
        for (TextNode n : all) {
            if (n.bounds.top < header.bounds.bottom || n.bounds.top > limit) continue;
            if (!n.text.matches("\\d{1,2}")) continue;
            int d = Integer.parseInt(n.text);
            if (d < 1 || d > 31) continue;
            if (out[d] == null || n.bounds.top < out[d].bounds.top) out[d] = n;
        }
        return out;
    }

    private static Rect gridBounds(TextNode[] days) {
        Rect grid = null;
        for (TextNode n : days) {
            if (n == null) continue;
            if (grid == null) grid = new Rect(n.bounds);
            else grid.union(n.bounds);
        }
        return grid;
    }

    /** Taps the calendar's next (or previous) arrow: whatever is clickable beside the grid. */
    private void tapArrow(TextNode header, Rect grid, boolean next) {
        Rect area = grid != null ? grid : header.bounds;
        int cell = grid != null ? Math.max(1, grid.width() / 7) : mm(8);
        AccessibilityNodeInfo best = null;
        Rect bestBounds = null;
        for (AccessibilityNodeInfo root : roots()) {
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            stack.add(root);
            while (!stack.isEmpty()) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                if (n == null) continue;
                for (int i = 0; i < n.getChildCount(); i++) stack.add(n.getChild(i));
                if (!n.isClickable() || !n.isVisibleToUser()) continue;
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.isEmpty() || r.width() > cell * 2) continue;
                if (r.centerY() < header.bounds.top || r.centerY() > area.bottom) continue;
                boolean side = next ? r.left >= area.right - cell / 4 : r.right <= area.left + cell / 4;
                if (!side) continue;
                int gap = next ? r.left - area.right : area.left - r.right;
                int bestGap = bestBounds == null ? Integer.MAX_VALUE
                        : next ? bestBounds.left - area.right : area.left - bestBounds.right;
                if (gap < bestGap) {
                    best = n;
                    bestBounds = r;
                }
            }
        }
        if (best != null) {
            if (!best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                tap(bestBounds.centerX(), bestBounds.centerY());
            }
            return;
        }
        // No arrow found in the tree: tap where it usually is, beside the middle of the grid.
        int x = next ? area.right + cell * 2 / 3 : area.left - cell * 2 / 3;
        log("No arrow found, tapping " + x + "," + area.centerY());
        tap(x, area.centerY());
    }

    /** The cell's background colour: the most common colour around the edge of its box. */
    private static String cellColour(Bitmap bmp, Rect b) {
        int insetX = Math.max(2, b.width() / 8), insetY = Math.max(2, b.height() / 8);
        int[][] points = {
                {b.left + insetX, b.top + insetY}, {b.right - insetX, b.top + insetY},
                {b.left + insetX, b.bottom - insetY}, {b.right - insetX, b.bottom - insetY},
                {b.left + insetX, b.centerY()}, {b.right - insetX, b.centerY()},
                {b.centerX(), b.top + insetY}, {b.centerX(), b.bottom - insetY}};
        java.util.Map<String, Integer> votes = new java.util.HashMap<>();
        String best = "WHITE";
        int bestVotes = 0;
        for (int[] p : points) {
            if (p[0] < 0 || p[1] < 0 || p[0] >= bmp.getWidth() || p[1] >= bmp.getHeight()) continue;
            String c = DayChoice.colourName(bmp.getPixel(p[0], p[1]));
            int v = votes.merge(c, 1, Integer::sum);
            if (v > bestVotes) {
                bestVotes = v;
                best = c;
            }
        }
        return best;
    }

    // ---- Radio button, checkbox, Continue -----------------------------------------

    private static final String[] FORM_STEPS = {"\"Available\" slot", "checkbox", "Continue button"};


    /** Calendar step finished: tick the radio button and checkbox, then press Continue. */
    private void afterCalendar(String message) {
        if (!running) return;
        log(message);
        if (stepMode == STEP_CAL || !Keywords.loadFinish(this)) {
            stop(message);
            return;
        }
        handler.postDelayed(safe(() -> formStep(new boolean[3], FORM_SCROLLS, message)), 700);
    }

    /**
     * Ticks the radio button, then the checkbox, then presses Continue, each
     * as soon as it is on screen; scrolls the page 10 mm between looks. When
     * Continue shows up, anything not found by then is taken as not there.
     */
    private void formStep(boolean[] done, int scrollsLeft, String summary) {
        if (!running) return;
        AccessibilityNodeInfo cont = formNode(2);
        // 1) Controls the page reports ("Available" slot, real checkboxes).
        AccessibilityNodeInfo agreeText = null;
        for (int stage = 0; stage < 2; stage++) {
            if (done[stage]) continue;
            AccessibilityNodeInfo n = formNode(stage);
            if (n == null) continue;
            CharSequence cls = n.getClassName();
            if (stage == 1 && !n.isCheckable() && (cls == null || !cls.toString().contains("CheckBox"))) {
                // Only an "I hereby / I agree" text: find its drawn box in the screenshot below.
                agreeText = n;
                continue;
            }
            done[stage] = true;
            if (n.isChecked()) {
                log("The " + FORM_STEPS[stage] + " is already ticked");
                continue;
            }
            int s = stage;
            tick(n, FORM_STEPS[stage]);
            // Check it took, then carry on.
            handler.postDelayed(safe(() -> {
                AccessibilityNodeInfo again = formNode(s);
                if (again != null && again.isCheckable() && !again.isChecked()) {
                    log("The " + FORM_STEPS[s] + " didn't tick, clicking it directly");
                    again.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                formStep(done, scrollsLeft, summary);
            }), 300);
            return;
        }
        if (done[0] && done[1]) {
            continueOrScroll(done, cont, scrollsLeft, summary);
            return;
        }

        // 2) Drawn checkbox: look for an empty square with a label next to it on screen.
        AccessibilityNodeInfo agree = agreeText;
        removeHighlight();
        setButtonVisible(false);
        handler.postDelayed(safe(() -> capture(bmp -> {
            setButtonVisible(true);
            if (bmp == null) {
                log("No screenshot for the form (" + screenshotError + ")");
                continueOrScroll(done, cont, scrollsLeft, summary);
                return;
            }
            float mmPx = mm(10) / 10f;
            new Thread(() -> {
                FormDetector.Result shapes;
                try {
                    shapes = FormDetector.find(bmp, mmPx);
                } catch (Throwable e) {
                    shapes = null;
                }
                bmp.recycle();
                FormDetector.Result found = shapes;
                handler.post(safe(() -> {
                    if (!running) return;
                    Rect target = null;
                    int stage = -1;
                    if (found != null && !done[1] && !found.squares.isEmpty()) {
                        target = found.squares.get(0);
                        stage = 1;
                    }
                    if (target == null && !done[1] && agree != null) {
                        // No box drawn where we could see it: tap just left of the agreement text.
                        Rect r = new Rect();
                        agree.getBoundsInScreen(r);
                        done[1] = true;
                        int x = Math.max(0, r.left - mm(5)), y = Math.min(r.bottom, r.top + mm(3));
                        log("Tapping left of \"" + agree.getText() + "\" at " + x + "," + y);
                        showHighlight(null, null, null, x, y);
                        tap(x, y);
                        handler.postDelayed(safe(() -> formStep(done, scrollsLeft, summary)), 300);
                        return;
                    }
                    if (target == null) {
                        continueOrScroll(done, cont, scrollsLeft, summary);
                        return;
                    }
                    done[stage] = true;
                    log("Tapping the drawn " + FORM_STEPS[stage] + " at " + target.toShortString());
                    showHighlight(null, null, target, -1, -1);
                    tap(target.centerX(), target.centerY());
                    handler.postDelayed(safe(() -> formStep(done, scrollsLeft, summary)), 300);
                }));
            }).start();
        })), 80);
    }

    /** Presses Continue if it is on screen, else scrolls the page 10 mm and looks again. */
    private void continueOrScroll(boolean[] done, AccessibilityNodeInfo cont, int scrollsLeft,
                                  String summary) {
        if (cont != null) {
            for (int stage = 0; stage < 2; stage++) {
                if (!done[stage]) log("No " + FORM_STEPS[stage] + " found before Continue");
            }
            Rect r = new Rect();
            cont.getBoundsInScreen(r);
            log("Pressing Continue");
            showHighlight(null, null, r, -1, -1);
            tap(r.centerX(), r.centerY());
            stop(summary + ". Pressed Continue");
            return;
        }
        if (scrollsLeft <= 0) {
            stop(summary + ". Couldn't find the Continue button");
            return;
        }
        pageSwipe(true);
        handler.postDelayed(safe(() -> formStep(done, scrollsLeft - 1, summary)), 350);
    }

    /** Taps a radio button or checkbox like a finger (on its label if the box itself is tiny). */
    private void tick(AccessibilityNodeInfo n, String what) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() < mm(2) || r.height() < mm(2)) {
            // Hidden input: tap the surrounding control instead.
            for (AccessibilityNodeInfo p = n.getParent(); p != null; p = p.getParent()) {
                Rect pr = new Rect();
                p.getBoundsInScreen(pr);
                if (pr.width() >= mm(2) && pr.height() >= mm(2)) {
                    r = pr;
                    break;
                }
            }
        }
        log("Tapping the " + what + " at " + r.centerX() + "," + r.centerY());
        showHighlight(null, null, r, -1, -1);
        tap(r.centerX(), r.centerY());
    }

    /**
     * The top-most visible control for a form step: 0 radio button, 1 checkbox
     * (or an "I agree / accept / declare" item if there is no real checkbox),
     * 2 Continue.
     */
    private AccessibilityNodeInfo formNode(int stage) {
        Rect screen = screenBounds();
        AccessibilityNodeInfo best = null, fallback = null;
        int bestTop = Integer.MAX_VALUE, fallbackTop = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo root : roots()) {
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            stack.add(root);
            while (!stack.isEmpty()) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                if (n == null) continue;
                for (int i = 0; i < n.getChildCount(); i++) stack.add(n.getChild(i));
                if (!n.isVisibleToUser()) continue;
                CharSequence cls = n.getClassName();
                String c = cls == null ? "" : cls.toString();
                CharSequence t = n.getText();
                if (t == null || t.length() == 0) t = n.getContentDescription();
                String text = t == null ? "" : t.toString().trim().toLowerCase(Locale.ROOT);
                boolean hit = false, weak = false;
                if (stage == 0) {
                    // The slot marked "Available" (not "Not available" / "Unavailable").
                    hit = text.matches(".*\\bavailable\\b.*")
                            && !text.matches(".*\\b(not|un)\\s*available\\b.*")
                            && !text.contains("unavailable");
                    if (hit) {
                        AccessibilityNodeInfo clickable = clickableSelfOrParent(n);
                        // Prefer a tappable slot over plain text such as the colour legend.
                        if (clickable.isClickable()) {
                            n = clickable;
                        } else {
                            hit = false;
                            weak = true;
                        }
                    }
                } else if (stage == 1) {
                    hit = c.contains("CheckBox")
                            || (n.isCheckable() && !c.contains("Radio") && !c.contains("Switch"));
                    weak = !hit && text.matches(".*\\b(i hereby|hereby|i agree|agree|accept|declare|i confirm|undertake)\\b.*");
                } else {
                    hit = text.startsWith("continue");
                    if (hit) n = clickableSelfOrParent(n);
                }
                if ((!hit && !weak) || n == null) continue;
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.isEmpty() || r.bottom < 0 || r.top > screen.bottom) continue;
                if (r.top < 0 || r.bottom > screen.bottom) continue;
                if (hit && r.top < bestTop) {
                    best = n;
                    bestTop = r.top;
                } else if (weak && r.top < fallbackTop) {
                    fallback = n;
                    fallbackTop = r.top;
                }
            }
        }
        return best != null ? best : fallback;
    }

    private static AccessibilityNodeInfo clickableSelfOrParent(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo p = n; p != null; p = p.getParent()) {
            if (p.isClickable()) return p;
        }
        return n;
    }

    /** Runs {@code r}, turning any crash into a message instead of stopping the app. */
    private Runnable safe(Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable e) {
                fail(e);
            }
        };
    }

    private void fail(Throwable e) {
        setButtonVisible(true);
        stop("Error: " + e);
    }

    private void log(String line) {
        runLog.append(line).append('\n');
    }

    /** Millimetres in screen pixels. */
    private int mm(float mm) {
        return Math.round(getResources().getDisplayMetrics().ydpi / 25.4f * mm);
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
        handler.postDelayed(safe(() -> capture(bmp -> {
            if (bmp == null) {
                stop("Couldn't take a screenshot: " + screenshotError);
                return;
            }
            analyze(bmp, result -> {
                busy = false;
                showPreview(bmp, result);
            });
        })), 200);
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
            markup.tapX = hit.line.centerX();
            markup.tapY = hit.line.top - mm(4);
            markup.backupX = hit.line.centerX();
            markup.backupY = hit.line.bottom + mm(4);
            markup.caption = result.hits.size() + " dropdown(s) found!\n"
                    + "Red = underline, green = arrow.\n"
                    + "Pink = opening tap, 4 mm above the line.\n"
                    + "Blue dot = option tap, 4 mm below\n"
                    + "(used when no keywords are set).\n"
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

    private void tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        dispatchGesture(gesture, null, null);
    }

    private void swipe(int x1, int y1, int x2, int y2, long durationMs) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                .build();
        dispatchGesture(gesture, null, null);
    }

    /** Drags, then keeps the finger still for a moment before lifting so the list doesn't fling. */
    private void drag(int x1, int y1, int x2, int y2, long durationMs) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription move =
                new GestureDescription.StrokeDescription(path, 0, durationMs, true);
        dispatchGesture(new GestureDescription.Builder().addStroke(move).build(),
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        Path hold = new Path();
                        hold.moveTo(x2, y2);
                        dispatchGesture(new GestureDescription.Builder()
                                .addStroke(move.continueStroke(hold, 0, 150, false)).build(),
                                null, null);
                    }
                }, null);
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
