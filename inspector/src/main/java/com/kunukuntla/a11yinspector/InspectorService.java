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
 * Shows a floating Scan button over any app. Tap: scan the screen now. Long press: deep scan
 * (wake the web views first, wait, then scan). The result shows in a card over the page and is
 * kept for the app's own screen (Share / Copy / Save).
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
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        closeCard();
        closeAsk();
        if (ticker != null) ticker.stop("Stopped");
        if (deleter != null) deleter.stop("Stopped");
        if (delButton != null) {
            try {
                windowManager.removeView(delButton);
            } catch (RuntimeException ignored) {
            }
        }
        if (tickButton != null) {
            try {
                windowManager.removeView(tickButton);
            } catch (RuntimeException ignored) {
            }
        }
        if (button != null) {
            try {
                windowManager.removeView(button);
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

    // ---- the Tick button: tick every checkbox directly, clear pop-ups ----------------

    private TextView tickButton;
    private WindowManager.LayoutParams tickParams;
    private Ticker ticker;

    @SuppressLint("ClickableViewAccessibility")
    private void showTickButton() {
        ticker = new Ticker(this, (summary, log) -> {
            try (FileOutputStream out = openFileOutput(REPORT_FILE, MODE_PRIVATE)) {
                out.write(log.getBytes());
            } catch (java.io.IOException ignored) {
            }
            tickButton.setText("☑\nTick");
            showCard(summary);
        });
        tickButton = new TextView(this);
        tickButton.setText("☑\nTick");
        tickButton.setTextColor(Color.WHITE);
        tickButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tickButton.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xEE6A2C91);
        bg.setStroke(dp(2), 0x66FFFFFF);
        tickButton.setBackground(bg);
        tickButton.setElevation(dp(4));
        tickParams = new WindowManager.LayoutParams(dp(56), dp(56),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        tickParams.gravity = Gravity.TOP | Gravity.START;
        tickParams.x = dp(12);
        tickParams.y = dp(226);
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        float[] down = new float[2];
        int[] start = new int[2];
        boolean[] dragged = {false};
        tickButton.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    start[0] = tickParams.x;
                    start[1] = tickParams.y;
                    dragged[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (!dragged[0] && Math.hypot(dx, dy) > slop) dragged[0] = true;
                    if (dragged[0]) {
                        tickParams.x = start[0] + (int) dx;
                        tickParams.y = start[1] + (int) dy;
                        windowManager.updateViewLayout(tickButton, tickParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragged[0]) {
                        closeCard();
                        if (ticker.isRunning()) {
                            ticker.stop("Stopped");
                        } else {
                            tickButton.setText("■\nStop");
                            ticker.start();
                        }
                    }
                    return true;
                default:
                    return true;
            }
        });
        windowManager.addView(tickButton, tickParams);
        showDeleteButton();
    }

    // ---- the Del button: delete the rows you type, clearing the two pop-ups ---------

    private TextView delButton;
    private WindowManager.LayoutParams delParams;
    private Deleter deleter;
    private View ask;

    @SuppressLint("ClickableViewAccessibility")
    private void showDeleteButton() {
        deleter = new Deleter(this, (summary, log) -> {
            try (FileOutputStream out = openFileOutput(REPORT_FILE, MODE_PRIVATE)) {
                out.write(log.getBytes());
            } catch (java.io.IOException ignored) {
            }
            delButton.setText("🗑\nDel");
            showCard(summary);
        });
        delButton = new TextView(this);
        delButton.setText("🗑\nDel");
        delButton.setTextColor(Color.WHITE);
        delButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        delButton.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xEEC62828);
        bg.setStroke(dp(2), 0x66FFFFFF);
        delButton.setBackground(bg);
        delButton.setElevation(dp(4));
        delParams = new WindowManager.LayoutParams(dp(56), dp(56),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        delParams.gravity = Gravity.TOP | Gravity.START;
        delParams.x = dp(12);
        delParams.y = dp(292);
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        float[] down = new float[2];
        int[] start = new int[2];
        boolean[] dragged = {false};
        delButton.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    start[0] = delParams.x;
                    start[1] = delParams.y;
                    dragged[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (!dragged[0] && Math.hypot(dx, dy) > slop) dragged[0] = true;
                    if (dragged[0]) {
                        delParams.x = start[0] + (int) dx;
                        delParams.y = start[1] + (int) dy;
                        windowManager.updateViewLayout(delButton, delParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragged[0]) {
                        closeCard();
                        if (deleter.isRunning()) deleter.stop("Stopped");
                        else askRows();
                    }
                    return true;
                default:
                    return true;
            }
        });
        windowManager.addView(delButton, delParams);
    }

    /** Asks which row numbers to delete, then starts. */
    private void askRows() {
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
        title.setText("Which numbers to delete?");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(title);
        TextView hint = new TextView(this);
        hint.setText("Row numbers on the page, e.g. 2, 4 or 3-5. Each row's delete button is "
                + "pressed and its two pop-ups cleared.");
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(0, dp(4), 0, dp(10));
        box.addView(hint);

        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        input.setText(prefs.getString("delete_rows", ""));
        input.setSelectAllOnFocus(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0x88FFFFFF);
        input.setHint("2, 4");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        box.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        row.addView(cardButton("Cancel", v -> closeAsk()));
        row.addView(cardButton("Delete", v -> {
            String text = input.getText().toString();
            List<Integer> rows = parseRows(text);
            if (rows.isEmpty()) {
                Toast.makeText(this, "Type the row numbers, e.g. 2, 4", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString("delete_rows", text).apply();
            closeAsk();
            delButton.setText("■\nStop");
            // Let the keyboard go down before the page is read.
            handler.postDelayed(() -> deleter.start(rows), 400);
        }));
        box.addView(row);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                getResources().getDisplayMetrics().widthPixels * 88 / 100,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(120);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
        try {
            windowManager.addView(box, lp);
            ask = box;
            input.requestFocus();
            handler.postDelayed(() -> {
                android.view.inputmethod.InputMethodManager im =
                        getSystemService(android.view.inputmethod.InputMethodManager.class);
                if (im != null) im.showSoftInput(input, 0);
            }, 200);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Couldn't show the question: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** "2, 4 6-8" -> [2, 4, 6, 7, 8]. */
    static List<Integer> parseRows(String text) {
        List<Integer> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(?:\\s*-\\s*(\\d+))?").matcher(text);
        while (m.find()) {
            try {
                int a = Integer.parseInt(m.group(1));
                int b = m.group(2) == null ? a : Integer.parseInt(m.group(2));
                for (int i = Math.min(a, b); i <= Math.max(a, b) && out.size() < 200; i++) out.add(i);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
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

    private void finish(boolean deep) {
        Scanner.Result r;
        try {
            r = Scanner.scan(this, deep);
        } catch (RuntimeException e) {
            r = new Scanner.Result("Scan failed: " + e, "Scan failed: " + e);
        }
        try (FileOutputStream out = openFileOutput(REPORT_FILE, MODE_PRIVATE)) {
            out.write(r.report.getBytes());
        } catch (java.io.IOException ignored) {
        }
        scanning = false;
        button.setAlpha(1f);
        showCard(r.summary);
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
