package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills the slot page straight through accessibility: picks the option you typed in the
 * dropdown, picks the date in the calendar (moving month by month with its arrows when the
 * date isn't shown), ticks the checkbox, picks the radio button, and presses Continue.
 */
final class Booker {

    interface Listener {
        void done(String summary, String log);
    }

    static final class Plan {
        String option = "", radio = "";
        int day, month, year;
    }

    private static final Pattern MONTH_ID = Pattern.compile("(\\d{1,2})/(\\d{4})");

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final StringBuilder log = new StringBuilder();
    private final StringBuilder done = new StringBuilder();
    private boolean running;
    private int gen;
    private long start;
    private Plan plan;

    Booker(AccessibilityService service, Listener listener) {
        this.service = service;
        this.listener = listener;
    }

    boolean isRunning() {
        return running;
    }

    void start(Plan p) {
        if (running) return;
        running = true;
        gen++;
        plan = p;
        start = SystemClock.uptimeMillis();
        log.setLength(0);
        done.setLength(0);
        log("Book: option \"" + p.option + "\", date " + p.day + "/" + p.month + "/" + p.year
                + (p.radio.isEmpty() ? "" : ", radio \"" + p.radio + "\""));
        later(() -> pickOption(0), 50);
    }

    void stop(String why) {
        if (!running) return;
        running = false;
        gen++;
        log("END: " + why);
        String summary = why + (done.length() == 0 ? "" : "\n" + done);
        listener.done(summary, "A11y Inspector - Book run\n=========================\n" + summary
                + "\n\nSTEPS\n" + log);
    }

    // ---- 1) the dropdown ------------------------------------------------------------

    private void pickOption(int tries) {
        if (plan.option.isEmpty()) {
            log("dropdown: no option given - left as it is");
            pickDate(0, 0);
            return;
        }
        AccessibilityNodeInfo field = dropdownField();
        if (field != null && matchScore(Taught.label(field), plan.option) >= 2) {
            log("dropdown: already \"" + Taught.label(field) + "\"");
            done.append("✓ Option: ").append(Taught.label(field)).append('\n');
            pickDate(0, 0);
            return;
        }
        AccessibilityNodeInfo item = optionItem();
        if (item == null) {
            if (tries >= 3) {
                stop("✗ Option \"" + plan.option + "\" not found in the dropdown");
                return;
            }
            if (field == null) {
                stop("✗ No dropdown on this page");
                return;
            }
            log("dropdown: opening it");
            click(field);
            later(() -> pickOption(tries + 1), 400);
            return;
        }
        String text = Taught.label(item);
        log("dropdown: choosing \"" + text + "\"");
        Rect r = bounds(item);
        if (r.height() <= 4) {
            // Further down the list: bring it into view first.
            item.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
            later(() -> {
                click(item);
                waitFor(() -> optionChosen(text), 2000, ok -> afterOption(ok, text));
            }, 250);
        } else {
            click(item);
            waitFor(() -> optionChosen(text), 2000, ok -> afterOption(ok, text));
        }
    }

    private void afterOption(boolean ok, String text) {
        if (!ok) {
            stop("✗ Chose \"" + text + "\" but the dropdown didn't change");
            return;
        }
        log("dropdown: \"" + text + "\" chosen ✓");
        done.append("✓ Option: ").append(text).append('\n');
        // The calendar redraws for the new option: wait for its days.
        waitFor(() -> !dayCells().isEmpty(), 3000, ok2 -> pickDate(0, 0));
    }

    private boolean optionChosen(String text) {
        AccessibilityNodeInfo f = dropdownField();
        if (f == null) return false;
        String now = norm(Taught.label(f)), want = norm(text);
        return !now.isEmpty() && (now.equals(want) || want.startsWith(now) || now.startsWith(want));
    }

    /** The dropdown's own box: the page's text field (it shows the chosen option). */
    private AccessibilityNodeInfo dropdownField() {
        for (AccessibilityNodeInfo n : Taught.nodes(service)) {
            String cls = String.valueOf(n.getClassName());
            String role = Taught.role(n).toLowerCase(Locale.ROOT);
            if ((cls.endsWith("EditText") || role.contains("combobox") || role.contains("textfield"))
                    && n.isVisibleToUser()) {
                return n;
            }
        }
        return null;
    }

    /** The list option best matching what you typed (all its words), or null. */
    private AccessibilityNodeInfo optionItem() {
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        for (AccessibilityNodeInfo n : Taught.nodes(service)) {
            String role = Taught.role(n).toLowerCase(Locale.ROOT);
            if (!role.contains("listitem") && !role.contains("option") && !role.contains("menuitem")) continue;
            int score = matchScore(Taught.label(n), plan.option);
            if (score > bestScore) {
                best = n;
                bestScore = score;
            }
        }
        return best;
    }

    /** 0 no match, 1 every word found in it, 2 the same text. */
    private static int matchScore(String label, String want) {
        String l = norm(label), w = norm(want);
        if (l.isEmpty() || w.isEmpty()) return 0;
        if (l.equals(w)) return 2;
        for (String word : w.split(" ")) if (!l.contains(word)) return 0;
        return 1;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace("…", "").replaceAll("[^a-z0-9]+", " ").trim();
    }

    // ---- 2) the date ----------------------------------------------------------------

    private void pickDate(int moves, int waits) {
        if (plan.day <= 0) {
            log("date: none given - left as it is");
            tickBox(0);
            return;
        }
        Map<AccessibilityNodeInfo, int[]> cells = dayCells();
        if (cells.isEmpty()) {
            if (waits < 10) {
                later(() -> pickDate(moves, waits + 1), 200);
                return;
            }
            stop("✗ No calendar on this page");
            return;
        }
        for (Map.Entry<AccessibilityNodeInfo, int[]> e : cells.entrySet()) {
            int[] d = e.getValue();
            if (d[0] == plan.day && d[1] == plan.month && d[2] == plan.year) {
                AccessibilityNodeInfo cell = e.getKey();
                if (!cell.isEnabled()) {
                    stop("✗ " + dateText() + " can't be chosen (not open for booking)");
                    return;
                }
                log("date: pressing " + dateText());
                click(cell);
                done.append("✓ Date: ").append(dateText()).append('\n');
                later(() -> tickBox(0), 400);
                return;
            }
        }
        // Not on the calendar shown: move a month towards it with the arrows.
        int shown = shownMonth(cells);
        int want = plan.year * 12 + plan.month - 1;
        if (moves >= 24) {
            stop("✗ " + dateText() + " not found after 24 months");
            return;
        }
        boolean forward = want > shown;
        AccessibilityNodeInfo arrow = arrow(forward);
        if (arrow == null) {
            stop("✗ " + dateText() + " isn't shown and there's no " + (forward ? "next" : "previous") + " month arrow");
            return;
        }
        log("date: " + monthText(shown) + " shown - going " + (forward ? "forward" : "back") + " a month");
        click(arrow);
        waitFor(() -> shownMonth(dayCells()) != shown, 2000, moved -> {
            if (!moved) {
                stop("✗ The calendar didn't move past " + monthText(shown) + " (no later dates open?)");
                return;
            }
            pickDate(moves + 1, 0);
        });
    }

    /** The calendar's day cells: {day, month, year}, the month from the cell's page id "M/YYYY". */
    private Map<AccessibilityNodeInfo, int[]> dayCells() {
        Map<AccessibilityNodeInfo, int[]> out = new HashMap<>();
        for (AccessibilityNodeInfo n : Taught.nodes(service)) {
            String id = n.getViewIdResourceName();
            String text = Taught.label(n);
            if (id == null || !text.matches("\\d{1,2}")) continue;
            Matcher m = MONTH_ID.matcher(id);
            if (!m.find()) continue;
            out.put(n, new int[] {Integer.parseInt(text), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
        }
        return out;
    }

    /** The month most of the cells belong to (the grid also shows a few days either side). */
    private static int shownMonth(Map<AccessibilityNodeInfo, int[]> cells) {
        Map<Integer, Integer> count = new HashMap<>();
        int best = -1, bestCount = 0;
        for (int[] d : cells.values()) {
            int k = d[2] * 12 + d[1] - 1;
            int c = count.merge(k, 1, Integer::sum);
            if (c > bestCount) {
                best = k;
                bestCount = c;
            }
        }
        return best;
    }

    private AccessibilityNodeInfo arrow(boolean forward) {
        for (AccessibilityNodeInfo n : Taught.nodes(service)) {
            if (!n.isClickable()) continue;
            String l = Taught.label(n).toLowerCase(Locale.ROOT);
            boolean right = l.contains("right") || l.contains("next") || l.contains("forward") || l.equals("›")
                    || l.equals(">");
            boolean left = l.contains("left") || l.contains("prev") || l.equals("‹") || l.equals("<");
            if (l.contains("white_back_arrow")) continue; // the page's back button
            if (forward ? right : left) return n;
        }
        return null;
    }

    private String dateText() {
        return plan.day + "/" + plan.month + "/" + plan.year;
    }

    private static String monthText(int k) {
        return (k % 12 + 1) + "/" + (k / 12);
    }

    // ---- 3) the checkbox ------------------------------------------------------------

    private void tickBox(int waits) {
        AccessibilityNodeInfo box = null;
        for (AccessibilityNodeInfo n : Taught.nodes(service)) {
            String cls = String.valueOf(n.getClassName());
            String role = Taught.role(n).toLowerCase(Locale.ROOT);
            if (cls.endsWith("RadioButton") || role.contains("radio") || cls.endsWith("Switch")) continue;
            if (cls.endsWith("CheckBox") || role.contains("checkbox") || n.isCheckable()) {
                box = n;
                break;
            }
        }
        if (box == null) {
            if (waits < 10) {
                later(() -> tickBox(waits + 1), 200);
                return;
            }
            log("checkbox: none on the page - skipped");
            pickRadio(0);
            return;
        }
        if (box.isChecked()) {
            log("checkbox: already ticked");
            done.append("✓ Checkbox (already ticked)\n");
            pickRadio(0);
            return;
        }
        AccessibilityNodeInfo b = box;
        b.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        later(() -> {
            log("checkbox: clicking it");
            b.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            waitFor(() -> isChecked(b), 800, ok -> {
                if (!ok) {
                    // A real tap on what you see (its label, when the box itself is hidden).
                    Rect r = visible(b);
                    if (r != null) {
                        log("checkbox: click didn't tick it - tapping it at " + r.centerX() + "," + r.centerY());
                        tap(r.centerX(), r.centerY());
                    }
                    waitFor(() -> isChecked(b), 800, ok2 -> {
                        if (!ok2) {
                            stop("✗ The checkbox didn't tick");
                            return;
                        }
                        ticked();
                    });
                    return;
                }
                ticked();
            });
        }, 200);
    }

    private void ticked() {
        log("checkbox: ticked ✓");
        done.append("✓ Checkbox ticked\n");
        pickRadio(0);
    }

    // ---- 4) the radio button ----------------------------------------------------------

    private void pickRadio(int waits) {
        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        for (AccessibilityNodeInfo n : Taught.nodes(service)) {
            String cls = String.valueOf(n.getClassName());
            String role = Taught.role(n).toLowerCase(Locale.ROOT);
            if (!cls.endsWith("RadioButton") && !role.contains("radio")) continue;
            if (role.contains("radiogroup") || !n.isEnabled()) continue;
            int score = plan.radio.isEmpty() ? 0 : matchScore(radioText(n), plan.radio);
            if (!plan.radio.isEmpty() && score == 0) continue;
            if (score > bestScore) { // the first one wins a tie
                best = n;
                bestScore = score;
            }
        }
        if (best == null) {
            if (waits < 10) {
                later(() -> pickRadio(waits + 1), 200);
                return;
            }
            log("radio: " + (plan.radio.isEmpty() ? "none on the page" : "no \"" + plan.radio + "\"") + " - skipped");
            pressContinue(0);
            return;
        }
        AccessibilityNodeInfo r = best;
        String text = radioText(r);
        if (r.isChecked()) {
            log("radio: \"" + text + "\" already chosen");
            done.append("✓ Radio: ").append(text).append('\n');
            pressContinue(0);
            return;
        }
        r.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        later(() -> {
            log("radio: choosing \"" + text + "\"");
            r.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            waitFor(() -> isChecked(r), 800, ok -> {
                if (!ok) {
                    Rect v = visible(r);
                    if (v != null) tap(v.centerX(), v.centerY());
                }
                waitFor(() -> isChecked(r), ok ? 0 : 800, ok2 -> {
                    log("radio: " + (ok2 ? "chosen ✓" : "not shown as chosen - going on"));
                    done.append(ok2 ? "✓ Radio: " : "? Radio: ").append(text).append('\n');
                    pressContinue(0);
                });
            });
        }, 200);
    }

    /** The radio button's own words, or its label's (next to it). */
    private static String radioText(AccessibilityNodeInfo n) {
        String t = Taught.label(n);
        if (!t.isEmpty()) return t;
        AccessibilityNodeInfo p = n.getParent();
        return p == null ? "" : Taught.label(p);
    }

    // ---- 5) Continue ------------------------------------------------------------------

    private void pressContinue(int waits) {
        AccessibilityNodeInfo button = null;
        for (AccessibilityNodeInfo n : Taught.nodes(service)) {
            if (n.isClickable() && norm(Taught.label(n)).equals("continue")) button = n;
        }
        if (button == null || !button.isEnabled()) {
            if (waits < 15) {
                later(() -> pressContinue(waits + 1), 200);
                return;
            }
            stop(button == null ? "✗ No Continue button" : "✗ Continue stayed off (something still missing?)");
            return;
        }
        AccessibilityNodeInfo b = button;
        b.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        later(() -> {
            log("continue: pressing it");
            click(b);
            done.append("✓ Continue pressed\n");
            later(() -> stop("Done"), 300);
        }, 200);
    }

    // ---- helpers ----------------------------------------------------------------------

    private void click(AccessibilityNodeInfo n) {
        if (!n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Rect r = visible(n);
            if (r != null) tap(r.centerX(), r.centerY());
        }
    }

    /** Where the element (or, when it is hidden, its nearest sized parent) is on screen. */
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

    private static boolean isChecked(AccessibilityNodeInfo n) {
        try {
            n.refresh();
        } catch (RuntimeException ignored) {
        }
        return n.isChecked();
    }

    /** Checks {@code test} every 60 ms until it holds or {@code ms} pass. */
    private void waitFor(Supplier<Boolean> test, long ms, Consumer<Boolean> then) {
        boolean ok;
        try {
            ok = test.get();
        } catch (RuntimeException e) {
            ok = false;
        }
        if (ok || ms <= 0) {
            then.accept(ok);
            return;
        }
        later(() -> waitFor(test, ms - 60, then), 60);
    }

    private void tap(int x, int y) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    private void later(Runnable r, long ms) {
        int g = gen;
        handler.postDelayed(() -> {
            if (!running || g != gen) return;
            try {
                r.run();
            } catch (RuntimeException e) {
                stop("Error: " + e);
            }
        }, ms);
    }

    private void log(String line) {
        log.append(SystemClock.uptimeMillis() - start).append(" ms  ").append(line).append('\n');
    }

    /** "15/10/2026", "15-10-26", "15.10" (this year, or next if already past) -> plan. */
    static boolean parseDate(String s, Plan p) {
        Matcher m = Pattern.compile("(\\d{1,2})\\D+(\\d{1,2})(?:\\D+(\\d{2,4}))?").matcher(s.trim());
        if (!m.find()) return false;
        int d = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2));
        if (d < 1 || d > 31 || mo < 1 || mo > 12) return false;
        java.util.Calendar now = java.util.Calendar.getInstance();
        int y;
        if (m.group(3) != null) {
            y = Integer.parseInt(m.group(3));
            if (y < 100) y += 2000;
        } else {
            y = now.get(java.util.Calendar.YEAR);
            int today = (now.get(java.util.Calendar.MONTH) + 1) * 100 + now.get(java.util.Calendar.DAY_OF_MONTH);
            if (mo * 100 + d < today) y++;
        }
        p.day = d;
        p.month = mo;
        p.year = y;
        return true;
    }
}
