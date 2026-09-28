package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
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
import java.util.function.Consumer;

/**
 * The deeper part of a scan, from a screenshot of the screen as you see it:
 * <ul>
 *   <li>every text read off the picture (OCR), and which of it the page does NOT report to
 *       accessibility (a pop-up drawn but hidden, text in pictures ...);</li>
 *   <li>the checkbox shapes in the picture (empty ones), and whether the page reports a
 *       checkbox there;</li>
 *   <li>the colours of the page's tappable elements and checkboxes;</li>
 *   <li>what covers the page (a dimmed cover = a pop-up is up);</li>
 *   <li>on request, the whole page: it scrolls to the end, screen by screen, and lists what
 *       each new screen brought (also elements the page only adds when scrolled to).</li>
 * </ul>
 * Reads only - apart from the scrolling of the whole-page scan, nothing is tapped.
 */
final class DeepLook {

    interface Done {
        void done(String summary, String report);
    }

    private final AccessibilityService service;
    private final ScreenWords words;
    private final Handler main = new Handler(Looper.getMainLooper());

    DeepLook(AccessibilityService service) {
        this.service = service;
        this.words = new ScreenWords(service);
    }

    // ---- one screen ------------------------------------------------------------------------

    void screen(Done done) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            done.done("⚠️ Picture checks need Android 11 or newer", "");
            return;
        }
        words.shot(true, shot -> {
            if (shot == null) {
                done.done("⚠️ No screenshot - picture checks skipped", "");
                return;
            }
            try {
                analyse(shot, done);
            } catch (RuntimeException e) {
                done.done("⚠️ Picture checks failed: " + e, "");
            }
        });
    }

    private void analyse(ScreenWords.Shot shot, Done done) {
        List<AccessibilityNodeInfo> nodes = Taught.nodes(service);
        Rect screen = new Rect(0, 0, shot.w, shot.h);
        int top = bar("status_bar_height");

        // Texts the page reports (on screen), to tell what it doesn't.
        List<String> treeTexts = new ArrayList<>();
        List<AccessibilityNodeInfo> boxes = new ArrayList<>(), tappable = new ArrayList<>();
        for (AccessibilityNodeInfo n : nodes) {
            String t = norm(Taught.label(n));
            if (!t.isEmpty()) treeTexts.add(t);
            CharSequence h = Build.VERSION.SDK_INT >= 26 ? n.getHintText() : null;
            if (h != null) treeTexts.add(norm(h.toString()));
            String cls = String.valueOf(n.getClassName());
            String role = Taught.role(n).toLowerCase(Locale.ROOT);
            Rect r = bounds(n);
            boolean onScreen = r.width() > 0 && r.height() > 0 && Rect.intersects(r, screen);
            if (!onScreen) continue;
            if (cls.endsWith("CheckBox") || role.contains("checkbox") || n.isCheckable()) boxes.add(n);
            else if (n.isClickable() && r.width() < shot.w * 9 / 10) tappable.add(n);
        }

        // 1) Text on screen the page doesn't report.
        StringBuilder hidden = new StringBuilder(), allText = new StringBuilder();
        int hiddenCount = 0, textCount = 0;
        List<ScreenWords.Word> lines = new ArrayList<>();
        for (ScreenWords.Word w : shot.words) if (w.whole && w.box.top >= top) lines.add(w);
        lines.sort((a, b) -> a.box.top != b.box.top ? Integer.compare(a.box.top, b.box.top)
                : Integer.compare(a.box.left, b.box.left));
        for (ScreenWords.Word w : lines) {
            textCount++;
            String t = norm(w.text);
            allText.append("  \"").append(cut(w.text, 60)).append("\" @").append(w.box.left).append(',')
                    .append(w.box.top).append(' ').append(w.box.width()).append('x').append(w.box.height()).append('\n');
            if (t.length() < 2 || reported(t, treeTexts)) continue;
            hiddenCount++;
            hidden.append("  \"").append(cut(w.text, 60)).append("\" @").append(w.box.centerX()).append(',')
                    .append(w.box.centerY()).append('\n');
        }

        // 2) Checkbox shapes in the picture vs checkboxes in the tree.
        int half = 2, hw = shot.w / half, hh = shot.h / half;
        int[] lum = new int[hw * hh];
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < hw; x++) {
                int c = shot.px[(y * half) * shot.w + x * half];
                lum[y * hw + x] = (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114) / 1000;
            }
        }
        StringBuilder shapes = new StringBuilder();
        int emptyShapes = 0, unreported = 0;
        for (Rect b : BoxFinder.find(lum, hw, hh, dp(14) / half, dp(48) / half)) {
            Rect r = new Rect(b.left * half, b.top * half, b.right * half, b.bottom * half);
            if (r.top < top) continue;
            emptyShapes++;
            AccessibilityNodeInfo at = null;
            for (AccessibilityNodeInfo n : boxes) {
                Rect nb = visible(n);
                if (nb != null && Rect.intersects(nb, r)) at = n;
            }
            if (at == null) unreported++;
            shapes.append("  empty box @").append(r.centerX()).append(',').append(r.centerY()).append(' ')
                    .append(r.width()).append('x').append(r.height()).append(" - ")
                    .append(at == null ? "NOT reported by the page (only a picture)"
                            : "the page's checkbox" + (at.isChecked() ? " (but it says ☑!)" : " ☐"))
                    .append(" · colour ").append(hex(colours(shot, r, 1)[0])).append('\n');
        }
        for (AccessibilityNodeInfo n : boxes) {
            Rect r = visible(n);
            if (r == null) continue;
            shapes.append("  page's checkbox ").append(n.isChecked() ? "☑" : "☐").append(" @").append(r.centerX())
                    .append(',').append(r.centerY()).append(" (").append(r.width()).append('x').append(r.height())
                    .append(") looks ").append(describeColours(shot, r)).append('\n');
        }

        // 3) Colours of the tappable elements.
        StringBuilder colours = new StringBuilder();
        int listed = 0;
        for (AccessibilityNodeInfo n : tappable) {
            if (listed >= 60) break;
            Rect r = bounds(n);
            String label = Taught.label(n);
            colours.append("  ").append(label.isEmpty() ? shortClass(n) : "\"" + cut(label, 30) + "\"")
                    .append(" @").append(r.centerX()).append(',').append(r.centerY()).append(" - ")
                    .append(describeColours(shot, r)).append('\n');
            listed++;
        }

        // 4) A cover over the page: most of the screen darker than usual.
        int[] whole = colours(shot, new Rect(0, top, shot.w, shot.h), 1);
        int bgLum = lumOf(whole[0]);
        String cover = bgLum < 90 ? "The screen is mostly dark (" + hex(whole[0])
                + ") - a dimmed cover: a pop-up may be up." : "No dimmed cover (main colour " + hex(whole[0]) + ").";

        StringBuilder sum = new StringBuilder();
        sum.append("📷 Picture: ").append(textCount).append(" texts read");
        if (hiddenCount > 0) sum.append(", ").append(hiddenCount).append(" NOT reported to accessibility");
        sum.append('\n');
        sum.append("📷 Empty checkbox shapes: ").append(emptyShapes)
                .append(unreported > 0 ? " (" + unreported + " not reported by the page)" : "")
                .append(" · page's checkboxes on screen: ").append(countVisible(boxes)).append('\n');
        if (bgLum < 90) sum.append("📷 ").append(cover).append('\n');

        StringBuilder rep = new StringBuilder();
        rep.append("\nPICTURE CHECKS (screenshot of the screen as you see it)\n");
        rep.append("  ").append(cover).append('\n');
        rep.append("\nTEXT ON SCREEN THE PAGE DOESN'T REPORT (").append(hiddenCount).append(")\n");
        rep.append(hidden.length() == 0 ? "  none - everything readable is also in the tree\n" : hidden.toString());
        rep.append("\nCHECKBOXES: SHAPES IN THE PICTURE AND THE PAGE'S OWN\n");
        rep.append(shapes.length() == 0 ? "  none\n" : shapes.toString());
        rep.append("\nCOLOURS OF THE TAPPABLE ELEMENTS (main colour · second colour)\n");
        rep.append(colours.length() == 0 ? "  none\n" : colours.toString());
        rep.append("\nALL TEXT READ OFF THE PICTURE (").append(textCount).append(")\n");
        rep.append(allText.length() == 0 ? "  none\n" : allText.toString());
        done.done(sum.toString(), rep.toString());
    }

    // ---- the whole page: scroll to the end, screen by screen --------------------------------

    private static final int MAX_SCREENS = 25;

    void wholePage(Consumer<String> progress, Done done) {
        Map<String, Integer> firstSeen = new LinkedHashMap<>();
        List<String> perScreen = new ArrayList<>();
        step(0, 0, "", firstSeen, perScreen, progress, done);
    }

    private void step(int screenNo, int still, String lastKey, Map<String, Integer> firstSeen,
                      List<String> perScreen, Consumer<String> progress, Done done) {
        progress.accept("Whole page: screen " + (screenNo + 1) + "…");
        words.shot(true, shot -> {
            // What this screen shows: the page's elements (on screen) and the picture's text.
            Set<String> here = new LinkedHashSet<>();
            Rect screen = new Rect(0, 0, 100000, 100000);
            if (shot != null) screen.set(0, bar("status_bar_height"), shot.w, shot.h);
            for (AccessibilityNodeInfo n : Taught.nodes(service)) {
                Rect r = bounds(n);
                if (r.height() <= 0 || !Rect.intersects(r, screen)) continue;
                String l = Taught.label(n);
                String kind = kind(n);
                if (l.isEmpty() && kind.isEmpty()) continue;
                here.add((kind.isEmpty() ? "text" : kind) + (l.isEmpty() ? "" : " \"" + cut(l, 50) + "\""));
            }
            if (shot != null && shot.words != null) {
                for (ScreenWords.Word w : shot.words) {
                    if (w.whole && w.box.top >= screen.top) here.add("picture \"" + cut(w.text, 50) + "\"");
                }
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
            int nowStill = key.equals(lastKey) ? still + 1 : 0;
            if (nowStill >= 1 || screenNo + 1 >= MAX_SCREENS) {
                finishWhole(screenNo + 1, nowStill >= 1, firstSeen, perScreen, done);
                return;
            }
            scroll(true);
            main.postDelayed(() -> step(screenNo + 1, nowStill, key, firstSeen, perScreen, progress, done), 900);
        });
    }

    private void finishWhole(int screens, boolean reachedEnd, Map<String, Integer> firstSeen,
                             List<String> perScreen, Done done) {
        // Back to the top.
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

    private void scroll(boolean down) {
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        float a = dm.heightPixels * 0.72f, b = dm.heightPixels * 0.30f;
        Path p = new Path();
        p.moveTo(dm.widthPixels / 2f, down ? a : b);
        p.lineTo(dm.widthPixels / 2f, down ? b : a);
        service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, down ? 450 : 200)).build(), null, null);
    }

    // ---- helpers --------------------------------------------------------------------------

    private static String kind(AccessibilityNodeInfo n) {
        String cls = String.valueOf(n.getClassName());
        String role = Taught.role(n).toLowerCase(Locale.ROOT);
        if (cls.endsWith("RadioButton") || role.contains("radio")) return "radio" + (n.isChecked() ? "☑" : "☐");
        if (cls.endsWith("CheckBox") || role.contains("checkbox") || n.isCheckable()) return "checkbox" + (n.isChecked() ? "☑" : "☐");
        if (n.isEditable() || cls.endsWith("EditText")) return "field";
        if (cls.endsWith("Button") || role.equals("button")) return "button";
        if (n.isClickable()) return "tappable";
        return "";
    }

    /** The page reports this text (or a text containing it, or contained in it). */
    private static boolean reported(String t, List<String> tree) {
        for (String s : tree) {
            if (s.isEmpty()) continue;
            if (s.equals(t) || s.contains(t) || (t.length() > 6 && t.contains(s) && s.length() * 2 > t.length())) return true;
        }
        return false;
    }

    /** The two most common colours in the box (sampled), most common first. */
    private static int[] colours(ScreenWords.Shot shot, Rect r, int want) {
        int x0 = Math.max(0, r.left), x1 = Math.min(shot.w, r.right);
        int y0 = Math.max(0, r.top), y1 = Math.min(shot.h, r.bottom);
        Map<Integer, Integer> count = new HashMap<>();
        int step = Math.max(1, Math.min(x1 - x0, y1 - y0) / 24);
        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                int c = shot.px[y * shot.w + x];
                int q = ((c >> 16) & 0xF0) << 16 | ((c >> 8) & 0xF0) << 8 | (c & 0xF0);
                count.merge(q, 1, Integer::sum);
            }
        }
        int first = 0, second = 0, n1 = -1, n2 = -1;
        for (Map.Entry<Integer, Integer> e : count.entrySet()) {
            if (e.getValue() > n1) {
                second = first;
                n2 = n1;
                first = e.getKey();
                n1 = e.getValue();
            } else if (e.getValue() > n2) {
                second = e.getKey();
                n2 = e.getValue();
            }
        }
        return new int[] {first, n2 < 0 ? first : second};
    }

    private static String describeColours(ScreenWords.Shot shot, Rect r) {
        int[] c = colours(shot, r, 2);
        return hex(c[0]) + " · " + hex(c[1]);
    }

    private static int lumOf(int c) {
        return (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114) / 1000;
    }

    private static String hex(int c) {
        return String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF);
    }

    private static int countVisible(List<AccessibilityNodeInfo> boxes) {
        int n = 0;
        for (AccessibilityNodeInfo b : boxes) if (visible(b) != null) n++;
        return n;
    }

    private static Rect visible(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        for (int i = 0; p != null && i < 4; i++, p = p.getParent()) {
            Rect r = bounds(p);
            if (r.width() > 4 && r.height() > 4) return r;
        }
        return null;
    }

    private static Rect bounds(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r;
    }

    private static String shortClass(AccessibilityNodeInfo n) {
        String c = String.valueOf(n.getClassName());
        return c.substring(c.lastIndexOf('.') + 1);
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String cut(String s, int max) {
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private int bar(String name) {
        int id = service.getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? service.getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(int v) {
        return Math.round(v * service.getResources().getDisplayMetrics().density);
    }
}
