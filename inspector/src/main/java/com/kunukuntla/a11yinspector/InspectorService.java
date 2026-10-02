package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Floating buttons over any app, all working through accessibility (no screenshots):
 * <ul>
 *   <li>🔍 Scan - tap: scan the screen now; long press: deep scan (wake the web views first,
 *       then every property of every element and the app's own details too);</li>
 *   <li>☑ Tick - ticks every empty checkbox, clearing the pop-up after each;</li>
 *   <li>✖ Clear - clears the pop-ups up now (presses their OK / Close);</li>
 *   <li>📅 Book - picks the dropdown option, the date in the calendar, the checkbox, the radio
 *       button, then Continue.</li>
 * </ul>
 * Results show in a card over the page and are kept for the app's own screen (Share / Copy / Save).
 */
public class InspectorService extends AccessibilityService {

    static final String REPORT_FILE = "last_scan.txt";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private TextView button;
    private WindowManager.LayoutParams buttonParams;
    private View card;
    private boolean scanning;

    @Override
    protected void onServiceConnected() {
        windowManager = getSystemService(WindowManager.class);
        showButton();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence evPkg = event.getPackageName();
        if (autoClear && evPkg != null && !getPackageName().contentEquals(evPkg)) {
            int t = event.getEventType();
            if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                autoCheckSoon();
            }
        }
        if (evPkg != null && !getPackageName().contentEquals(evPkg)) {
            // Which screen of which app is open (for the app details), and the event recorder.
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getClassName() != null
                    && !"com.android.systemui".contentEquals(evPkg)) {
                openScreen.put(evPkg.toString(), event.getClassName().toString());
            }
            if (recorder.on && evPkg.toString().equals(recordPkg)) recorder.add(event);
        }
        // Short messages (toasts) the page shows while Book runs: its errors.
        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                && booker != null && booker.isRunning()
                && !getPackageName().contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) {
            StringBuilder sb = new StringBuilder();
            for (CharSequence t : event.getText()) sb.append(t).append(' ');
            booker.toast(sb.toString());
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        closeCard();
        closeAsk();
        if (ticker != null) ticker.stop("Stopped");
        if (missedBox != null) missedBox.hide();
        if (clearer != null) clearer.stop("Stopped");
        if (booker != null) booker.stop("Stopped");
        cancelLinkedTick();
        getSharedPreferences("settings", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(shownListener);
        for (View v : new View[] {button, tickButton, clearButton, bookButton, goButton, reportButton, linkButton}) {
            if (v == null) continue;
            try {
                windowManager.removeView(v);
            } catch (RuntimeException ignored) {
            }
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- the floating button ------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private void showButton() {
        button = new TextView(this);
        button.setText("🔍\nScan");
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xEE2E7D32);
        bg.setStroke(dp(2), 0x66FFFFFF);
        button.setBackground(bg);
        button.setElevation(dp(4));

        buttonParams = new WindowManager.LayoutParams(dp(56), dp(56),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        buttonParams.gravity = Gravity.TOP | Gravity.START;
        buttonParams.x = dp(12);
        buttonParams.y = dp(160);

        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        float[] down = new float[2];
        int[] start = new int[2];
        boolean[] dragged = {false}, longDone = {false};
        Runnable onLong = () -> {
            longDone[0] = true;
            scan(true);
        };
        button.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    start[0] = buttonParams.x;
                    start[1] = buttonParams.y;
                    dragged[0] = false;
                    longDone[0] = false;
                    handler.postDelayed(onLong, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (!dragged[0] && Math.hypot(dx, dy) > slop) {
                        dragged[0] = true;
                        handler.removeCallbacks(onLong);
                    }
                    if (dragged[0]) {
                        buttonParams.x = start[0] + (int) dx;
                        buttonParams.y = start[1] + (int) dy;
                        windowManager.updateViewLayout(button, buttonParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(onLong);
                    if (!dragged[0] && !longDone[0]) scan(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(onLong);
                    return true;
                default:
                    return false;
            }
        });
        windowManager.addView(button, buttonParams);
        showTickButton();
    }

    // ---- Tick, Clear and Book --------------------------------------------------------

    private TextView tickButton, clearButton, bookButton, goButton;
    private Ticker ticker, clearer;
    private Booker booker;
    /** The rows not added in the Tick run, renumbered as after removing the earlier ones. */
    private MissedBox missedBox;
    private View ask;

    private void showTickButton() {
        missedBox = new MissedBox(this, windowManager);
        ticker = new Ticker(this, new Ticker.Listener() {
            @Override
            public void done(String summary, String log) {
                saveReport(log);
                tickButton.setText("☑\nTick");
                finished(summary);
            }

            @Override
            public void notAdded(java.util.List<String> rows) {
                // Live, in the corner: stays after the run, until the next Tick run starts.
                missedBox.show(rows);
            }
        });
        tickButton = floating("☑\nTick", 0xEE6A2C91, 226, v -> {
            closeCard();
            if (ticker.isRunning()) {
                ticker.stop("Stopped");
            } else if (!busy()) {
                tickButton.setText("■\nStop");
                ticker.start();
            }
        });
        clearer = new Ticker(this, (summary, log) -> {
            saveReport(log);
            clearButton.setText("✖\nClear");
            finished(summary);
        });
        clearButton = floating("✖\nClear", 0xEEE65100, 292, v -> {
            closeCard();
            if (clearer.isRunning()) {
                clearer.stop("Stopped");
            } else if (!busy()) {
                clearButton.setText("■\nStop");
                clearer.clearNow();
            }
        }, v -> toggleAutoClear());
        booker = new Booker(this, (summary, log) -> {
            saveReport(log);
            bookButton.setText("📅\nBook");
            goButton.setText("▶\nGo");
            finished(summary);
            if (linkOn && booker.handedOver()) tickAfterGo();
        });
        // Book: choose what to fill (from the page's own dropdown list) and save it.
        bookButton = floating("📅\nBook", 0xEE1565C0, 358, v -> {
            closeCard();
            if (booker.isRunning()) booker.stop("Stopped");
            else if (!busy()) {
                Toast.makeText(this, "Reading the dropdown's options…", Toast.LENGTH_SHORT).show();
                Booker.options(this, handler, this::askBooking);
            }
        });
        // Go: fill the page straight away with the saved choices - no form, no reading first.
        goButton = floating("▶\nGo", 0xEE00897B, 424, v -> {
            closeCard();
            if (pendingTick != null) {
                cancelLinkedTick(); // Stop in the 1 s gap: Tick doesn't start
                Toast.makeText(this, "Tick after Go cancelled", Toast.LENGTH_SHORT).show();
            } else if (booker.isRunning()) booker.stop("Stopped");
            else if (!busy()) startSaved();
        });
        showReportButton();
        showLinkButton();
        applyShown();
        getSharedPreferences("settings", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(shownListener);
    }

    // ---- 🔗 Link: Go and Tick one after the other -------------------------------------

    /** Go and Tick linked: when Go gets past the slot page, Tick starts 1 s later by itself. */
    private boolean linkOn;
    private TextView linkButton;
    /** Tick, waiting out the 1 s after Go (null when none waits). */
    private Runnable pendingTick;

    private void showLinkButton() {
        linkOn = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("link_go_tick", false);
        linkButton = floating("🔗\nLink", 0xEE6D4C41, 556, v -> {
            closeCard();
            linkOn = !linkOn;
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("link_go_tick", linkOn).apply();
            booker.setHandOver(linkOn); // also for a Go running now
            if (!linkOn) cancelLinkedTick();
            showLink();
            Toast.makeText(this, linkOn
                    ? "Go → Tick linked: when Go reaches the sevak page, Tick starts 1 s later"
                    : "Go and Tick not linked - each runs on its own", Toast.LENGTH_SHORT).show();
        });
        showLink();
    }

    private void showLink() {
        linkButton.setText(linkOn ? "🔗\nOn" : "🔗\nLink");
        GradientDrawable bg = (GradientDrawable) linkButton.getBackground();
        bg.setStroke(linkOn ? dp(4) : dp(2), linkOn ? 0xFF7CFC00 : 0x66FFFFFF);
    }

    /** Go got past the slot page: Tick starts 1 s from now (tap Go in the gap to cancel it). */
    private void tickAfterGo() {
        cancelLinkedTick();
        Toast.makeText(this, "Go done - Tick starts in 1 s", Toast.LENGTH_SHORT).show();
        goButton.setText("⏱\nCancel");
        pendingTick = () -> {
            pendingTick = null;
            goButton.setText("▶\nGo");
            if (!linkOn || ticker.isRunning() || clearer.isRunning() || booker.isRunning()) return;
            tickButton.setText("■\nStop");
            ticker.start();
        };
        handler.postDelayed(pendingTick, 1000);
    }

    private void cancelLinkedTick() {
        if (pendingTick == null) return;
        handler.removeCallbacks(pendingTick);
        pendingTick = null;
        goButton.setText("▶\nGo");
    }

    // ---- which round buttons are on the screen (chosen in the app) ---------------------

    /** The round buttons by name, in their order down the screen. */
    private View[] shownButtons() {
        return new View[] {button, tickButton, clearButton, bookButton, goButton, reportButton, linkButton};
    }

    static final String[] SHOWN_KEYS = {"scan", "tick", "clear", "book", "go", "report", "link"};

    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener shownListener =
            (sp, key) -> {
                if (key != null && key.startsWith("show_")) applyShown();
            };

    /** Shows the chosen buttons (all, until you choose), stacked down the left edge in order. */
    private void applyShown() {
        android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        View[] views = shownButtons();
        int k = 0;
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            if (v == null) continue;
            boolean on = sp.getBoolean("show_" + SHOWN_KEYS[i], true);
            v.setVisibility(on ? View.VISIBLE : View.GONE);
            try {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
                // A hidden button's window lets every touch through to the page under it.
                if (on) {
                    lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    lp.x = dp(12);
                    lp.y = dp(160 + 66 * k);
                } else {
                    lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                }
                windowManager.updateViewLayout(v, lp);
            } catch (RuntimeException ignored) {
            }
            if (on) k++;
        }
    }

    // ---- the last run's result: kept for the 📋 Report button, not shown by itself ----

    private String lastSummary = "";
    private TextView reportButton;

    /** A run ended: its result is kept (📋 Report shows it), only its first line is said. */
    private void finished(String summary) {
        lastSummary = summary;
        String[] lines = summary.split("\n");
        String head = lines[0] + (lines.length > 1 ? " · " + lines[1] : "");
        Toast.makeText(this, head + " - 📋 Report for more", Toast.LENGTH_SHORT).show();
    }

    private void showReportButton() {
        reportButton = floating("📋\nReport", 0xEE455A64, 490, v -> {
            if (card != null) {
                closeCard();
                return;
            }
            showCard(lastSummary.isEmpty() ? "No run yet - Tick, Clear, Book or Go first." : lastSummary);
        });
    }

    /** Starts Book with the choices saved in its form; with none saved, opens the form. */
    private void startSaved() {
        android.content.SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        Booker.Plan plan = new Booker.Plan();
        plan.option = prefs.getString("book_option", "");
        plan.radio = prefs.getString("book_radio", "");
        String d = prefs.getString("book_date", "");
        if (!d.isEmpty() && !Booker.parseDate(d, plan)) d = "";
        if (plan.option.isEmpty() && d.isEmpty()) {
            Toast.makeText(this, "Nothing chosen yet - choose in Book first", Toast.LENGTH_SHORT).show();
            Booker.options(this, handler, this::askBooking);
            return;
        }
        Toast.makeText(this, "Go: " + (plan.option.isEmpty() ? "" : plan.option) + (d.isEmpty() ? "" : " · " + d)
                + (plan.radio.isEmpty() ? "" : " · " + plan.radio), Toast.LENGTH_SHORT).show();
        goButton.setText("■\nStop");
        cancelLinkedTick();
        booker.setHandOver(linkOn);
        booker.start(plan);
    }

    // ---- Auto-clear: press the OK of every new pop-up the moment it comes up ----------

    private boolean autoClear, autoPending;
    private Page.Before autoBaseline;
    private long autoLastPress;
    private String autoLastKey = "";
    private int autoSameTries, autoPressed;

    /** Long press on Clear: watch the page and press the OK of each pop-up that comes up. */
    private void toggleAutoClear() {
        closeCard();
        autoClear = !autoClear;
        if (autoClear) {
            autoBaseline = new Page.Before(this);
            autoPressed = 0;
            autoLastKey = "";
            clearButton.setText("✖\nAuto");
            GradientDrawable bg = (GradientDrawable) clearButton.getBackground();
            bg.setStroke(dp(4), 0xFF7CFC00);
            Toast.makeText(this, "Auto-clear ON: the OK of every new pop-up is pressed. Long-press again to stop.",
                    Toast.LENGTH_LONG).show();
        } else {
            clearButton.setText("✖\nClear");
            GradientDrawable bg = (GradientDrawable) clearButton.getBackground();
            bg.setStroke(dp(2), 0x66FFFFFF);
            Toast.makeText(this, "Auto-clear OFF - " + autoPressed + " pop-up(s) cleared", Toast.LENGTH_SHORT).show();
        }
    }

    /** The page changed: look for a new pop-up shortly (changes come in bursts). */
    private void autoCheckSoon() {
        if (autoPending) return;
        autoPending = true;
        handler.postDelayed(() -> {
            autoPending = false;
            if (!autoClear) return;
            try {
                autoCheck();
            } catch (RuntimeException ignored) {
            }
        }, 150);
    }

    private void autoCheck() {
        // Tick and Clear clear their own pop-ups: never tap the same one twice.
        if (ticker.isRunning() || clearer.isRunning()) return;
        AccessibilityNodeInfo ok = Page.newOk(this, autoBaseline);
        if (ok == null && Page.coverCame(this, autoBaseline)) {
            // A web pop-up that hides its OK: its cover came; tap the OK where Tick learned it is.
            android.content.SharedPreferences sp = getSharedPreferences("popup", MODE_PRIVATE);
            String spot = sp.getString("ok_spot_" + appInFront(), sp.getString("ok_spot", null));
            android.graphics.Rect r = spot == null ? null : android.graphics.Rect.unflattenFromString(spot);
            long now = android.os.SystemClock.uptimeMillis();
            if (r != null && now - autoLastPress >= 700) {
                autoLastPress = now;
                autoPressed++;
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(r.centerX(), r.centerY());
                dispatchGesture(new android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 60))
                        .build(), null, null);
                Toast.makeText(this, "Auto-clear: tapped the pop-up's OK", Toast.LENGTH_SHORT).show();
                handler.postDelayed(this::autoCheckSoon, 800);
                return;
            }
        }
        if (ok == null) {
            // No pop-up: this is the page as it is now; a pop-up is what comes on top of it.
            autoBaseline = new Page.Before(this);
            autoSameTries = 0;
            autoShot();
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - autoLastPress < 700) {
            autoCheckSoon(); // pressed a moment ago: look again once it has had time to go
            return;
        }
        String key = Page.key(ok);
        autoSameTries = key.equals(autoLastKey) ? autoSameTries + 1 : 0;
        autoLastKey = key;
        if (autoSameTries >= 3) return; // it won't go: leave it, don't keep pressing
        android.graphics.Rect r = Page.bounds(ok);
        if (!ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(r.centerX(), r.centerY());
            dispatchGesture(new android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 60))
                    .build(), null, null);
        }
        autoLastPress = now;
        if (autoSameTries == 0) autoPressed++;
        Toast.makeText(this, "Auto-clear: pressed \"" + Page.label(ok) + "\"", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::autoCheckSoon, 800);
    }

    private PurpleFinder autoFinder;
    private boolean autoShotQueued;

    /**
     * A pop-up the page draws without reporting it: its purple button found on a screenshot
     * (at most ~3 a second, only while the page is changing) and tapped.
     */
    private void autoShot() {
        if (!PurpleFinder.available() || autoShotQueued) return;
        if (autoFinder == null) autoFinder = new PurpleFinder(this);
        autoShotQueued = true;
        handler.postDelayed(() -> autoFinder.find(r -> {
            autoShotQueued = false;
            if (!autoClear || r == null || ticker.isRunning() || clearer.isRunning()) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - autoLastPress < 700) return;
            autoLastPress = now;
            autoPressed++;
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(r.centerX(), r.centerY());
            dispatchGesture(new android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 60))
                    .build(), null, null);
            Toast.makeText(this, "Auto-clear: tapped the pop-up's button", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::autoCheckSoon, 800);
        }, why -> autoShotQueued = false, autoBaseline), autoFinder.waitMs());
    }

    /** Another run is on: say so instead of starting a second one over it. */
    private boolean busy() {
        boolean on = ticker.isRunning() || clearer.isRunning() || booker.isRunning();
        if (on) Toast.makeText(this, "Stop the running one first", Toast.LENGTH_SHORT).show();
        return on;
    }

    private void saveReport(String text) {
        try (FileOutputStream out = openFileOutput(REPORT_FILE, MODE_PRIVATE)) {
            out.write(text.getBytes());
        } catch (java.io.IOException ignored) {
        }
    }

    private TextView floating(String label, int colour, int yDp, View.OnClickListener onTap) {
        return floating(label, colour, yDp, onTap, null);
    }

    /** A round floating button you can drag; a tap runs {@code onTap}, a long press {@code onLong}. */
    @SuppressLint("ClickableViewAccessibility")
    private TextView floating(String label, int colour, int yDp, View.OnClickListener onTap,
                              View.OnClickListener onLong) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(colour);
        bg.setStroke(dp(2), 0x66FFFFFF);
        b.setBackground(bg);
        b.setElevation(dp(4));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dp(56), dp(56),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(yDp);
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        float[] down = new float[2];
        int[] start = new int[2];
        boolean[] dragged = {false}, longDone = {false};
        Runnable longPress = () -> {
            longDone[0] = true;
            if (onLong != null) onLong.onClick(b);
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    start[0] = lp.x;
                    start[1] = lp.y;
                    dragged[0] = false;
                    longDone[0] = false;
                    if (onLong != null) handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (!dragged[0] && Math.hypot(dx, dy) > slop) {
                        dragged[0] = true;
                        handler.removeCallbacks(longPress);
                    }
                    if (dragged[0]) {
                        lp.x = start[0] + (int) dx;
                        lp.y = start[1] + (int) dy;
                        windowManager.updateViewLayout(b, lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPress);
                    if (!dragged[0] && !longDone[0]) onTap.onClick(v);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPress);
                    return true;
                default:
                    return true;
            }
        });
        windowManager.addView(b, lp);
        return b;
    }

    /** Asks for the dropdown option, the date and the radio button, then fills the page. */
    private void askBooking(List<String> choices) {
        closeAsk();
        android.content.SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF81B1D22);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);
        TextView title = new TextView(this);
        title.setText("Book: what to fill");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(title);
        TextView hint = new TextView(this);
        hint.setText("Chooses the dropdown option, the date (moving months if needed), ticks the "
                + "checkbox, picks the radio button, then presses Continue. Leave a box empty to skip it.");
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(0, dp(4), 0, dp(6));
        box.addView(hint);
        android.widget.EditText option = field(box, choices.isEmpty() ? "Dropdown option (a few of its words)"
                        : "Dropdown option - pick one below (or type a few of its words)",
                "e.g. Tirumala Male Only", prefs.getString("book_option", ""), android.text.InputType.TYPE_CLASS_TEXT);
        if (!choices.isEmpty()) {
            // The page's own options: tap one to choose it.
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            List<TextView> rows = new ArrayList<>();
            String saved = option.getText().toString();
            for (String c : choices) {
                TextView t = new TextView(this);
                t.setText(c);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                t.setPadding(dp(10), dp(8), dp(10), dp(8));
                rows.add(t);
                list.addView(t);
            }
            Runnable paint = () -> {
                String cur = option.getText().toString();
                for (TextView t : rows) {
                    boolean on = t.getText().toString().equals(cur);
                    t.setTextColor(on ? Color.WHITE : 0xDDFFFFFF);
                    t.setBackgroundColor(on ? 0xFF1565C0 : 0x00000000);
                }
            };
            for (TextView t : rows) {
                t.setOnClickListener(v -> {
                    option.setText(t.getText());
                    paint.run();
                });
            }
            paint.run();
            ScrollView sc = new ScrollView(this);
            sc.addView(list);
            GradientDrawable lbg = new GradientDrawable();
            lbg.setColor(0x33FFFFFF);
            lbg.setCornerRadius(dp(8));
            sc.setBackground(lbg);
            box.addView(sc, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    Math.min(dp(220), dp(36) * choices.size() + dp(8))));
            // Scroll to the chosen one.
            for (TextView t : rows) {
                if (t.getText().toString().equals(saved)) sc.post(() -> sc.scrollTo(0, t.getTop()));
            }
        }
        android.widget.EditText date = field(box, "Date", "e.g. 15/10/2026",
                prefs.getString("book_date", ""), android.text.InputType.TYPE_CLASS_DATETIME
                        | android.text.InputType.TYPE_DATETIME_VARIATION_DATE);
        android.widget.EditText radio = field(box, "Radio button (a few of its words; empty = the first)",
                "e.g. Batch A", prefs.getString("book_radio", ""), android.text.InputType.TYPE_CLASS_TEXT);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        row.addView(cardButton("Cancel", v -> closeAsk()));
        // Save: kept for ▶ Go. Save & Start: kept, and filled now.
        for (boolean startNow : new boolean[] {false, true}) {
            row.addView(cardButton(startNow ? "Save & Start" : "Save", v -> {
                Booker.Plan plan = new Booker.Plan();
                plan.option = option.getText().toString().trim();
                plan.radio = radio.getText().toString().trim();
                String d = date.getText().toString().trim();
                if (!d.isEmpty() && !Booker.parseDate(d, plan)) {
                    Toast.makeText(this, "Type the date like 15/10/2026", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.edit().putString("book_option", plan.option).putString("book_date", d)
                        .putString("book_radio", plan.radio).apply();
                closeAsk();
                if (!startNow) {
                    Toast.makeText(this, "Saved - press ▶ Go to fill the page", Toast.LENGTH_SHORT).show();
                    return;
                }
                bookButton.setText("■\nStop");
                handler.postDelayed(() -> { // the keyboard goes down first
                    cancelLinkedTick();
                    booker.setHandOver(linkOn);
                    booker.start(plan);
                }, 400);
            }));
        }
        box.addView(row);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                getResources().getDisplayMetrics().widthPixels * 90 / 100,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(90);
        lp.softInputMode = choices.isEmpty() ? WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                : WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
        try {
            windowManager.addView(box, lp);
            ask = box;
            if (choices.isEmpty()) option.requestFocus();
        } catch (RuntimeException e) {
            Toast.makeText(this, "Couldn't show the form: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private android.widget.EditText field(LinearLayout box, String label, String hint, String value, int type) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFF9AE6A1);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        l.setPadding(0, dp(8), 0, 0);
        box.addView(l);
        android.widget.EditText e = new android.widget.EditText(this);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setText(value);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0x88FFFFFF);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        box.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private void closeAsk() {
        if (ask == null) return;
        try {
            windowManager.removeView(ask);
        } catch (RuntimeException ignored) {
        }
        ask = null;
    }

    // ---- scanning ---------------------------------------------------------------

    /** Scans the screen in front; {@code deep}: wake the web views first and wait. */
    private void scan(boolean deep) {
        if (scanning) return;
        scanning = true;
        closeCard();
        button.setAlpha(0.5f);
        if (!deep) {
            handler.postDelayed(() -> finish(false), 100);
            return;
        }
        int woken = 0;
        for (AccessibilityNodeInfo root : roots()) {
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            stack.add(root);
            while (!stack.isEmpty()) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                if (n == null) continue;
                for (int i = 0; i < n.getChildCount(); i++) stack.add(n.getChild(i));
                CharSequence c = n.getClassName();
                if (c != null && c.toString().contains("WebView")) {
                    n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                    woken++;
                }
            }
        }
        Toast.makeText(this, "Deep scan: waking " + woken + " web view(s)…", Toast.LENGTH_SHORT).show();
        handler.postDelayed(() -> finish(true), 1500);
    }

    private WholePage wholePage;
    private final java.util.Map<String, String> openScreen = new java.util.HashMap<>();
    private final RawScan.Recorder recorder = new RawScan.Recorder();
    private String recordPkg = "";

    /** The app in front (not us, not the status bar). */
    private String appInFront() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && active.getPackageName() != null && !getPackageName().contentEquals(active.getPackageName())) {
            return active.getPackageName().toString();
        }
        for (AccessibilityNodeInfo r : roots()) {
            if (r.getPackageName() != null && !"com.android.systemui".contentEquals(r.getPackageName())) {
                return r.getPackageName().toString();
            }
        }
        return "";
    }

    /** Records the app's accessibility events for 15 s while you use it. */
    private void record() {
        closeCard();
        recordPkg = appInFront();
        if (recordPkg.isEmpty()) {
            Toast.makeText(this, "No app in front to record", Toast.LENGTH_SHORT).show();
            return;
        }
        recorder.start();
        Toast.makeText(this, "Recording " + recordPkg + " for 15 s - use the app now", Toast.LENGTH_LONG).show();
        button.setAlpha(0.5f);
        handler.postDelayed(() -> {
            String rep = recorder.stop(recordPkg);
            button.setAlpha(1f);
            done(recorder.summary(), rep, false);
        }, 15000);
    }

    private void finish(boolean deep) {
        Scanner.Result r;
        try {
            String pkg = appInFront();
            r = Scanner.scan(this, deep, openScreen.get(pkg));
        } catch (RuntimeException e) {
            r = new Scanner.Result("Scan failed: " + e, "Scan failed: " + e);
        }
        done(r.summary, r.report, deep);
    }

    private void done(String summary, String report, boolean deep) {
        saveReport(report);
        scanning = false;
        button.setAlpha(1f);
        if (deep) {
            showCard(summary, new String[] {"Page code", "Whole page ↓", "Record 15 s"},
                    new View.OnClickListener[] {v -> pageCode(), v -> wholePage(), v -> record()});
        } else {
            showCard(summary);
        }
    }

    /** The web page on screen written out as HTML-like code (from what the browser reports). */
    private void pageCode() {
        closeCard();
        String code;
        try {
            code = PageCode.write(this);
        } catch (RuntimeException e) {
            code = "Page code failed: " + e;
        }
        String when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date());
        saveReport("A11y Inspector - page code - " + when + "\n=================================================\n" + code);
        String[] lines = code.split("\n");
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 60); i++) head.append(lines[i]).append('\n');
        showCard(head + (lines.length > 60 ? "… open Full report for all " + lines.length + " lines" : ""));
    }

    /** Scrolls the page to the end, screen by screen, listing what each screen brings. */
    private void wholePage() {
        closeCard();
        if (wholePage == null) wholePage = new WholePage(this);
        scanning = true;
        button.setAlpha(0.5f);
        wholePage.walk((sum, rep) -> done(sum, rep, false));
    }

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && !getPackageName().contentEquals(
                        r.getPackageName() == null ? "" : r.getPackageName())) out.add(r);
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    // ---- the result card over the page --------------------------------------------

    private void showCard(String summary) {
        showCard(summary, new String[0], new View.OnClickListener[0]);
    }

    private void showCard(String summary, String[] extraLabels, View.OnClickListener[] extras) {
        closeCard();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01B1D22);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);

        TextView text = new TextView(this);
        text.setText(summary);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        box.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dp(360), getResources().getDisplayMetrics().heightPixels / 2)));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        if (extras.length > 0) {
            // The deeper looks, on their own row.
            LinearLayout more = new LinearLayout(this);
            more.setGravity(Gravity.END);
            for (int i = 0; i < extras.length; i++) more.addView(cardButton(extraLabels[i], extras[i]));
            box.addView(more);
        }
        row.addView(cardButton("Close", v -> closeCard()));
        row.addView(cardButton("Full report", v -> {
            closeCard();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        box.addView(row, rowLp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                getResources().getDisplayMetrics().widthPixels * 92 / 100,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        try {
            windowManager.addView(box, lp);
            card = box;
        } catch (RuntimeException e) {
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show();
        }
    }

    private TextView cardButton(String label, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(0xFF9AE6A1);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setOnClickListener(onClick);
        return b;
    }

    private void closeCard() {
        if (card == null) return;
        try {
            windowManager.removeView(card);
        } catch (RuntimeException ignored) {
        }
        card = null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
