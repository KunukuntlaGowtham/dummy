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
    private boolean destroyed;
    private String popupWatchReport = "";

    @Override
    protected void onServiceConnected() {
        windowManager = getSystemService(WindowManager.class);
        showButton();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence evPkg = event.getPackageName();
        // Tick's waiting look runs the moment the page changes, not at its next timer.
        if (ticker != null) ticker.onPageEvent(event);
        if (deleter != null) deleter.onPageEvent(event);
        if (autoClear && evPkg != null && !getPackageName().contentEquals(evPkg)) {
            int t = event.getEventType();
            if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                autoCheckSoon();
            }
        }
        if (evPkg != null && !getPackageName().contentEquals(evPkg)) {
            int t = event.getEventType();
            if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                pageCheckSoon();
                // Right after page 3's Continue: page 4's Confirm pressed the moment it comes.
                if (flow != null) flow.onEvent(evPkg.toString());
            }
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
        destroyed = true;
        closeCard();
        closeAsk();
        if (ticker != null) ticker.stop("Stopped");
        if (deleter != null) deleter.stop("Stopped");
        if (missedBox != null) missedBox.hide();
        if (clearer != null) clearer.stop("Stopped");
        if (booker != null) booker.stop("Stopped");
        cancelLinkedTick();
        getSharedPreferences("settings", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(shownListener);
        for (View v : new View[] {button, tickButton, clearButton, bookButton, goButton, reportButton, autoButton, deleteButton, linkButton, pageBadge}) {
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
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setLineSpacing(0, 0.9f);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Glass.button(0x2E7D32, getResources().getDisplayMetrics().density));
        button.setElevation(dp(8));

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
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start();
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
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                    handler.removeCallbacks(onLong);
                    if (!dragged[0] && !longDone[0]) scan(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                    handler.removeCallbacks(onLong);
                    return true;
                default:
                    return false;
            }
        });
        windowManager.addView(button, buttonParams);
        showTickButton();
        showPageBadge();
    }

    // ---- 📍 which page of the booking is on screen ---------------------------------------

    /** Top of the screen: "📍 Page 3 · Ticking sevaks" - hidden on other pages. Takes no touches. */
    private TextView pageBadge;
    private boolean pageCheckQueued;
    /** The page as last read (OTHER until read, or with the badge off). */
    private PageKind.Kind pageKind = PageKind.Kind.OTHER;

    private void showPageBadge() {
        pageBadge = new TextView(this);
        pageBadge.setTextColor(Color.WHITE);
        pageBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pageBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pageBadge.setPadding(dp(12), dp(5), dp(12), dp(5));
        pageBadge.setElevation(dp(4));
        pageBadge.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(30);
        try {
            windowManager.addView(pageBadge, lp);
        } catch (RuntimeException e) {
            pageBadge = null;
            return;
        }
        flow = new AutoFlow(this, handler, new AutoFlow.Host() {
            @Override
            public void recheck() {
                checkPage();
            }

            @Override
            public void page1(PageKind.Facts f) {
                rememberPage1(f);
                lastFacts = f;
                lastKind = PageKind.Kind.SEVAK_LIST;
                PageOrder order = orders.get(f.pkg);
                if (order == null) orders.put(f.pkg, order = new PageOrder());
                order.set(1);
            }

            @Override
            public void say(String message) {
                Toast.makeText(InspectorService.this, message, Toast.LENGTH_SHORT).show();
            }

            @Override
            public boolean mayGoBack() {
                // Tick and Delete not linked: Tick only notes the rows - you go back and press 🗑.
                return retryOn() && !retryUsed;
            }

            @Override
            public void missedAtPage1() {
                startRetry();
            }

            @Override
            public void backFailed() {
                testTripEnded(); // a test trip that stopped early still turns test mode off
            }

            @Override
            public boolean testTripAfterTick() {
                return InspectorService.this.testTripAfterTick();
            }
        });
        pageCheckSoon();
    }

    /** The page changed: look which page it is shortly (changes come in bursts). */
    private long lastPageCheck;

    private void pageCheckSoon() {
        if (pageCheckQueued || pageBadge == null) return;
        pageCheckQueued = true;
        // 120 ms after a change, but at most one read every 300 ms while the page keeps changing.
        long since = android.os.SystemClock.uptimeMillis() - lastPageCheck;
        handler.postDelayed(() -> {
            pageCheckQueued = false;
            lastPageCheck = android.os.SystemClock.uptimeMillis();
            checkPage();
        }, Math.max(120, 300 - since));
    }

    // Auto: page 3's Continue (or Back to pages 2 and 1), page 4's Confirm.
    private AutoFlow flow;
    /** The last reading (for page 1: the page read just before page 2 came). */
    private PageKind.Facts lastFacts;
    private PageKind.Kind lastKind = PageKind.Kind.OTHER;
    /** Each app's place in the pages' order. */
    private final java.util.Map<String, PageOrder> orders = new java.util.HashMap<>();

    private void checkPage() {
        if (destroyed || pageBadge == null) return;
        android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        boolean show = sp.getBoolean("show_page", true), auto = AutoFlow.enabled(this);
        if (!show) pageBadge.setVisibility(View.GONE);
        if (!show && !auto) {
            pageKind = PageKind.Kind.OTHER;
            return;
        }
        // While a run goes the page isn't read again: Tick's speed comes first. The badge
        // keeps the page the run started on.
        if (scanning || deleting() || ticker.isRunning() || clearer.isRunning() || booker.isRunning()) return;
        PageKind.Facts f;
        try {
            f = PageKind.read(this);
        } catch (RuntimeException e) {
            return;
        }
        PageKind.Kind read = kindOf(f);
        // The pages keep their order, 1 → 2 → 3 → 4: a reading that jumps (a page still
        // drawing) isn't taken until it holds - the badge stays, and the page is read again.
        PageOrder order = orders.get(f.pkg);
        if (order == null) orders.put(f.pkg, order = new PageOrder());
        int prevPage = order.pos();
        PageKind.Kind kind = PageKind.Kind.of(order.next(read.number));
        // Page 4's Confirm only right after page 3 (or still on 4) - never a Confirm met elsewhere.
        boolean confirmInOrder = kind == PageKind.Kind.CONFIRM && (prevPage == 3 || prevPage == 4);
        if (read != PageKind.Kind.OTHER && kind == PageKind.Kind.OTHER) {
            pageCheckSoon();
            return;
        }
        // Page 1 is the page before page 2, whatever it is: kept when page 2 comes after it.
        if (kind == PageKind.Kind.CALENDAR && lastFacts != null && lastFacts.pkg.equals(f.pkg)
                && (lastKind == PageKind.Kind.OTHER || lastKind == PageKind.Kind.SEVAK_LIST)
                && PageKind.decide(lastFacts) == PageKind.Kind.OTHER) {
            rememberPage1(lastFacts);
        }
        if (kind == PageKind.Kind.CALENDAR || kind == PageKind.Kind.TICKING) {
            if (!f.pkg.equals(sp.getString("booking_pkg", ""))) sp.edit().putString("booking_pkg", f.pkg).apply();
        }
        lastFacts = f;
        lastKind = kind;
        pageKind = kind;
        // During the retry its own steps press Continue: Auto's would press it twice.
        if (auto && flow != null && !retrying) flow.onPage(kind, f, sp.getString("booking_pkg", ""), confirmInOrder);
        if (!show) return;
        if (kind == PageKind.Kind.OTHER) {
            pageBadge.setVisibility(View.GONE);
            return;
        }
        int colour = kind == PageKind.Kind.SEVAK_LIST ? 0xEE1565C0
                : kind == PageKind.Kind.CALENDAR ? 0xEE00897B
                : kind == PageKind.Kind.CONFIRM ? 0xEEE65100
                : 0xEE2E7D32; // not purple: Tick's screenshots look for the purple OK
        pageBadge.setBackground(Glass.pill(colour, getResources().getDisplayMetrics().density));
        pageBadge.setText((testMode() ? "🧪 TEST · " : "📍 ") + kind.title() + (auto ? " · auto" : ""));
        pageBadge.setContentDescription(kind.title() + ": " + f.describe());
        pageBadge.setVisibility(View.VISIBLE);
    }

    /** Pages 2-4 by what they have; page 1 by its words (the page kept as the one before page 2). */
    private PageKind.Kind kindOf(PageKind.Facts f) {
        PageKind.Kind kind = PageKind.decide(f);
        if (kind != PageKind.Kind.OTHER) return kind;
        String kept = getSharedPreferences("settings", MODE_PRIVATE).getString("page1_" + f.pkg, "");
        if (kept.isEmpty()) return kind;
        java.util.Set<String> words = new java.util.HashSet<>(java.util.Arrays.asList(kept.split("\u0001")));
        return PageKind.samePage(words, f.texts) ? PageKind.Kind.SEVAK_LIST : kind;
    }

    private void rememberPage1(PageKind.Facts f) {
        if (f.texts.size() < 3 || f.pkg.isEmpty()) return;
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("page1_" + f.pkg, String.join("\u0001", f.texts)).apply();
    }

    /** One line for reports: which page, and what on it says so. */
    private String pageLine() {
        try {
            PageKind.Facts f = PageKind.read(this);
            return "📍 " + kindOf(f).title() + " (" + f.describe() + ")\n";
        } catch (RuntimeException e) {
            return "";
        }
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
                // Page 3 decides now, not at the next page change: Continue when every box
                // is ticked, back to page 1 when one is still empty.
                // (Not when you stopped it yourself: then nothing goes back.)
                // Page 3's names on its rows, for Delete to check it deletes the same sevaks.
                try {
                    rowNames = readRowNames();
                } catch (RuntimeException e) {
                    rowNames = new ArrayList<>();
                }
                if (flow != null && !summary.startsWith("Stopped")) {
                    if (AutoFlow.enabled(InspectorService.this)) {
                        flow.tickEnded(); // Auto decides on page 3: Continue, the retry, or the test trip
                    } else if (testMode()) {
                        // Test mode with Auto off: the test trip runs by itself all the same.
                        handler.postDelayed(() -> startTestTripNow(), 300);
                    }
                }
                handler.post(InspectorService.this::checkPage);
            }

            @Override
            public void notAdded(java.util.List<String> rows) {
                // Live, in the corner: stays after the run, until the next Tick run starts.
                missedBox.show(rows);
                lastMissed = new ArrayList<>(rows);
            }
        });
        tickButton = floating("☑\nTick", 0xEE6A2C91, 226, v -> {
            closeCard();
            if (ticker.isRunning()) {
                ticker.stop("Stopped");
            } else if (!busy()) {
                // The badge's last reading: no extra page read before Tick starts.
                PageKind.Kind kind = pageKind;
                if (kind == PageKind.Kind.CALENDAR || kind == PageKind.Kind.SEVAK_LIST) {
                    // Only said: Tick still runs (the page may be read wrongly).
                    Toast.makeText(this, "📍 This looks like " + kind.title() + " - Tick is for Page 3",
                            Toast.LENGTH_SHORT).show();
                }
                retryUsed = false; // a new booking: its one retry again
                if (flow != null) flow.holdConfirm(false);
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
            goButton.setText("▶\nGo");
            bookButton.setText("📅\nBook");
            finished(summary);
            if (retrying) {
                rlog("page 2: Go - " + summary.replace('\n', ' '));
                // Its own steps too (slot, checkbox, Continue), so the report shows what happened.
                int steps = log.indexOf("STEPS");
                if (steps >= 0) retryLog.append("    ").append(log.substring(steps).replace("\n", "\n    ")).append('\n');
                // Page 2 done (moved on): on to page 3.
                if (booker.handedOver()) retryPage3(0);
                else endRetry("Go didn't get past page 2");
            } else if (linkOn() && booker.handedOver()) {
                // Go started on page 3: no page change to wait for - Tick at once.
                if (booker.startedOnSevaks()) tickNow();
                else tickAfterGo();
            }
        });
        // The calendar's screenshot (which days are green) is taken with our buttons off it.
        booker.setOverlayHider(new java.util.function.Consumer<Boolean>() {
            private int[] was;

            @Override
            public void accept(Boolean hide) {
                View[] round = shownButtons();
                View[] vs = java.util.Arrays.copyOf(round, round.length + 2);
                vs[round.length] = pageBadge;
                vs[round.length + 1] = missedBox == null ? null : missedBox.view();
                if (hide) {
                    was = new int[vs.length];
                    for (int i = 0; i < vs.length; i++) {
                        if (vs[i] == null) continue;
                        was[i] = vs[i].getVisibility();
                        vs[i].setVisibility(View.INVISIBLE);
                    }
                } else if (was != null) {
                    for (int i = 0; i < vs.length; i++) if (vs[i] != null) vs[i].setVisibility(was[i]);
                    was = null;
                }
            }
        });
        // Book: choose what Go fills - the dropdown option (from the page's own list), the date, the slot.
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
        }, v -> {
            // Long press: choose what Go fills (from the page's own dropdown list) and save it.
            closeCard();
            if (booker.isRunning() || busy()) return;
            Toast.makeText(this, "Reading the dropdown's options…", Toast.LENGTH_SHORT).show();
            Booker.options(this, handler, this::askBooking);
        });
        showReportButton();
        showAutoButton();
        showDeleteButton();
        showLinkButton();
        applyShown();
        getSharedPreferences("settings", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(shownListener);
    }

    // ---- Go then Tick (part of 🤖 Auto) ----------------------------------------------------

    /** With 🤖 Auto on, Go and Tick are linked: past the slot page, Tick starts 1 s later by itself. */
    private boolean linkOn() {
        return AutoFlow.enabled(this);
    }

    /** Tick, waiting out the 1 s after Go (null when none waits). */
    private Runnable pendingTick;

    // ---- 🤖 Auto: page 3's Continue (or Back to pages 2 and 1), page 4's Confirm ------------

    private TextView autoButton;

    private void showAutoButton() {
        autoButton = floating("🤖\nAuto", 0xEE37474F, 622, v -> {
            closeCard();
            boolean on = !AutoFlow.enabled(this);
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("auto_flow", on).apply();
            booker.setHandOver(on); // also for a Go running now
            if (flow != null) flow.clearTickEnded(); // a Tick that ended before is never acted on now
            if (!on) cancelLinkedTick();
            Toast.makeText(this, on
                    ? "🤖 Auto ON: Go → Tick → Continue → Confirm runs by itself"
                    : "🤖 Auto OFF - each button runs on its own", Toast.LENGTH_SHORT).show();
        }, v -> markPage1());
        showAuto();
    }

    /** Long press on 🤖: this page is page 1 - pages 2, 3, 4 follow it in order from here. */
    private void markPage1() {
        closeCard();
        try {
            PageKind.Facts f = PageKind.read(this);
            rememberPage1(f);
            PageOrder order = orders.get(f.pkg);
            if (order == null) orders.put(f.pkg, order = new PageOrder());
            order.set(1);
            lastFacts = f;
            lastKind = PageKind.Kind.SEVAK_LIST;
            pageKind = PageKind.Kind.SEVAK_LIST;
            Toast.makeText(this, "📍 This is page 1 - pages 2, 3, 4 follow in order", Toast.LENGTH_SHORT).show();
            checkPage();
        } catch (RuntimeException e) {
            Toast.makeText(this, "📍 Couldn't read this page", Toast.LENGTH_SHORT).show();
        }
    }

    private void showAuto() {
        if (autoButton == null) return;
        boolean on = AutoFlow.enabled(this);
        autoButton.setText(on ? "🤖\nOn" : "🤖\nAuto");
        autoButton.setContentDescription(on ? "Auto on" : "Auto off");
        GradientDrawable bg = (GradientDrawable) autoButton.getBackground();
        bg.setStroke(on ? dp(3) : Math.round(1.5f * getResources().getDisplayMetrics().density),
                on ? 0xFF34C759 : 0x99FFFFFF);
    }

    // ---- ✋ Manual / 🔗 Linked: how Tick and Delete work together ---------------------------

    private TextView linkButton;

    /**
     * One tap switches: ✋ Manual - Tick only ticks and notes who it couldn't add, you go back
     * and tap 🗑 on page 1; 🔗 Linked - with 🤖 Auto on, back, delete and retry run by themselves.
     */
    private void showLinkButton() {
        linkButton = floating("✋\nManual", 0xEE8D6E63, 754, v -> {
            closeCard();
            boolean on = !retryOn();
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("auto_delete_missed", on).apply();
            Toast.makeText(this, on
                    ? "🔗 LINKED: rows Tick can't add → back, 🗑 delete, forward - by itself (with 🤖 Auto on)"
                    : "✋ MANUAL: Tick only ticks - go back yourself and tap 🗑 on page 1", Toast.LENGTH_LONG).show();
        });
        showLink();
    }

    private void showLink() {
        if (linkButton == null) return;
        boolean on = retryOn();
        linkButton.setText(on ? "🔗\nLinked" : "✋\nManual");
        linkButton.setContentDescription(on ? "Tick and Delete linked" : "Tick and Delete manual");
        linkButton.setBackground(Glass.button(on ? 0xC62828 : 0x8D6E63, getResources().getDisplayMetrics().density));
        GradientDrawable bg = (GradientDrawable) linkButton.getBackground();
        if (on) bg.setStroke(dp(3), 0xFF34C759);
    }

    // ---- 🗑 Delete (testing): sevak profiles by their numbers, each with its 2 pop-ups --------

    private TextView deleteButton;
    private DeleteRun deleter;

    private boolean deleting() {
        return deleter != null && deleter.isRunning();
    }

    private void showDeleteButton() {
        deleter = new DeleteRun(this, (summary, log) -> {
            saveReport(log);
            deleteButton.setText("🗑\nDelete");
            finished(summary);
            if (retrying) {
                rlog("page 1: delete - " + summary.replace('\n', ' '));
                if (summary.startsWith("Stopped")) endRetry("Delete was stopped");
                else handler.postDelayed(this::retryContinuePage1, 150);
            }
        });
        deleteButton = floating("🗑\nDelete", 0xEEC62828, 688, v -> {
            closeCard();
            if (deleter.isRunning()) deleter.stop("Stopped");
            else if (!busy()) deleteTap();
        }, v -> {
            closeCard();
            if (!deleter.isRunning() && !busy()) deleteHere();
        });
    }

    /** Go got past the slot page: Tick starts 1 s from now (tap Go in the gap to cancel it). */
    private void tickAfterGo() {
        cancelLinkedTick();
        Toast.makeText(this, "Go done - Tick starts in 1 s", Toast.LENGTH_SHORT).show();
        goButton.setText("⏱\nCancel");
        pendingTick = () -> {
            pendingTick = null;
            goButton.setText("▶\nGo");
            if (!linkOn() || ticker.isRunning() || clearer.isRunning() || booker.isRunning()) return;
            if (stillPage2()) return;
            tickButton.setText("■\nStop");
            ticker.start();
        };
        handler.postDelayed(pendingTick, 1000);
    }

    /** Page 2 still in front: a linked Tick isn't started there (it would untick the declaration). */
    private boolean stillPage2() {
        PageKind.Kind k;
        try {
            k = PageKind.decide(PageKind.read(this));
        } catch (RuntimeException e) {
            return false;
        }
        if (k != PageKind.Kind.CALENDAR) return false;
        Toast.makeText(this, "📍 Still on page 2 - Tick not started", Toast.LENGTH_SHORT).show();
        return true;
    }

    /** Go started on page 3 (Auto on): Tick starts now, no 1 s gap. */
    private void tickNow() {
        if (!linkOn() || ticker.isRunning() || clearer.isRunning() || booker.isRunning()) return;
        Toast.makeText(this, "Go: already on page 3 - Tick now", Toast.LENGTH_SHORT).show();
        tickButton.setText("■\nStop");
        ticker.start();
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
        return new View[] {button, tickButton, clearButton, bookButton, goButton, autoButton, reportButton, deleteButton, linkButton};
    }

    /** Down the screen from one round button to the next: 56 dp button + 20 dp gap. */
    private static final int BUTTON_STEP = 76;

    static final String[] SHOWN_KEYS = {"scan", "tick", "clear", "book", "go", "auto", "report", "delete", "link"};

    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener shownListener =
            (sp, key) -> {
                if (key != null && key.startsWith("show_")) {
                    applyShown();
                    checkPage();
                } else if ("test_mode".equals(key)) {
                    applyShown();
                    checkPage(); // the badge's 🧪 TEST
                } else if ("auto_delete_missed".equals(key)) {
                    showLink();
                } else if ("auto_flow".equals(key)) {
                    showAuto();
                    checkPage(); // the badge's "· auto", and a page already ready goes on
                }
            };

    /** Shows the chosen buttons (all, until you choose), stacked down the left edge in order. */
    private void applyShown() {
        android.content.SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        View[] views = shownButtons();
        // As far apart as the screen allows (up to 20 dp between): every shown button fits, any phone.
        int shown = 0;
        for (int i = 0; i < views.length; i++) {
            if (views[i] == null || !sp.getBoolean("show_" + SHOWN_KEYS[i], true)) continue;
            shown++;
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenDp = Math.round(dm.heightPixels / dm.density);
        int top = Math.min(140, Math.max(60, screenDp / 6));
        int step = shown <= 1 ? BUTTON_STEP
                : Math.max(58, Math.min(BUTTON_STEP, (screenDp - top - 56 - 48) / (shown - 1)));
        int k = 0;
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            if (v == null) continue;
            boolean on = sp.getBoolean("show_" + SHOWN_KEYS[i], true);
            // 🗑 only in test mode: no stray tap can delete.
            v.setVisibility(on ? View.VISIBLE : View.GONE);
            try {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
                // A hidden button's window lets every touch through to the page under it.
                if (on) {
                    lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    lp.x = dp(12);
                    lp.y = dp(top + step * k);
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

    /** Choices saved for Go (an option or a date). */
    private boolean hasSavedPlan() {
        android.content.SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        return !p.getString("book_option", "").isEmpty() || !p.getString("book_date", "").isEmpty();
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
        if (!retrying) {
            retryUsed = false; // a new booking: its one retry again
            if (flow != null) flow.holdConfirm(false);
        }
        booker.setHandOver(linkOn() || retrying);
        // One read of the page for Go's checks (page 3, page 1) - Go isn't held up by a second.
        PageKind.Facts goFacts = null;
        if (!retrying) {
            try {
                goFacts = PageKind.read(this);
            } catch (RuntimeException ignored) {
            }
        }
        // Go on page 3 with every box ticked already: only its Continue is left.
        if (!retrying) {
            PageKind.Facts f3 = goFacts;
            // Empty boxes: Tick ticks them (it clears each pop-up) - with 🤖 on or off.
            if (f3 != null && PageKind.decide(f3) == PageKind.Kind.TICKING && f3.empty() > 0 && !f3.contOn) {
                goButton.setText("▶\nGo");
                Toast.makeText(this, "Go: already on page 3 - Tick", Toast.LENGTH_SHORT).show();
                tickButton.setText("■\nStop");
                ticker.start();
                return;
            }
            if (f3 != null && PageKind.decide(f3) == PageKind.Kind.TICKING && f3.empty() == 0 && f3.contOn) {
                AccessibilityNodeInfo c3 = pressableButton(PageKind::isContinue);
                goButton.setText("▶\nGo");
                if (c3 != null) {
                    if (linkOn() && flow != null && !testTrip()) {
                        flow.watchConfirm(c3.getPackageName() == null ? "" : c3.getPackageName().toString());
                    }
                    press(c3);
                    Toast.makeText(this, "Go: page 3 is all ticked - Continue pressed", Toast.LENGTH_SHORT).show();
                }
                return;
            }
        }
        // Go on page 1: its Continue first, then page 2 is filled the moment it shows.
        if (!retrying && onPage1(goFacts)) {
            AccessibilityNodeInfo c = pressableButton(PageKind::isContinue);
            if (c == null) {
                goButton.setText("▶\nGo");
                Toast.makeText(this, "Go: on page 1 but its Continue is off", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "Go: page 1 - Continue, then page 2", Toast.LENGTH_SHORT).show();
            press(c);
            awaitPage(PageKind.Kind.CALENDAR, 6000, ok -> {
                if (!ok) {
                    goButton.setText("▶\nGo");
                    Toast.makeText(this, "Go: page 2 didn't come after page 1's Continue", Toast.LENGTH_SHORT).show();
                    return;
                }
                booker.start(plan);
            });
            return;
        }
        booker.start(plan);
    }

    /** Page 1 open now: the page kept as the one before page 2, or the booking app's page with only a Continue. */
    private boolean onPage1(PageKind.Facts f) {
        if (f == null) return false;
        try {
            PageKind.Kind k = kindOf(f);
            if (k == PageKind.Kind.SEVAK_LIST || PageKind.isPage1(f.texts)) return true;
            String booking = getSharedPreferences("settings", MODE_PRIVATE).getString("booking_pkg", "");
            return k == PageKind.Kind.OTHER && f.cont && f.boxes == 0 && f.days == 0 && f.dropdowns == 0
                    && !booking.isEmpty() && booking.equals(f.pkg) && SevakDeleter.cards(this).size() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Up to {@code ms} (looked at every 60 ms) for a page of this kind, then {@code then}. */
    private void awaitPage(PageKind.Kind want, long ms, java.util.function.Consumer<Boolean> then) {
        long until = android.os.SystemClock.uptimeMillis() + ms;
        Runnable[] look = new Runnable[1];
        look[0] = () -> {
            if (destroyed) return;
            boolean ok;
            try {
                ok = PageKind.decide(PageKind.read(this)) == want;
            } catch (RuntimeException e) {
                ok = false;
            }
            if (ok || android.os.SystemClock.uptimeMillis() >= until) then.accept(ok);
            else handler.postDelayed(look[0], 60);
        };
        handler.post(look[0]);
    }

    // ---- Retry once after rows Tick missed: delete them on page 1, then pages 2 → 3 → 4 ----

    /** The rows the last Tick run didn't add (as Tick numbers them). */
    private List<String> lastMissed = new ArrayList<>();
    /** This booking's one retry is used (Tick missing rows again leaves it on page 3). */
    private boolean retryUsed;
    /** The retry runs: delete on page 1, Continue, page 2's checkbox, page 3's Tick. */
    private boolean retrying;

    private boolean retryOn() {
        return getSharedPreferences("settings", MODE_PRIVATE).getBoolean("auto_delete_missed", false);
    }

    /** Back on page 1 for rows Tick missed: delete them (numbered as after each delete), then Continue. */
    // Test mode (set in the app): the numbers to delete on the trip instead of the rows not
    // added - "0" deletes none - and after any Tick run (all ticked too) the trip runs once.

    /** This trip is a test (Confirm never pressed): set when it starts, kept until a new booking. */
    private boolean testTrip() {
        return flow != null && flow.confirmHeld();
    }

    private boolean testMode() {
        return getSharedPreferences("settings", MODE_PRIVATE).getBoolean("test_mode", false);
    }

    /** Test mode's numbers ("0": none - only the trip back and forward). */
    private List<Integer> testNumbers() {
        return DeleteRun.parse(getSharedPreferences("settings", MODE_PRIVATE).getString("test_delete", "0"));
    }

    /** The numbers the next trip deletes (null: the rows Tick didn't add), and their names (null: by number). */
    private List<Integer> tripNumbers;
    private List<String> tripNames;

    /** Page 3's sevak name on each row (in checkbox order), read when Tick ended. */
    private List<String> rowNames = new ArrayList<>();

    /** The rows Tick didn't add, as numbers to delete one after another, with page 3's names. */
    private static final class Missed {
        final List<Integer> numbers = new ArrayList<>();
        final List<String> names = new ArrayList<>();
    }

    /**
     * The rows the last Tick didn't add - taken once: deleted, they are forgotten (a later 🗑
     * never deletes the same numbers again, now other people).
     */
    private Missed takeMissed() {
        Missed m = new Missed();
        m.numbers.addAll(MissedBox.afterRemoving(lastMissed));
        List<Integer> rows = new ArrayList<>();
        for (String r : lastMissed) {
            try {
                rows.add(Integer.parseInt(r.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        java.util.Collections.sort(rows);
        for (int row : rows) {
            String n = row - 1 < rowNames.size() ? rowNames.get(row - 1) : "";
            m.names.add(n == null || n.isEmpty() ? null : n);
        }
        lastMissed = new ArrayList<>();
        rowNames = new ArrayList<>();
        return m;
    }



    /** Each checkbox's row (page order): the first word-like text around it that isn't a label word. */
    private List<String> readRowNames() {
        List<String> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : Page.nodes(this)) {
            if (!Page.isCheckbox(n)) continue;
            String name = "";
            AccessibilityNodeInfo a = n.getParent();
            // Only the row's own box: climbing stops where another checkbox (another row) is inside.
            for (int up = 0; up < 4 && a != null && name.isEmpty(); up++, a = a.getParent()) {
                if (checkboxesIn(a, 0) > 1) break;
                name = firstName(a, 0);
            }
            out.add(name);
        }
        // Two rows with the same name: the names can't be trusted - by number only.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String name : out) {
            if (name.isEmpty()) continue;
            if (!seen.add(name.toLowerCase(java.util.Locale.ROOT))) return new ArrayList<>();
        }
        return out;
    }

    private static int checkboxesIn(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 6) return 0;
        int c = Page.isCheckbox(n) ? 1 : 0;
        for (int i = 0; i < n.getChildCount() && c < 2; i++) c += checkboxesIn(n.getChild(i), depth + 1);
        return c;
    }

    private static String firstName(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 3) return "";
        String l = Page.label(n);
        if (DeleteRun.looksLikeName(l) && !Page.isCheckbox(n)) return l;
        for (int i = 0; i < n.getChildCount(); i++) {
            String c = firstName(n.getChild(i), depth + 1);
            if (!c.isEmpty()) return c;
        }
        return "";
    }

    /**
     * 🗑 tapped: on page 1 - the rows the last Tick didn't add (test mode: its numbers) are
     * deleted right here. On page 2 / 3 - the trip back to page 1 and forward, when Tick and
     * Delete are linked (Settings) or in test mode; else you're told to go back first.
     */
    private void deleteTap() {
        PageKind.Kind k;
        try {
            k = PageKind.decide(PageKind.read(this));
        } catch (RuntimeException e) {
            k = PageKind.Kind.OTHER;
        }
        boolean inBooking = k == PageKind.Kind.CALENDAR || k == PageKind.Kind.TICKING || k == PageKind.Kind.CONFIRM;
        if (!inBooking) {
            deleteHere();
        } else if (retryOn() || testMode()) {
            deleteNow();
        } else {
            Toast.makeText(this, "🗑 Go back to page 1 and press 🗑 there (or link Tick and Delete in Settings)",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 🗑 Delete: the trip now - test mode's numbers, else the rows the last Tick didn't add. */
    private void deleteNow() {
        if (flow == null || flow.busy()) return;
        boolean test = testMode();
        Missed m = test ? null : takeMissed();
        List<Integer> numbers = test ? testNumbers() : m.numbers;
        if (!test && numbers.isEmpty()) {
            Toast.makeText(this, "🗑 Nothing to delete - the last Tick added every row", Toast.LENGTH_SHORT).show();
            return;
        }
        retryUsed = false;
        tripNumbers = numbers;
        tripNames = test ? null : m.names;
        flow.holdConfirm(test); // a test trip never presses Confirm
        PageKind.Kind k;
        try {
            k = PageKind.decide(PageKind.read(this));
        } catch (RuntimeException e) {
            k = PageKind.Kind.OTHER;
        }
        if (k == PageKind.Kind.TICKING || k == PageKind.Kind.CALENDAR) {
            Toast.makeText(this, "↻ " + (numbers.isEmpty() ? "No delete" : "Delete " + numbers)
                    + ": back to page 1, then forward", Toast.LENGTH_SHORT).show();
            flow.backForRetry(testMode() ? "Test mode" : "Delete"); // page 1 reached: startRetry goes on
        } else {
            startRetry(); // on page 1 already
        }
    }

    /**
     * Long press on 🗑: only delete, here on page 1 - test mode's numbers, else the rows the
     * last Tick didn't add - no Back before, no Continue after.
     */
    private void deleteHere() {
        boolean test = testMode();
        Missed m = test ? null : takeMissed();
        List<Integer> numbers = test ? testNumbers() : m.numbers;
        if (numbers.isEmpty()) {
            Toast.makeText(this, test ? "🗑 Test mode's numbers are 0 - nothing to delete"
                    : "🗑 Nothing to delete - the last Tick added every row", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "🗑 Deleting " + numbers + " here only (no Continue after)", Toast.LENGTH_SHORT).show();
        retrying = false;
        deleteButton.setText("■\nStop");
        deleter.start(numbers, test ? null : m.names);
    }

    /** Tick ended: in test mode the trip runs once whatever was ticked (AutoFlow goes back). */
    private boolean testTripAfterTick() {
        if (!testMode()) return false;
        if (retryUsed) {
            Toast.makeText(this, "🧪 Test trip not started - one already ran for this booking (press Tick or Go to start a new one)",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        tripNumbers = testNumbers();
        tripNames = null;
        if (flow != null) flow.holdConfirm(true); // a test never books
        // One test trip, then test mode is off - when the trip ends (🗑 stays to stop it meanwhile).
        autoTestTrip = true;
        Toast.makeText(this, "🧪 Test trip " + (tripNumbers.isEmpty() ? "(no delete)" : "deleting " + tripNumbers)
                + " - test mode turns off when it ends", Toast.LENGTH_LONG).show();
        return true;
    }

    private void startRetry() {
        boolean given = tripNumbers != null;
        List<Integer> numbers;
        List<String> expect;
        if (given) {
            numbers = tripNumbers;
            expect = tripNames;
        } else {
            if (deleting() || !retryOn() || retryUsed || lastMissed.isEmpty()) {
                tripNumbers = null;
                testTripEnded();
                checkPage();
                return;
            }
            Missed m = takeMissed();
            numbers = m.numbers;
            expect = m.names;
        }
        tripNumbers = null;
        tripNames = null;
        if (deleting() || !given && numbers.isEmpty()) {
            testTripEnded();
            checkPage();
            return;
        }
        retryUsed = true;
        retrying = true;
        if (numbers.isEmpty()) {
            Toast.makeText(this, "↻ Nothing deleted - Continue, page 2's checkbox, page 3", Toast.LENGTH_SHORT).show();
            retryContinuePage1();
            return;
        }
        Toast.makeText(this, "🗑 Deleting " + numbers + ", then Continue", Toast.LENGTH_SHORT).show();
        deleteButton.setText("■\nStop");
        deleter.start(numbers, expect);
    }

    /** The automatic test trip runs: test mode turns off when it ends. */
    private boolean autoTestTrip;

    private void testTripEnded() {
        if (!autoTestTrip) return;
        autoTestTrip = false;
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("test_mode", false).apply();
        Toast.makeText(this, "🧪 Test trip done - test mode is off", Toast.LENGTH_SHORT).show();
    }

    /** Tick ended in test mode with Auto off: on page 3, the test trip now (back, delete, forward). */
    private void startTestTripNow() {
        boolean running = scanning || deleting() || pendingTick != null || ticker.isRunning()
                || clearer.isRunning() || booker.isRunning();
        if (flow == null || flow.busy() || retrying || running) {
            Toast.makeText(this, "🧪 Test trip not started - something else was running", Toast.LENGTH_SHORT).show();
            testTripEnded();
            return;
        }
        PageKind.Kind k;
        try {
            k = PageKind.decide(PageKind.read(this));
        } catch (RuntimeException e) {
            return;
        }
        if (k != PageKind.Kind.TICKING) {
            Toast.makeText(this, "🧪 Test trip not started - this doesn't read as page 3 (" + k.title() + ")",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!testTripAfterTick()) return;
        flow.backForRetry("Test mode");
    }

    private void endRetry(String why) {
        retrying = false;
        testTripEnded();
        saveRetryLog("↻ Retry stopped: " + why);
        Toast.makeText(this, "↻ Retry stopped: " + why, Toast.LENGTH_LONG).show();
        checkPage();
    }

    /** Up to {@code ms} for {@code test} (looked at every 60 ms), then {@code then}. */
    private void waitUntil(java.util.function.BooleanSupplier test, long ms, java.util.function.Consumer<Boolean> then) {
        long until = android.os.SystemClock.uptimeMillis() + ms;
        Runnable[] look = new Runnable[1];
        look[0] = () -> {
            if (destroyed || !retrying) return;
            boolean ok;
            try {
                ok = test.getAsBoolean();
            } catch (RuntimeException e) {
                ok = false;
            }
            if (ok || android.os.SystemClock.uptimeMillis() >= until) then.accept(ok);
            else handler.postDelayed(look[0], 60);
        };
        handler.post(look[0]);
    }

    /** A button with these words, on and on screen (itself or the box around it), or null. */
    private AccessibilityNodeInfo pressableButton(java.util.function.Predicate<String> words) {
        for (AccessibilityNodeInfo n : Page.nodes(this)) {
            if (!words.test(Page.label(n))) continue; // also below the screen (TTD's Continue often is)
            AccessibilityNodeInfo b = PageKind.pressable(n);
            if (b != null && b.isEnabled() && n.isEnabled()) return b;
        }
        return null;
    }

    private void press(AccessibilityNodeInfo b) {
        if (b == null) return; // gone since it was seen: the step's own wait notices
        if (!b.isVisibleToUser()) b.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        if (b.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        android.graphics.Rect r = Page.bounds(b);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(r.centerX(), r.centerY());
        dispatchGesture(new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 50))
                .build(), null, null);
    }

    /** Page 1, deletes done: Continue (up to 3 s for it to turn on), then page 2. */
    private void retryContinuePage1() {
        waitUntil(() -> pressableButton(PageKind::isContinue) != null, 3000, on -> {
            if (!on) {
                endRetry("Continue on page 1 stayed off");
                return;
            }
            rlog("page 1: Continue pressed");
            press(pressableButton(PageKind::isContinue));
            waitUntil(() -> PageKind.decide(PageKind.read(this)) == PageKind.Kind.CALENDAR, 6000, at2 -> {
                if (at2) retryPage2();
                else endRetry("page 2 didn't come after Continue");
            });
        });
    }

    /** The retry's steps, with their times (saved for 📋 Report when it ends). */
    private final StringBuilder retryLog = new StringBuilder();
    private long retryStart;

    private void rlog(String step) {
        if (retryLog.length() == 0) retryStart = android.os.SystemClock.uptimeMillis();
        retryLog.append(String.format(java.util.Locale.ROOT, "%6d ms  ",
                android.os.SystemClock.uptimeMillis() - retryStart)).append(step).append('\n');
    }

    private void saveRetryLog(String end) {
        rlog(end);
        saveReport("A11y Inspector - Auto retry\n=========================\n" + end + "\n\nSTEPS\n" + retryLog);
        retryLog.setLength(0);
    }

    /** Page 2's first empty checkbox, or null (not drawn yet). */
    private AccessibilityNodeInfo emptyBox() {
        for (AccessibilityNodeInfo n : Page.nodes(this)) if (Page.isCheckbox(n) && !n.isChecked()) return n;
        return null;
    }

    private boolean anyBoxTicked() {
        for (AccessibilityNodeInfo n : Page.nodes(this)) if (Page.isCheckbox(n) && n.isChecked()) return true;
        return false;
    }

    /**
     * Page 2 again: everything is still chosen but its checkbox. TTD keeps Continue on all the
     * time and its checkbox doesn't report a tick (it reads empty after it) - so the checkbox is
     * ticked with ONE action (a second try would untick it) and Continue pressed right after.
     */
    private void retryPage2() {
        rlog("page 2: waiting for its checkbox");
        waitUntil(() -> emptyBox() != null || anyBoxTicked(), 3000, seen -> {
            AccessibilityNodeInfo box = emptyBox();
            if (box == null) {
                rlog(seen ? "page 2: checkbox already ticked" : "page 2: no checkbox came in 3 s");
                page2Continue();
                return;
            }
            if (box.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                rlog("page 2: checkbox \"" + cutLabel(box) + "\" - clicked once");
            } else {
                android.graphics.Rect r = Page.visible(box);
                if (r != null) tapAt(r.centerX(), r.centerY());
                rlog("page 2: checkbox click refused - tapped once" + (r == null ? " (not on screen ✗)" : ""));
            }
            handler.postDelayed(() -> {
                if (!destroyed && retrying) page2Continue();
            }, 250);
        });
    }

    private static String cutLabel(AccessibilityNodeInfo n) {
        String l = Page.label(n);
        if (l.isEmpty() && n.getParent() != null) l = Page.label(n.getParent());
        return l.length() > 40 ? l.substring(0, 40) + "…" : l;
    }

    private void tapAt(int x, int y) {
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(x, y);
        dispatchGesture(new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 50))
                .build(), null, null);
    }

    /**
     * Page 2's Continue (also below the screen - brought up and pressed), then page 3. No
     * Continue at all in 2.5 s: a full Go.
     */
    private void page2Continue() {
        waitUntil(() -> pressableButton(PageKind::isContinue) != null, 2500, on -> {
            if (!on) {
                rlog("page 2: no Continue - filling it with Go");
                Toast.makeText(this, "↻ Page 2: no Continue - filling it with Go", Toast.LENGTH_SHORT).show();
                startSaved();
                return;
            }
            rlog("page 2: Continue pressed");
            press(pressableButton(PageKind::isContinue));
            waitUntil(() -> PageKind.decide(PageKind.read(this)) == PageKind.Kind.TICKING, 6000, at3 -> {
                if (at3) retryPage3(0);
                else endRetry("page 3 didn't come after Continue");
            });
        });
    }

    /** Page 3 again: nothing ticked - Continue the moment it is on, then Confirm on page 4 at once. */
    private void retryPage3(int tries) {
        waitUntil(() -> pressableButton(PageKind::isContinue) != null, 3000, on -> {
            if (!on) {
                endRetry("Continue on page 3 stayed off");
                return;
            }
            AccessibilityNodeInfo c = pressableButton(PageKind::isContinue);
            if (c == null) {
                endRetry("Continue on page 3 went");
                return;
            }
            String pkg = c.getPackageName() == null ? "" : c.getPackageName().toString();
            if (flow != null && !testTrip()) flow.watchConfirm(pkg); // page 4's Confirm pressed the moment it comes
            press(c);
            rlog("page 3: Continue pressed - " + (testTrip() ? "test: Confirm left for you" : "Confirm next"));
            Toast.makeText(this, "↻ Page 3: Continue pressed - " + (testTrip() ? "test: Confirm left for you" : "Confirm next"),
                    Toast.LENGTH_SHORT).show();
            // The retry stays on (Auto's own steps paused) until page 3 has gone: no second Continue.
            waitUntil(() -> PageKind.decide(PageKind.read(this)) != PageKind.Kind.TICKING, 6000, gone -> {
                retrying = false;
                testTripEnded();
                saveRetryLog(gone ? "page 3 left - retry done" : "page 3 still shown 6 s after Continue - retry done");
                checkPage();
            });
        });
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
            bg.setStroke(dp(3), 0xFF34C759);
            Toast.makeText(this, "Auto-clear ON: the OK of every new pop-up is pressed. Long-press again to stop.",
                    Toast.LENGTH_LONG).show();
        } else {
            clearButton.setText("✖\nClear");
            GradientDrawable bg = (GradientDrawable) clearButton.getBackground();
            bg.setStroke(Math.round(1.5f * getResources().getDisplayMetrics().density), 0x99FFFFFF);
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
        if (scanning || deleting() || ticker.isRunning() || clearer.isRunning() || booker.isRunning()) return;
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
            if (!autoClear || scanning || r == null || ticker.isRunning() || clearer.isRunning() || booker.isRunning()) return;
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
        boolean on = scanning || deleting() || pendingTick != null || ticker.isRunning() || clearer.isRunning() || booker.isRunning();
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
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setLineSpacing(0, 0.9f);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(label.replace('\n', ' '));
        b.setBackground(Glass.button(colour, getResources().getDisplayMetrics().density));
        b.setElevation(dp(8));
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
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start();
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
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                    handler.removeCallbacks(longPress);
                    if (!dragged[0] && !longDone[0]) onTap.onClick(v);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
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
        box.setBackground(Glass.darkPanel(getResources().getDisplayMetrics().density));
        box.setElevation(dp(12));
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
        // The date: picked on a calendar (tap the date to open / close it) - no typing.
        String[] picked = {prefs.getString("book_date", "")};
        TextView dateLabel = new TextView(this);
        dateLabel.setTextColor(0xFFC7C6FF);
        dateLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dateLabel.setPadding(0, dp(8), 0, 0);
        dateLabel.setText("Date");
        box.addView(dateLabel);
        TextView dateShown = new TextView(this);
        dateShown.setTextColor(Color.WHITE);
        dateShown.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        dateShown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        dateShown.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable dbg = new GradientDrawable();
        dbg.setCornerRadius(dp(14));
        dbg.setColor(0x26FFFFFF);
        dbg.setStroke(dp(1), 0x66FFFFFF);
        dateShown.setBackground(dbg);
        Runnable showDate = () -> dateShown.setText(picked[0].isEmpty()
                ? "📅 Pick a date  (none: left as the page has it)" : "📅 " + picked[0] + "   ▾");
        showDate.run();
        box.addView(dateShown);
        android.widget.FrameLayout calHolder = new android.widget.FrameLayout(this);
        calHolder.setBackground(Glass.lightCard(getResources().getDisplayMetrics().density));
        calHolder.setVisibility(View.GONE);
        android.widget.CalendarView cal = new android.widget.CalendarView(this);
        cal.setMinDate(System.currentTimeMillis() - 1000);
        Booker.Plan was = new Booker.Plan();
        if (Booker.parseDate(picked[0], was)) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.set(was.year, was.month - 1, was.day);
            try {
                cal.setDate(c.getTimeInMillis(), false, true);
            } catch (RuntimeException ignored) {
            }
        }
        cal.setOnDateChangeListener((v, y, m, d) -> {
            picked[0] = d + "/" + (m + 1) + "/" + y;
            showDate.run();
            calHolder.setVisibility(View.GONE);
        });
        calHolder.addView(cal, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(300)));
        // The day already chosen can't be "changed" to itself: this keeps it and closes the calendar.
        TextView keep = new TextView(this);
        keep.setText("✓ Use this date");
        keep.setTextColor(Glass.ACCENT);
        keep.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        keep.setPadding(dp(14), dp(8), dp(14), dp(8));
        keep.setOnClickListener(v -> {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(cal.getDate());
            picked[0] = c.get(java.util.Calendar.DAY_OF_MONTH) + "/" + (c.get(java.util.Calendar.MONTH) + 1)
                    + "/" + c.get(java.util.Calendar.YEAR);
            showDate.run();
            calHolder.setVisibility(View.GONE);
        });
        calHolder.addView(keep, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END));
        LinearLayout.LayoutParams calLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        calLp.topMargin = dp(6);
        box.addView(calHolder, calLp);
        dateShown.setOnClickListener(v -> calHolder.setVisibility(
                calHolder.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        dateShown.setOnLongClickListener(v -> {
            picked[0] = "";
            showDate.run();
            Toast.makeText(this, "No date: Go leaves it as the page has it", Toast.LENGTH_SHORT).show();
            return true;
        });
        android.widget.EditText radio = field(box, "Radio button (a few of its words; empty = the first)",
                "e.g. Batch A", prefs.getString("book_radio", ""), android.text.InputType.TYPE_CLASS_TEXT);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams formRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        formRowLp.topMargin = dp(10);
        row.setLayoutParams(formRowLp);
        row.addView(cardButton("Cancel", v -> closeAsk()), cardButtonLp(true));
        // Save: kept for ▶ Go. Save & Start: kept, and filled now.
        for (boolean startNow : new boolean[] {false, true}) {
            row.addView(cardButton(startNow ? "Save & Start" : "Save", v -> {
                Booker.Plan plan = new Booker.Plan();
                plan.option = option.getText().toString().trim();
                plan.radio = radio.getText().toString().trim();
                String d = picked[0];
                if (!d.isEmpty() && !Booker.parseDate(d, plan)) d = "";
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
                    booker.setHandOver(linkOn());
                    booker.start(plan);
                }, 400);
            }, startNow), cardButtonLp(true));
        }
        box.addView(row);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                getResources().getDisplayMetrics().widthPixels * 90 / 100,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                0, // kept inside the screen: a tall form scrolls
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(90);
        lp.softInputMode = choices.isEmpty() ? WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                : WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(box);
        try {
            windowManager.addView(formScroll, lp);
            ask = formScroll;
            if (choices.isEmpty()) option.requestFocus();
        } catch (RuntimeException e) {
            Toast.makeText(this, "Couldn't show the form: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private android.widget.EditText field(LinearLayout box, String label, String hint, String value, int type) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFFC7C6FF);
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
        if (busy()) return;
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
        if (busy()) return;
        recordPkg = appInFront();
        if (recordPkg.isEmpty()) {
            Toast.makeText(this, "No app in front to record", Toast.LENGTH_SHORT).show();
            return;
        }
        scanning = true;
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
            String page = pageLine();
            r = new Scanner.Result(page + r.summary, page + r.report);
        } catch (RuntimeException e) {
            r = new Scanner.Result("Scan failed: " + e, "Scan failed: " + e);
        }
        if (!deep) {
            done(r.summary, r.report, false);
            return;
        }
        Scanner.Result scan = r;
        String evidence;
        try { evidence = PopupDiagnostics.treeEvidence(this); }
        catch (RuntimeException e) { evidence = "\nPopup diagnostics failed: " + e; }
        String details = evidence;
        // Hide our buttons briefly so the screenshot shows the page underneath.
        View[] overlays = shownButtons();
        int[] visibility = new int[overlays.length];
        for (int i = 0; i < overlays.length; i++) {
            visibility[i] = overlays[i] == null ? View.GONE : overlays[i].getVisibility();
            if (overlays[i] != null) overlays[i].setVisibility(View.INVISIBLE);
        }
        handler.postDelayed(() -> PopupDiagnostics.capture(this, visual -> {
            if (destroyed) return;
            for (int i = 0; i < overlays.length; i++)
                if (overlays[i] != null) overlays[i].setVisibility(visibility[i]);
            String watch = popupWatchReport;
            popupWatchReport = "";
            done(scan.summary + "\nVisual popup evidence collected; see Full report for the PNG or capture error.",
                    scan.report + details + watch + visual, true);
        }), 400);
    }

    private void done(String summary, String report, boolean deep) {
        saveReport(report);
        lastSummary = summary;
        scanning = false;
        button.setAlpha(1f);
        if (deep) {
            showCard(summary, new String[] {"Page code", "Whole page ↓", "Record 15 s", "Watch popup 15 s"},
                    new View.OnClickListener[] {v -> pageCode(), v -> wholePage(), v -> record(), v -> watchPopup()});
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
        if (busy()) return;
        if (wholePage == null) wholePage = new WholePage(this);
        scanning = true;
        button.setAlpha(0.5f);
        wholePage.walk((sum, rep) -> done(sum, rep, false));
    }

    /** Start before triggering the popup; leave it open for the final screenshot. */
    private void watchPopup() {
        if (busy()) return;
        closeCard();
        scanning = true;
        button.setAlpha(0.5f);
        final String pkg = appInFront();
        final java.util.Map<String, String> baseline;
        try { baseline = PopupDiagnostics.snapshot(this); }
        catch (RuntimeException e) {
            done("Popup watch failed", "Popup watch failed: " + e, false);
            return;
        }
        Toast.makeText(this, "15 s: trigger the popup now and leave it open. Auto-clear pauses during capture.",
                Toast.LENGTH_LONG).show();
        watchPopupStep(pkg, baseline, baseline, new StringBuilder("\nPOPUP WATCH (500 ms samples, 15 s)\n"
                + "Node IDs are per snapshot/session; index matching on older Android is approximate.\n"), 0);
    }

    private void watchPopupStep(String pkg, java.util.Map<String, String> baseline,
                               java.util.Map<String, String> previous, StringBuilder log, int step) {
        handler.postDelayed(() -> {
            if (destroyed) return;
            try {
                if (!pkg.equals(appInFront())) {
                    done("Popup watch stopped: app changed", log + "\nApp changed; capture stopped.", false);
                    return;
                }
                java.util.Map<String, String> now = PopupDiagnostics.snapshot(this);
                String delta = PopupDiagnostics.changes(previous, now);
                if (!delta.isEmpty() && log.length() < 150000)
                    log.append("\nAt ").append((step + 1) * 500).append(" ms\n").append(delta);
                if (step + 1 < 30) watchPopupStep(pkg, baseline, now, log, step + 1);
                else {
                    log.append("\nFINAL COMPARED WITH START\n").append(PopupDiagnostics.changes(baseline, now));
                    popupWatchReport = log.toString();
                    finish(true);
                }
            } catch (RuntimeException e) {
                done("Popup watch failed", log + "\n" + e, false);
            }
        }, 500);
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
        box.setBackground(Glass.darkPanel(getResources().getDisplayMetrics().density));
        box.setElevation(dp(12));

        TextView text = new TextView(this);
        text.setText(summary);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        text.setLineSpacing(dp(2), 1f);
        text.setTextIsSelectable(false);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        box.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dp(360), getResources().getDisplayMetrics().heightPixels / 2)));

        View line = new View(this);
        line.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lineLp.topMargin = dp(10);
        lineLp.bottomMargin = dp(6);
        box.addView(line, lineLp);
        if (extras.length > 0) {
            // The deeper looks: two to a row, each button the same width, with a gap between.
            for (int i = 0; i < extras.length; i += 2) {
                LinearLayout more = new LinearLayout(this);
                for (int j = i; j < i + 2; j++) {
                    View b = j < extras.length ? cardButton(extraLabels[j], extras[j], false) : new View(this);
                    more.addView(b, cardButtonLp(true));
                }
                box.addView(more);
            }
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        row.addView(cardButton("Close", v -> closeCard(), false), cardButtonLp(false));
        row.addView(cardButton("Full report", v -> {
            closeCard();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }, true), cardButtonLp(false));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(4);
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
        return cardButton(label, onClick, false);
    }

    /** A rounded button on a card: outlined green, or filled green when it's the main one. */
    private TextView cardButton(String label, View.OnClickListener onClick, boolean main) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(main ? Glass.ACCENT : Color.WHITE);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(44));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        // Glass buttons: the main one bright white, the others clear with a white rim.
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        if (main) bg.setColor(0xF2FFFFFF);
        else {
            bg.setColor(0x26FFFFFF);
            bg.setStroke(dp(1), 0x66FFFFFF);
        }
        b.setBackground(bg);
        b.setOnClickListener(onClick);
        return b;
    }

    /** Space around a card button, so buttons side by side never touch; {@code fill}: share the row equally. */
    private LinearLayout.LayoutParams cardButtonLp(boolean fill) {
        LinearLayout.LayoutParams lp = fill
                ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(5), dp(5), dp(5), dp(5));
        return lp;
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
