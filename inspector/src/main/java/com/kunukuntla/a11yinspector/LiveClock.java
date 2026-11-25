package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

/**
 * 🕒 A live clock in the top-right corner (the phone's time, to a tenth of a second), for
 * pressing at the exact moment a booking opens. Drag it to move it (the place is kept);
 * ⚙ Settings "🕒 Clock" shows / hides it.
 */
final class LiveClock {

    private static TextView view;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static Runnable tick;

    @SuppressLint("ClickableViewAccessibility")
    static void show(AccessibilityService service) {
        hide();
        WindowManager wm = service.getSystemService(WindowManager.class);
        float d = service.getResources().getDisplayMetrics().density;
        SharedPreferences sp = service.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE);
        TextView t = new TextView(service);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        t.setPadding((int) (10 * d), (int) (4 * d), (int) (10 * d), (int) (4 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(14 * d);
        bg.setColor(0xCC1B1B1F);
        t.setBackground(bg);
        t.setElevation(4 * d);
        t.setContentDescription("Clock");
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        // Top-right, just under the status bar (over the page's empty header corner); or where you dragged it.
        lp.x = sp.getInt("clock_x", (int) (8 * d));
        lp.y = sp.getInt("clock_y", (int) (62 * d));
        float[] down = new float[2];
        int[] start = new int[2];
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    start[0] = lp.x;
                    start[1] = lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // Gravity END: x counts from the right edge, so a move right makes it smaller.
                    lp.x = Math.max(0, start[0] - (int) (e.getRawX() - down[0]));
                    lp.y = Math.max(0, start[1] + (int) (e.getRawY() - down[1]));
                    try {
                        wm.updateViewLayout(t, lp);
                    } catch (RuntimeException ignored) {
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    sp.edit().putInt("clock_x", lp.x).putInt("clock_y", lp.y).apply();
                    return true;
                default:
                    return true;
            }
        });
        try {
            wm.addView(t, lp);
        } catch (RuntimeException e) {
            return;
        }
        view = t;
        tick = new Runnable() {
            @Override
            public void run() {
                if (view != t) return;
                boolean on = sp.getBoolean("show_clock", true);
                t.setVisibility(on ? View.VISIBLE : View.GONE);
                if (on) {
                    Calendar c = Calendar.getInstance();
                    int h = c.get(Calendar.HOUR);
                    if (h == 0) h = 12;
                    t.setText(String.format(Locale.ROOT, "%d:%02d:%02d.%d %s", h, c.get(Calendar.MINUTE),
                            c.get(Calendar.SECOND), c.get(Calendar.MILLISECOND) / 100,
                            c.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM"));
                }
                // Each tenth of a second, lined up with the clock's own tenths; hidden: once a second.
                long ms = System.currentTimeMillis() % 100;
                HANDLER.postDelayed(this, on ? 100 - ms : 1000);
            }
        };
        HANDLER.post(tick);
    }

    static void hide() {
        if (tick != null) HANDLER.removeCallbacks(tick);
        tick = null;
        if (view != null) {
            try {
                view.getContext().getSystemService(WindowManager.class).removeView(view);
            } catch (RuntimeException ignored) {
            }
            view = null;
        }
    }

    /** The calendar's screenshot (▶ Go, 📅 Book): hidden meanwhile. */
    private static int before = View.VISIBLE;

    static void hideForShot(boolean hide) {
        if (view == null) return;
        if (hide) {
            before = view.getVisibility();
            view.setVisibility(View.INVISIBLE);
        } else {
            view.setVisibility(before);
        }
    }
}
