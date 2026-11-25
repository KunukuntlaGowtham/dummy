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

    /** One profile's result, the moment it is known: who (page 3's name, else the card's), the number, deleted or not, why. */
    interface ProfileListener {
        void result(String who, int number, boolean ok, String why);
    }

    private ProfileListener profileListener;

    void setProfileListener(ProfileListener l) {
        profileListener = l;
    }

    private void report(boolean ok, String why) {
        if (profileListener == null) return;
        String who = expected != null && !expected.isEmpty() ? expected : name;
        profileListener.result(who, original, ok, why);
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
        // ⚙ Settings can turn the name check off (for a while): then each profile is deleted by its number alone.
        boolean checkNames = service.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("delete_name_check", true);
        if (!checkNames) expect = null;
        names.clear();
        for (int i = 0; i < numbers.size(); i++) names.add(expect != null && i < expect.size() ? expect.get(i) : null);
        running = true;
        gen++;
        start = SystemClock.uptimeMillis();
        log.setLength(0);
        todo.clear();
        todo.addAll(numbers);
        listWaits = 0;
        deleted.clear();
        failed.clear();
        deletedWho.clear();
        failedWho.clear();
        pkg = "";
        for (android.view.accessibility.AccessibilityWindowInfo w : Page.windows(service)) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root != null && root.getPackageName() != null) {
                pkg = root.getPackageName().toString();
                break;
            }
        }
        runBase = new Page.Before(service);
        startCounts.clear();
        List<SevakDeleter.Card> atStart = SevakDeleter.cards(service);
        for (SevakDeleter.Card c : atStart) startCounts.merge(c.name, 1, Integer::sum);
        startCards = atStart.size();
        verifyText = "";
        log(cardsLine().trim());
        log("deleting " + numbers + " (each as numbered on screen when its turn comes)"
                + (checkNames ? "" : " - NAME CHECK OFF: by number only"));
        nextProfile();
    }

    void stop(String why) {
        if (!running) return;
        running = false;
        gen++;
        log("END: " + why);
        StringBuilder sum = new StringBuilder(why).append('\n');
        if (!deleted.isEmpty()) sum.append("🗑 Deleted: ").append(join(deleted)).append(named(deletedWho)).append('\n');
        if (!failed.isEmpty()) sum.append("✗ Not deleted: ").append(join(failed)).append(named(failedWho)).append('\n');
        if (!verifyText.isEmpty()) sum.append(verifyText).append('\n');
        sum.append("⏱ ").append(SystemClock.uptimeMillis() - start).append(" ms");
        listener.done(sum.toString(), "A11y Inspector - Delete (testing)\n=========================\n" + sum
                + "\n\nSTEPS\n" + log);
    }

    private final List<String> deletedWho = new ArrayList<>(), failedWho = new ArrayList<>();
    /** How many cards had each name when the run started (for the final check). */
    private final java.util.Map<String, Integer> startCounts = new java.util.HashMap<>();
    private String verifyText = "";

    /**
     * Before it ends: the page read once more - each person said to be deleted must have one card
     * fewer with that name than at the start; one who doesn't is marked "not deleted" after all.
     */
    private void verifyAtEnd() {
        List<SevakDeleter.Card> now = SevakDeleter.cards(service);
        if (now.isEmpty() || deletedWho.isEmpty()) {
            verifyText = deletedWho.isEmpty() ? "" : "Not checked at the end (the sevaks list isn't showing)";
            return;
        }
        java.util.Map<String, Integer> cur = new java.util.HashMap<>();
        for (SevakDeleter.Card c : now) cur.merge(c.name, 1, Integer::sum);
        List<String> gone = new ArrayList<>(), still = new ArrayList<>();
        for (String who : deletedWho) {
            if (who == null || who.isEmpty()) continue;
            int before0 = 0, after = 0;
            for (java.util.Map.Entry<String, Integer> e : startCounts.entrySet()) if (sameName(e.getKey(), who)) before0 += e.getValue();
            for (java.util.Map.Entry<String, Integer> e : cur.entrySet()) if (sameName(e.getKey(), who)) after += e.getValue();
            if (before0 > 0 && after >= before0) still.add(who);
            else gone.add(who);
        }
        StringBuilder sb = new StringBuilder("Checked on the sevaks list at the end: ");
        sb.append(gone.isEmpty() ? "" : "gone ✓ " + gone);
        if (!still.isEmpty()) sb.append(gone.isEmpty() ? "" : "; ").append("⚠ STILL THERE ").append(still);
        verifyText = sb.toString();
        log(verifyText);
        for (String who : still) if (profileListener != null) profileListener.result(who, -1, false, "still on the list when checked at the end");
    }

    private static String named(List<String> who) {
        StringBuilder sb = new StringBuilder();
        for (String w : who) if (w != null && !w.isEmpty()) sb.append(sb.length() == 0 ? " (" : ", ").append(w);
        return sb.length() == 0 ? "" : sb.append(')').toString();
    }

    private static String join(List<Integer> l) {
        StringBuilder sb = new StringBuilder();
        for (int i : l) sb.append(sb.length() == 0 ? "" : ", ").append(i);
        return sb.toString();
    }

    private String lastLine = "";

    private void log(String s) {
        lastLine = s.trim();
        log.append(String.format(java.util.Locale.ROOT, "%6d ms  ", SystemClock.uptimeMillis() - start)).append(s).append('\n');
    }

    // ---- one profile after another ------------------------------------------------------

    private int original, cardsBefore;
    /** Times the list was read again for this profile because it wasn't whole yet. */
    private int listWaits;
    private String name = "";

    /** Profiles on the list when the run started: after each delete one fewer is expected. */
    private int startCards;

    /** How many profiles the list should have now: the start, less the ones deleted so far. */
    private int expectedCards() {
        return Math.max(1, startCards - deleted.size());
    }

    /**
     * The list is whole again: as many profiles as expected (the start less those deleted), at
     * least one with a bin, the same on two looks in a row. After a delete the page rebuilds its
     * list: half drawn it can show one stray "profile" (a text like "23 Available") - never
     * taken as the list.
     */
    private boolean listWhole(List<SevakDeleter.Card> cards) {
        if (cards.size() < expectedCards()) return false;
        for (SevakDeleter.Card c : cards) if (c.bin != null) return true;
        return false;
    }

    private void nextProfile() {
        if (!running) return;
        // Before each (and before the check at the end): no pop-up up, and the list whole and
        // settled - after a delete the page rebuilds its list and shows its last pop-up.
        int g = gen;
        long waitStart = SystemClock.uptimeMillis();
        int[] last = {-1};
        when(() -> {
            if (Page.popup(service, runBase) != null || Page.coverCame(service, runBase)) {
                last[0] = -1;
                return false;
            }
            List<SevakDeleter.Card> cards = SevakDeleter.cards(service);
            int n = cards.size();
            boolean settled = n > 0 && n == last[0] && listWhole(cards);
            last[0] = n;
            return settled;
        }, SystemClock.uptimeMillis() + 6000, ready -> {
            if (g != gen) return;
            long waited = SystemClock.uptimeMillis() - waitStart;
            if (waited > 150 || !ready) {
                log("  page ready after " + waited + " ms" + (ready ? "" : " (list not whole: "
                        + SevakDeleter.cards(service).size() + " profiles, " + expectedCards() + " expected - going on)"));
            }
            if (todo.isEmpty()) {
                verifyAtEnd();
                stop(failed.isEmpty() ? "✅ Done" : "Done - some not deleted");
                return;
            }
            deleteNext();
        });
    }

    private void deleteNext() {
        original = todo.remove(0);
        expected = names.isEmpty() ? null : names.remove(0);
        name = ""; // this profile's own name, once its card is found (never the last one's)
        tapRetries = 0;
        int now = original; // as numbered on screen now - the page is read again for each
        List<SevakDeleter.Card> cards = SevakDeleter.cards(service);
        SevakDeleter.Card card = null;
        for (SevakDeleter.Card c : cards) if (c.number == now) card = c;
        boolean notFound = card == null || expected != null && !sameName(card.name, expected);
        if (notFound && !listWhole(cards) && listWaits++ < 2) {
            // The list isn't whole yet (still being rebuilt): read again in a second, not a ✗.
            log("profile " + original + ": the list isn't whole yet (" + cards.size() + " profiles, "
                    + expectedCards() + " expected) - reading it again in 1 s");
            todo.add(0, original);
            names.add(0, expected);
            int g = gen;
            handler.postDelayed(() -> {
                if (g == gen && running) deleteNext();
            }, 1000);
            return;
        }
        listWaits = 0;
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
        // Off screen: clicked through accessibility first - no scrolling at all. If no pop-up comes
        // (the click did nothing), the retry below brings the bin on screen and taps it.
        if (clickThrough(card.bin)) {
            tapBin(card, cards.size(), null);
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
        failedWho.add(expected != null && !expected.isEmpty() ? expected : name);
        report(false, lastLine);
        log(cardsLine());
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

    /** The bin tapped again when no pop-up came (a tap that missed): at most twice per profile. */
    private int tapRetries, tappedNumber;

    private void tapBin(SevakDeleter.Card card, int count, Rect r) {
        name = card.name;
        tappedNumber = card.number;
        lateCloses = 0;
        sameNameBefore = 0;
        for (SevakDeleter.Card c : SevakDeleter.cards(service)) if (c.name.equals(name)) sameNameBefore++;
        if (sameNameBefore == 0) sameNameBefore = 1;
        cardsBefore = count;
        stepStart = SystemClock.uptimeMillis();
        before = new Page.Before(service);
        if (r != null) {
            log("profile " + original + " " + name + ": bin tapped at " + r.centerX() + "," + r.centerY());
            tap(r);
        } else {
            log("profile " + original + " " + name + ": bin clicked through accessibility (off screen, no scrolling)");
        }
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
                // A tap that missed (the page moved, the bin wasn't ready): the same card's bin again -
                // only while nothing has gone (same number of cards, the same card still there).
                if (stage == 1 && !up && tapRetries < 2 && retryTap()) return;
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
    /** A click on the bin (or the box around it) through accessibility, with the bin anywhere on the page. */
    private boolean clickThrough(AccessibilityNodeInfo bin) {
        AccessibilityNodeInfo n = bin;
        for (int up = 0; up < 3 && n != null; up++, n = n.getParent()) {
            try {
                if ((up == 0 || n.isClickable()) && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    private boolean retryTap() {
        List<SevakDeleter.Card> now = SevakDeleter.cards(service);
        if (now.size() != cardsBefore) return false;
        SevakDeleter.Card c = null;
        for (SevakDeleter.Card x : now) if (x.number == tappedNumber && x.name.equals(name)) c = x;
        if (c == null || c.bin == null) return false;
        tapRetries++;
        log("  no pop-up - tapping " + name + "'s bin again (try " + (tapRetries + 1) + ")");
        int count = now.size(), num = c.number;
        String nm = c.name;
        Rect r = SevakDeleter.where(c.bin);
        if (r != null) {
            tapBin(c, count, r);
            return true;
        }
        c.bin.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        int g = gen;
        handler.postDelayed(() -> {
            if (g != gen) return;
            SevakDeleter.Card c2 = sameCard(num, nm);
            Rect r2 = c2 == null || c2.bin == null ? null : SevakDeleter.where(c2.bin);
            if (r2 == null) checkGone();
            else tapBin(c2, count, r2);
        }, 350);
        return true;
    }

    /** The profiles on the page now, for the report: "1 Name, 2 Name (no bin) ...". */
    private String cardsLine() {
        StringBuilder sb = new StringBuilder("  profiles on the page: ");
        int n = 0;
        for (SevakDeleter.Card c : SevakDeleter.cards(service)) {
            if (n++ >= 40) {
                sb.append(" ...");
                break;
            }
            sb.append(n == 1 ? "" : ", ").append(c.number).append(' ').append(c.name).append(c.bin == null ? " (no bin)" : "");
        }
        return n == 0 ? "  profiles on the page: none read" : sb.toString();
    }

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
                deletedWho.add(expected != null && !expected.isEmpty() ? expected : name);
                log("profile " + original + " " + name + ": deleted ✓ (" + ms + " ms)");
                report(true, "");
            } else {
                // Not seen gone yet: the page may still be rebuilding its list - looked at once more
                // before it counts as not deleted (a wrong ✗ moves every later number by one).
                log("profile " + original + " " + name + ": not seen gone yet (" + ms + " ms, "
                        + SevakDeleter.cards(service).size() + " profiles, were " + cardsBefore + ") - looking once more");
                handler.postDelayed(() -> {
                    if (g != gen) return;
                    List<SevakDeleter.Card> now = SevakDeleter.cards(service);
                    int same = 0;
                    for (SevakDeleter.Card c : now) if (c.name.equals(name)) same++;
                    boolean goneNow = !now.isEmpty() && now.size() < cardsBefore && same < sameNameBefore
                            && Page.popup(service, before) == null && !Page.coverCame(service, before);
                    if (goneNow) {
                        runBase = new Page.Before(service);
                        deleted.add(original);
                        deletedWho.add(expected != null && !expected.isEmpty() ? expected : name);
                        log("profile " + original + " " + name + ": deleted ✓ (seen on the second look, "
                                + (SystemClock.uptimeMillis() - stepStart) + " ms)");
                        report(true, "");
                        nextProfile();
                    } else {
                        log("profile " + original + " " + name + ": still there ✗ (" + now.size() + " profiles, were " + cardsBefore + ")");
                        notDeleted();
                    }
                }, 1200);
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
