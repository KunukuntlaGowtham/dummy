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
import java.util.List;
import java.util.Locale;

/**
 * Bin Press. Opened on the sevaks list, the round 🗑 button starts it: for each number you typed
 * in the app (highest first) it finds that profile, taps its delete bin, and when a pop-up comes it
 * presses Back. Then it checks that the profile is gone and goes on to the next number.
 * Independent of any other app: its own reading of the page, its own taps.
 */
public class BinService extends AccessibilityService {

    static final String PREFS = "binpress";
    static final String KEY_NUMBERS = "numbers", KEY_BACKS = "backs", KEY_LOG = "log";

    static volatile boolean connected;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private TextView button;
    private boolean running;
    private int gen;
    private long startedAt;

    private final List<Integer> todo = new ArrayList<>();
    private final List<String> deleted = new ArrayList<>(), failed = new ArrayList<>();
    private final StringBuilder log = new StringBuilder();

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

    private void start() {
        List<Integer> numbers = Numbers.parse(prefs().getString(KEY_NUMBERS, ""));
        if (numbers.isEmpty()) {
            Toast.makeText(this, "Open Bin Press and type the numbers to delete first", Toast.LENGTH_LONG).show();
            return;
        }
        running = true;
        gen++;
        startedAt = SystemClock.uptimeMillis();
        todo.clear();
        todo.addAll(numbers);
        deleted.clear();
        failed.clear();
        log.setLength(0);
        setButton(true);
        say("deleting " + numbers + " (highest first); " + backs() + " Back press(es) when a pop-up comes");
        handler.postDelayed(this::next, 150);
    }

    private void stop(String why) {
        if (!running) return;
        running = false;
        gen++;
        setButton(false);
        say("END: " + why);
        StringBuilder sum = new StringBuilder(why).append('\n');
        if (!deleted.isEmpty()) sum.append("Deleted: ").append(String.join(", ", deleted)).append('\n');
        if (!failed.isEmpty()) sum.append("NOT deleted: ").append(String.join(", ", failed)).append('\n');
        String text = sum + "\nSTEPS\n" + log;
        prefs().edit().putString(KEY_LOG, text).apply();
        Toast.makeText(this, sum.toString().trim(), Toast.LENGTH_LONG).show();
    }

    private int backs() {
        return Math.max(1, Math.min(3, prefs().getInt(KEY_BACKS, 1)));
    }

    private void say(String s) {
        log.append(String.format(Locale.ROOT, "%6d ms  ", SystemClock.uptimeMillis() - startedAt)).append(s).append('\n');
    }

    private void next() {
        if (!running) return;
        if (todo.isEmpty()) {
            stop(failed.isEmpty() ? "Done - all deleted" : "Done - some not deleted");
            return;
        }
        number = todo.remove(0);
        tapTries = 0;
        findAndTap(0);
    }

    private int number, tapTries;
    private String name = "";
    private int cardsBefore;

    /** Find the profile (up to ~3 s: the page may still be drawing), bring its bin on screen, tap it. */
    private void findAndTap(int tries) {
        if (!running) return;
        int g = gen;
        Cards.Card card = Cards.find(this, number);
        if (card == null || card.bin == null) {
            if (tries < 10) {
                handler.postDelayed(() -> {
                    if (g == gen) findAndTap(tries + 1);
                }, 300);
                return;
            }
            say("profile " + number + ": " + (card == null ? "not on the page" : "has no bin") + " ✗");
            failed.add(String.valueOf(number));
            next();
            return;
        }
        Rect r = Cards.where(card.bin);
        if (r == null) {
            if (tries < 10) {
                card.bin.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                handler.postDelayed(() -> {
                    if (g == gen) findAndTap(tries + 1);
                }, 400);
                return;
            }
            say("profile " + number + " " + card.name + ": its bin never came on screen ✗");
            failed.add(number + " " + card.name);
            next();
            return;
        }
        name = card.name;
        cardsBefore = Cards.read(this).size();
        String sig = Cards.signature(this);
        say("profile " + number + " " + name + ": tapping its bin at " + r.centerX() + "," + r.centerY());
        tap(r.centerX(), r.centerY());
        waitForPopup(sig, SystemClock.uptimeMillis());
    }

    /** The page changed (a pop-up came): up to 3 s. */
    private void waitForPopup(String sig, long since) {
        if (!running) return;
        int g = gen;
        handler.postDelayed(() -> {
            if (g != gen) return;
            boolean changed = !Cards.signature(this).equals(sig);
            long waited = SystemClock.uptimeMillis() - since;
            if (changed) {
                say("  pop-up came after " + waited + " ms");
                handler.postDelayed(() -> pressBack(0), 350); // a moment to be drawn
            } else if (waited >= 3000) {
                say("  no pop-up came");
                if (tapTries < 1 && Cards.read(this).size() == cardsBefore) {
                    tapTries++;
                    say("  tapping the bin once more");
                    findAndTap(0);
                } else {
                    checkGone();
                }
            } else {
                waitForPopup(sig, since);
            }
        }, 100);
    }

    private void pressBack(int done) {
        if (!running) return;
        say("  Back pressed (" + (done + 1) + " of " + backs() + ")");
        performGlobalAction(GLOBAL_ACTION_BACK);
        if (done + 1 < backs()) {
            int g = gen;
            handler.postDelayed(() -> {
                if (g == gen) pressBack(done + 1);
            }, 600);
        } else {
            checkGone();
        }
    }

    /** Gone: fewer profiles on the page, seen on two looks in a row (not a page still redrawing); up to 3 s. */
    private void checkGone() {
        gone(SystemClock.uptimeMillis(), -1);
    }

    private void gone(long since, int last) {
        if (!running) return;
        int g = gen;
        handler.postDelayed(() -> {
            if (g != gen) return;
            int now = Cards.read(this).size();
            boolean ok = now > 0 && now < cardsBefore && now == last;
            if (ok) {
                say("profile " + number + " " + name + ": deleted ✓ (" + (SystemClock.uptimeMillis() - since) + " ms)");
                deleted.add(number + " " + name);
                handler.postDelayed(this::next, 200);
            } else if (SystemClock.uptimeMillis() - since >= 3000) {
                say("profile " + number + " " + name + ": still there ✗ (" + now + " profiles, were " + cardsBefore + ")");
                failed.add(number + " " + name);
                handler.postDelayed(this::next, 200);
            } else {
                gone(since, now);
            }
        }, 150);
    }

    private void tap(int x, int y) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }
}
