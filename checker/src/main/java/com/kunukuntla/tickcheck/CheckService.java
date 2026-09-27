package com.kunukuntla.tickcheck;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watch: while you tick the boxes and close the pop-ups yourself, it keeps taking screenshots,
 * finds the empty boxes and the ticked ones with their row numbers, and shows them over the
 * page - red = not ticked, green = ticked - with the running "not ticked" list on a bar at
 * the top. List shows the final list (Copy numbers, New list). Nothing is tapped.
 */
public class CheckService extends AccessibilityService {

    static final String RESULT_FILE = "last_check.txt";

    private static final Pattern ROW_NUMBER =
            Pattern.compile("^\\(?(\\d{1,4})[.):]?(?:\\s.*)?$", Pattern.DOTALL);
    private static final Pattern ONLY_NUMBER = Pattern.compile("^\\(?(\\d{1,4})[.):]?$");

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView watchButton;
    private View card;
    private Marks marks;
    private Snap snap;
    private boolean watching;
    private int gen;

    /** Every row seen, in the order first seen: number -> ticked. */
    private final LinkedHashMap<String, Boolean> state = new LinkedHashMap<>();

    @Override
    protected void onServiceConnected() {
        wm = getSystemService(WindowManager.class);
        snap = new Snap(this);
        load();
        watchButton = floating("👁\nWatch", 0xEE6A2C91, dp(200), v -> {
            if (watching) stopWatching();
            else startWatching();
        }, this::newList);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        stopWatching();
        closeCard();
        for (View v : new View[] {watchButton}) {
            if (v == null) continue;
            try {
                wm.removeView(v);
            } catch (RuntimeException ignored) {
            }
        }
        super.onDestroy();
    }

    // ---- watching: screenshot, find, show, again ------------------------------------------

    private void startWatching() {
        closeCard();
        // Each Watch is one pass over the ticked page: a fresh list.
        state.clear();
        snap.stripLeft = snap.stripRight = -1;
        prefs().edit().remove("rows").apply();
        watching = true;
        gen++;
        watchButton.setText("■\nStop");
        if (marks == null) {
            marks = new Marks(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            wm.addView(marks, lp);
        }
        marks.bar = "👁 Watching…\nscroll the page slowly from the top to the end, then tap 👁";
        marks.invalidate();
        look(gen);
    }

    private void stopWatching() {
        watching = false;
        gen++;
        if (watchButton != null) watchButton.setText("👁\nWatch");
        // The numbers stay on screen after Stop; tap Watch to go on, hold it for a new list.
        if (marks != null) {
            marks.bar = barText(0, 0).replaceFirst("\n.*", "") + "\n✔ done · tap 👁 to check again · hold 👁 to hide";
            marks.invalidate();
        }
    }

    /** One look: our marks out of the picture, a screenshot, the marks back with what it found. */
    private final java.util.concurrent.ExecutorService treeWorker = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void look(int g) {
        if (!watching || g != gen) return;
        long started = android.os.SystemClock.uptimeMillis();
        // The page's own numbers are read at the same time as the screenshot is taken.
        java.util.concurrent.Future<List<Snap.Word>> tree = treeWorker.submit(() -> {
            try {
                return Rows.numbersOnScreen(this);
            } catch (RuntimeException e) {
                return new ArrayList<Snap.Word>();
            }
        });
        main.postDelayed(() -> {
            if (!watching || g != gen) return;
            snap.take(dp(14), dp(48), result -> {
                if (!watching || g != gen) {
                    result.drop();
                    return;
                }
                List<Snap.Word> before;
                try {
                    before = tree.get(400, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    before = new ArrayList<>();
                }
                // The page's numbers again, now: if they moved, the page was scrolling while
                // the picture was taken - then only the numbers in the picture itself are used,
                // so no box gets the number of another row.
                List<Snap.Word> after;
                try {
                    after = Rows.numbersOnScreen(this);
                } catch (RuntimeException e) {
                    after = new ArrayList<>();
                }
                boolean still = samePlaces(before, after);
                numbers(result, still ? after : new ArrayList<>(), numbers -> {
                    if (!watching || g != gen) return;
                    try {
                        process(result, numbers);
                    } catch (RuntimeException e) {
                        if (marks != null) {
                            marks.bar = "Error: " + e;
                            marks.invalidate();
                        }
                    }
                    // Android allows about 3 screenshots a second: the next one as soon as it may.
                    long wait = Math.max(0, started + 340 - android.os.SystemClock.uptimeMillis());
                    main.postDelayed(() -> look(g), wait);
                });
            }, why -> {
                if (marks != null) {
                    marks.bar = "No screenshot: " + why;
                    marks.invalidate();
                }
                main.postDelayed(() -> look(g), 800);
            });
        }, 0);
    }

    /**
     * The row numbers on screen: the page's own (fast, from accessibility) and, only when some
     * empty box has none beside it, the numbers read off the picture (slower).
     */
    private static boolean samePlaces(List<Snap.Word> a, List<Snap.Word> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).text.equals(b.get(i).text) || !a.get(i).box.equals(b.get(i).box)) return false;
        }
        return true;
    }

    private void numbers(Snap.Result r, List<Snap.Word> treeNumbers, java.util.function.Consumer<List<Snap.Word>> done) {
        List<Snap.Word> numbers = new ArrayList<>(treeNumbers);
        int top = statusBar() + panelHeight();
        numbers.removeIf(w -> w.box.top < top);
        boolean missing = numbers.isEmpty();
        for (Rect b : r.emptyBoxes) if (numberBeside(b, numbers) == null) missing = true;
        if (!missing) {
            r.drop();
            done.accept(numbers);
            return;
        }
        snap.readWords(r, read -> {
            for (Snap.Word w : read.words) {
                if (w.box.top >= top && ONLY_NUMBER.matcher(w.text.trim()).matches()) numbers.add(w);
            }
            done.accept(numbers);
        });
    }

    private void process(Snap.Result snapped, List<Snap.Word> numbers) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int top = statusBar() + panelHeight(), bottom = dm.heightPixels - dp(40);
        List<Mark> shown = new ArrayList<>();
        Set<String> emptyHere = new LinkedHashSet<>();
        List<Integer> dxs = new ArrayList<>(), numXs = new ArrayList<>(), sizes = new ArrayList<>();
        for (Rect b : snapped.emptyBoxes) {
            if (b.top < top || b.bottom > bottom || onOurButtons(b)) continue;
            Snap.Word n = numberBeside(b, numbers);
            if (n == null) {
                shown.add(new Mark(b, "?", false));
                continue;
            }
            String num = digits(n.text);
            emptyHere.add(num);
            shown.add(new Mark(b, num, false));
            dxs.add(b.centerX() - n.box.centerX());
            numXs.add(n.box.centerX());
            sizes.add(Math.max(b.width(), b.height()));
        }
        // Only the boxes' column is searched from now on (faster).
        if (!dxs.isEmpty()) {
            int boxX = median(numXs) + median(dxs), size = median(sizes);
            snap.stripLeft = boxX - size * 3;
            snap.stripRight = boxX + size * 3;
        }
        Set<String> tickedHere = new LinkedHashSet<>();
        boolean changed = false;
        for (String num : emptyHere) {
            // Once seen empty it stays on the list (the page is already ticked; nothing changes).
            Boolean was = state.put(num, false);
            if (was == null || was) changed = true;
        }

        if (changed) save();
        if (marks != null) {
            marks.marks = shown;
            marks.bar = barText(emptyHere.size(), tickedHere.size());
            marks.invalidate();
        }
    }

    private String barText(int emptyNow, int tickedNow) {
        List<String> not = notTicked();
        return (not.isEmpty() ? "✅ No empty box seen yet" : "☐ " + String.join(", ", shifted(not)))
                + "\n" + (not.isEmpty() ? "" : "rows " + String.join(", ", not) + " · ")
                + "scroll to the end, then tap 👁";
    }

    private static boolean samePlaces(List<Snap.Word> a, List<Snap.Word> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).text.equals(b.get(i).text) || !a.get(i).box.equals(b.get(i).box)) return false;
        }
        return true;
    }

    private void numbers(Snap.Result r, List<Snap.Word> treeNumbers, java.util.function.Consumer<List<Snap.Word>> done) {
        List<Snap.Word> numbers = new ArrayList<>(treeNumbers);
        int top = statusBar() + panelHeight();
        numbers.removeIf(w -> w.box.top < top);
        boolean missing = numbers.isEmpty();
        for (Rect b : r.emptyBoxes) if (numberBeside(b, numbers) == null) missing = true;
        if (!missing) {
            r.drop();
            done.accept(numbers);
            return;
        }
        snap.readWords(r, read -> {
            for (Snap.Word w : read.words) {
                if (w.box.top >= top && ONLY_NUMBER.matcher(w.text.trim()).matches()) numbers.add(w);
            }
            done.accept(numbers);
        });
    }

    private void process(Snap.Result snapped, List<Snap.Word> numbers) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int top = statusBar() + panelHeight(), bottom = dm.heightPixels - dp(40);
        List<Mark> shown = new ArrayList<>();
        Set<String> emptyHere = new LinkedHashSet<>();
        List<Integer> dxs = new ArrayList<>(), numXs = new ArrayList<>(), sizes = new ArrayList<>();
        for (Rect b : snapped.emptyBoxes) {
            if (b.top < top || b.bottom > bottom || onOurButtons(b)) continue;
            Snap.Word n = numberBeside(b, numbers);
            if (n == null) {
                shown.add(new Mark(b, "?", false));
                continue;
            }
            String num = digits(n.text);
            emptyHere.add(num);
            shown.add(new Mark(b, num, false));
            dxs.add(b.centerX() - n.box.centerX());
            numXs.add(n.box.centerX());
            sizes.add(Math.max(b.width(), b.height()));
        }
        // Only the boxes' column is searched from now on (faster).
        if (!dxs.isEmpty()) {
            int boxX = median(numXs) + median(dxs), size = median(sizes);
            snap.stripLeft = boxX - size * 3;
            snap.stripRight = boxX + size * 3;
        }
        Set<String> tickedHere = new LinkedHashSet<>();
        boolean changed = false;
        for (String num : emptyHere) {
            // Once seen empty it stays on the list (the page is already ticked; nothing changes).
            Boolean was = state.put(num, false);
            if (was == null || was) changed = true;
        }

        if (changed) save();
        if (marks != null) {
            marks.marks = shown;
            marks.bar = barText(emptyHere.size(), tickedHere.size());
            marks.invalidate();
        }
    }

    private String barText(int emptyNow, int tickedNow) {
        List<String> not = notTicked();
        int ticked = 0;
        for (boolean t : state.values()) if (t) ticked++;
        return (not.isEmpty() ? "✅ None left unticked" : "☐ " + String.join(", ", shifted(not)))
                + "\n" + "rows " + String.join(", ", not) + " not ticked · ☑ " + ticked + " of " + state.size() + " rows seen · on screen now: ☐"
                + emptyNow + " ☑" + tickedNow;
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

    private boolean onOurButtons(Rect b) {
        for (View v : new View[] {watchButton}) {
            if (v == null) continue;
            int[] at = new int[2];
            v.getLocationOnScreen(at);
            if (Rect.intersects(b, new Rect(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight()))) return true;
        }
        return false;
    }

    // ---- the list, kept across looks (and restarts) ----------------------------------------

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("list", MODE_PRIVATE);
    }

    private void load() {
        state.clear();
        for (String line : prefs().getString("rows", "").split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length >= 2 && f[0].matches("\\d+")) state.put(f[0], f[1].equals("1"));
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Boolean> e : state.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue() ? "1" : "0").append('\n');
        }
        prefs().edit().putString("rows", sb.toString()).apply();
    }

    private List<String> sortedKeys() {
        List<String> keys = new ArrayList<>(state.keySet());
        Collections.sort(keys, (a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
        return keys;
    }

    /**
     * The not-ticked rows as they will be numbered when each is deleted in turn from the top:
     * every earlier deletion moves the later rows up one. 6, 8, 10 -> 6, 7, 8; 4, 10, 12 -> 4, 9, 10.
     */
    private static List<String> shifted(List<String> rows) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) out.add(String.valueOf(Integer.parseInt(rows.get(i)) - i));
        return out;
    }

    private List<String> notTicked() {
        List<String> out = new ArrayList<>();
        for (String k : sortedKeys()) if (!state.get(k)) out.add(k);
        return out;
    }

    private void showList() {
        if (state.isEmpty()) {
            showCard("Nothing seen yet.\n\nTap 👁 Watch, then tick the boxes and close the pop-ups "
                    + "yourself, scrolling down the page. Tap 📋 List at the end.", "");
            return;
        }
        List<String> not = notTicked(), yes = new ArrayList<>();
        for (String k : sortedKeys()) if (state.get(k)) yes.add(k);
        StringBuilder text = new StringBuilder();
        text.append(state.size()).append(" rows seen\n\n");
        if (not.isEmpty()) text.append("✅ All ticked\n");
        else text.append("☐ NOT ticked (").append(not.size()).append("):\n")
                .append(String.join(", ", shifted(not))).append("\n(rows ").append(String.join(", ", not))
                .append(" - each one less by the not-ticked rows before it)\n");
        if (!yes.isEmpty()) text.append("\n☑ Ticked (").append(yes.size()).append("): ").append(String.join(", ", yes)).append('\n');
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        try (FileOutputStream out = openFileOutput(RESULT_FILE, MODE_PRIVATE)) {
            out.write(("Tick Check - " + stamp + "\n\n" + text).getBytes());
        } catch (java.io.IOException ignored) {
        }
        showCard(text.toString().trim(), String.join(", ", shifted(not)));
    }

    /** Hold 👁: stop and take the numbers off the screen. */
    private void newList() {
        stopWatching();
        state.clear();
        prefs().edit().clear().apply();
        snap.stripLeft = snap.stripRight = -1;
        closeCard();
        if (marks != null) {
            try {
                wm.removeView(marks);
            } catch (RuntimeException ignored) {
            }
            marks = null;
        }
    }

    // ---- the marks over the page ------------------------------------------------------------

    private static final class Mark {
        final Rect box;
        final String number;
        final boolean ticked;

        Mark(Rect box, String number, boolean ticked) {
            this.box = box;
            this.number = number;
            this.ticked = ticked;
        }
    }

    /** Draws a red (not ticked) or green (ticked) frame and number on each box, and the bar. */
    private final class Marks extends View {
        List<Mark> marks = new ArrayList<>();
        String bar = "";
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        Marks(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(3));
            text.setColor(Color.WHITE);
            text.setTextSize(dp(13));
            text.setFakeBoldText(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // Only the live numbers (no frames on the page): a panel under the status bar.
            if (bar.isEmpty()) return;
            int[] at = new int[2];
            getLocationOnScreen(at);
            canvas.translate(-at[0], -at[1]);
            float y = statusBar();
            float width = getResources().getDisplayMetrics().widthPixels;
            fill.setColor(0xF01B1D22);
            canvas.drawRect(0, y, width, y + panelHeight(), fill);
            text.setTextSize(dp(20));
            text.setColor(bar.startsWith("✅") ? 0xFF9AE6A1 : 0xFFFF8A80);
            String main = bar, sub = "";
            int nl = bar.indexOf('\n');
            if (nl >= 0) {
                main = bar.substring(0, nl);
                sub = bar.substring(nl + 1);
            }
            // Shrink a long list to fit the width.
            float tw = text.measureText(main);
            if (tw > width - dp(16)) text.setTextSize(dp(20) * (width - dp(16)) / tw);
            canvas.drawText(main, dp(8), y + dp(26), text);
            text.setTextSize(dp(12));
            text.setColor(0xCCFFFFFF);
            canvas.drawText(sub, dp(8), y + dp(44), text);
        }
    }

    private int panelHeight() {
        return dp(52);
    }

    // ---- buttons and the card -------------------------------------------------------------

    /** A round button on the right edge; drag it to move it. */
    @SuppressLint("ClickableViewAccessibility")
    private TextView floating(String label, int colour, int y, View.OnClickListener onTap, Runnable onHold) {
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
        boolean[] dragged = {false}, held = {false};
        Runnable hold = () -> {
            held[0] = true;
            onHold.run();
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    start[0] = p.x;
                    start[1] = p.y;
                    dragged[0] = false;
                    held[0] = false;
                    main.postDelayed(hold, 700);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (!dragged[0] && Math.hypot(dx, dy) > slop) {
                        dragged[0] = true;
                        main.removeCallbacks(hold);
                    }
                    if (dragged[0]) {
                        p.x = start[0] - (int) dx; // anchored on the right
                        p.y = start[1] + (int) dy;
                        wm.updateViewLayout(b, p);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(hold);
                    if (!dragged[0] && !held[0]) onTap.onClick(b);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(hold);
                    return true;
                default:
                    return true;
            }
        });
        wm.addView(b, p);
        return b;
    }

    private void showCard(String body, String copy) {
        closeCard();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01B1D22);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);
        TextView t = new TextView(this);
        t.setText(body);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        ScrollView sc = new ScrollView(this);
        sc.addView(t);
        box.addView(sc);
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

    // ---- helpers ----------------------------------------------------------------------------

    private static String digits(String text) {
        Matcher m = ROW_NUMBER.matcher(text.trim());
        return m.matches() ? m.group(1) : text.replaceAll("\\D", "");
    }

    private static int median(List<Integer> v) {
        List<Integer> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    private int statusBar() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
