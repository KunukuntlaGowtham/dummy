package com.kunukuntla.ttdfiller;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * ▶ Go on the Special Entry Darshan slot page ("Darshan Slots"), straight through to payment:
 * <ol>
 * <li>your dates, in your order: the month moved to (its arrow tapped) and the day tapped, until
 * the page says "Select any 1 Slot: Dated …" for it;</li>
 * <li>a slot between your hours (12 PM - 10 PM unless set otherwise) with enough tickets left -
 * the one with the most left (or the earliest); none: the next date;</li>
 * <li>"Number of Tickets" = the people in your booking (the most the list offers when fewer);</li>
 * <li>the slot's round button tapped, Continue;</li>
 * <li>the pilgrims filled (as 📝 Fill does), Continue when every box is right;</li>
 * <li>on the review page, Pay Now.</li>
 * </ol>
 * No date with a slot: Refresh, and all the dates again, until the time you set runs out.
 * ⏹ stops it at once.
 */
final class GoRun {

    interface Listener {
        void progress(String text);

        void finished(boolean ok, String report, String details);
    }

    private final AccessibilityService s;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Booking booking;
    private final Listener out;
    private final BooleanSupplier alive;

    private boolean running;
    private int gen;
    private long started, retryUntil;
    private List<int[]> dates = new ArrayList<>();
    private int need, fromH, toH, round, contTries;
    private boolean earliest, any, payNow;
    private int[] date;
    private int dateAt, tickets;
    private SlotPage.Slot slot;
    private Filler filler;
    private final List<String> notes = new ArrayList<>();
    private final StringBuilder log = new StringBuilder();

    GoRun(AccessibilityService s, Booking booking, Listener out) {
        this.s = s;
        this.booking = booking;
        this.out = out;
        this.alive = () -> running;
    }

    boolean running() {
        return running;
    }

    void cancel() {
        if (!running) return;
        if (filler != null && filler.running()) filler.cancel();
        end(false, "⏹ Stopped");
    }

    void start() {
        running = true;
        gen++;
        started = SystemClock.uptimeMillis();
        SharedPreferences sp = Booking.prefs(s);
        dates = SlotPage.parseDates(sp.getString("go_dates", ""), NetTime.now());
        fromH = clampHour(sp.getInt("slot_from", 12));
        toH = clampHour(sp.getInt("slot_to", 22));
        earliest = sp.getBoolean("slot_earliest", false);
        any = sp.getBoolean("slot_any", false);
        payNow = sp.getBoolean("pay_now", true);
        retryUntil = started + Math.max(0, sp.getInt("go_retry_s", 120)) * 1000L;
        need = booking.people().size();
        if (need == 0) {
            end(false, "✗ " + booking.code() + " has nobody in it (no names in ttd_db)");
            return;
        }
        log("Go: " + booking.code() + ", " + need + " people, dates " + datesText() + ", slots " + hourText(fromH)
                + "–" + hourText(toH) + (earliest ? " earliest" : " most left") + (any ? ", else any" : ""));
        where(0);
    }

    /** Started on a later page: carried on from there. */
    private void where(int waits) {
        List<AccessibilityNodeInfo> nodes = Nodes.all(s);
        if (SlotPage.button(nodes, "Pay Now") != null) {
            log("started on the review page");
            pay();
            return;
        }
        if (!Form.read(s).pilgrims.isEmpty()) {
            log("started on the pilgrim page");
            fill(0, -1);
            return;
        }
        if (SlotPage.isSlotPage(nodes)) {
            if (dates.isEmpty()) {
                end(false, "✗ No dates set. Open TTD Filler and type your dates in the ▶ Go card (e.g. 22/12/2026, 23/12/2026).");
                return;
            }
            round = 1;
            tryDate(0, 0);
            return;
        }
        if (waits < 15) {
            later(() -> where(waits + 1), 200);
            return;
        }
        end(false, "✗ Open the Darshan Slots page (the calendar), then press ▶ Go.");
    }

    // ---- 1) the date ----

    private void tryDate(int k, int moves) {
        if (k >= dates.size()) {
            nextRound();
            return;
        }
        date = dates.get(k);
        dateAt = k;
        List<AccessibilityNodeInfo> nodes = Nodes.all(s);
        Map<AccessibilityNodeInfo, int[]> cells = SlotPage.dayCells(nodes);
        if (cells.isEmpty()) {
            if (!Form.read(s).pilgrims.isEmpty()) {
                fill(0, -1);
                return;
            }
            end(false, "✗ The calendar went from the page");
            return;
        }
        if (Arrays.equals(SlotPage.chosenDate(nodes), date)) {
            log(SlotPage.text(date) + ": already chosen");
            afterDate(0);
            return;
        }
        AccessibilityNodeInfo cell = SlotPage.cell(cells, date);
        int shown = SlotPage.shownMonth(cells), want = date[2] * 12 + date[1] - 1;
        if (cell == null) {
            boolean forward = want > shown;
            AccessibilityNodeInfo arrow = SlotPage.arrow(nodes, forward);
            if (moves >= 12 || arrow == null) {
                note(SlotPage.text(date) + ": not on the calendar");
                tryDate(k + 1, 0);
                return;
            }
            out.progress("Calendar " + (forward ? "→" : "←") + " to " + date[1] + "/" + date[2] + "…");
            // A real tap on the arrow (this calendar ignores a plain click); a click if the month didn't move.
            tapNode(arrow, () -> waitFor(() -> monthMoved(shown), 1500, 40, moved -> {
                if (moved) {
                    tryDate(k, moves + 1);
                    return;
                }
                arrow.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                waitFor(() -> monthMoved(shown), 1200, 40, moved2 -> {
                    if (moved2) tryDate(k, moves + 1);
                    else {
                        note(SlotPage.text(date) + ": the calendar doesn't go to that month");
                        tryDate(k + 1, 0);
                    }
                });
            }));
            return;
        }
        out.progress((round > 1 ? "Try " + round + " · " : "") + "Date " + SlotPage.text(date) + "…");
        long t0 = SystemClock.uptimeMillis();
        tapNode(cell, () -> waitFor(this::dateTaken, 1100, 40, ok -> {
            if (ok) {
                log(SlotPage.text(date) + ": chosen by tap in " + (SystemClock.uptimeMillis() - t0) + " ms");
                afterDate(0);
                return;
            }
            cell.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            waitFor(this::dateTaken, 800, 40, ok2 -> {
                if (ok2) {
                    log(SlotPage.text(date) + ": chosen by click");
                    afterDate(0);
                } else {
                    note(SlotPage.text(date) + ": not open (no slots came)");
                    tryDate(k + 1, 0);
                }
            });
        }));
    }

    private boolean monthMoved(int shown) {
        return SlotPage.shownMonth(SlotPage.dayCells(Nodes.all(s))) != shown;
    }

    private boolean dateTaken() {
        return Arrays.equals(SlotPage.chosenDate(Nodes.all(s)), date);
    }

    // ---- 2) the slot and 3) the tickets ----

    private void afterDate(int waits) {
        List<SlotPage.Slot> slots = SlotPage.slots(Nodes.all(s));
        if (slots.isEmpty()) {
            if (waits < 25) { // the cards come a moment after the date
                later(() -> afterDate(waits + 1), 60);
                return;
            }
            note(SlotPage.text(date) + ": no slots shown");
            tryDate(dateAt + 1, 0);
            return;
        }
        SlotPage.Slot pick = SlotPage.best(slots, need, fromH, toH, earliest, any);
        if (pick == null) {
            StringBuilder all = new StringBuilder();
            for (SlotPage.Slot sl : slots) all.append(all.length() > 0 ? ", " : "").append(sl.describe());
            note(SlotPage.text(date) + ": no slot " + hourText(fromH) + "–" + hourText(toH) + " with " + need + " left [" + all + "]");
            tryDate(dateAt + 1, 0);
            return;
        }
        log(SlotPage.text(date) + ": best slot " + pick.describe());
        setTickets(() -> chooseSlot(pick.time));
    }

    private void setTickets(Runnable then) {
        AccessibilityNodeInfo box = SlotPage.ticketsBox(Nodes.all(s));
        if (box == null) {
            log("no Number of Tickets box");
            tickets = need;
            then.run();
            return;
        }
        out.progress("Tickets: " + need + "…");
        // The people in the booking; the most the list offers when it offers fewer.
        String[] wants = new String[need];
        for (int i = 0; i < need; i++) wants[i] = String.valueOf(need - i);
        ListPick.choose(s, h, alive, box, wants, (ok, how) -> {
            tickets = Match.age(Form.value(box)).isEmpty() ? need : Integer.parseInt(Match.age(Form.value(box)));
            log("tickets: " + how);
            if (!ok) note("Number of Tickets: " + how);
            else if (tickets < need) note("only " + tickets + " tickets offered - the first " + tickets + " people are filled");
            then.run();
        });
    }

    /** The slot found again by its time (the page may have redrawn), its round button tapped, Continue. */
    private void chooseSlot(String time) {
        SlotPage.Slot pick = null;
        for (SlotPage.Slot sl : SlotPage.slots(Nodes.all(s))) if (sl.time.equals(time)) pick = sl;
        if (pick == null) {
            note(SlotPage.text(date) + ": the " + time + " slot went");
            tryDate(dateAt + 1, 0);
            return;
        }
        slot = pick;
        contTries = 0;
        out.progress("Slot " + pick.time + "…");
        AccessibilityNodeInfo target = pick.round != null ? pick.round : pick.card;
        tapNode(target, () -> later(this::pressContinue, 200));
    }

    private void pressContinue() {
        AccessibilityNodeInfo c = SlotPage.button(Nodes.all(s), "Continue");
        if (c == null) {
            end(false, "✗ No Continue button on the slot page");
            return;
        }
        out.progress("Continue…");
        if (!c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tapNode(c, () -> {
        });
        waitFor(() -> !Form.read(s).pilgrims.isEmpty(), 6000, 120, ok -> {
            if (ok) {
                log("pilgrim page after " + secs(SystemClock.uptimeMillis() - started));
                fill(0, -1);
                return;
            }
            if (++contTries < 2 && slot != null && slot.card != null) {
                // The slot may not have taken the tap: its card clicked, then Continue again.
                log("Continue didn't open the pilgrim page - choosing the slot again");
                slot.card.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                later(this::pressContinue, 250);
                return;
            }
            end(false, "✗ Continue didn't open the pilgrim page" + pageMessage());
        });
    }

    // ---- 4) the pilgrims, 5) Pay Now ----

    /** Once the pilgrim boxes hold still (the page has drawn them all), filled with Continue at the end. */
    private void fill(int looks, int lastCount) {
        int n = Form.read(s).pilgrims.size();
        if (n == 0 && looks >= 30) {
            end(false, "✗ The pilgrim boxes didn't come");
            return;
        }
        if (n == 0 || n != lastCount && looks < 8) {
            later(() -> fill(looks + 1, n), 120);
            return;
        }
        out.progress("Filling " + Math.min(n, need) + " pilgrims…");
        filler = new Filler(s, booking, true, new Filler.Listener() {
            @Override
            public void progress(String text) {
                out.progress(text);
            }

            @Override
            public void finished(boolean ok, String report, String details) {
                if (!running) return;
                log(report);
                if (!details.isEmpty()) log(details);
                if (!ok || !report.contains("Pressed Continue")) {
                    end(false, "⚠ Stopped at the pilgrim page:\n" + report);
                    return;
                }
                pay();
            }
        });
        filler.start(null);
    }

    private void pay() {
        out.progress("Review page…");
        waitFor(() -> SlotPage.button(Nodes.all(s), "Pay Now") != null, 8000, 100, ok -> {
            if (!ok) {
                end(false, "✗ The review page (Pay Now) didn't come" + pageMessage());
                return;
            }
            String head = summary();
            if (!payNow) {
                end(true, "✅ " + head + "\n➡ Review page is up - press Pay Now yourself");
                return;
            }
            AccessibilityNodeInfo p = SlotPage.button(Nodes.all(s), "Pay Now");
            if (p != null && !p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tapNode(p, () -> {
            });
            end(true, "✅ " + head + "\n💳 Pressed Pay Now");
        });
    }

    private String summary() {
        StringBuilder b = new StringBuilder(booking.code());
        if (date != null) b.append(" · ").append(SlotPage.text(date));
        if (slot != null) b.append(" · ").append(slot.describe());
        if (tickets > 0) b.append(" · ").append(tickets).append(" tickets");
        b.append(" · ").append(secs(SystemClock.uptimeMillis() - started));
        if (round > 1) b.append(" · try ").append(round);
        for (String n : notes) b.append("\n• ").append(n);
        return b.toString();
    }

    // ---- nothing fitted: Refresh, all the dates again ----

    private void nextRound() {
        if (SystemClock.uptimeMillis() >= retryUntil) {
            StringBuilder b = new StringBuilder("✗ No slot after " + round + " tr" + (round == 1 ? "y" : "ies") + " ("
                    + secs(SystemClock.uptimeMillis() - started) + ")");
            for (String n : notes) b.append("\n• ").append(n);
            end(false, b.toString());
            return;
        }
        round++;
        log("try " + round + ": " + String.join("; ", notes));
        notes.clear();
        out.progress("Try " + round + ": Refresh…");
        AccessibilityNodeInfo r = SlotPage.button(Nodes.all(s), "Refresh");
        if (r != null && !r.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tapNode(r, () -> {
        });
        later(() -> tryDate(0, 0), 900);
    }

    // ---- helpers ----

    /** A real tap on it where it really is (scrolled into view first when it isn't; a click when it can't be placed). */
    private void tapNode(AccessibilityNodeInfo n, Runnable then) {
        Rect r = rect(n);
        if (Nodes.onScreen(s, r)) {
            tapIn(r);
            then.run();
            return;
        }
        n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        settle(n, SystemClock.uptimeMillis() + 450, null, r2 -> {
            if (Nodes.onScreen(s, r2)) tapIn(r2);
            else n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            then.run();
        });
    }

    private void settle(AccessibilityNodeInfo n, long until, Rect last, Consumer<Rect> then) {
        Rect r = rect(n);
        if (Nodes.onScreen(s, r) && r.equals(last) || SystemClock.uptimeMillis() >= until) {
            then.accept(r);
            return;
        }
        later(() -> settle(n, until, r, then), 25);
    }

    private void tapIn(Rect r) {
        int x = r.centerX(), y = r.centerY();
        Rect avoid = FillService.instance == null ? null : FillService.instance.pillRect();
        if (avoid != null && avoid.contains(x, y)) x = r.right - Math.max(10, r.width() / 8);
        Nodes.tapAt(s, x, y);
    }

    private static Rect rect(AccessibilityNodeInfo n) {
        try {
            n.refresh();
        } catch (RuntimeException ignored) {
        }
        return Nodes.realRect(n);
    }

    /** What the page or the app says (an alert, a toast), for the report. */
    private String pageMessage() {
        StringBuilder b = new StringBuilder();
        for (AccessibilityNodeInfo n : Nodes.all(s)) {
            String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
            boolean alert = Nodes.role(n).equals("alert") && !id.contains("route-announcer");
            if (!alert && !id.toLowerCase(Locale.ROOT).contains("toast")) continue;
            String l = Nodes.label(n);
            if (!l.isEmpty() && b.indexOf(l) < 0) b.append(b.length() > 0 ? " · " : "").append(l);
        }
        return b.length() == 0 ? "" : "\nThe page says: " + b;
    }

    private void note(String n) {
        notes.add(n);
        log(n);
    }

    private void log(String line) {
        log.append(secs(SystemClock.uptimeMillis() - started)).append("  ").append(line).append('\n');
    }

    private String datesText() {
        StringBuilder b = new StringBuilder();
        for (int[] d : dates) b.append(b.length() > 0 ? ", " : "").append(SlotPage.text(d));
        return b.length() == 0 ? "none" : b.toString();
    }

    static String hourText(int h) {
        int x = h % 12 == 0 ? 12 : h % 12;
        return x + (h < 12 || h == 24 ? " AM" : " PM");
    }

    private static int clampHour(int h) {
        return Math.max(0, Math.min(24, h));
    }

    private static String secs(long ms) {
        return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    private void end(boolean ok, String report) {
        running = false;
        gen++;
        out.finished(ok, report, "Go log:\n" + log.toString().trim());
    }

    private void waitFor(BooleanSupplier test, long ms, long every, Consumer<Boolean> then) {
        boolean ok;
        try {
            ok = test.getAsBoolean();
        } catch (RuntimeException e) {
            ok = false;
        }
        if (ok || ms <= 0) {
            then.accept(ok);
            return;
        }
        later(() -> waitFor(test, ms - every, every, then), every);
    }

    private void later(Runnable r, long ms) {
        int g = gen;
        h.postDelayed(() -> {
            if (!running || g != gen) return;
            try {
                r.run();
            } catch (RuntimeException e) {
                end(false, "✗ Error: " + e);
            }
        }, ms);
    }
}
