package com.kunukuntla.binpress;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bin Press. Opened on the sevaks list, the round 🗑 button starts it: for each number you typed in
 * the app (highest first) it finds that profile and taps its delete bin - then goes straight on to
 * the next number. It does not wait for a pop-up, never presses Back and never closes a pop-up: it
 * stays on that page and taps the next bin at once. When all are tapped it looks once at the page to
 * see who is gone, and taps again for anyone still there.
 * Independent of any other app: its own reading of the page, its own taps.
 */
public class BinService extends AccessibilityService {

    static final String PREFS = "binpress";
    static final String KEY_NUMBERS = "numbers", KEY_GAP = "gap", KEY_LOG = "log";
    static final int DEFAULT_GAP_MS = 100;

    static volatile boolean connected;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private TextView button;
    private boolean running;
    private int gen;
    private long startedAt;

    private final List<Integer> todo = new ArrayList<>();
    /** The people tapped this run (by name), and how many cards had each name at the start. */
    private final List<String> tapped = new ArrayList<>();
    private final Map<String, Integer> startCounts = new HashMap<>();
    private final StringBuilder log = new StringBuilder();
    private int pass;

    @Override
    protected void onServiceConnected() {
        connected = true;
        windowManager = getSystemService(WindowManager.class);
        showButton();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        connected = false;
        gen++;
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (button != null) {
            try {
                windowManager.removeView(button);
            } catch (RuntimeException ignored) {
            }
        }
        super.onDestroy();
    }

    // ---- the round button ---------------------------------------------------------------

    private void showButton() {
        button = new TextView(this);
        button.setText("🗑\nGo");
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xEEC62828);
        bg.setStroke(3, 0x99FFFFFF);
        button.setBackground(bg);
        button.setElevation(16);
        float d = getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(Math.round(56 * d), Math.round(56 * d),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.round(12 * d);
        lp.y = Math.round(220 * d);
        button.setOnClickListener(v -> {
            if (running) stop("Stopped");
            else start();
        });
        try {
            windowManager.addView(button, lp);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Bin Press: couldn't show its button", Toast.LENGTH_LONG).show();
        }
    }

    private void setButton(boolean on) {
        if (button != null) button.setText(on ? "■\nStop" : "🗑\nGo");
    }

    // ---- the run ------------------------------------------------------------------------

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private int gap() {
        return Math.max(30, Math.min(1500, prefs().getInt(KEY_GAP, DEFAULT_GAP_MS)));
    }

    private void start() {
        List<Integer> numbers = Numbers.parse(prefs().getString(KEY_NUMBERS, ""));
        if (numbers.isEmpty()) {
            Toast.makeText(this, "Open Bin Press and type the numbers to delete first", Toast.LENGTH_LONG).show();
            return;
        }
        running = true;
        gen++;
        pass = 0;
        startedAt = SystemClock.uptimeMillis();
        todo.clear();
        todo.addAll(numbers);
        tapped.clear();
        startCounts.clear();
        log.setLength(0);
        for (Cards.Card c : Cards.read(this)) startCounts.merge(c.name, 1, Integer::sum);
        setButton(true);
        say("tapping the bins of " + numbers + " (highest first), " + gap() + " ms apart - no waiting for pop-ups, no Back");
        next();
    }

    private void stop(String why) {
        if (!running) return;
        running = false;
        gen++;
        setButton(false);
        say("END: " + why);
        String text = why + "\n\nSTEPS\n" + log;
        prefs().edit().putString(KEY_LOG, text).apply();
        Toast.makeText(this, why, Toast.LENGTH_LONG).show();
    }

    private void say(String s) {
        log.append(String.format(Locale.ROOT, "%6d ms  ", SystemClock.uptimeMillis() - startedAt)).append(s).append('\n');
    }

    private int number;

    private void next() {
        if (!running) return;
        if (todo.isEmpty()) {
            verify(SystemClock.uptimeMillis());
            return;
        }
        number = todo.remove(0);
        fire(0);
    }

    /** Find the profile (a moment if the page is still drawing), bring its bin on screen, tap it, go on. */
    private void fire(int tries) {
        if (!running) return;
        int g = gen;
        Cards.Card card = Cards.find(this, number);
        if (card == null || card.bin == null) {
            if (tries < 8) {
                handler.postDelayed(() -> {
                    if (g == gen) fire(tries + 1);
                }, 150);
                return;
            }
            say("profile " + number + ": " + (card == null ? "not on the page" : "has no bin") + " - skipped");
            next();
            return;
        }
        Rect r = Cards.where(card.bin);
        if (r == null) {
            if (tries < 8) {
                card.bin.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                handler.postDelayed(() -> {
                    if (g == gen) fire(tries + 1);
                }, 250);
                return;
            }
            say("profile " + number + " " + card.name + ": its bin never came on screen - skipped");
            next();
            return;
        }
        say("profile " + number + " " + card.name + ": bin tapped at " + r.centerX() + "," + r.centerY());
        if (!tapped.contains(card.name)) tapped.add(card.name);
        tap(r.centerX(), r.centerY(), () -> handler.postDelayed(() -> {
            if (g == gen) next();
        }, gap()));
    }

    /**
     * All bins tapped: the page looked at (up to 3.5 s, until everyone tapped is gone). Anyone still
     * there is tapped again - once - found by name, since their numbers have moved.
     */
    private void verify(long since) {
        if (!running) return;
        int g = gen;
        handler.postDelayed(() -> {
            if (g != gen) return;
            List<String> still = stillThere();
            boolean timeUp = SystemClock.uptimeMillis() - since >= 3500;
            if (still.isEmpty()) {
                finish(still);
            } else if (timeUp) {
                if (pass == 0) {
                    pass = 1;
                    List<Integer> again = new ArrayList<>();
                    for (Cards.Card c : Cards.read(this)) if (still.contains(c.name)) again.add(c.number);
                    java.util.Collections.sort(again, java.util.Collections.reverseOrder());
                    if (again.isEmpty()) {
                        finish(still);
                    } else {
                        say("not gone yet: " + still + " - tapping their bins once more (now " + again + ")");
                        todo.addAll(again);
                        next();
                    }
                } else {
                    finish(still);
                }
            } else {
                verify(since);
            }
        }, 250);
    }

    /** Tapped names with as many cards on the page as at the start. */
    private List<String> stillThere() {
        Map<String, Integer> now = new HashMap<>();
        for (Cards.Card c : Cards.read(this)) now.merge(c.name, 1, Integer::sum);
        List<String> still = new ArrayList<>();
        for (String name : tapped) {
            Integer before = startCounts.get(name);
            Integer after = now.get(name);
            if (before != null && (after == null ? 0 : after) >= before) still.add(name);
        }
        return still;
    }

    private void finish(List<String> still) {
        List<String> gone = new ArrayList<>(tapped);
        gone.removeAll(still);
        StringBuilder sum = new StringBuilder();
        sum.append(still.isEmpty() ? "Done - all gone" : "Done - some still on the list");
        if (!gone.isEmpty()) sum.append("\nGone: ").append(String.join(", ", gone));
        if (!still.isEmpty()) sum.append("\nSTILL THERE: ").append(String.join(", ", still));
        stop(sum.toString());
    }

    private void tap(int x, int y, Runnable then) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50)).build();
        boolean sent = dispatchGesture(gd, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                then.run();
            }

            @Override
            public void onCancelled(GestureDescription g) {
                then.run();
            }
        }, handler);
        if (!sent) then.run();
    }
}
