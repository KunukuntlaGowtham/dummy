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
    private static final int MAX_SCROLLS = 25;

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
        runLog.setLength(0);
        log("Start");
        findAndOpen();
    }

    private void stop(String message) {
        if (message != null) log(message);
        Keywords.saveLastRun(this, runLog.toString());
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
        handler.postDelayed(safe(() -> capture(bmp -> {
            if (bmp == null) {
                setButtonVisible(true);
                log("No screenshot (" + screenshotError + "), looking for native dropdowns");
                open(nodeTargets());
                return;
            }
            analyze(bmp, result -> {
                bmp.recycle();
                setButtonVisible(true);
                List<Target> targets = new ArrayList<>();
                for (DropdownDetector.Hit h : result.hits) {
                    // Open it with one tap 4 mm above the line (not on the arrow).
                    targets.add(new Target(h.line, h.arrow, h.line.centerX(),
                            h.line.top - mm(4), null));
                }
                log("Screenshot " + result.width + "x" + result.height + ": "
                        + result.lines.size() + " long line(s), " + targets.size() + " dropdown(s)");
                // Nothing seen in the picture: try native dropdown widgets instead.
                open(targets.isEmpty() ? nodeTargets() : targets);
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
    }

    /** Taps the dropdown once, then picks an option. */
    private void openAndPick(Target t) {
        if (!running) return;
        // Remember the text already on screen; the options are text that shows up after opening.
        Set<String> before = new HashSet<>();
        for (TextNode n : texts()) before.add(n.text);

        boolean clicked = t.node != null && t.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) tap(t.tapX, t.tapY);

        List<String> keywords = Keywords.list(this);
        if (keywords.isEmpty()) {
            // No keywords: the first option sits just below the line.
            handler.postDelayed(safe(() -> tapBelow(t)), OPEN_WAIT_MS);
        } else {
            handler.postDelayed(safe(() -> search(t, before, keywords, OPEN_CHECKS, MAX_SCROLLS, null)),
                    OPEN_WAIT_MS);
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
     * Looks for an option containing one of the keywords (earliest keyword
     * wins) and taps it. If none is visible, scrolls the dropdown and looks
     * again, until the list stops moving.
     */
    private void search(Target t, Set<String> before, List<String> keywords,
                        int checksLeft, int scrollsLeft, String lastSeen) {
        if (!running) return;
        List<TextNode> options = options(t, before);

        for (String k : keywords) {
            for (TextNode o : options) {
                if (o.text.toLowerCase(java.util.Locale.ROOT).contains(k)) {
                    log("Found \"" + o.text + "\" for keyword \"" + k + "\"");
                    showHighlight(null, null, o.bounds, -1, -1);
                    tap(o.bounds.centerX(), o.bounds.centerY());
                    afterDropdown("Selected: " + o.text + " (keyword \"" + k + "\")");
                    return;
                }
            }
        }

        if (options.isEmpty() && lastSeen == null && checksLeft > 1) {
            // The list may still be opening.
            handler.postDelayed(safe(() -> search(t, before, keywords, checksLeft - 1,
                    scrollsLeft, null)), 300);
            return;
        }

        StringBuilder seenBuilder = new StringBuilder();
        for (TextNode o : options) seenBuilder.append(o.text).append('|');
        String seen = seenBuilder.toString();
        log("Visible options: " + options.size()
                + (options.isEmpty() ? "" : " (" + options.get(0).text + " ... "
                + options.get(options.size() - 1).text + ")"));
        if (options.isEmpty() || seen.equals(lastSeen) || scrollsLeft <= 0) {
            afterDropdown("No option matching your keywords found" + (options.isEmpty()
                    ? " (couldn't read the options)" : " (reached the end of the list)"));
            return;
        }

        // Scroll the list up by 10 mm, just below the line, and look again.
        int x = t.line.centerX();
        int from = t.line.bottom + mm(12);
        int to = t.line.bottom + mm(2);
        log("Swiping the list up 10 mm (" + from + " -> " + to + ")");
        swipe(x, from, x, to);
        handler.postDelayed(safe(() -> search(t, before, keywords, checksLeft,
                scrollsLeft - 1, seen)), 450);
    }

    /** Text that appeared after the dropdown opened, in the dropdown's columns, top to bottom. */
    private List<TextNode> options(Target t, Set<String> before) {
        List<TextNode> out = new ArrayList<>();
        for (TextNode n : texts()) {
            if (before.contains(n.text)) continue;
            if (n.bounds.contains(t.tapX, t.tapY)) continue;
            if (n.bounds.right < t.line.left || n.bounds.left > t.line.right) continue;
            out.add(n);
        }
        out.sort((a, b) -> Integer.compare(a.bounds.top, b.bounds.top));
        return out;
    }

    /** Every piece of visible text on screen (a node's own text or description). */
    private List<TextNode> texts() {
        List<TextNode> out = new ArrayList<>();
        for (AccessibilityNodeInfo root : roots()) {
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            stack.add(root);
            while (!stack.isEmpty()) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                if (n == null) continue;
                CharSequence txt = n.getText();
                if (txt == null || txt.length() == 0) txt = n.getContentDescription();
                if (txt != null && n.isVisibleToUser()) {
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
        log(message);
        int mode = DayChoice.loadMode(this);
        if (mode == DayChoice.MODE_OFF) {
            stop(message);
            return;
        }
        LocalDate date = DayChoice.parse(DayChoice.loadDate(this));
        if (date == null) {
            stop(message + ". Calendar skipped: enter the day as DD/MM/YYYY in the app");
            return;
        }
        log("Calendar: want " + date + (mode == DayChoice.MODE_EXACT ? " (exact)" : " (or best)"));
        handler.postDelayed(safe(() -> calendarStep(date, mode, MAX_MONTH_CHANGES)), 500);
    }

    /** Moves the calendar to the wanted month, then reads the day colours and taps a day. */
    private void calendarStep(LocalDate date, int mode, int changesLeft) {
        if (!running) return;
        List<TextNode> all = texts();
        List<TextNode> headers = new ArrayList<>();
        for (TextNode n : all) if (DayChoice.monthOf(n.text) >= 0) headers.add(n);
        if (headers.isEmpty()) {
            stop("Couldn't find the calendar's month name (like \"October 2026\")");
            return;
        }
        headers.sort((a, b) -> Integer.compare(a.bounds.top, b.bounds.top));
        int want = DayChoice.monthOf(date);

        TextNode header = null;
        for (TextNode h : headers) if (DayChoice.monthOf(h.text) == want) header = h;
        if (header == null) {
            TextNode shown = headers.get(0);
            int diff = want - DayChoice.monthOf(shown.text);
            if (changesLeft <= 0) {
                stop("Couldn't reach " + date.getMonth() + " " + date.getYear() + " in the calendar");
                return;
            }
            Rect grid = gridBounds(days(shown, headers, all));
            log("Calendar shows " + shown.text + ", tapping " + (diff > 0 ? "next" : "previous"));
            tapArrow(shown, grid, diff > 0);
            handler.postDelayed(safe(() -> calendarStep(date, mode, changesLeft - 1)), 600);
            return;
        }

        TextNode[] days = days(header, headers, all);
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
        if (DayChoice.isOpen(colours[want])) {
            pick = want;
        } else if (mode == DayChoice.MODE_BEST) {
            // Nearest open day; on a tie, the earlier one.
            for (int dist = 1; dist <= 31 && pick < 0; dist++) {
                for (int d : new int[] {want - dist, want + dist}) {
                    if (d >= 1 && d <= 31 && days[d] != null && DayChoice.isOpen(colours[d])) {
                        pick = d;
                        break;
                    }
                }
            }
        }
        if (pick < 0) {
            stop("Day " + want + " is " + colours[want].toLowerCase(Locale.ROOT)
                    + (mode == DayChoice.MODE_BEST ? " and no green or yellow day this month"
                    : ", not available"));
            return;
        }
        TextNode cell = days[pick];
        showHighlight(null, null, cell.bounds, -1, -1);
        if (!cell.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            tap(cell.bounds.centerX(), cell.bounds.centerY());
        }
        stop(pick == want ? "Selected day " + pick + " (" + colours[pick].toLowerCase(Locale.ROOT) + ")"
                : "Day " + want + " is " + colours[want].toLowerCase(Locale.ROOT)
                + ", selected nearest open day " + pick + " ("
                + colours[pick].toLowerCase(Locale.ROOT) + ")");
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

    private void swipe(int x1, int y1, int x2, int y2) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 400))
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
