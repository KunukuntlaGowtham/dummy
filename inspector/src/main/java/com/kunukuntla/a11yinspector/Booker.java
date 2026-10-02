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
        if (field != null && matchScore(Page.label(field), plan.option) >= 2) {
            log("dropdown: already \"" + Page.label(field) + "\"");
            done.append("✓ Option: ").append(Page.label(field)).append('\n');
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
        String text = Page.label(item);
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
        String now = norm(Page.label(f)), want = norm(text);
        return !now.isEmpty() && (now.equals(want) || want.startsWith(now) || now.startsWith(want));
    }

    /** The dropdown's own box: the page's text field (it shows the chosen option). */
    private AccessibilityNodeInfo dropdownField() {
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            String cls = String.valueOf(n.getClassName());
            String role = Page.role(n).toLowerCase(Locale.ROOT);
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
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            String role = Page.role(n).toLowerCase(Locale.ROOT);
            if (!role.contains("listitem") && !role.contains("option") && !role.contains("menuitem")) continue;
            int score = matchScore(Page.label(n), plan.option);
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
                    log("date: " + dateText() + " is shown as not open - tapping it anyway to see what the page says");
                }
                pageBefore = pageTexts();
                // Clear of the page's header first, then one way after another until the page
                // shows it took the day.
                inView(cell, 0, r -> chooseDay(0));
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
        // A real tap on the arrow, like on the days (this calendar ignores a plain click);
        // if the month doesn't change, a click is tried too.
        inView(arrow, 0, r -> {
            log("date: " + monthText(shown) + " shown - tapping the " + (forward ? "next" : "previous")
                    + " month arrow at " + r.centerX() + "," + r.centerY());
            tap(r.centerX(), r.centerY());
            waitFor(() -> shownMonth(dayCells()) != shown, 1500, moved -> {
                if (moved) {
                    pickDate(moves + 1, 0);
                    return;
                }
                log("date: the month didn't change - clicking the arrow instead");
                arrow.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                waitFor(() -> shownMonth(dayCells()) != shown, 1500, moved2 -> {
                    if (!moved2) {
                        stop("✗ The calendar didn't move past " + monthText(shown)
                                + " (no " + (forward ? "later" : "earlier") + " months open?)");
                        return;
                    }
                    pickDate(moves + 1, 0);
                });
            });
        });
    }

    /** The day's cell on the calendar now (the page may redraw it), or null. */
    private AccessibilityNodeInfo dayCell() {
        for (Map.Entry<AccessibilityNodeInfo, int[]> e : dayCells().entrySet()) {
            int[] d = e.getValue();
            if (d[0] == plan.day && d[1] == plan.month && d[2] == plan.year) return e.getKey();
        }
        return null;
    }

    /**
     * What the page shows now, to see whether choosing the day changed it: every text, and
     * the state (selected, checked, on/off) of everything that can be pressed.
     */
    private java.util.Set<String> pageState() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            String l = Page.label(n);
            if (!l.isEmpty()) out.add("t:" + l);
            if (n.isClickable() || n.isCheckable()) {
                out.add("s:" + l + "|" + n.isSelected() + n.isChecked() + n.isEnabled());
            }
            CharSequence st = android.os.Build.VERSION.SDK_INT >= 30 ? n.getStateDescription() : null;
            if (st != null) out.add("d:" + l + "|" + st);
        }
        return out;
    }

    /**
     * Chooses the day, one way after another, until the page changes (new texts such as the
     * slots or the chosen date, the day shown selected, Continue turning on):
     * 0) a click on the day's cell, 1) a click on the number inside it, 2) a tap on the cell,
     * 3) a longer press on the number. With no change after all four, goes on, unconfirmed.
     */
    private void chooseDay(int way) {
        AccessibilityNodeInfo cell = dayCell();
        if (cell == null) {
            stop("✗ " + dateText() + " went from the calendar");
            return;
        }
        java.util.Set<String> before = pageState();
        AccessibilityNodeInfo number = cell;
        for (int i = 0; i < cell.getChildCount(); i++) {
            AccessibilityNodeInfo c = cell.getChild(i);
            if (c != null && Page.label(c).equals(String.valueOf(plan.day))) number = c;
        }
        Rect r = visible(cell), nr = visible(number);
        switch (way) {
            case 0:
                log("date: clicking the day " + dateText());
                cell.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                break;
            case 1:
                if (number == cell) {
                    chooseDay(2);
                    return;
                }
                log("date: clicking the number inside the day");
                number.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                break;
            case 2:
                if (r == null) {
                    chooseDay(3);
                    return;
                }
                log("date: tapping the day at " + r.centerX() + "," + r.centerY());
                tap(r.centerX(), r.centerY(), 80);
                break;
            case 3:
                Rect t = nr != null ? nr : r;
                if (t == null) {
                    dayUnconfirmed();
                    return;
                }
                log("date: pressing the number at " + t.centerX() + "," + t.centerY() + " a little longer");
                tap(t.centerX(), t.centerY(), 220);
                break;
            default:
                dayUnconfirmed();
                return;
        }
        waitFor(() -> !pageState().equals(before), 1500, changed -> {
            if (changed) {
                log("date: the page took it ✓ (way " + (way + 1) + ")");
                done.append("✓ Date: ").append(dateText()).append('\n');
                later(() -> {
                    said("after the date");
                    tickBox(0);
                }, 400);
            } else {
                log("date: nothing changed on the page");
                chooseDay(way + 1);
            }
        });
    }

    private void dayUnconfirmed() {
        log("date: tried 4 ways, the page showed no change - going on");
        done.append("? Date: ").append(dateText()).append(" (not confirmed - the page showed no change)\n");
        said("after the date");
        tickBox(0);
    }

    /**
     * The calendar's day cells: {day, month, year}. The month comes from the cell's page id
     * ("M/YYYY"); for other calendars from the cell's own words ("15 October 2026", "Oct 15,
     * 2026", "15/10/2026"), else the bare day numbers with the month from the calendar's
     * header ("October 2026").
     */
    private Map<AccessibilityNodeInfo, int[]> dayCells() {
        return dayCells(service);
    }

    static Map<AccessibilityNodeInfo, int[]> dayCells(AccessibilityService service) {
        Map<AccessibilityNodeInfo, int[]> out = new HashMap<>();
        java.util.List<AccessibilityNodeInfo> nodes = Page.nodes(service);
        for (AccessibilityNodeInfo n : nodes) {
            String id = n.getViewIdResourceName();
            String text = Page.label(n);
            if (id == null || !text.matches("\\d{1,2}")) continue;
            Matcher m = MONTH_ID.matcher(id);
            if (!m.find()) continue;
            out.put(n, new int[] {Integer.parseInt(text), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
        }
        if (!out.isEmpty()) return out;
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence d = n.getContentDescription();
            int[] date = fullDate(d == null ? "" : d.toString());
            if (date == null) date = fullDate(Page.label(n));
            if (date != null) out.put(n, date);
        }
        if (!out.isEmpty()) return out;
        int[] header = null;
        for (AccessibilityNodeInfo n : nodes) {
            header = monthYear(Page.label(n));
            if (header != null) break;
        }
        if (header == null) return out;
        for (AccessibilityNodeInfo n : nodes) {
            String text = Page.label(n);
            if (!text.matches("\\d{1,2}") || !(n.isClickable() || n.getParent() != null && n.getParent().isClickable())) continue;
            int day = Integer.parseInt(text);
            if (day >= 1 && day <= 31) out.put(n, new int[] {day, header[0], header[1]});
        }
        return out;
    }

    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug",
            "sep", "oct", "nov", "dec"};

    private static int monthOf(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.length() < 3) return 0;
        for (int i = 0; i < 12; i++) if (w.startsWith(MONTHS[i])) return i + 1;
        return 0;
    }

    /** "15 October 2026", "Thursday, Oct 15, 2026", "15/10/2026" -> {day, month, year}. */
    static int[] fullDate(String s) {
        if (s == null || s.isEmpty()) return null;
        Matcher m = Pattern.compile("(\\d{1,2})\\s+([A-Za-z]{3,})\\.?,?\\s+(\\d{4})").matcher(s);
        if (m.find() && monthOf(m.group(2)) > 0) {
            return new int[] {Integer.parseInt(m.group(1)), monthOf(m.group(2)), Integer.parseInt(m.group(3))};
        }
        m = Pattern.compile("([A-Za-z]{3,})\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})").matcher(s);
        if (m.find() && monthOf(m.group(1)) > 0) {
            return new int[] {Integer.parseInt(m.group(2)), monthOf(m.group(1)), Integer.parseInt(m.group(3))};
        }
        m = Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})$").matcher(s.trim());
        if (m.find()) {
            int d = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2));
            if (d >= 1 && d <= 31 && mo >= 1 && mo <= 12) return new int[] {d, mo, Integer.parseInt(m.group(3))};
        }
        return null;
    }

    /** A calendar header, "October 2026" / "Oct 2026" -> {month, year}. */
    static int[] monthYear(String s) {
        Matcher m = Pattern.compile("^([A-Za-z]{3,})\\.?,?\\s+(\\d{4})$").matcher(s.trim());
        if (!m.find() || monthOf(m.group(1)) == 0) return null;
        return new int[] {monthOf(m.group(1)), Integer.parseInt(m.group(2))};
    }

    /** The month most of the cells belong to (the grid also shows a few days either side). */
    static int shownMonth(Map<AccessibilityNodeInfo, int[]> cells) {
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
        return arrow(service, forward);
    }

    /** The calendar's next (or previous) month arrow, or null. */
    static AccessibilityNodeInfo arrow(AccessibilityService service, boolean forward) {
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            if (!n.isClickable()) continue;
            String l = Page.label(n).toLowerCase(Locale.ROOT);
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

    static String monthText(int k) {
        return (k % 12 + 1) + "/" + (k / 12);
    }

    // ---- 3) the checkbox ------------------------------------------------------------

    private void tickBox(int waits) {
        AccessibilityNodeInfo box = null;
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            String cls = String.valueOf(n.getClassName());
            String role = Page.role(n).toLowerCase(Locale.ROOT);
            if (cls.endsWith("RadioButton") || role.contains("radio") || cls.endsWith("Switch")) continue;
            if (cls.endsWith("CheckBox") || role.contains("checkbox") || n.isCheckable()) {
                box = n;
                break;
            }
        }
        if (box == null) {
            if (waits < 40) { // the slots load after the date: up to 8 s
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
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            String cls = String.valueOf(n.getClassName());
            String role = Page.role(n).toLowerCase(Locale.ROOT);
            if (!cls.endsWith("RadioButton") && !role.contains("radio")) continue;
            if (role.contains("radiogroup") || !n.isEnabled()) continue;
            int score = plan.radio.isEmpty() ? 0 : matchScore(radioText(n), plan.radio);
            if (!plan.radio.isEmpty() && score == 0) continue;
            if (score > bestScore) { // the first one wins a tie
                best = n;
                bestScore = score;
            }
        }
        if (best == null) best = roundButton();
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
        if (!isRadio(r)) {
            // The page draws its choice as a small round button (not reported as a radio).
            inView(r, 0, v -> {
                log("radio: tapping the round choice button at " + v.centerX() + "," + v.centerY()
                        + (text.isEmpty() ? "" : " (\"" + text + "\")"));
                tap(v.centerX(), v.centerY());
                done.append("✓ Radio (round button)").append(text.isEmpty() ? "" : ": " + text).append('\n');
                later(() -> pressContinue(0), 400);
            });
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

    static boolean isRadio(AccessibilityNodeInfo n) {
        return String.valueOf(n.getClassName()).endsWith("RadioButton")
                || Page.role(n).toLowerCase(Locale.ROOT).contains("radio");
    }

    /**
     * A choice drawn as a small round, unnamed button in a card (the slot card): the one whose
     * card has your words, else the first. Null when there is none.
     */
    private AccessibilityNodeInfo roundButton() {
        AccessibilityNodeInfo first = null;
        int small = dp(34);
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            if (!n.isClickable() || !Page.label(n).isEmpty()) continue;
            String cls = String.valueOf(n.getClassName());
            if (!cls.endsWith("Button") || cls.endsWith("ImageButton")) continue;
            Rect r = bounds(n);
            if (r.width() < dp(10) || r.width() > small || Math.abs(r.width() - r.height()) > dp(6)) continue;
            if (plan.radio.isEmpty()) return n;
            if (first == null) first = n;
            if (matchScore(radioText(n), plan.radio) > 0) return n;
        }
        return first; // your words not found in any card: the first one
    }

    /** The radio button's own words, or its card's words (up to 3 levels up). */
    static String radioText(AccessibilityNodeInfo n) {
        String t = Page.label(n);
        if (!t.isEmpty()) return t;
        AccessibilityNodeInfo p = n.getParent();
        for (int i = 0; p != null && i < 3; i++, p = p.getParent()) {
            StringBuilder sb = new StringBuilder();
            collectText(p, sb, 0);
            if (sb.length() > 0) return sb.toString().trim();
        }
        return "";
    }

    private static void collectText(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 4 || sb.length() > 200) return;
        String l = Page.label(n);
        if (!l.isEmpty()) sb.append(l).append(' ');
        for (int i = 0; i < n.getChildCount(); i++) collectText(n.getChild(i), sb, depth + 1);
    }

    // ---- 5) Continue ------------------------------------------------------------------

    private void pressContinue(int waits) {
        AccessibilityNodeInfo button = null;
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            if (n.isClickable() && norm(Page.label(n)).equals("continue")) button = n;
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
            pageBefore = pageTexts();
            click(b);
            done.append("✓ Continue pressed\n");
            // Wait for the next screen, or for the page's message saying what is wrong.
            later(() -> {
                said("after Continue");
                boolean stillHere = false;
                for (AccessibilityNodeInfo n : Page.nodes(service)) {
                    if (n.isVisibleToUser() && norm(Page.label(n)).equals("continue")) stillHere = true;
                }
                done.append(stillHere ? "• Stayed on this page\n" : "• Moved to the next screen\n");
                stop("Done");
            }, 2500);
        }, 200);
    }

    // ---- what the page says (its error messages) -------------------------------------------

    private java.util.Set<String> pageBefore = new java.util.HashSet<>();

    /** Every text on the page now (not bare numbers, like the calendar's days). */
    private java.util.Set<String> pageTexts() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            String t = Page.label(n);
            if (t.length() < 2 || t.matches("[\\d\\s/:.-]+")) continue;
            out.add(t);
        }
        return out;
    }

    /** Logs the texts that came up since {@code pageBefore}: the page's messages, errors. */
    private void said(String when) {
        java.util.List<String> fresh = new java.util.ArrayList<>();
        for (String t : pageTexts()) if (!pageBefore.contains(t)) fresh.add(t);
        if (fresh.isEmpty()) {
            log("page " + when + ": no new message");
            return;
        }
        String msg = String.join(" | ", fresh.subList(0, Math.min(8, fresh.size())));
        log("page " + when + " says: " + msg);
        done.append("💬 ").append(when).append(": ").append(msg).append('\n');
    }

    /** A toast (short message) the page showed: logged as a message. */
    void toast(String text) {
        if (!running || text == null || text.trim().isEmpty()) return;
        log("page toast: " + text.trim());
        done.append("💬 toast: ").append(text.trim()).append('\n');
    }

    // ---- the dropdown's options, for the form ---------------------------------------------

    /**
     * Every option in the page's dropdown (opening it if it is closed), for you to choose
     * from. Empty when the page has no dropdown.
     */
    static void options(AccessibilityService service, Handler handler, Consumer<java.util.List<String>> done) {
        java.util.List<String> now = optionTexts(service);
        if (!now.isEmpty()) {
            done.accept(now);
            return;
        }
        AccessibilityNodeInfo field = null;
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            String cls = String.valueOf(n.getClassName());
            String role = Page.role(n).toLowerCase(Locale.ROOT);
            if (cls.endsWith("EditText") || role.contains("combobox") || role.contains("textfield")) {
                field = n;
                break;
            }
        }
        if (field == null) {
            done.accept(now);
            return;
        }
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        long until = SystemClock.uptimeMillis() + 1500;
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            java.util.List<String> got = optionTexts(service);
            if (!got.isEmpty() || SystemClock.uptimeMillis() > until) done.accept(got);
            else handler.postDelayed(poll[0], 100);
        };
        handler.postDelayed(poll[0], 150);
    }

    static java.util.List<String> optionTexts(AccessibilityService service) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            String role = Page.role(n).toLowerCase(Locale.ROOT);
            if (!role.contains("listitem") && !role.contains("option") && !role.contains("menuitem")) continue;
            String t = Page.label(n);
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    // ---- helpers ----------------------------------------------------------------------

    /**
     * Makes sure the element is on screen and clear of the page's header and the bottom edge
     * (a tap there would land on something else), then hands its place to {@code then}.
     */
    private void inView(AccessibilityNodeInfo n, int tries, Consumer<Rect> then) {
        try {
            n.refresh();
        } catch (RuntimeException ignored) {
        }
        Rect r = visible(n);
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        int top = dp(150), bottom = dm.heightPixels - dp(90);
        if (r != null && r.top >= top && r.bottom <= bottom) {
            then.accept(r);
            return;
        }
        if (tries >= 3) {
            if (r != null) then.accept(r);
            else stop("✗ Couldn't bring it on screen");
            return;
        }
        if (tries == 0) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        } else {
            // Drag the page so it comes to the middle of the screen.
            boolean up = r != null && r.top < top;
            Path p = new Path();
            float cx = dm.widthPixels / 2f, a = dm.heightPixels * 0.40f, b = dm.heightPixels * 0.62f;
            p.moveTo(cx, up ? a : b);
            p.lineTo(cx, up ? b : a);
            service.dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 350)).build(), null, null);
        }
        later(() -> inView(n, tries + 1, then), 450);
    }

    private int dp(int v) {
        return Math.round(v * service.getResources().getDisplayMetrics().density);
    }

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
        tap(x, y, 60);
    }

    private void tap(int x, int y, long ms) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null);
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
