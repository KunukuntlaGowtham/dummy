package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Teach: you tap the things yourself - the dustbin, each pop-up's button - and the app
 * notes what is there (the page's button, how it looks, what the pop-up adds to the page).
 * Every tap is passed on to the app underneath, so it really happens (a checkbox is ticked,
 * a row is deleted). Tick and Del then press those straight away.
 */
final class Teacher {

    private final AccessibilityService service;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ScreenWords words;
    private final Consumer<String> done;
    private final StringBuilder learned = new StringBuilder();
    private View layer;

    Teacher(AccessibilityService service, Consumer<String> done) {
        this.service = service;
        this.wm = service.getSystemService(WindowManager.class);
        this.words = new ScreenWords(service);
        this.done = done;
    }

    boolean isActive() {
        return layer != null;
    }

    void cancel() {
        hide();
    }

    // ---- the two lessons -------------------------------------------------------------

    void teachTick() {
        learned.setLength(0);
        ask("Teach Tick 1/2: tap an EMPTY checkbox (it will be ticked).", null, (x, y) ->
                record(x, y, null, b -> askTickPopup()));
    }

    private void askTickPopup() {
        ask("Teach Tick 2/2: when the pop-up is up, tap its OK.", "No pop-up came", (x, y) -> {
            if (x < 0) {
                Taught.remove(service, Taught.TICK_POPUP);
                finish("No pop-up after a tick: Tick won't wait for one.");
                return;
            }
            record(x, y, Taught.TICK_POPUP, b -> finish(null));
        });
    }

    void teachDelete() {
        learned.setLength(0);
        ask("Teach Del 1/3: tap the DUSTBIN of a row you want deleted (it WILL be deleted).", null,
                (x, y) -> record(x, y, Taught.DEL_BIN, b -> askDelPopup(1)));
    }

    private void askDelPopup(int which) {
        String key = which == 1 ? Taught.DEL_POPUP_1 : Taught.DEL_POPUP_2;
        String text = which == 1
                ? "Teach Del 2/3: tap the first pop-up's button (Yes / Delete)."
                : "Teach Del 3/3: tap the second pop-up's button (OK).";
        ask(text, which == 1 ? "No pop-up came" : "No second pop-up", (x, y) -> {
            if (x < 0) {
                Taught.remove(service, key);
                if (which == 1) Taught.remove(service, Taught.DEL_POPUP_2);
                learned.append(which == 1 ? "• No pop-up after the dustbin\n" : "• No second pop-up\n");
                finish(null);
                return;
            }
            record(x, y, key, b -> {
                if (which == 1) askDelPopup(2);
                else finish(null);
            });
        });
    }

    private void finish(String extra) {
        hide();
        done.accept("Learned ✓\n" + learned + (extra == null ? "" : extra + "\n")
                + "Tick and Del now press these straight away.");
    }

    // ---- noting one tap ----------------------------------------------------------------

    private interface Tap {
        /** Where you tapped; (-1, -1) for the banner's "no pop-up" button. */
        void at(int x, int y);
    }

    /**
     * Notes what is at (x, y) - the page's button, how the spot looks, the page's elements -
     * then passes the tap on to the app and, 0.7 s later, notes what went away with it.
     */
    private void record(int x, int y, String key, Consumer<Taught.Button> next) {
        Taught.Button b = new Taught.Button();
        int w = dp(30), h = dp(18);
        b.spot = new Rect(x - w, y - h, x + w, y + h);
        AccessibilityNodeInfo node = Taught.buttonAt(service, x, y);
        if (node != null) {
            b.cls = String.valueOf(node.getClassName());
            b.label = Taught.label(node);
        }
        rowNumberDy(b, x, y);
        Set<String> with = Taught.shapes(service);
        words.shot(false, shot -> {
            if (shot != null) b.look = shot.grid(b.spot);
            tap(x, y);
            handler.postDelayed(() -> {
                Set<String> after = Taught.shapes(service);
                Set<String> only = new HashSet<>(with);
                only.removeAll(after);
                b.shapes = only;
                words.shot(false, later -> {
                    if (later != null) b.gone = later.grid(b.spot);
                    if (key != null) {
                        Taught.put(service, key, b);
                        learned.append("• ").append(name(key)).append(" at ").append(x).append(',').append(y)
                                .append(b.label.isEmpty() ? "" : " (\"" + b.label + "\")")
                                .append(key.equals(Taught.DEL_BIN) ? ""
                                        : b.shapes.isEmpty() ? " - found by its look"
                                        : " - seen on the page (" + b.shapes.size() + " elements)")
                                .append('\n');
                    }
                    next.accept(b);
                });
            }, 700);
        });
    }

    private static String name(String key) {
        switch (key) {
            case Taught.TICK_POPUP: return "Tick's pop-up OK";
            case Taught.DEL_BIN: return "Dustbin";
            case Taught.DEL_POPUP_1: return "Del pop-up 1 button";
            default: return "Del pop-up 2 button";
        }
    }

    /** For the dustbin: how far it sits below the number on its row (0 when level with it). */
    private void rowNumberDy(Taught.Button b, int x, int y) {
        int bestGap = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : Taught.nodes(service)) {
            if (!Taught.label(n).matches("\\(?\\d{1,4}[.)]?")) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.height() <= 0 || r.right > x || Math.abs(r.centerY() - y) > dp(40)) continue;
            int gap = x - r.right;
            if (gap < bestGap) {
                bestGap = gap;
                b.dy = y - r.centerY();
                b.hasDy = true;
            }
        }
    }

    // ---- the see-through layer that catches your tap ------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private void ask(String text, String noLabel, Tap onTap) {
        hide();
        FrameLayout root = new FrameLayout(service);
        root.setBackgroundColor(0x33000000);

        LinearLayout banner = new LinearLayout(service);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(dp(16), dp(12), dp(16), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0E65100);
        bg.setCornerRadius(dp(14));
        banner.setBackground(bg);
        banner.setClickable(true); // a touch on the banner isn't taken for your tap
        TextView msg = new TextView(service);
        msg.setText(text);
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        banner.addView(msg);
        LinearLayout row = new LinearLayout(service);
        row.setGravity(Gravity.END);
        if (noLabel != null) row.addView(button(noLabel, v -> onTap.at(-1, -1)));
        row.addView(button("Cancel", v -> hide()));
        banner.addView(row);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        blp.setMargins(dp(12), dp(40), dp(12), 0);
        root.addView(banner, blp);

        boolean[] used = {false};
        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() != MotionEvent.ACTION_UP || used[0]) return true;
            used[0] = true;
            int x = Math.round(e.getRawX()), y = Math.round(e.getRawY());
            hide();
            // Let the layer go before the screen is looked at and the tap is passed on.
            handler.postDelayed(() -> onTap.at(x, y), 120);
            return true;
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(root, lp);
        layer = root;
    }

    private TextView button(String label, View.OnClickListener onClick) {
        TextView b = new TextView(service);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setOnClickListener(onClick);
        return b;
    }

    private void hide() {
        if (layer == null) return;
        try {
            wm.removeView(layer);
        } catch (RuntimeException ignored) {
        }
        layer = null;
    }

    private void tap(int x, int y) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    private int dp(int v) {
        return Math.round(v * service.getResources().getDisplayMetrics().density);
    }
}
