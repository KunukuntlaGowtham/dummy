package com.kunukuntla.ttdfiller;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The 📝 Fill button over the TTD app (drag it anywhere): a tap fills the pilgrim boxes on the
 * page from the booking you picked; a tap while it fills stops it. 👥 picks the booking right
 * there, without leaving the TTD page. With "fill by itself" on, the page is filled the moment
 * its pilgrim boxes come up. The TTD site in a browser works too (the button shows on its form).
 */
public class FillService extends AccessibilityService {

    static final String TTD = "com.ttdapp";
    private static final Set<String> BROWSERS = new HashSet<>(Arrays.asList(
            "com.android.chrome", "com.chrome.beta", "com.sec.android.app.sbrowser", "org.mozilla.firefox",
            "com.microsoft.emmx", "com.brave.browser", "com.opera.browser", "com.mi.globalbrowser"));

    static FillService instance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout pill;
    private WindowManager.LayoutParams pillLp;
    private Button fillBtn;
    private TextView info;
    private boolean pillShown;
    private View picker;
    private Filler filler;

    private boolean checkQueued, autoDone;
    /** The page as the last look read it (a fill started right after uses it instead of reading again). */
    private Form lastForm;
    private long lastFormAt;
    private long lastCheck;
    private int formLooks, lastBoxes, goneLooks;
    private String report = "";
    private boolean reportOk;
    private long reportUntil;

    private final Runnable secondLook = () -> {
        if (!checkQueued) check();
    };

    private SharedPreferences prefs() {
        return Booking.prefs(this);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildPill();
        queueCheck();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        queueCheck();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (filler != null) filler.cancel();
        closePicker();
        if (pillShown) {
            try {
                wm.removeView(pill);
            } catch (RuntimeException ignored) {
            }
        }
        pillShown = false;
        instance = null;
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- what is in front, looked at most every 0.3 s ----

    private void queueCheck() {
        if (checkQueued) return;
        checkQueued = true;
        long wait = Math.max(0, 300 - (SystemClock.uptimeMillis() - lastCheck));
        h.postDelayed(this::check, wait);
    }

    private void check() {
        checkQueued = false;
        lastCheck = SystemClock.uptimeMillis();
        boolean busy = filler != null && filler.running();
        String front = Nodes.frontPackage(this);
        boolean ttd = TTD.equals(front);
        int boxes = 0;
        if (!busy && (ttd || BROWSERS.contains(front))) {
            lastForm = Form.read(this);
            lastFormAt = SystemClock.uptimeMillis();
            boxes = lastForm.pilgrims.size();
        }
        setPillShown(ttd || boxes > 0 || busy || picker != null);
        if (busy) return;
        if (boxes > 0) {
            goneLooks = 0;
            formLooks = boxes == lastBoxes ? formLooks + 1 : 1;
            lastBoxes = boxes;
            maybeAuto();
            // A page that went quiet after one look still gets its second look (for "by itself").
            // The second look soon (the boxes holding still), not after the usual 0.3 s.
            if (!autoDone && formLooks < 2) {
                h.removeCallbacks(secondLook);
                h.postDelayed(secondLook, 120);
            }
        } else {
            formLooks = 0;
            lastBoxes = 0;
            // Off the pilgrim page for a moment: the next time it comes up is a new page to fill.
            if (++goneLooks >= 3) autoDone = false;
        }
        showInfo();
    }

    /** Fill by itself: once per visit of the page, when its boxes held still for two looks. */
    private void maybeAuto() {
        if (autoDone || formLooks < 2 || picker != null || !prefs().getBoolean("auto", true)) return;
        Booking b = Booking.picked(this);
        if (b == null) return;
        autoDone = true;
        startFill(b);
    }

    private void startFill(Booking b) {
        if (filler != null && filler.running()) return;
        autoDone = true;
        filler = new Filler(this, b, prefs().getBoolean("continue", false), new Filler.Listener() {
            @Override
            public void progress(String text) {
                say(text, 0xFFFFE082);
            }

            @Override
            public void finished(boolean ok, String r, String details) {
                report = r;
                reportOk = ok;
                reportUntil = SystemClock.uptimeMillis() + 15_000;
                prefs().edit().putString("last_report", r).putString("last_details", details)
                        .putLong("last_report_at", System.currentTimeMillis()).apply();
                fillBtn.setText("📝 Fill");
                showInfo();
                h.postDelayed(FillService.this::showInfo, 15_100);
            }
        });
        fillBtn.setText("⏹ Stop");
        Form recent = lastForm != null && SystemClock.uptimeMillis() - lastFormAt < 1500 ? lastForm : null;
        lastForm = null;
        filler.start(recent);
    }

    private void fillTapped() {
        if (filler != null && filler.running()) {
            filler.cancel();
            return;
        }
        Booking b = Booking.picked(this);
        if (b == null) {
            openPicker();
            return;
        }
        startFill(b);
    }

    // ---- the button ----

    @SuppressLint("ClickableViewAccessibility")
    private void buildPill() {
        pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE311B92);
        bg.setCornerRadius(dp(14));
        pill.setBackground(bg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        fillBtn = chip("📝 Fill", 0xFF7E57C2);
        Button pick = chip("👥", 0xFF5E35B1);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        gap.leftMargin = dp(6);
        row.addView(fillBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        row.addView(pick, gap);
        pill.addView(row);

        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(11);
        info.setMaxWidth(dp(230));
        info.setPadding(dp(2), dp(4), dp(2), 0);
        pill.addView(info);

        pillLp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        pillLp.gravity = Gravity.TOP | Gravity.START;
        pillLp.x = prefs().getInt("btn_x", dp(8));
        pillLp.y = prefs().getInt("btn_y", dp(260));

        dragger(fillBtn, this::fillTapped);
        dragger(pick, this::openPicker);
        dragger(info, () -> {
            // A tap on the words: the last report in full for a while.
            if (!report.isEmpty()) {
                reportUntil = SystemClock.uptimeMillis() + 15_000;
                showInfo();
            }
        });
        showInfo();
    }

    /** Drags the whole button when moved; a tap when not. */
    @SuppressLint("ClickableViewAccessibility")
    private void dragger(View v, Runnable tap) {
        final float[] start = new float[4];
        final boolean[] moved = {false};
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawX();
                    start[1] = e.getRawY();
                    start[2] = pillLp.x;
                    start[3] = pillLp.y;
                    moved[0] = false;
                    view.setAlpha(0.7f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - start[0], dy = e.getRawY() - start[1];
                    if (!moved[0] && Math.hypot(dx, dy) < dp(8)) return true;
                    moved[0] = true;
                    pillLp.x = Math.max(0, (int) (start[2] + dx));
                    pillLp.y = Math.max(0, (int) (start[3] + dy));
                    if (pillShown) wm.updateViewLayout(pill, pillLp);
                    return true;
                case MotionEvent.ACTION_UP:
                    view.setAlpha(1f);
                    if (moved[0]) prefs().edit().putInt("btn_x", pillLp.x).putInt("btn_y", pillLp.y).apply();
                    else tap.run();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.setAlpha(1f);
                    return true;
                default:
                    return false;
            }
        });
    }

    /** Where the 📝 button is on screen (a tap there would hit it, not the page); null when hidden. */
    Rect pillRect() {
        if (!pillShown || pill == null) return null;
        int[] at = new int[2];
        pill.getLocationOnScreen(at);
        return new Rect(at[0], at[1], at[0] + pill.getWidth(), at[1] + pill.getHeight());
    }

    private void setPillShown(boolean show) {
        if (show == pillShown || wm == null) return;
        try {
            if (show) wm.addView(pill, pillLp);
            else wm.removeView(pill);
            pillShown = show;
        } catch (RuntimeException ignored) {
        }
    }

    private void say(String text, int colour) {
        info.setText(text);
        info.setTextColor(colour);
    }

    /** The words under the button: the last fill's report for 15 s, else the booking picked. */
    private void showInfo() {
        if (info == null || filler != null && filler.running()) return;
        if (!report.isEmpty() && SystemClock.uptimeMillis() < reportUntil) {
            say(shortReport(), reportOk ? 0xFFA5D6A7 : 0xFFFFAB91);
            return;
        }
        Booking b = Booking.picked(this);
        if (!Supa.signedIn(this)) say("Open TTD Filler and sign in", 0xFFFFAB91);
        else if (b == null) say("No booking picked - tap 👥", 0xFFFFE082);
        else say(b.title() + (prefs().getBoolean("auto", true) ? "\nfills by itself on the pilgrim page" : ""), Color.WHITE);
    }

    /** The report as it fits under the button: its first 4 lines (all of it is in the app). */
    private String shortReport() {
        String[] lines = report.split("\n");
        if (lines.length <= 4) return report;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4; i++) b.append(i > 0 ? "\n" : "").append(lines[i]);
        return b.append("\n… ").append(lines.length - 4).append(" more - see the TTD Filler app").toString();
    }

    // ---- 👥 the picker, over the TTD page ----

    private void openPicker() {
        if (picker != null) {
            closePicker();
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        // Back closes it (it holds the keys while its search box is open).
        LinearLayout panel = new LinearLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) closePicker();
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(2), 0xFF5E35B1);
        panel.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText("Pick a booking");
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF311B92);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button reload = chip("⟳", 0xFF5E35B1);
        Button close = chip("✕", 0xFF757575);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        gap.leftMargin = dp(6);
        head.addView(reload, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        head.addView(close, gap);
        panel.addView(head);

        EditText search = new EditText(this);
        search.setHint("Search code, name, mobile");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setTextColor(Color.BLACK);
        panel.addView(search);

        TextView status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(0xFF616161);
        panel.addView(status);

        ScrollView sc = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        sc.addView(rows);
        panel.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Runnable render = () -> fillRows(rows, status, search.getText().toString());
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                render.run();
            }
        });
        Runnable load = () -> {
            status.setText("Loading bookings…");
            new Thread(() -> {
                String err = Booking.refresh(this);
                h.post(() -> {
                    if (picker == null) return;
                    render.run();
                    if (!err.isEmpty()) status.setText("✗ " + err + (Booking.cached(this).isEmpty() ? "" : " - showing the last list"));
                });
            }).start();
        };
        reload.setOnClickListener(v -> load.run());
        close.setOnClickListener(v -> closePicker());

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dm.widthPixels - dp(24), (int) (dm.heightPixels * 0.62f),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(70);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        try {
            wm.addView(panel, lp);
            picker = panel;
        } catch (RuntimeException e) {
            return;
        }
        render.run();
        if (!Supa.signedIn(this)) status.setText("Sign in in the TTD Filler app first");
        else if (Booking.cached(this).isEmpty() || System.currentTimeMillis() - Booking.refreshedAt(this) > 10 * 60_000) load.run();
    }

    private void fillRows(LinearLayout rows, TextView status, String query) {
        rows.removeAllViews();
        List<Booking> all = Booking.cached(this);
        Booking picked = Booking.picked(this);
        int shown = 0;
        for (Booking b : all) {
            if (!b.matches(query)) continue;
            if (++shown > 300) break;
            boolean on = picked != null && picked.id() == b.id();
            TextView r = new TextView(this);
            StringBuilder sb = new StringBuilder(on ? "✅ " : "").append(b.title());
            List<Booking.Person> ps = b.people();
            if (ps.size() > 1) {
                sb.append("\n   with ");
                for (int i = 1; i < ps.size(); i++) sb.append(i > 1 ? ", " : "").append(ps.get(i).name);
            }
            if (!b.s("mobile").isEmpty()) sb.append("\n   📞 ").append(b.s("mobile"));
            r.setText(sb);
            r.setTextSize(14);
            r.setTextColor(on ? 0xFF1B5E20 : 0xFF212121);
            r.setPadding(dp(8), dp(10), dp(8), dp(10));
            GradientDrawable rb = new GradientDrawable();
            rb.setColor(on ? 0xFFE8F5E9 : 0xFFF3E5F5);
            rb.setCornerRadius(dp(10));
            r.setBackground(rb);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            r.setOnClickListener(v -> {
                Booking.pick(this, b.id());
                closePicker();
                // Picked on the pilgrim page: filled with it straight away when "by itself" is on.
                autoDone = false;
                formLooks = Math.max(formLooks, 1);
                showInfo();
                queueCheck();
            });
            rows.addView(r, lp);
        }
        status.setText(all.isEmpty() ? "No bookings yet - press ⟳" : shown + " of " + all.size() + " bookings");
    }

    private void closePicker() {
        if (picker == null) return;
        try {
            wm.removeView(picker);
        } catch (RuntimeException ignored) {
        }
        picker = null;
    }

    // ---- small things ----

    private Button chip(String text, int colour) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable d = new GradientDrawable();
        d.setColor(colour);
        d.setCornerRadius(dp(10));
        b.setBackground(d);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
