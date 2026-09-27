package com.kunukuntla.tickcheck;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
 * Two floating buttons. You tick the boxes and close the pop-ups yourself; after every 3-4
 * ticks tap Save (it notes every row and whether it is ticked); at the end tap List for the
 * rows never ticked. Only reads the page.
 */
public class CheckService extends AccessibilityService {

    static final String RESULT_FILE = "last_check.txt";

    private WindowManager wm;
    private TextView button;
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
        for (View b : new View[] {button, listButton}) {
            if (b == null) continue;
            try {
                wm.removeView(b);
            } catch (RuntimeException ignored) {
            }
        }
        super.onDestroy();
    }

    // ---- the two buttons: Save (after every few ticks) and List (at the end) ------------

    private TextView listButton;

    private void showButton() {
        button = floating("☑+\nSave #" + (captures() + 1), 0xEE6A2C91, dp(200), v -> capture());
        listButton = floating("📋\nList", 0xEE2E7D32, dp(272), v -> {
            if (card != null) closeCard();
            else showList();
        });
    }

    /** A round button on the right edge; drag it to move it. */
    @SuppressLint("ClickableViewAccessibility")
    private TextView floating(String text, int colour, int y, View.OnClickListener onTap) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(colour);
        bg.setStroke(dp(2), 0x66FFFFFF);
        b.setBackground(bg);
        b.setElevation(dp(4));
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(dp(60), dp(60),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = dp(8);
        p.y = y;
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        float[] down = new float[2];
        int[] start = new int[2];
        boolean[] dragged = {false};
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    start[0] = p.x;
                    start[1] = p.y;
                    dragged[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (!dragged[0] && Math.hypot(dx, dy) > slop) dragged[0] = true;
                    if (dragged[0]) {
                        p.x = start[0] - (int) dx; // anchored on the right
                        p.y = start[1] + (int) dy;
                        wm.updateViewLayout(b, p);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragged[0]) onTap.onClick(b);
                    return true;
                default:
                    return true;
            }
        });
        wm.addView(b, p);
        return b;
    }

    // ---- the list kept across the Saves --------------------------------------------------

    /**
     * Every row seen in any Save, in the order first seen: number -> "1" ticked / "0" not,
     * plus its name. A later Save updates a row (you may have ticked it since).
     */
    private final java.util.LinkedHashMap<String, Boolean> state = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, String> names = new java.util.HashMap<>();

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("list", MODE_PRIVATE);
    }

    private int captures() {
        return prefs().getInt("captures", 0);
    }

    private void load() {
        state.clear();
        names.clear();
        String saved = prefs().getString("rows", "");
        for (String line : saved.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 3) continue;
            state.put(f[0], f[1].equals("1"));
            if (!f[2].isEmpty()) names.put(f[0], f[2]);
        }
    }

    private void save(int captures) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Boolean> e : state.entrySet()) {
            String name = names.get(e.getKey());
            sb.append(e.getKey()).append('\t').append(e.getValue() ? "1" : "0").append('\t')
                    .append(name == null ? "" : name.replace('\t', ' ').replace('\n', ' ')).append('\n');
        }
        prefs().edit().putString("rows", sb.toString()).putInt("captures", captures).apply();
    }

    private Snap snap;
    private boolean saving;

    /** Save: a screenshot of the page now - its empty boxes and their numbers - added to the list. */
    private void capture() {
        if (saving) return;
        closeCard();
        if (snap == null) snap = new Snap(this);
        saving = true;
        // Our buttons out of the picture first.
        button.setVisibility(View.INVISIBLE);
        listButton.setVisibility(View.INVISIBLE);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> snap.take(dp(14), dp(48),
                result -> {
                    showButtons();
                    try {
                        process(result);
                    } catch (RuntimeException e) {
                        Toast.makeText(this, "Save failed: " + e, Toast.LENGTH_LONG).show();
                    }
                },
                why -> {
                    showButtons();
                    Toast.makeText(this, "Save failed: " + why, Toast.LENGTH_LONG).show();
                }), 150);
    }

    private void showButtons() {
        saving = false;
        button.setVisibility(View.VISIBLE);
        listButton.setVisibility(View.VISIBLE);
    }

    private static final java.util.regex.Pattern ROW_NUMBER =
            java.util.regex.Pattern.compile("^\\(?(\\d{1,4})[.):]?(?:\\s.*)?$", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern ONLY_NUMBER = java.util.regex.Pattern.compile("^\\(?(\\d{1,4})[.):]?$");

    private void process(Snap.Result snapped) {
        StringBuilder log = new StringBuilder();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int top = statusBar() + dp(4), bottom = dm.heightPixels - dp(40);
        // The numbers on screen: the page's own (accessibility) and those read off the picture.
        List<Snap.Word> numbers = new ArrayList<>();
        try {
            numbers.addAll(Rows.numbersOnScreen(this));
        } catch (RuntimeException ignored) {
        }
        for (Snap.Word w : snapped.words) if (ROW_NUMBER.matcher(w.text.trim()).matches()) numbers.add(w);

        load();
        android.content.SharedPreferences g = prefs();
        List<Integer> dxs = new ArrayList<>(), numXs = new ArrayList<>(), sizes = new ArrayList<>();
        java.util.Set<String> emptyHere = new java.util.LinkedHashSet<>();
        int unnumbered = 0;
        List<Rect> boxes = new ArrayList<>();
        for (Rect b : snapped.emptyBoxes) if (b.top >= top && b.bottom <= bottom) boxes.add(b);
        for (Rect b : boxes) {
            Snap.Word n = numberBeside(b, numbers);
            if (n == null) {
                unnumbered++;
                log.append("empty box at ").append(b.centerX()).append(',').append(b.centerY()).append(": no number beside it\n");
                continue;
            }
            String num = digits(n.text);
            emptyHere.add(num);
            dxs.add(b.centerX() - n.box.centerX());
            numXs.add(n.box.centerX());
            sizes.add(Math.max(b.width(), b.height()));
            log.append("row ").append(num).append(": empty box at ").append(b.centerX()).append(',').append(b.centerY()).append('\n');
        }
        // Where boxes sit beside their numbers: learned now, or from earlier saves.
        int dx, numX, size;
        if (!dxs.isEmpty()) {
            dx = median(dxs);
            numX = median(numXs);
            size = median(sizes);
            g.edit().putInt("dx", dx).putInt("numX", numX).putInt("size", size).apply();
        } else {
            dx = g.getInt("dx", Integer.MIN_VALUE);
            numX = g.getInt("numX", Integer.MIN_VALUE);
            size = g.getInt("size", dp(24));
        }
        // Ticked rows: a number in the numbers' column with a (filled) box where the empty ones are.
        java.util.Set<String> tickedHere = new java.util.LinkedHashSet<>();
        if (dx != Integer.MIN_VALUE) {
            for (Snap.Word n : numbers) {
                java.util.regex.Matcher m = ONLY_NUMBER.matcher(n.text.trim());
                if (!m.matches()) continue;
                String num = m.group(1);
                if (emptyHere.contains(num) || tickedHere.contains(num)) continue;
                if (Math.abs(n.box.centerX() - numX) > dp(30)) continue;
                if (n.box.top < top || n.box.bottom > bottom) continue;
                int cx = n.box.centerX() + dx, cy = n.box.centerY();
                Rect spot = new Rect(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
                int spread = snapped.spread(spot);
                if (spread >= 60) {
                    tickedHere.add(num);
                    log.append("row ").append(num).append(": ticked (box at ").append(cx).append(',').append(cy)
                            .append(" is filled)\n");
                } else {
                    log.append("row ").append(num).append(": no box where expected (").append(cx).append(',')
                            .append(cy).append(") - left out\n");
                }
            }
        }
        int added = 0, nowTicked = 0;
        for (String num : emptyHere) {
            if (!state.containsKey(num)) added++;
            state.put(num, false);
        }
        for (String num : tickedHere) {
            Boolean was = state.get(num);
            if (was == null) added++;
            else if (!was) nowTicked++;
            state.put(num, true);
        }
        int n = captures() + 1;
        save(n);
        int ticked = 0;
        for (boolean t : state.values()) if (t) ticked++;
        button.setText("☑+\nSave #" + (n + 1));
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        String line = "Save #" + n + " (" + stamp + "): " + emptyHere.size() + " not ticked, " + tickedHere.size()
                + " ticked on screen" + (unnumbered > 0 ? ", " + unnumbered + " empty box(es) without a number" : "");
        prefs().edit().putString("log", prefs().getString("log", "") + line + "\n" + log + "\n").apply();
        Toast.makeText(this, line + "\n" + state.size() + " rows so far (" + added + " new"
                + (nowTicked > 0 ? ", " + nowTicked + " ticked since" : "") + "): " + ticked + " ticked, "
                + (state.size() - ticked) + " not", Toast.LENGTH_LONG).show();
    }

    /** The nearest number on the same line as the box (left or right of it), like the main app. */
    private Snap.Word numberBeside(Rect box, List<Snap.Word> numbers) {
        int tolerance = Math.max(box.height() / 2, dp(9));
        Snap.Word best = null;
        int bestGap = Integer.MAX_VALUE;
        for (Snap.Word w : numbers) {
            Rect r = w.box;
            if (Math.abs(r.centerY() - box.centerY()) > tolerance || Rect.intersects(r, box)) continue;
            int gap = r.left >= box.right ? r.left - box.right : box.left - r.right;
            if (gap < bestGap) {
                bestGap = gap;
                best = w;
            }
        }
        return best;
    }

    private static String digits(String text) {
        java.util.regex.Matcher m = ROW_NUMBER.matcher(text.trim());
        return m.matches() ? m.group(1) : text.replaceAll("\\D", "");
    }

    private static int median(List<Integer> v) {
        List<Integer> s = new ArrayList<>(v);
        java.util.Collections.sort(s);
        return s.get(s.size() / 2);
    }

    private int statusBar() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    /** List: the rows never ticked in any Save, in number order. */
    private void showList() {
        load();
        if (state.isEmpty()) {
            showCard("Nothing saved yet.\n\nTick 3-4 boxes, tap ☑+ Save, and again after every few. "
                    + "Then tap 📋 List.", "");
            return;
        }
        List<String> keys = new ArrayList<>(state.keySet());
        keys.sort((a, b) -> {
            boolean na = a.matches("\\d+"), nb = b.matches("\\d+");
            if (na && nb) return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            return na ? -1 : nb ? 1 : 0;
        });
        List<String> not = new ArrayList<>(), yes = new ArrayList<>();
        StringBuilder rowsText = new StringBuilder();
        for (String k : keys) {
            if (state.get(k)) {
                yes.add(k);
            } else {
                not.add(k);
                String name = names.get(k);
                rowsText.append("   ").append(k).append(name == null || k.startsWith("?") ? "" : "  " + name).append('\n');
            }
        }
        StringBuilder text = new StringBuilder();
        text.append("From ").append(captures()).append(" save(s), ").append(state.size()).append(" rows\n\n");
        if (not.isEmpty()) {
            text.append("✅ All ticked\n");
        } else {
            text.append("☐ NOT ticked (").append(not.size()).append("):\n").append(String.join(", ", not))
                    .append("\n\n").append(rowsText);
        }
        if (!yes.isEmpty()) text.append("\n☑ Ticked (").append(yes.size()).append("): ").append(String.join(", ", yes)).append('\n');
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        try (FileOutputStream out = openFileOutput(RESULT_FILE, MODE_PRIVATE)) {
            out.write(("Tick Check - " + stamp + "\n\n" + text + "\n\nSAVES\n" + prefs().getString("log", "")).getBytes());
        } catch (java.io.IOException ignored) {
        }
        List<String> numbers = new ArrayList<>();
        for (String k : not) if (k.matches("\\d+")) numbers.add(k);
        showCard(text.toString().trim(), String.join(", ", numbers));
    }

    private void newList() {
        prefs().edit().clear().apply();
        state.clear();
        names.clear();
        button.setText("☑+\nSave #1");
        closeCard();
        Toast.makeText(this, "New list - tap ☑+ Save after every few ticks", Toast.LENGTH_SHORT).show();
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
        row.addView(cardButton("New list", v -> newList()));
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
