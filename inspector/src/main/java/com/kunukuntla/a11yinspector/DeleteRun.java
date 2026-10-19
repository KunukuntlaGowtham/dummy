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
import java.util.TreeSet;

/**
 * 🗑 Delete (testing): deletes sevak profiles by the numbers they have on screen now
 * ("2, 5"). Each delete moves the profiles below it up one, so a number is lowered by one
 * for every profile above it already deleted (5 is deleted as 4 once 2 has gone) - the
 * same counting as the "not added" box on the ticking page. Each profile: its bin tapped,
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

    /** "2, 5" / "2 5" / "5,2" -> 2, 5 (each once, smallest first); empty when none. */
    static List<Integer> parse(String s) {
        TreeSet<Integer> out = new TreeSet<>();
        for (String p : s.split("[^0-9]+")) {
            if (p.isEmpty()) continue;
            try {
                int n = Integer.parseInt(p);
                if (n > 0 && n < 100) out.add(n);
            } catch (NumberFormatException ignored) {
            }
        }
        return new ArrayList<>(out);
    }

    /** The number profile {@code original} has now, once the ones in {@code deleted} have gone. */
    static int nowNumber(int original, List<Integer> deleted) {
        int n = original;
        for (int d : deleted) if (d < original) n--;
        return n;
    }

    void start(List<Integer> numbers) {
        if (running) return;
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
        log("deleting " + numbers + " (numbers as on screen at the start)");
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
        original = todo.remove(0);
        int now = nowNumber(original, deleted);
        List<SevakDeleter.Card> cards = SevakDeleter.cards(service);
        SevakDeleter.Card card = null;
        for (SevakDeleter.Card c : cards) if (c.number == now) card = c;
        String as = now == original ? "" : " (now " + now + ")";
        if (card == null || card.bin == null) {
            log("profile " + original + as + ": " + (card == null ? "not on the screen" : "has no delete (" + card.name + ")") + " ✗");
            failed.add(original);
            nextProfile();
            return;
        }
        Rect r = SevakDeleter.where(card.bin);
        if (r == null) {
            card.bin.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
            SevakDeleter.Card c0 = card;
            int g = gen;
            handler.postDelayed(() -> {
                if (g != gen) return;
                Rect r2 = SevakDeleter.where(c0.bin);
                if (r2 == null) {
                    log("profile " + original + as + ": its bin isn't on screen ✗");
                    failed.add(original);
                    nextProfile();
                } else {
                    tapBin(c0, cards.size(), r2, as);
                }
            }, 300);
            return;
        }
        tapBin(card, cards.size(), r, as);
    }

    private Page.Before before;

    private void tapBin(SevakDeleter.Card card, int count, Rect r, String as) {
        name = card.name;
        cardsBefore = count;
        stepStart = SystemClock.uptimeMillis();
        before = new Page.Before(service);
        log("profile " + original + as + " " + name + ": bin tapped at " + r.centerX() + "," + r.centerY());
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
                afterPress(stage, null);
                return;
            }
            Rect spot = spot(stage);
            if (spot != null) {
                log("  tapping its kept place " + spot.centerX() + "," + spot.centerY());
                tap(spot);
                afterPress(stage, spot);
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
            afterPress(stage, r);
        }, why -> {
            if (g == gen) log("  screenshot: " + why);
        }, before);
    }

    /** After a press: the pop-up gone (place kept) -> next; still up -> the next pop-up is on it. */
    private void afterPress(int stage, Rect tapped) {
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
            if (stage == 1) {
                // Still covered: pop-up 2 came straight on top of pop-up 1 (or the tap missed).
                log("  still covered - taking it as pop-up 2");
                popup(2, true, SystemClock.uptimeMillis() + 1500);
            } else {
                if (tapped != null) forget(stage);
                log("  pop-up 2 still up - looking again");
                shot(2, SystemClock.uptimeMillis() + 1500);
            }
        });
    }

    /** The profile gone from the list? Then the next one. */
    private void checkGone() {
        int g = gen;
        when(() -> SevakDeleter.cards(service).size() < cardsBefore, SystemClock.uptimeMillis() + 1500, gone -> {
            if (g != gen) return;
            long ms = SystemClock.uptimeMillis() - stepStart;
            if (gone) {
                deleted.add(original);
                log("profile " + original + " " + name + ": deleted ✓ (" + ms + " ms)");
            } else {
                failed.add(original);
                log("profile " + original + " " + name + ": still there ✗ (" + ms + " ms)");
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
