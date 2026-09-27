package com.kunukuntla.tickcheck;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A floating Check button. You tick the boxes and close the pop-ups yourself; tap Check and
 * it lists, in page order, the rows still not ticked. Only reads the page.
 */
public class CheckService extends AccessibilityService {

    static final String RESULT_FILE = "last_check.txt";

    private WindowManager wm;
    private TextView button;
    private WindowManager.LayoutParams params;
    private View card;

    @Override
    protected void onServiceConnected() {
        wm = getSystemService(WindowManager.class);
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
        closeCard();
        if (button != null) {
            try {
                wm.removeView(button);
            } catch (RuntimeException ignored) {
            }
        }
        super.onDestroy();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showButton() {
        button = new TextView(this);
        button.setText("☐?\nCheck");
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xEE6A2C91);
        bg.setStroke(dp(2), 0x66FFFFFF);
        button.setBackground(bg);
        button.setElevation(dp(4));
        params = new WindowManager.LayoutParams(dp(60), dp(60),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(8);
        params.y = dp(200);
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        float[] down = new float[2];
        int[] start = new int[2];
        boolean[] dragged = {false};
        button.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    start[0] = params.x;
                    start[1] = params.y;
                    dragged[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (!dragged[0] && Math.hypot(dx, dy) > slop) dragged[0] = true;
                    if (dragged[0]) {
                        params.x = start[0] - (int) dx; // anchored on the right
                        params.y = start[1] + (int) dy;
                        wm.updateViewLayout(button, params);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragged[0]) {
                        if (card != null) closeCard();
                        else check();
                    }
                    return true;
                default:
                    return true;
            }
        });
        wm.addView(button, params);
    }

    private void check() {
        List<Rows.Row> rows;
        try {
            rows = Rows.read(this);
        } catch (RuntimeException e) {
            showCard("Couldn't read the page: " + e.getMessage(), "");
            return;
        }
        if (rows.isEmpty()) {
            showCard("No checkboxes on this page.", "");
            return;
        }
        List<String> not = new ArrayList<>(), yes = new ArrayList<>();
        for (Rows.Row r : rows) (r.ticked ? yes : not).add(r.show());
        StringBuilder text = new StringBuilder();
        if (not.isEmpty()) {
            text.append("✅ All ").append(rows.size()).append(" ticked\n");
        } else {
            text.append("☐ NOT ticked (").append(not.size()).append(" of ").append(rows.size())
                    .append("), in order:\n");
            for (String s : not) text.append("   ").append(s).append('\n');
        }
        if (!yes.isEmpty()) {
            text.append("\n☑ Ticked (").append(yes.size()).append("): ");
            List<String> nums = new ArrayList<>();
            for (Rows.Row r : rows) if (r.ticked) nums.add(r.number.isEmpty() ? "?" : r.number);
            text.append(String.join(", ", nums)).append('\n');
        }
        List<String> notNums = new ArrayList<>();
        for (Rows.Row r : rows) if (!r.ticked) notNums.add(r.number.isEmpty() ? "?" : r.number);
        String copy = String.join(", ", notNums);
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        try (FileOutputStream out = openFileOutput(RESULT_FILE, MODE_PRIVATE)) {
            out.write(("Tick Check - " + stamp + "\n\n" + text).getBytes());
        } catch (java.io.IOException ignored) {
        }
        showCard(text.toString().trim(), copy);
    }

    private void showCard(String text, String copy) {
        closeCard();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01B1D22);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.DEFAULT);
        ScrollView sc = new ScrollView(this);
        sc.addView(t);
        box.addView(sc, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        if (!copy.isEmpty()) {
            row.addView(cardButton("Copy numbers", v -> {
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Not ticked", copy));
                Toast.makeText(this, "Copied: " + copy, Toast.LENGTH_SHORT).show();
            }));
        }
        row.addView(cardButton("Check again", v -> check()));
        row.addView(cardButton("Close", v -> closeCard()));
        box.addView(row);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                getResources().getDisplayMetrics().widthPixels * 90 / 100,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        wm.addView(box, lp);
        card = box;
        // Keep it on screen at most 60% of the height.
        box.post(() -> {
            int max = getResources().getDisplayMetrics().heightPixels * 6 / 10;
            if (box.getHeight() > max) {
                lp.height = max;
                wm.updateViewLayout(box, lp);
            }
        });
    }

    private TextView cardButton(String label, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(0xFFD1A6F0);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setOnClickListener(onClick);
        return b;
    }

    private void closeCard() {
        if (card == null) return;
        try {
            wm.removeView(card);
        } catch (RuntimeException ignored) {
        }
        card = null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
