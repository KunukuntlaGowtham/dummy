package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The pages after the ticking, by themselves (on / off with the 🤖 Auto round button, "auto_flow"):
 * <ul>
 *   <li>Page 3 when a Tick run ends with a box still empty: Back to page 2, then page 1.</li>
 *   <li>Page 3, no empty checkbox left, Continue on: Continue is pressed. When the page
 *       stays on page 3 after it (its error), or Continue stays off with every box ticked:
 *       Back to page 2, then Back again to page 1 (the page before page 2, whatever it is).</li>
 *   <li>Page 4, a Confirm button: pressed at once - once per visit to the page.</li>
 * </ul>
 * Only in the app where page 2 or 3 was seen (never a Confirm in another app), and never
 * while Tick, Clear, Book or Scan runs.
 */
final class AutoFlow {

    interface Host {
        /** The page reads again now (after a step, so the next one is taken). */
        void recheck();

        /** This reading is page 1 (the page before page 2). */
        void page1(PageKind.Facts f);

        void say(String message);

        /** Tick missed rows: may Auto go back to page 1 for them (once per booking)? */
        boolean mayGoBack();

        /** Back on page 1 because Tick missed rows (the retry starts here). */
        void missedAtPage1();

        /** The Back presses for the retry / test trip stopped before page 1. */
        void backFailed();

        /** Tick ended: test mode's trip (back to page 1 and forward) instead of going on? */
        boolean testTripAfterTick();
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("auto_flow", false); // off until turned on: Go never books through by surprise
    }

    private final AccessibilityService service;
    private final Handler handler;
    private final Host host;
    /** A step under way: Continue's result, or the Back presses. */
    private boolean busy;
    /** These Back presses are for rows Tick missed (page 1 then starts the retry). */
    private boolean missed;
    /** Confirm pressed on this visit to page 4 (pressed again only after leaving it). */
    private boolean confirmDone;
    /** After Continue on page 3: page 4's Confirm is looked for on every page change. */
    private long watchUntil;
    private String watchPkg = "";
    private boolean confirmQueued;
    /** Page 3 with every box ticked and Continue off: since when (0: not so). */
    private long offSince;

    AutoFlow(AccessibilityService service, Handler handler, Host host) {
        this.service = service;
        this.handler = handler;
        this.host = host;
    }

    boolean busy() {
        return busy;
    }

    /** A Tick run has just ended: the next reading of page 3 decides (Continue, or back). */
    private boolean tickEnded;

    void tickEnded() {
        tickEnded = true;
    }

    /** Forget a Tick that ended earlier (Auto switched on / off: never acted on later). */
    void clearTickEnded() {
        tickEnded = false;
    }

    /** The page as read (only while nothing else runs); {@code bookingPkg}: the app of pages 2-3. */
    void onPage(PageKind.Kind kind, PageKind.Facts f, String bookingPkg, boolean confirmInOrder) {
        if (busy || !enabled(service)) return;
        if (kind != PageKind.Kind.CONFIRM) confirmDone = false;
        if (kind != PageKind.Kind.TICKING) offSince = 0;
        if (bookingPkg == null || !bookingPkg.equals(f.pkg)) return;
        // A test trip's page 4: Confirm is left for you (said once), until a new booking starts.
        if (holdConfirm && (kind == PageKind.Kind.CALENDAR || kind == PageKind.Kind.SEVAK_LIST)) holdConfirm = false;
        if (kind == PageKind.Kind.CONFIRM) {
            if (holdConfirm) {
                if (!confirmDone) {
                    confirmDone = true;
                    host.say("🧪 Test: page 4 reached - Confirm is left for you");
                }
                return;
            }
            // Only page 4 reached from page 3: a Confirm met anywhere else is left alone.
            if (!confirmDone && confirmInOrder) pressConfirm();
            return;
        }
        boolean afterTick = tickEnded;
        tickEnded = false;
        if (kind != PageKind.Kind.TICKING || f.boxes == 0) return;
        if (afterTick && host.testTripAfterTick()) {
            missed = true;
            backToPage1("Test mode");
            return;
        }
        if (f.empty() > 0) {
            offSince = 0;
            // Tick has ended and a box is still empty (a row the page refused): start again from page 1.
            if (afterTick) {
                String why = f.empty() + (f.empty() == 1 ? " checkbox" : " checkboxes") + " not ticked after Tick";
                if (!host.mayGoBack()) {
                    host.say("📍 Page 3: " + why + " again - left here (the retry was used)");
                    return;
                }
                missed = true;
                backToPage1(why);
            }
            return;
        }
        if (f.contOn) {
            offSince = 0;
            pressContinue(f.pkg);
        } else if (f.cont) {
            // Every box ticked and Continue off: give the page 1.5 s, then go back.
            long now = SystemClock.uptimeMillis();
            if (offSince == 0) {
                offSince = now;
                handler.postDelayed(host::recheck, 1600);
            } else if (now - offSince >= 1500) {
                offSince = 0;
                backToPage1("Continue stayed off with every box ticked");
            }
        }
    }

    /** A page change in {@code pkg}: right after page 3's Continue, Confirm is pressed the moment it comes. */
    void onEvent(String pkg) {
        if (holdConfirm) return;
        if (confirmQueued || confirmDone || SystemClock.uptimeMillis() > watchUntil || !pkg.equals(watchPkg)) return;
        confirmQueued = true;
        handler.post(() -> {
            confirmQueued = false;
            if (!confirmDone && SystemClock.uptimeMillis() <= watchUntil && enabled(service)) pressConfirm();
        });
    }

    // ---- page 4: Confirm ----------------------------------------------------------------

    /** A test trip runs: page 4's Confirm is never pressed (until a new booking starts). */
    private boolean holdConfirm;

    boolean confirmHeld() {
        return holdConfirm;
    }

    void holdConfirm(boolean hold) {
        holdConfirm = hold;
        if (hold) watchUntil = 0;
    }

    /** How long after page 3's Continue page 4's Confirm is waited for. */
    private static final long WATCH_MS = 20000;

    private void pressConfirm() {
        AccessibilityNodeInfo button = null;
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            if (!n.isVisibleToUser() || !PageKind.isConfirm(Page.label(n))) continue;
            if (PageKind.inDialog(n)) continue; // a pop-up's Confirm is never page 4's
            AccessibilityNodeInfo b = PageKind.pressable(n);
            if (b != null && b.isEnabled() && n.isEnabled()) {
                button = b;
                break;
            }
        }
        if (button == null) return; // not there yet, or off: the next page change looks again
        confirmDone = true;
        watchUntil = 0;
        if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(Page.bounds(button));
        host.say("📍 Page 4: Confirm pressed");
    }

    // ---- page 3: Continue, and Back when it doesn't go on ---------------------------------

    private void pressContinue(String pkg) {
        AccessibilityNodeInfo button = null;
        for (AccessibilityNodeInfo n : Page.nodes(service)) {
            if (!n.isVisibleToUser() || !PageKind.isContinue(Page.label(n))) continue;
            AccessibilityNodeInfo b = PageKind.pressable(n);
            if (b != null && b.isEnabled()) button = b;
        }
        if (button == null) return;
        busy = true;
        Set<String> before = PageKind.read(service).texts;
        watchPkg = pkg;
        watchUntil = SystemClock.uptimeMillis() + WATCH_MS;
        if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(Page.bounds(button));
        host.say("📍 Page 3: every box ticked - Continue pressed");
        afterContinue(before, SystemClock.uptimeMillis());
    }

    /** Up to 2.5 s for the page to move on; still page 3 then: its message, and back. */
    private void afterContinue(Set<String> before, long since) {
        handler.postDelayed(() -> {
            PageKind.Facts f;
            try {
                f = PageKind.read(service);
            } catch (RuntimeException e) {
                done();
                return;
            }
            if (PageKind.decide(f) != PageKind.Kind.TICKING) {
                done();
                return;
            }
            if (SystemClock.uptimeMillis() - since < 2500) {
                afterContinue(before, since);
                return;
            }
            List<String> fresh = new ArrayList<>();
            for (String t : f.texts) if (!before.contains(t) && fresh.size() < 4) fresh.add(t);
            watchUntil = 0;
            backToPage1("Continue didn't move on" + (fresh.isEmpty() ? "" : " (" + String.join(" | ", fresh) + ")"));
        }, 100);
    }

    private void done() {
        busy = false;
        host.recheck();
    }

    /** Back to page 2 (the calendar), then Back again to page 1 - the page before it. */
    /** Page 3's Continue was just pressed (by the retry): page 4's Confirm pressed the moment it comes. */
    void watchConfirm(String pkg) {
        watchPkg = pkg;
        watchUntil = SystemClock.uptimeMillis() + WATCH_MS;
        confirmDone = false;
    }

    /** Back to page 1 as for missed rows (page 1 then starts the retry) - Delete's test with 0. */
    void backForRetry(String why) {
        if (busy) return;
        missed = true;
        backToPage1(why);
    }

    private void backToPage1(String why) {
        busy = true;
        host.say("↩ " + why + " - back to page 2, then page 1");
        backTo2(0);
    }

    private void backTo2(int tries) {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        waitFor(k -> k == PageKind.Kind.CALENDAR, SystemClock.uptimeMillis(), f -> {
            if (f != null) {
                backTo1(0);
            } else if (tries < 1) {
                backTo2(tries + 1); // a pop-up on top took the first Back
            } else {
                host.say("↩ Back: page 2 didn't come - stopped");
                if (missed) host.backFailed();
                missed = false;
                done();
            }
        });
    }

    private void backTo1(int tries) {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        waitFor(k -> k != PageKind.Kind.CALENDAR, SystemClock.uptimeMillis(), f -> {
            if (f != null) {
                host.page1(f);
                host.say("↩ Back on page 1");
                boolean wasMissed = missed;
                missed = false;
                busy = false;
                if (wasMissed) host.missedAtPage1();
                else host.recheck();
            } else if (tries < 1) {
                backTo1(tries + 1);
            } else {
                host.say("↩ Back: still on page 2 - stopped");
                if (missed) host.backFailed();
                missed = false;
                done();
            }
        });
    }

    /**
     * Up to 3 s for a page of that kind, read the same twice in a row (not half drawn):
     * {@code then} gets its reading, or null.
     */
    private void waitFor(java.util.function.Predicate<PageKind.Kind> want, long since,
                         Consumer<PageKind.Facts> then) {
        PageKind.Facts[] last = {null};
        Runnable[] look = new Runnable[1];
        look[0] = () -> {
            PageKind.Facts f = null;
            try {
                f = PageKind.read(service);
            } catch (RuntimeException ignored) {
            }
            PageKind.Kind k = f == null ? PageKind.Kind.OTHER : PageKind.decide(f);
            boolean ok = f != null && f.texts.size() >= 3 && want.test(k);
            // A page known by what it has (page 2's calendar, page 1's own words) is taken at once;
            // anything else when it reads the same twice (not half drawn).
            boolean clear = k == PageKind.Kind.CALENDAR || k == PageKind.Kind.SEVAK_LIST;
            if (ok && (clear || last[0] != null && last[0].texts.equals(f.texts))) {
                then.accept(f);
                return;
            }
            last[0] = ok ? f : null;
            if (SystemClock.uptimeMillis() - since >= 3000) {
                then.accept(null);
                return;
            }
            handler.postDelayed(look[0], 60);
        };
        handler.postDelayed(look[0], 80);
    }

    private void tap(Rect r) {
        if (r == null || r.isEmpty()) return;
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }
}
