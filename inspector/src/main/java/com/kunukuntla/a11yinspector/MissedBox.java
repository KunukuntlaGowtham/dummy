package com.kunukuntla.a11yinspector;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A small red box in the top-right corner, during a Tick run: the rows not added so far, each
 * numbered as it will be once the rows not added before it are taken off the list (each one
 * removed moves the later rows up by one) - rows 5, 11 show as 5, 10; rows 2, 7, 14 as 2, 6, 12.
 * It takes no touches, so taps (yours and Tick's) go through to the page.
 */
final class MissedBox {

    private final Context context;
    private final WindowManager windowManager;
    private TextView box;

    MissedBox(Context context, WindowManager windowManager) {
        this.context = context;
        this.windowManager = windowManager;
    }

    /** Rows not added (as Tick numbers them) -> their numbers once the earlier ones are removed. */
    static List<Integer> afterRemoving(List<String> rows) {
        List<Integer> n = new ArrayList<>();
        for (String r : rows) {
            try {
                n.add(Integer.parseInt(r.trim()));
            } catch (NumberFormatException ignored) {
                // "?" - a box that never came on screen has no row number
            }
        }
        Collections.sort(n);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n.size(); i++) out.add(n.get(i) - i);
        return out;
    }

    private static String join(List<?> l) {
        StringBuilder sb = new StringBuilder();
        for (Object o : l) sb.append(sb.length() == 0 ? "" : ", ").append(o);
        return sb.toString();
    }

    /** Shows the rows (hidden when there are none). */
    void show(List<String> rows) {
        List<Integer> moved = afterRemoving(rows);
        if (moved.isEmpty()) {
            hide();
            return;
        }
        if (box == null) {
            box = new TextView(context);
            box.setTextColor(Color.WHITE);
            box.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            box.setGravity(Gravity.CENTER);
            int p = dp(8);
            box.setPadding(dp(12), p, dp(12), p);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF0C62828);
            bg.setCornerRadius(dp(10));
            bg.setStroke(dp(2), Color.WHITE);
            box.setBackground(bg);
            box.setElevation(dp(6));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = dp(10);
            lp.y = dp(64);
            try {
                windowManager.addView(box, lp);
            } catch (RuntimeException e) {
                box = null;
                return;
            }
        }
        box.setText("❌ Not added\n" + join(moved));
        box.setContentDescription("Not added: " + join(moved));
    }

    void hide() {
        if (box == null) return;
        try {
            windowManager.removeView(box);
        } catch (RuntimeException ignored) {
        }
        box = null;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics()));
    }
}
