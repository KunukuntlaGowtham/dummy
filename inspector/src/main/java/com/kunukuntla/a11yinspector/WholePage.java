package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The whole page, from the accessibility tree: scrolls it to the end screen by screen (the
 * page's own scroll action, a drag when it has none) and lists what each screen brought - also
 * what the page only adds once scrolled to - then scrolls back to the top. No screenshots.
 */
final class WholePage {

    interface Done {
        void done(String summary, String report);
    }

    private static final int MAX_SCREENS = 25;

    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());

    WholePage(AccessibilityService service) {
        this.service = service;
    }

    void walk(Done done) {
        step(0, "", new LinkedHashMap<>(), new ArrayList<>(), done);
    }

    private void step(int screenNo, String lastKey, Map<String, Integer> firstSeen, List<String> perScreen, Done done) {
        Set<String> here = new LinkedHashSet<>();
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        Rect screen = new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            Rect r = Page.bounds(n);
            if (r.height() <= 0 || !Rect.intersects(r, screen)) continue;
            String l = Page.label(n);
            String kind = kind(n);
            if (l.isEmpty() && kind.isEmpty()) continue;
            here.add((kind.isEmpty() ? "text" : kind) + (l.isEmpty() ? "" : " \"" + cut(l, 50) + "\""));
        }
        StringBuilder news = new StringBuilder();
        int fresh = 0;
        for (String k : here) {
            if (firstSeen.containsKey(k)) continue;
            firstSeen.put(k, screenNo + 1);
            news.append("    ").append(k).append('\n');
            fresh++;
        }
        perScreen.add("  Screen " + (screenNo + 1) + ": " + fresh + " new\n" + news);
        String key = String.join("|", here);
        if (key.equals(lastKey) || screenNo + 1 >= MAX_SCREENS) {
            finish(screenNo + 1, key.equals(lastKey), firstSeen, perScreen, done);
            return;
        }
        scroll(true);
        main.postDelayed(() -> step(screenNo + 1, key, firstSeen, perScreen, done), 900);
    }

    private void finish(int screens, boolean reachedEnd, Map<String, Integer> firstSeen,
                        List<String> perScreen, Done done) {
        for (int i = 0; i < screens + 2; i++) main.postDelayed(() -> scroll(false), 350L * i);
        Map<String, Integer> kinds = new HashMap<>();
        for (String k : firstSeen.keySet()) kinds.merge(k.split(" ")[0], 1, Integer::sum);
        StringBuilder sum = new StringBuilder();
        sum.append("📜 Whole page: ").append(screens).append(" screens")
                .append(reachedEnd ? " (to the end)" : " (stopped at " + MAX_SCREENS + ")").append(", ")
                .append(firstSeen.size()).append(" different things seen\n");
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : kinds.entrySet()) parts.add(e.getValue() + " " + e.getKey());
        sum.append("   ").append(String.join(", ", parts)).append('\n');
        StringBuilder rep = new StringBuilder();
        rep.append("A11y Inspector - whole page scan\n=================================\n").append(sum)
                .append("\nWHAT EACH SCREEN BROUGHT (scrolling down)\n");
        for (String s : perScreen) rep.append(s);
        done.done(sum.toString(), rep.toString());
    }

    /** The page's own scroll on its biggest list; a drag when that isn't taken. */
    private void scroll(boolean down) {
        AccessibilityNodeInfo list = null;
        long biggest = 0;
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            if (!n.isScrollable() || !n.isVisibleToUser()) continue;
            Rect r = Page.bounds(n);
            long area = (long) r.width() * r.height();
            if (area > biggest) {
                list = n;
                biggest = area;
            }
        }
        if (list != null && list.performAction(down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return;
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        float a = dm.heightPixels * 0.72f, b = dm.heightPixels * 0.30f;
        Path p = new Path();
        p.moveTo(dm.widthPixels / 2f, down ? a : b);
        p.lineTo(dm.widthPixels / 2f, down ? b : a);
        service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, down ? 450 : 200)).build(), null, null);
    }

    private static String kind(AccessibilityNodeInfo n) {
        String cls = String.valueOf(n.getClassName());
        String role = Page.role(n).toLowerCase(Locale.ROOT);
        if (cls.endsWith("RadioButton") || role.contains("radio")) return "radio" + (n.isChecked() ? "☑" : "☐");
        if (Page.isCheckbox(n)) return "checkbox" + (n.isChecked() ? "☑" : "☐");
        if (n.isEditable() || cls.endsWith("EditText")) return "field";
        if (cls.endsWith("Button") || role.equals("button")) return "button";
        if (n.isClickable()) return "tappable";
        return "";
    }

    private static String cut(String s, int max) {
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
