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
public class PickerService extends com.example.checkboxticker.CheckboxService {

    private static final long OPEN_WAIT_MS = 350;
    private static final int MAX_MONTH_CHANGES = 12;
    /** Draw outlines where the app taps. Off: taps happen without any marker. */
    private static final boolean SHOW_TAPS = false;
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
    private static final String[] STEP_LABELS = {"▼\nDrop", "📅☑\nCal", "☑\nCheck"};
    private TextView[] stepButtons;
    private static final String TICK_LABEL = "✔\nTick";
    private TextView tickButton;
    private static final String SHARE_OFF_LABEL = "📡\noff";
    private static final String SHARE_ON_LABEL = "📡\non";
    private TextView shareButton;
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
        super.onServiceConnected(); // the Checkbox Ticker part
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showButton();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        super.onAccessibilityEvent(event);
        updateShareButton();
        // Keep the Tick button in step when a ticker run ends by itself.
        if (tickButton != null) {
            String want = isLooping() ? "■\nStop" : TICK_LABEL;
            if (!want.contentEquals(tickButton.getText())) tickButton.setText(want);
        }
    }

    /** The ticker run ended; if it finished by itself (not Stop), go on to B+Del when chained. */
    @Override
    protected void onLoopStopped(String why) {
        if (tickButton != null) tickButton.setText(TICK_LABEL);
        if ("Stopped".equals(why) || !Keywords.loadChain(this, Keywords.CHAIN_TICK_BDEL)) return;
        handler.postDelayed(() -> {
            if (!running && !busy && !isLooping()) backScrollBack(true);
        }, 1000);
    }

    /** A row number at the start of a piece of text: "1", "2.", "(12)", "3) I agree ...". */
    private static final java.util.regex.Pattern ROW_NUMBER =
            java.util.regex.Pattern.compile("^\\(?(\\d{1,4})[.):]?(?:\\s.*)?$", java.util.regex.Pattern.DOTALL);

    /**
     * For the ticker's "number boxes by the number beside them" setting: the number printed on
     * the same horizontal line as each box. From the page's own text first; for boxes still
     * without one, from a screenshot read with text recognition.
     */
    @Override
    protected void rowNumbers(List<Rect> boxes, com.example.checkboxticker.CheckboxService.RowNumbersDone done) {
        List<Integer> labels = new ArrayList<>();
        List<Rect> where = new ArrayList<>();
        List<String> what = new ArrayList<>();
        for (TextNode t : texts(false)) {
            where.add(t.bounds);
            what.add(t.text);
        }
        boolean missing = false;
        for (Rect b : boxes) {
            Integer n = numberBeside(b, where, what);
            labels.add(n);
            if (n == null) missing = true;
        }
        if (!missing) {
            done.done(labels);
            return;
        }
        capture(bmp -> {
            if (bmp == null) {
                done.done(labels);
                return;
            }
            reader.readAll(bmp, 0, 0, lines -> safe(() -> {
                List<Rect> ocrWhere = new ArrayList<>();
                List<String> ocrWhat = new ArrayList<>();
                for (ScreenReader.Found f : lines) {
                    ocrWhere.add(f.box);
                    ocrWhat.add(f.text);
                }
                for (int i = 0; i < boxes.size(); i++) {
                    if (labels.get(i) == null) labels.set(i, numberBeside(boxes.get(i), ocrWhere, ocrWhat));
                }
                done.done(labels);
            }).run());
        });
    }

    /** The nearest number on exactly the same line as the box (left or right of it), or null. */
    private Integer numberBeside(Rect box, List<Rect> where, List<String> what) {
        int tolerance = Math.max(box.height() / 2, mm(1.5f));
        Integer best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int i = 0; i < where.size(); i++) {
            Rect r = where.get(i);
            if (Math.abs(r.centerY() - box.centerY()) > tolerance) continue; // not on the same line
            if (Rect.intersects(r, box)) continue;
            java.util.regex.Matcher m = ROW_NUMBER.matcher(what.get(i).trim());
            if (!m.matches()) continue;
            int gap = r.left >= box.right ? r.left - box.right : box.left - r.right;
            if (gap < bestGap) {
                bestGap = gap;
                best = Integer.parseInt(m.group(1));
            }
        }
        return best;
    }

    /** Our floating buttons and outlines, so the ticker never scans or taps them. */
    @Override
    protected List<Rect> extraOwnWindows() {
        List<Rect> out = new ArrayList<>();
        for (View v : new View[] {controls, highlight, preview}) {
            if (v == null || !v.isShown()) continue;
            int[] at = new int[2];
            v.getLocationOnScreen(at);
            out.add(new Rect(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight()));
        }
        return out;
    }

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
        controls.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable dock = new GradientDrawable();
        dock.setColor(0x661D1B26);
        dock.setCornerRadius(dp(34));
        controls.setBackground(dock);

        // One button per step, to test each on its own: Drop, Cal, Check (+ Continue), See.
        // Drop, and Cal + Check combined (date, fast 100 mm scroll, Available, checkbox, Continue).
        stepButtons = new TextView[] {
                roundButton(STEP_LABELS[STEP_DROP], 0xDD37474F, dp(52)),
                roundButton(STEP_LABELS[STEP_CAL], 0xDD1565C0, dp(52))};
        // Tick: runs the Checkbox Ticker (ticks every checkbox, scrolling down the page).
        tickButton = roundButton(TICK_LABEL, 0xDD00897B, dp(52));
        tickButton.setContentDescription("Tick the checkboxes");

        for (int i = 0; i < stepButtons.length; i++) {
            int step = i;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
            lp.bottomMargin = dp(6);
            controls.addView(stepButtons[i], lp);
            stepButtons[i].setOnTouchListener(new DragOrTap(() -> run(step), null));
        }
        controls.addView(tickButton, new LinearLayout.LayoutParams(dp(52), dp(52)));
        // Back twice, then delete the not-ticked rows by the dustbin on their line.
        backDeleteButton = roundButton(DELETE_LABEL, 0xDDAD1457, dp(52));
        backDeleteButton.setContentDescription("Back twice, then delete the not-ticked rows");
        backDeleteButton.setOnTouchListener(new DragOrTap(() -> {
            if (deleteRunning) endDelete("Stopped");
            else backScrollBack(true);
        }, null));
        LinearLayout.LayoutParams backDeleteLp = new LinearLayout.LayoutParams(dp(52), dp(52));
        backDeleteLp.topMargin = dp(6);
        controls.addView(backDeleteButton, backDeleteLp);
        // Small screen-share switch, usable right on the page.
        shareButton = roundButton(SHARE_OFF_LABEL, 0xFF5F5B6E, dp(40));
        shareButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        shareButton.setContentDescription("Screen sharing on or off");
        shareButton.setOnTouchListener(new DragOrTap(this::toggleShare, null));
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        shareLp.topMargin = dp(8);
        shareLp.gravity = Gravity.CENTER_HORIZONTAL;
        controls.addView(shareButton, shareLp);
        updateShareButton();
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
        tickButton.setOnTouchListener(new DragOrTap(this::toggleTicker, this::showWhatISee));
        windowManager.addView(controls, buttonParams);
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    private TextView roundButton(String text, int color, int size) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setShadowLayer(dp(2), 0, dp(1), 0x55000000);
        // A soft gradient disc with a thin light ring.
        int light = blend(color, 0xFFFFFFFF, 0.28f);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {light | 0xFF000000, color | 0xFF000000});
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(Math.max(1, dp(2)), 0x66FFFFFF);
        b.setBackground(bg);
        b.setElevation(dp(4));
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

    /** Tick button: start or stop the Checkbox Ticker's run. Long-press still shows See. */
    private void toggleTicker() {
        toggleLoop();
        handler.postDelayed(() -> tickButton.setText(isLooping() ? "■\nStop" : TICK_LABEL), 300);
    }

    /** A step button was tapped: run that step alone, or stop if something is running. */
    private void run(int step) {
        if (running) {
            stop("Stopped");
            return;
        }
        if (busy || deleteRunning) return;
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
        })), 60);
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Android 10 and older: only the shared screen can be read.
            Bitmap shared = sharedSnapshot();
            if (shared == null) {
                screenshotError = "this phone needs screen sharing - tap Start now";
                screenshotRefused();
            }
            done.accept(shared);
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap bmp = null;
                try {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    // Standard colours, at the size the tap coordinates use.
                    if (hw != null) {
                        bmp = com.example.checkboxticker.ScreenPictures.normalise(
                                PickerService.this, hw, 1);
                    }
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
                // A secure page or any other failure: fall back to the shared screen.
                if (errorCode != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    Bitmap shared = sharedSnapshot();
                    if (shared != null) {
                        safe(() -> done.accept(shared)).run();
                        return;
                    }
                }
                if (errorCode != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                        && errorCode != ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                    screenshotRefused();
                }
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

    private long lastShareAsk;

    private static final int BACK_SEARCH_SCROLLS = 20;
    private boolean backRunning;

    /**
     * Back button: tap the page's own "Back" button (scrolling down to it), wait for the other
     * page, scroll it down, and tap that page's "Back" button too - the phone's Back when a page
     * has none, so it always goes back twice. {@code thenDelete}: then Delete (the B+Del button).
     */
    private void backScrollBack(boolean thenDelete) {
        if (running || busy || backRunning || deleteRunning) return;
        backRunning = true;
        backDeleteButton.setAlpha(0.5f);
        String page1 = pageKey();
        tapPageBack(BACK_SEARCH_SCROLLS, first -> {
            // No Back button on the page: the phone's own Back instead, so it is always twice.
            if (!first) performGlobalAction(GLOBAL_ACTION_BACK);
            // Go on as soon as the other page is up (at most "Wait after Back").
            whenPageChanges(page1, Keywords.loadBackWait(this), () -> {
                int scrollMm = Keywords.loadBackScroll(this);
                if (scrollMm > 0) {
                    Rect screen = screenBounds();
                    int x = screen.centerX();
                    int from = screen.height() * 3 / 4;
                    int to = Math.max(mm(5), from - mm(scrollMm));
                    swipe(x, from, x, to, 250);
                }
                String page2 = pageKey();
                handler.postDelayed(() -> tapPageBack(BACK_SEARCH_SCROLLS, second -> {
                    if (!second) performGlobalAction(GLOBAL_ACTION_BACK);
                    endBack(null);
                    // Back twice lands on the list: delete the not-ticked rows there first.
                    if (thenDelete) {
                        whenPageChanges(page2, Keywords.loadBackWait(this),
                                () -> startDelete(this::afterBack));
                    } else {
                        afterBack();
                    }
                }), 300);
            });
        });
    }

    /** The page's text and where it is, to see when a new page has come up. */
    private String pageKey() {
        StringBuilder sb = new StringBuilder();
        for (TextNode t : texts(false)) sb.append(t.key()).append('|');
        return sb.toString();
    }

    /**
     * Runs {@code then} once the page differs from {@code before} and has held still for a
     * moment (a new page is up), or after {@code maxMs} at the latest.
     */
    private void whenPageChanges(String before, long maxMs, Runnable then) {
        long end = android.os.SystemClock.uptimeMillis() + Math.max(maxMs, 300);
        pollPage(before, null, end, then);
    }

    private void pollPage(String before, String last, long end, Runnable then) {
        handler.postDelayed(() -> {
            String now = pageKey();
            boolean settled = !now.equals(before) && now.equals(last) && !now.isEmpty();
            if (settled || android.os.SystemClock.uptimeMillis() >= end) then.run();
            else pollPage(before, now, end, then);
        }, 150);
    }

    /** Back (and any Delete after it) finished: go on to Drop when chained. */
    private void afterBack() {
        if (Keywords.loadChain(this, Keywords.CHAIN_BACK_DROP)) {
            handler.postDelayed(() -> {
                if (!running && !busy && !deleteRunning) run(STEP_DROP);
            }, 1000);
        }
    }

    private void endBack(String problem) {
        backRunning = false;
        backDeleteButton.setAlpha(1f);
        if (problem != null) Toast.makeText(this, problem, Toast.LENGTH_SHORT).show();
    }

    /** Finds the page's "Back" button (the lowest one on screen), scrolling down to it, and taps it. */
    private void tapPageBack(int scrollsLeft, Consumer<Boolean> done) {
        AccessibilityNodeInfo back = pageBackButton();
        if (back != null) {
            Rect r = new Rect();
            back.getBoundsInScreen(r);
            tap(r.centerX(), r.centerY());
            done.accept(true);
            return;
        }
        if (scrollsLeft <= 0) {
            done.accept(false);
            return;
        }
        Rect screen = screenBounds();
        int x = screen.centerX();
        int from = screen.height() * 4 / 5;
        swipe(x, from, x, screen.height() / 6, 150);
        handler.postDelayed(() -> tapPageBack(scrollsLeft - 1, done), 250);
    }

    /** A visible button whose text is "Back" (or "Go back" / "Previous"), lowest on screen. */
    private AccessibilityNodeInfo pageBackButton() {
        Rect screen = screenBounds();
        AccessibilityNodeInfo best = null;
        int bestTop = -1;
        for (AccessibilityNodeInfo root : roots()) {
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            stack.add(root);
            while (!stack.isEmpty()) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                if (n == null) continue;
                for (int i = 0; i < n.getChildCount(); i++) stack.add(n.getChild(i));
                if (!n.isVisibleToUser()) continue;
                CharSequence t = n.getText();
                if (t == null || t.length() == 0) t = n.getContentDescription();
                if (t == null) continue;
                String text = Keywords.norm(t.toString());
                if (!(text.equals("back") || text.equals("go back") || text.equals("previous")
                        || text.startsWith("back "))) continue;
                AccessibilityNodeInfo target = clickableSelfOrParent(n);
                Rect r = new Rect();
                target.getBoundsInScreen(r);
                if (r.isEmpty() || r.top < 0 || r.bottom > screen.bottom) continue;
                if (r.top > bestTop) {
                    best = target;
                    bestTop = r.top;
                }
            }
        }
        return best;
    }

    // ---- Delete the not-ticked rows -------------------------------------------

    private static final String DELETE_LABEL = "↩🗑\nB+Del";
    /** Looks (and scrolls) allowed to bring one row onto the screen. */
    private static final int DELETE_STEPS = 40;
    /** A card's number on its own: "2", "12.", "(3)", "#4" (OCR may read 1 as l, I or |, 0 as O). */
    private static final java.util.regex.Pattern CARD_NUMBER =
            java.util.regex.Pattern.compile("^[(#]?([0-9lI|O]{1,4})[.):]?$");
    /** Words on a "Delete?" dialog's confirm button, the likeliest first. */
    private static final List<String> CONFIRM_WORDS = java.util.Arrays.asList(
            "delete", "yes", "yes delete", "remove", "confirm", "ok");
    private TextView backDeleteButton;
    private boolean deleteRunning;
    /** Bumped on every start, so callbacks from a stopped run do nothing. */
    private int deleteGen;
    private int deleteSteps, deleteTries, deleted;
    /** The list as it is shown ("6, 7, 12"), deleted in that order; the next one is at [deleted]. */
    private final List<Integer> deleteQueue = new ArrayList<>();
    private String lastScreenKey;
    private Runnable afterDelete;

    /** A piece of text on screen (from the page itself or the screenshot); a card number if set. */
    private static final class Line {
        final Rect box;
        final String text;
        int number = -1;

        Line(Rect box, String text) {
            this.box = box;
            this.text = text;
        }
    }

    /** One look at the screen: its text, and its pixels when a screenshot was possible. */
    private static final class Seen {
        final List<Line> lines = new ArrayList<>();
        int[] px;
        int w, h;
    }

    /**
     * Deletes the not-ticked list exactly as it is shown ("6, 7, 12" - each row already less
     * the misses before it, so after 6 goes the old 7 is the new 6 and the list's own next
     * number is right): one number after another, find it on screen (scrolling to it), tap the
     * dustbin on its line, confirm, and take a new screenshot to check the card went, then move
     * on to the next number in the list. {@code then} runs after.
     */
    private void startDelete(Runnable then) {
        if (running || busy || backRunning || deleteRunning || isLooping()) return;
        List<Integer> rows = notTickedRows();
        if (rows.isEmpty()) {
            Toast.makeText(this, "Not-ticked list is empty - nothing to delete",
                    Toast.LENGTH_SHORT).show();
            if (then != null) then.run();
            return;
        }
        afterDelete = then;
        deleteRunning = true;
        deleteGen++;
        deleted = 0;
        deleteQueue.clear();
        for (int i = 0; i < rows.size(); i++) deleteQueue.add(rows.get(i) - i);
        deleteTries = 0;
        deleteSteps = 0;
        lastScreenKey = null;
        backDeleteButton.setText("■\nStop");
        showStatus("delete: list " + listText());
        handler.postDelayed(deleteStep(this::deleteNext), 300);
    }

    private void endDelete(String message) {
        if (!deleteRunning) return;
        deleteRunning = false;
        deleteGen++;
        backDeleteButton.setText(DELETE_LABEL);
        setButtonVisible(true);
        showStatus("delete: " + message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        Runnable then = afterDelete;
        afterDelete = null;
        if (then != null && !"Stopped".equals(message)) handler.postDelayed(then, 1000);
    }

    /** A step of this delete run: skipped once the run ends, and a crash ends the run. */
    private Runnable deleteStep(Runnable r) {
        int gen = deleteGen;
        return () -> {
            if (!deleteRunning || gen != deleteGen) return;
            try {
                r.run();
            } catch (Throwable e) {
                endDelete("Error: " + e);
            }
        };
    }

    private android.content.SharedPreferences tickPrefs() {
        return getSharedPreferences(com.example.checkboxticker.CheckboxService.PREFS, MODE_PRIVATE);
    }

    private int pref(String key, int fallback) {
        return Math.max(0, Math.min(10000, tickPrefs().getInt(key, fallback)));
    }

    private void deleteNext() {
        int target = deleteQueue.get(deleted);
        showStatus("delete " + target + " (" + (deleted + 1) + " of " + deleteQueue.size()
                + ") - list " + listText());
        readScreen(seen -> findRow(target, seen));
    }

    /** The list being deleted, the ones done ticked off: "✓6, ✓7, 12". */
    private String listText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < deleteQueue.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(i < deleted ? "✓" : "").append(deleteQueue.get(i));
        }
        return sb.toString();
    }

    /** Takes a screenshot (our buttons hidden) and reads it, together with the page's own text. */
    private void readScreen(Consumer<Seen> done) {
        setButtonVisible(false);
        handler.postDelayed(deleteStep(() -> capture(bmp -> deleteStep(() -> {
            setButtonVisible(true);
            Seen seen = new Seen();
            for (TextNode t : texts(false)) seen.lines.add(new Line(t.bounds, t.text));
            if (bmp == null) {
                done.accept(seen);
                return;
            }
            seen.w = bmp.getWidth();
            seen.h = bmp.getHeight();
            seen.px = new int[seen.w * seen.h];
            bmp.getPixels(seen.px, 0, seen.w, 0, 0, seen.w, seen.h);
            reader.readAll(bmp, 0, 0, lines -> deleteStep(() -> {
                for (ScreenReader.Found f : lines) seen.lines.add(new Line(f.box, f.text));
                done.accept(seen);
            }).run());
        }).run())), 150);
    }

    /** The row's number on screen and in reach: tap its bin. Otherwise scroll towards it. */
    private void findRow(int target, Seen seen) {
        Rect screen = screenBounds();
        List<Bin> numbers = binsOnScreen(seen);
        Bin hit = null;
        for (Bin b : numbers) if (b.number == target) hit = b;
        int safeTop = screen.height() / 8;
        int safeBottom = gestureTop() - mm(12);
        if (hit != null && hit.box.top >= safeTop && hit.box.bottom <= safeBottom) {
            deleteSteps = 0;
            lastScreenKey = null;
            tapBin(target, hit, seen);
            return;
        }
        String key = screenKey(seen);
        if (key.equals(lastScreenKey)) {
            endDelete("Row " + target + " not found - the page no longer scrolls");
            return;
        }
        lastScreenKey = key;
        if (++deleteSteps > DELETE_STEPS) {
            endDelete("Row " + target + " not found");
            return;
        }
        int dy; // > 0: bring what is further down the page up
        if (hit != null) {
            dy = hit.box.centerY() - screen.height() * 2 / 5;
        } else if (numbers.isEmpty() || numbers.get(numbers.size() - 1).number < target) {
            dy = screen.height() / 2;
        } else if (numbers.get(0).number > target) {
            dy = -screen.height() / 2;
        } else {
            dy = screen.height() / 4; // between the numbers seen but not read: nudge and look again
        }
        showStatus("delete: row " + target + " - scrolling " + (dy > 0 ? "down" : "up"));
        scrollPage(dy);
        handler.postDelayed(deleteStep(() -> readScreen(s -> findRow(target, s))),
                pref("scrollWaitMs", 300) + 400L);
    }

    /** A dustbin on screen, the number on its line (as the Tick reads it), and that number's text. */
    private static final class Bin {
        final Rect box;
        final int number;
        final Line row;

        Bin(Rect box, int number, Line row) {
            this.box = box;
            this.number = number;
            this.row = row;
        }
    }

    /**
     * Every dustbin on screen, top to bottom, each with its number found the way the Tick
     * numbers its boxes ({@link #numberBeside}): the nearest number on exactly the same
     * horizontal line as the bin, from the page's own text and the screenshot's.
     */
    private List<Bin> binsOnScreen(Seen seen) {
        List<Rect> where = new ArrayList<>();
        List<String> what = new ArrayList<>();
        for (Line l : seen.lines) {
            where.add(l.box);
            what.add(l.text);
        }
        List<Bin> out = new ArrayList<>();
        // A bin sits on a card's number line: look along each line that starts with a number.
        for (Line row : cardNumbers(seen)) {
            Rect bin = binBeside(row, seen);
            if (bin == null) continue;
            Integer n = numberBeside(bin, where, what);
            if (n == null || n <= 0) continue;
            boolean twice = false;
            for (Bin o : out) if (o.number == n || Rect.intersects(o.box, bin)) twice = true;
            if (!twice) out.add(new Bin(bin, n, row));
        }
        out.sort((a, b) -> Integer.compare(a.box.top, b.box.top));
        return out;
    }

    /** The card numbers on screen, top to bottom: a number on its own in the left part. */
    private List<Line> cardNumbers(Seen seen) {
        Rect screen = screenBounds();
        List<Line> out = new ArrayList<>();
        for (Line l : seen.lines) {
            java.util.regex.Matcher m = CARD_NUMBER.matcher(l.text.trim());
            if (!m.matches() || !m.group(1).matches(".*[0-9].*")) continue;
            if (l.box.centerX() > screen.width() * 2 / 5) continue;
            if (l.box.top < screen.height() / 20 || l.box.bottom > gestureTop()) continue;
            String digits = m.group(1).replace('l', '1').replace('I', '1').replace('|', '1')
                    .replace('O', '0');
            int n = Integer.parseInt(digits);
            if (n <= 0) continue;
            boolean twice = false; // the page's text and the screenshot both found it
            for (Line o : out) {
                if (o.number == n && Math.abs(o.box.centerY() - l.box.centerY()) < mm(4)) twice = true;
            }
            if (twice) continue;
            l.number = n;
            out.add(l);
        }
        out.sort((a, b) -> Integer.compare(a.box.top, b.box.top));
        return out;
    }

    /** What is on screen, to tell when a scroll moved nothing (the end of the page). */
    private static String screenKey(Seen seen) {
        List<String> parts = new ArrayList<>();
        for (Line l : seen.lines) parts.add(l.text + "@" + l.box.top / 8);
        java.util.Collections.sort(parts);
        return String.join("|", parts);
    }

    /** Taps the dustbin on the number's line, then deals with a confirm dialog. */
    private void tapBin(int target, Bin hit, Seen seen) {
        Rect bin = hit.box;
        Set<String> before = cardText(hit.row, seen);
        List<Line> confirmsBefore = confirmLines(seen.lines);
        tapThrough(bin.centerX(), bin.centerY());
        showStatus("delete: row " + target + " - bin tapped");
        handler.postDelayed(deleteStep(() -> confirm(target, before, confirmsBefore, 3)),
                pref("delWaitMs", 800));
    }

    /**
     * The dustbin right of the number on the same line: a node that says delete / bin / trash,
     * else the biggest icon in the screenshot on that line (the bin, not the thin arrow).
     */
    private Rect binBeside(Line number, Seen seen) {
        Rect screen = screenBounds();
        int half = Math.max(number.box.height(), mm(4));
        int cy = number.box.centerY();
        for (TextNode t : texts(false)) {
            String raw = t.text;
            String w = Keywords.norm(raw);
            boolean named = raw.contains("🗑") || w.equals("delete") || w.contains("trash")
                    || w.equals("bin") || w.contains("dustbin") || w.equals("remove")
                    || w.startsWith("delete ");
            if (!named || Math.abs(t.bounds.centerY() - cy) > half) continue;
            if (t.bounds.left <= number.box.right || t.bounds.width() > screen.width() / 4) continue;
            return t.bounds;
        }
        if (seen.px == null) return null;
        int x0 = Math.max(number.box.right + mm(8), seen.w * 2 / 5);
        int x1 = seen.w * 97 / 100; // not the scroll bar at the edge
        int[] r = BinFinder.find(seen.px, seen.w, seen.h, cy - half, cy + half, x0, x1, mm(1));
        return r == null ? null : new Rect(r[0], r[1], r[2], r[3]);
    }

    /**
     * The text of the card under a number (name, date of birth, ...), down to the next card's
     * number, so a new screenshot can tell whether that card is still there.
     */
    private Set<String> cardText(Line number, Seen seen) {
        int bottom = number.box.bottom + mm(30);
        for (Line n : cardNumbers(seen)) {
            if (n.box.top > number.box.bottom) bottom = Math.min(bottom, n.box.top);
        }
        Set<String> out = new HashSet<>();
        for (Line l : seen.lines) {
            if (l.box.top < number.box.bottom - mm(1) || l.box.top >= bottom) continue;
            if (CARD_NUMBER.matcher(l.text.trim()).matches()) continue;
            String t = Keywords.norm(l.text);
            if (t.length() >= 4) out.add(t);
        }
        return out;
    }

    /** Most of the text is the same: the same card. */
    private static boolean sameCard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        int common = 0;
        for (String s : a) if (b.contains(s)) common++;
        return common >= Math.max(1, (Math.min(a.size(), b.size()) + 1) / 2);
    }

    private static List<Line> confirmLines(List<Line> lines) {
        List<Line> out = new ArrayList<>();
        for (Line l : lines) if (CONFIRM_WORDS.contains(Keywords.norm(l.text))) out.add(l);
        return out;
    }

    /** A confirm button that was not on the page before the bin tap, the likeliest word first. */
    private Line newConfirm(List<Line> lines, List<Line> before) {
        Line best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Line l : confirmLines(lines)) {
            String w = Keywords.norm(l.text);
            boolean old = false;
            for (Line b : before) {
                if (Keywords.norm(b.text).equals(w)
                        && Math.abs(b.box.centerX() - l.box.centerX()) < mm(3)
                        && Math.abs(b.box.centerY() - l.box.centerY()) < mm(3)) old = true;
            }
            int rank = CONFIRM_WORDS.indexOf(w);
            if (!old && rank < bestRank) {
                best = l;
                bestRank = rank;
            }
        }
        return best;
    }

    /**
     * After the bin tap (and a wait so the dialog has fully come up): tap the dialog's confirm
     * button if one came up, then check the card went. The page's own text is tried first; a
     * screenshot when that has no button.
     */
    private void confirm(int target, Set<String> before, List<Line> confirmsBefore, int looksLeft) {
        List<Line> tree = new ArrayList<>();
        for (TextNode t : texts(false)) tree.add(new Line(t.bounds, t.text));
        Line button = newConfirm(tree, confirmsBefore);
        if (button != null) {
            confirmTapped(target, before, button);
            return;
        }
        readScreen(seen -> {
            Line b = newConfirm(seen.lines, confirmsBefore);
            if (b != null) {
                confirmTapped(target, before, b);
            } else if (gone(before, seen)) {
                rowDeleted(target, seen); // deleted straight away, no dialog
            } else if (looksLeft > 1) {
                handler.postDelayed(deleteStep(() ->
                        confirm(target, before, confirmsBefore, looksLeft - 1)), 300);
            } else {
                verify(target, before, 2);
            }
        });
    }

    private void confirmTapped(int target, Set<String> before, Line button) {
        tapThrough(button.box.centerX(), button.box.centerY());
        showStatus("delete: row " + target + " - tapped " + button.text);
        handler.postDelayed(deleteStep(() -> verify(target, before, 3)), pref("delCheckMs", 900));
    }

    /** A new screenshot: the card must be gone. If not, look again, then tap its bin again. */
    private void verify(int target, Set<String> before, int looksLeft) {
        readScreen(seen -> {
            if (gone(before, seen)) {
                rowDeleted(target, seen);
            } else if (looksLeft > 1) {
                handler.postDelayed(deleteStep(() -> verify(target, before, looksLeft - 1)), 600);
            } else if (++deleteTries < 2) {
                showStatus("delete: row " + target + " still there - trying again");
                findRow(target, seen);
            } else {
                endDelete("Row " + target + " did not go away - stopped");
            }
        });
    }

    /** No card on screen has the deleted card's text any more. */
    private boolean gone(Set<String> before, Seen seen) {
        if (before.isEmpty()) return true; // nothing to compare: trust the tap
        for (Line n : cardNumbers(seen)) {
            if (sameCard(before, cardText(n, seen))) return false;
        }
        return true;
    }

    /** The row went: every later row moves up one, so the list does too. Then the next row. */
    private void rowDeleted(int target, Seen seen) {
        deleted++;
        deleteTries = 0;
        // Keep the saved list to the ones not deleted yet, so a stopped run carries on from
        // there. The rows after the deleted one moved up one; what the list shows stays the same.
        List<Integer> rows = new ArrayList<>();
        for (int r : notTickedRows()) {
            if (r < target) rows.add(r);
            else if (r > target) rows.add(r - 1);
        }
        replaceNotTickedRows(deleted >= deleteQueue.size() ? new ArrayList<>() : rows);
        if (deleted >= deleteQueue.size()) {
            endDelete("Deleted " + listText() + " - done");
        } else if (deleted >= Math.max(1, tickPrefs().getInt("maxDeletes", 20))) {
            endDelete("Stopped after " + deleted + " deletes");
        } else {
            int next = deleteQueue.get(deleted);
            showStatus("delete: " + target + " deleted - next " + next + " (" + (deleted + 1)
                    + " of " + deleteQueue.size() + ") - list " + listText());
            findRow(next, seen); // this screenshot already shows the page as it is now
        }
    }

    /** Taps, letting the tap through our own buttons when they sit on that spot. */
    private void tapThrough(int x, int y) {
        Rect ours = null;
        if (controls != null && controls.isShown()) {
            int[] at = new int[2];
            controls.getLocationOnScreen(at);
            ours = new Rect(at[0], at[1], at[0] + controls.getWidth(), at[1] + controls.getHeight());
        }
        if (ours == null || !ours.contains(x, y)) {
            tap(x, y);
            return;
        }
        buttonParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        windowManager.updateViewLayout(controls, buttonParams);
        handler.postDelayed(() -> {
            tap(x, y);
            handler.postDelayed(() -> {
                buttonParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                if (controls != null) windowManager.updateViewLayout(controls, buttonParams);
            }, 300);
        }, 100);
    }

    /** Moves the page by {@code dy} pixels (> 0 shows what is further down), without a fling. */
    private void scrollPage(int dy) {
        Rect screen = screenBounds();
        int x = screen.centerX();
        int dist = Math.max(mm(8), Math.min(Math.abs(dy), screen.height() * 3 / 5));
        if (dy > 0) {
            int from = screen.height() * 3 / 4;
            drag(x, from, x, from - dist, 450);
        } else {
            int from = screen.height() / 5;
            drag(x, from, x, from + dist, 450);
        }
    }

    /** Where the system's gesture / navigation strip starts: never tapped. */
    private int gestureTop() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        int bar = id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        return screenBounds().height() - Math.max(bar, dp(24)) - dp(16);
    }

    /** Shows the share-your-screen prompt over the current page (no page change). */
    private void askShare() {
        startActivity(new Intent(this, ShareActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /** Share button: switch screen sharing on (prompt over the page) or off. */
    private void toggleShare() {
        if (com.example.checkboxticker.ScreenService.Companion.getInstance() != null) {
            stopService(new Intent(this, com.example.checkboxticker.ScreenService.class));
            Toast.makeText(this, "Screen sharing off", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::updateShareButton, 400);
        } else {
            askShare();
        }
    }

    private void updateShareButton() {
        if (shareButton == null) return;
        boolean on = com.example.checkboxticker.ScreenService.Companion.getInstance() != null;
        String want = on ? SHARE_ON_LABEL : SHARE_OFF_LABEL;
        if (!want.contentEquals(shareButton.getText())) {
            shareButton.setText(want);
            shareButton.setBackground(shareDisc(on));
        }
    }

    private GradientDrawable shareDisc(boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(on ? 0xFF2E9E62 : 0xFF5F5B6E);
        bg.setStroke(Math.max(1, dp(2)), 0x66FFFFFF);
        return bg;
    }

    /**
     * This phone won't give an accessibility screenshot (Android 10 or older, or the maker
     * blocks it) and screen sharing is off: open the Share screen prompt, at most every 20 s.
     */
    @Override
    protected void screenshotRefused() {
        if (com.example.checkboxticker.ScreenService.Companion.getInstance() != null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastShareAsk < 20000) return;
        lastShareAsk = now;
        Toast.makeText(this, "This phone needs screen sharing - tap Start now",
                Toast.LENGTH_LONG).show();
        askShare();
    }

    /** The latest picture from the shared screen (Checkbox Ticker's screen reading), or null. */
    private static Bitmap sharedSnapshot() {
        com.example.checkboxticker.ScreenService screen =
                com.example.checkboxticker.ScreenService.Companion.getInstance();
        return screen == null ? null : screen.snapshot();
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

        // The page's text only says where to scroll; the tap itself always goes where the option
        // is actually seen in a screenshot (below), never at a position that may be hidden
        // behind the list.
        if (match != null && !inView(match)) {
            if (checksLeft > 0) {
                // The page knows the match is further down the list: bring it into view, fast.
                log("\"" + match.text + "\" is out of view, bringing it into view");
                match.node.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                handler.postDelayed(safe(() -> search(t, before, keywords, exact, checksLeft - 1,
                        scrollsLeft, stuck, lastSeen)), 200);
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
                scrollsLeft - 1, nowStuck, seen)), 550);
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
        })), 50);
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
        Rect screen = screenBounds();
        for (AccessibilityNodeInfo p = o.node.getParent(); p != null; p = p.getParent()) {
            if (!p.isScrollable()) continue;
            Rect r = new Rect();
            p.getBoundsInScreen(r);
            // Only a list box (not the whole page) can hide an option by scrolling.
            if ((long) r.width() * r.height() > (long) screen.width() * screen.height() / 2) return true;
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
            stop(message);
            if (Keywords.loadChain(this, Keywords.CHAIN_DROP_CAL)) {
                handler.postDelayed(() -> {
                    if (!running && !busy) run(STEP_CAL);
                }, 1000);
            }
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
                stepMode == STEP_CAL ? 0 : 300);
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
            handler.postDelayed(safe(() -> calendarStep(date, mode, changesLeft, scrollsLeft - 1)), 250);
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
            handler.postDelayed(safe(() -> calendarStep(date, mode, changesLeft, scrollsLeft - 1)), 250);
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
            handler.postDelayed(safe(() -> calendarStep(date, mode, changesLeft - 1, scrollsLeft)), 400);
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
        })), 60);
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
        if (down) swipe(x, y, x, y - mm(10), 150);
        else swipe(x, y - mm(10), x, y, 150);
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
        if (stepMode == STEP_CAL) {
            // Cal + Check button: after the date, a fast 100 mm scroll down, then Check.
            handler.postDelayed(safe(() -> {
                Rect screen = screenBounds();
                int x = screen.centerX();
                int from = screen.bottom - mm(8);
                int to = Math.max(mm(5), from - mm(100));
                log("Fast scroll down " + (from - to) + " px (100 mm)");
                swipe(x, from, x, to, 150);
                handler.postDelayed(safe(() -> formStep(new boolean[3], FORM_SCROLLS, message)), 450);
            }), 250);
            return;
        }
        if (!Keywords.loadFinish(this)) {
            stop(message);
            return;
        }
        // Date picked: a quick 10 mm scroll down, then the Check step.
        handler.postDelayed(safe(() -> {
            log("Quick 10 mm scroll, then Check");
            pageSwipe(true);
            handler.postDelayed(safe(() -> formStep(new boolean[3], FORM_SCROLLS, message)), 250);
        }), 300);
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
                        handler.postDelayed(safe(() -> formStep(done, scrollsLeft, summary)), 200);
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
                    handler.postDelayed(safe(() -> formStep(done, scrollsLeft, summary)), 200);
                }));
            }).start();
        })), 50);
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
            if (Keywords.loadChain(this, Keywords.CHAIN_CAL_TICK)) {
                // Next page loads, then the ticker takes over by itself.
                handler.postDelayed(() -> {
                    if (!running && !isLooping()) {
                        startLoop();
                        if (tickButton != null) tickButton.setText(isLooping() ? "■\nStop" : TICK_LABEL);
                    }
                }, 1000);
            }
            return;
        }
        if (scrollsLeft <= 0) {
            stop(summary + ". Couldn't find the Continue button");
            return;
        }
        pageSwipe(true);
        handler.postDelayed(safe(() -> formStep(done, scrollsLeft - 1, summary)), 220);
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
        List<TextNode> availables = new ArrayList<>();
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
                    // The second one from the top (see below); tapped on the word itself.
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
                if (hit && stage == 0) availables.add(new TextNode(n, r, text));
                if (hit && r.top < bestTop) {
                    best = n;
                    bestTop = r.top;
                } else if (weak && r.top < fallbackTop) {
                    fallback = n;
                    fallbackTop = r.top;
                }
            }
        }
        if (stage == 0 && availables.size() >= 2) {
            // Take the second "Available" from the top (the first is usually not the slot).
            availables.sort((a, b) -> a.bounds.top != b.bounds.top
                    ? Integer.compare(a.bounds.top, b.bounds.top)
                    : Integer.compare(a.bounds.left, b.bounds.left));
            return availables.get(1).node;
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
        if (!SHOW_TAPS) return; // taps are not shown on screen
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
            if (com.example.checkboxticker.ScreenService.Companion.getInstance() == null) {
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
