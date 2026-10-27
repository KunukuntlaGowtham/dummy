package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 🗑 Delete (testing): deletes sevak profiles one after another, each by the number it has
 * on screen at that moment, in the order typed ("2, 5": profile 2, then whoever is number 5
 * once 2 has gone - the one that was 6). The page is read again before each. Each profile: its bin tapped,
 * then its 2 pop-ups (asks, then says done) closed the moment they come - at the place
 * their button was last time, else found on a screenshot by its purple button (and kept).
 */
final class DeleteRun {

    interface Listener {
        void done(String summary, String log);
    }

    private final AccessibilityService service;
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private final Listener listener;
    private final PurpleFinder finder;
    private final StringBuilder log = new StringBuilder();
    private final List<Integer> todo = new ArrayList<>(), deleted = new ArrayList<>(), failed = new ArrayList<>();
    private boolean running;
    private int gen;
    private long start, stepStart;
    private String pkg = "";

    DeleteRun(AccessibilityService service, Listener listener) {
        this.service = service;
        this.listener = listener;
        this.finder = new PurpleFinder(service);
    }

    boolean isRunning() {
        return running;
    }

    /** "2, 5" / "2 5" -> 2, 5 - in the order typed (a number twice: deleted twice); empty when none. */
    static List<Integer> parse(String s) {
        List<Integer> out = new ArrayList<>();
        for (String p : s.split("[^0-9]+")) {
            if (p.isEmpty()) continue;
            try {
                int n = Integer.parseInt(p);
                if (n > 0 && n < 100) out.add(n);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** The name each profile should have (null: not known - by number only), in step with the numbers. */
    private final List<String> names = new ArrayList<>();
    private String expected;

    void start(List<Integer> numbers) {
        start(numbers, null);
    }

    /**
     * {@code expect}: for each number, the sevak's name as page 3 showed it (null where not
     * known). A profile is deleted only when its card has that name; when the number holds
     * someone else, the card with that name is deleted instead; when none has it, nothing.
     */
    void start(List<Integer> numbers, List<String> expect) {
        if (running) return;
        names.clear();
        for (int i = 0; i < numbers.size(); i++) names.add(expect != null && i < expect.size() ? expect.get(i) : null);
        running = true;
        gen++;
        start = SystemClock.uptimeMillis();
        log.setLength(0);
        todo.clear();
        todo.addAll(numbers);
        deleted.clear();
        failed.clear();
        pkg = "";
        for (android.view.accessibility.AccessibilityWindowInfo w : Page.windows(service)) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root != null && root.getPackageName() != null) {
                pkg = root.getPackageName().toString();
                break;
            }
        }
        runBase = new Page.Before(service);
        log("deleting " + numbers + " (each as numbered on screen when its turn comes)");
        nextProfile();
    }

    void stop(String why) {
        if (!running) return;
        running = false;
        gen++;
        log("END: " + why);
        StringBuilder sum = new StringBuilder(why).append('\n');
        if (!deleted.isEmpty()) sum.append("🗑 Deleted: ").append(join(deleted)).append('\n');
        if (!failed.isEmpty()) sum.append("✗ Not deleted: ").append(join(failed)).append('\n');
        sum.append("⏱ ").append(SystemClock.uptimeMillis() - start).append(" ms");
        listener.done(sum.toString(), "A11y Inspector - Delete (testing)\n=========================\n" + sum
                + "\n\nSTEPS\n" + log);
    }

    private static String join(List<Integer> l) {
        StringBuilder sb = new StringBuilder();
        for (int i : l) sb.append(sb.length() == 0 ? "" : ", ").append(i);
        return sb.toString();
    }

    private void log(String s) {
        log.append(String.format(java.util.Locale.ROOT, "%6d ms  ", SystemClock.uptimeMillis() - start)).append(s).append('\n');
    }

    // ---- one profile after another ------------------------------------------------------

    private int original, cardsBefore;
    private String name = "";

    private void nextProfile() {
        if (!running) return;
        if (todo.isEmpty()) {
            stop(failed.isEmpty() ? "✅ Done" : "Done - some not deleted");
            return;
        }
        // Before each: no pop-up up, and the list settled (the same profiles on two looks in
        // a row) - after a delete the page rebuilds its list and shows its last pop-up.
        int g = gen;
        long waitStart = SystemClock.uptimeMillis();
        int[] last = {-1};
        when(() -> {
            if (Page.popup(service, runBase) != null || Page.coverCame(service, runBase)) {
                last[0] = -1;
                return false;
            }
            int n = SevakDeleter.cards(service).size();
            boolean settled = n > 0 && n == last[0];
            last[0] = n;
            return settled;
        }, SystemClock.uptimeMillis() + 3000, ready -> {
            if (g != gen) return;
            long waited = SystemClock.uptimeMillis() - waitStart;
            if (waited > 150) log("  page ready after " + waited + " ms" + (ready ? "" : " (not settled - going on)"));
            deleteNext();
        });
    }

    private void deleteNext() {
        original = todo.remove(0);
        expected = names.isEmpty() ? null : names.remove(0);
        int now = original; // as numbered on screen now - the page is read again for each
        List<SevakDeleter.Card> cards = SevakDeleter.cards(service);
        SevakDeleter.Card card = null;
        for (SevakDeleter.Card c : cards) if (c.number == now) card = c;
        if (expected != null && (card == null || !sameName(card.name, expected))) {
            // Someone else at this number: the one card with the expected name, wherever it is.
            List<SevakDeleter.Card> named = new ArrayList<>();
            for (SevakDeleter.Card c : cards) if (sameName(c.name, expected)) named.add(c);
            if (named.size() != 1) {
                log("profile " + original + ": " + (named.isEmpty() ? "no" : named.size() + " profiles named")
                        + " \"" + expected + "\" on the page"
                        + (card == null ? "" : " (" + now + " is " + card.name + ")") + " - not deleted ✗");
                notDeleted();
                return;
            }
            SevakDeleter.Card byName = named.get(0);
            log("profile " + original + ": \"" + expected + "\" is number " + byName.number + " now - deleting that one");
            card = byName;
        }
        if (expected == null && card != null) {
            log("profile " + original + ": by number only (no name from page 3) - " + card.name);
        }
        if (card == null || card.bin == null) {
            log("profile " + original + ": " + (card == null ? "not on the page (" + cards.size() + " profiles)"
                    : "has no delete (" + card.name + ")") + " ✗");
            notDeleted();
            return;
        }
        Rect r = SevakDeleter.where(card.bin);
        if (r != null) {
            tapBin(card, cards.size(), r);
            return;
        }
        // Off screen: brought on screen, and tapped the moment it shows (no fixed wait).
        card.bin.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        int g = gen;
        int count = cards.size();
        // Found again as the same card - its number and its name - never whoever has the number asked.
        int targetNo = card.number;
        String targetName = card.name;
        when(() -> {
            SevakDeleter.Card c = sameCard(targetNo, targetName);
            return c != null && c.bin != null && SevakDeleter.where(c.bin) != null;
        }, SystemClock.uptimeMillis() + 800, shown -> {
            if (g != gen) return;
            SevakDeleter.Card c = sameCard(targetNo, targetName);
            Rect r2 = c == null || c.bin == null ? null : SevakDeleter.where(c.bin);
            if (r2 == null) {
                log("profile " + original + ": its bin didn't come on screen ✗");
                notDeleted();
            } else {
                log("profile " + original + ": brought on screen");
                tapBin(c, count, r2);
            }
        });
    }

    private Page.Before before;
    /** The page at the start, with no pop-up up: a pop-up is what comes on top of it. */
    private Page.Before runBase;

    /**
     * This profile wasn't deleted: it still holds its place, so each later number at or after it
     * (counted as if it had gone) is one further down now.
     */
    private void notDeleted() {
        failed.add(original);
        shiftAfterFailure(todo, original);
        nextProfile();
    }

    /** The numbers still to go, once profile {@code failedAt} stayed: those at or after it move one down. */
    static void shiftAfterFailure(List<Integer> todo, int failedAt) {
        for (int i = 0; i < todo.size(); i++) if (todo.get(i) >= failedAt) todo.set(i, todo.get(i) + 1);
    }

    static final java.util.Set<String> NOT_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "female", "male", "yrs", "years", "sevak", "sevaks", "confirm", "select", "add", "edit", "dob"));

    /**
     * A person's name: letters only (no digits - never "22 yrs", a date or a masked number like
     * XXXXXX2596), at least two words of two letters, and not a label word (Female, DOB ...).
     */
    static boolean looksLikeName(String l) {
        String t = l.trim();
        if (t.isEmpty() || t.length() > 60 || !t.matches("[A-Za-z .'-]+")) return false;
        String[] words = t.split("[\\s.'-]+");
        int real = 0;
        for (String w : words) if (w.length() >= 2) real++;
        if (real < 2) return false;
        if (t.replaceAll("[^A-Za-z]", "").matches("X+")) return false; // a masked number
        return !NOT_NAMES.contains(words[0].toLowerCase(java.util.Locale.ROOT));
    }

    /** The card at {@code number} if it still has {@code name}, else null. */
    private SevakDeleter.Card sameCard(int number, String name) {
        SevakDeleter.Card c = SevakDeleter.card(service, number);
        return c != null && c.name.equals(name) ? c : null;
    }

    /**
     * The same person: every word of the shorter name is a whole word of the longer one, and the
     * shorter has at least two words ("Ravi" never matches "Ravindra Kumar", a bare surname
     * never matches the whole family). Letters only, any case.
     */
    static boolean sameName(String a, String b) {
        List<String> x = words(a), y = words(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        List<String> shorter = x.size() <= y.size() ? x : y, longer = shorter == x ? y : x;
        if (shorter.size() < 2 && !x.equals(y)) return false;
        return longer.containsAll(shorter);
    }

    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.toLowerCase(java.util.Locale.ROOT).split("[^a-z]+")) if (w.length() >= 2) out.add(w);
        return out;
    }

    /** Profiles with this profile's name before the delete (it counts as gone once there is one fewer). */
    private int sameNameBefore;

    private void tapBin(SevakDeleter.Card card, int count, Rect r) {
        name = card.name;
        lateCloses = 0;
        sameNameBefore = 0;
        for (SevakDeleter.Card c : SevakDeleter.cards(service)) if (c.name.equals(name)) sameNameBefore++;
        if (sameNameBefore == 0) sameNameBefore = 1;
        cardsBefore = count;
        stepStart = SystemClock.uptimeMillis();
        before = new Page.Before(service);
        log("profile " + original + " " + name + ": bin tapped at " + r.centerX() + "," + r.centerY());
        tap(r);
        popup(1, false, SystemClock.uptimeMillis() + 2500);
    }

    // ---- its 2 pop-ups ------------------------------------------------------------------

    /**
     * Pop-up {@code stage} (1: asks, 2: says done): closed the moment it shows - its button
     * when the page reports it, else the place kept from last time, else a screenshot.
     * {@code up}: it is up already (pop-up 1's cover never went: pop-up 2 came on it).
     */
    private void popup(int stage, boolean up, long until) {
        int g = gen;
        when(() -> up || Page.popup(service, before) != null || Page.coverCame(service, before), until, came -> {
            if (g != gen) return;
            if (!came) {
                if (stage == 1) log("  no pop-up came");
                else log("  no 2nd pop-up");
                checkGone();
                return;
            }
            log("  pop-up " + stage + " after " + (SystemClock.uptimeMillis() - stepStart) + " ms");
            Page.Popup p = up ? null : Page.popup(service, before);
            if (p != null && p.button != null) {
                Rect b = Page.bounds(p.button);
                log("  pressing \"" + Page.label(p.button) + "\"");
                if (!p.button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(b);
                afterPress(stage, null, false);
                return;
            }
            Rect spot = spot(stage);
            if (spot != null) {
                log("  tapping its kept place " + spot.centerX() + "," + spot.centerY());
                tap(spot);
                afterPress(stage, spot, true);
                return;
            }
            shot(stage, until);
        });
    }

    /** A screenshot finds the purple button; tapped, and its place kept once the pop-up goes. */
    private void shot(int stage, long until) {
        int g = gen;
        log("  looking for its purple button (screenshot)");
        finder.find(r -> {
            if (g != gen) return;
            if (r == null) {
                // (A failed screenshot is told below; it is tried again here too.)
                if (SystemClock.uptimeMillis() < until) {
                    handler.postDelayed(() -> shot(stage, until), Math.max(50, finder.waitMs()));
                } else {
                    log("  no purple button found ✗");
                    checkGone();
                }
                return;
            }
            log("  purple button at " + r.centerX() + "," + r.centerY() + " - tapping it");
            tap(r);
            afterPress(stage, r, false);
        }, why -> {
            if (g == gen) log("  screenshot: " + why);
        }, before);
    }

    /** After a press: the pop-up gone (place kept) -> next; still up -> the next pop-up is on it. */
    /** {@code kept}: the tap was at the place kept from last time (not found now). */
    private void afterPress(int stage, Rect tapped, boolean kept) {
        int g = gen;
        long pressed = SystemClock.uptimeMillis();
        when(() -> Page.popup(service, before) == null && !Page.coverCame(service, before),
                pressed + 600, gone -> {
            if (g != gen) return;
            if (gone) {
                log("  pop-up " + stage + " closed in " + (SystemClock.uptimeMillis() - pressed) + " ms");
                if (tapped != null) keep(stage, tapped);
                if (stage == 1) popup(2, false, SystemClock.uptimeMillis() + 1500);
                else checkGone();
                return;
            }
            if (kept) {
                // The kept place didn't close it: forgotten, and the button found on a screenshot.
                forget(stage);
                log("  its kept place didn't close it - looking for the button (screenshot)");
                shot(stage, SystemClock.uptimeMillis() + 1500);
                return;
            }
            if (stage == 1) {
                // Still covered after a found button: pop-up 2 came straight on top of pop-up 1.
                log("  still covered - taking it as pop-up 2");
                popup(2, true, SystemClock.uptimeMillis() + 1500);
            } else {
                if (tapped != null) forget(stage);
                log("  pop-up 2 still up - looking again");
                shot(2, SystemClock.uptimeMillis() + 1500);
            }
        });
    }

    /** Pop-ups closed late for this profile (one that came after its watch ended). */
    private int lateCloses;

    /**
     * The profile gone: fewer profiles, the same on two looks in a row, no pop-up up. A pop-up
     * that comes meanwhile (late) is closed first - up to twice - then the profile is looked at.
     */
    private void checkGone() {
        int g = gen;
        int[] last = {-1};
        int[] covered = {0};
        boolean[] late = {false};
        when(() -> {
            if (Page.popup(service, before) != null || Page.coverCame(service, before)) {
                // A pop-up only when it is there on two looks in a row (not the page redrawing).
                last[0] = -1;
                late[0] = ++covered[0] >= 2;
                return late[0];
            }
            covered[0] = 0;
            late[0] = false;
            List<SevakDeleter.Card> now = SevakDeleter.cards(service);
            int n = now.size();
            // Gone: fewer profiles, and fewer with its name (two people may share a name).
            int same = 0;
            for (SevakDeleter.Card c : now) if (c.name.equals(name)) same++;
            boolean ok = n > 0 && n < cardsBefore && same < sameNameBefore && n == last[0];
            last[0] = n;
            return ok;
        }, SystemClock.uptimeMillis() + 3000, gone -> {
            if (g != gen) return;
            if (late[0] && lateCloses < 2) {
                lateCloses++;
                // Found on a screenshot - never a blind tap at a kept place.
                log("  a pop-up came late - finding its button (screenshot)");
                shot(2, SystemClock.uptimeMillis() + 1500);
                return;
            }
            if (late[0]) gone = false;
            long ms = SystemClock.uptimeMillis() - stepStart;
            if (gone) {
                runBase = new Page.Before(service); // the page as it is now, rebuilt, no pop-up
                deleted.add(original);
                log("profile " + original + " " + name + ": deleted ✓ (" + ms + " ms)");
            } else {
                log("profile " + original + " " + name + ": still there? ✗ (" + ms + " ms, "
                        + SevakDeleter.cards(service).size() + " profiles, were " + cardsBefore + ")");
                notDeleted();
                return;
            }
            nextProfile();
        });
    }

    // ---- the places the 2 pop-ups' buttons were (kept per app) ---------------------------

    private SharedPreferences prefs() {
        return service.getSharedPreferences("popup", Context.MODE_PRIVATE);
    }

    private Rect spot(int stage) {
        String s = prefs().getString("delete_spot" + stage + "_" + pkg, null);
        return s == null ? null : Rect.unflattenFromString(s);
    }

    private void keep(int stage, Rect r) {
        prefs().edit().putString("delete_spot" + stage + "_" + pkg, r.flattenToString()).apply();
    }

    private void forget(int stage) {
        prefs().edit().remove("delete_spot" + stage + "_" + pkg).apply();
    }

    // ---- waiting: on every page change, and every 40 ms ----------------------------------

    private Runnable pending;

    /** The page changed: a waiting look runs now, not at its next timer. */
    void onPageEvent(AccessibilityEvent e) {
        if (!running || pending == null) return;
        CharSequence p = e.getPackageName();
        if (p != null && service.getPackageName().contentEquals(p)) return;
        Runnable r = pending;
        handler.removeCallbacks(r);
        handler.postDelayed(r, 10);
    }

    private void when(java.util.function.BooleanSupplier ok, long until, java.util.function.Consumer<Boolean> then) {
        int g = gen;
        Runnable[] look = new Runnable[1];
        look[0] = () -> {
            pending = null;
            if (g != gen) return;
            boolean yes;
            try {
                yes = ok.getAsBoolean();
            } catch (RuntimeException e) {
                yes = false;
            }
            if (yes || SystemClock.uptimeMillis() >= until) {
                then.accept(yes);
                return;
            }
            pending = look[0];
            handler.postDelayed(look[0], 40);
        };
        pending = look[0];
        handler.postDelayed(look[0], 30);
    }

    private void tap(Rect r) {
        if (r == null || r.isEmpty()) return;
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50)).build(), null, null);
    }
}
