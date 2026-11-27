package com.kunukuntla.ttdfiller;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * One TTD list box chosen (the slot page's "Number of Tickets"), the way the pilgrim lists are:
 * a real tap on the box opens its list (an accessibility click only focuses it; that is tried
 * second), only list lines that came after the tap count, the option is clicked - tapped when
 * the box didn't take it - and the box is read back.
 */
final class ListPick {

    interface Done {
        void done(boolean ok, String how);
    }

    private final AccessibilityService s;
    private final Handler h;
    private final BooleanSupplier alive;
    private final AccessibilityNodeInfo box;
    private final String[] wants;
    private final Done done;
    private Set<AccessibilityNodeInfo> before;
    private AccessibilityNodeInfo item;
    private String itemText = "", seen = "";

    private ListPick(AccessibilityService s, Handler h, BooleanSupplier alive, AccessibilityNodeInfo box, String[] wants, Done done) {
        this.s = s;
        this.h = h;
        this.alive = alive;
        this.box = box;
        this.wants = wants;
        this.done = done;
    }

    /** {@code alive}: false once the run that asked was stopped (nothing more is done then). */
    static void choose(AccessibilityService s, Handler h, BooleanSupplier alive, AccessibilityNodeInfo box, String[] wants, Done done) {
        new ListPick(s, h, alive, box, wants, done).start();
    }

    private void start() {
        // Already right only when it shows the FIRST choice (the others are only fallbacks for a
        // list that doesn't offer it - a box showing "1" is not "5").
        if (Match.fits(value(), new String[]{wants[0]})) {
            done.done(true, "already " + value());
            return;
        }
        before = new HashSet<>(Form.optionsAll(s));
        Rect r = rect(box);
        if (Nodes.onScreen(s, r)) {
            Nodes.tapAt(s, r.centerX(), r.centerY());
            watch(SystemClock.uptimeMillis() + 700, false);
            return;
        }
        box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        later(() -> {
            Rect r2 = rect(box);
            if (Nodes.onScreen(s, r2)) Nodes.tapAt(s, r2.centerX(), r2.centerY());
            else box.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            watch(SystemClock.uptimeMillis() + 700, false);
        }, 300);
    }

    private void watch(long until, boolean clicked) {
        List<AccessibilityNodeInfo> fresh = new ArrayList<>();
        for (AccessibilityNodeInfo n : Form.optionsAll(s)) if (!before.contains(n)) fresh.add(n);
        if (!fresh.isEmpty()) {
            List<String> texts = Form.labels(fresh);
            seen = " (its list: " + String.join(" | ", texts) + ")";
            int i = Match.best(texts, wants);
            if (i < 0) {
                close();
                done.done(false, "\"" + wants[0] + "\" isn't in its list (" + String.join(" | ", texts) + ")");
                return;
            }
            item = fresh.get(i);
            itemText = texts.get(i);
            if (!item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tapItem();
            confirm(SystemClock.uptimeMillis() + 600, false);
            return;
        }
        if (SystemClock.uptimeMillis() < until) {
            later(() -> watch(until, clicked), 20);
            return;
        }
        if (!clicked) {
            box.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            later(() -> watch(SystemClock.uptimeMillis() + 400, true), 20);
            return;
        }
        done.done(false, "its list didn't open");
    }

    private void confirm(long until, boolean tapped) {
        if (Match.fits(value(), new String[]{itemText})) {
            done.done(true, "chose " + itemText + seen);
            return;
        }
        if (SystemClock.uptimeMillis() < until) {
            later(() -> confirm(until, tapped), 20);
            return;
        }
        if (!tapped && stillThere(item)) {
            tapItem();
            confirm(SystemClock.uptimeMillis() + 600, true);
            return;
        }
        close();
        done.done(false, "chose " + itemText + " but the box shows \"" + value() + "\"");
    }

    private void tapItem() {
        Rect r = rect(item);
        if (Nodes.onScreen(s, r)) Nodes.tapAt(s, r.centerX(), r.centerY());
    }

    /** Its list closed again when it is still open (tapped like it was opened). */
    private void close() {
        for (AccessibilityNodeInfo n : Form.optionsAll(s)) {
            if (before.contains(n)) continue;
            Rect r = rect(box);
            if (Nodes.onScreen(s, r)) Nodes.tapAt(s, r.centerX(), r.centerY());
            return;
        }
    }

    private String value() {
        try {
            box.refresh();
        } catch (RuntimeException ignored) {
        }
        return Form.value(box);
    }

    private static boolean stillThere(AccessibilityNodeInfo n) {
        try {
            return n != null && n.refresh();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Rect rect(AccessibilityNodeInfo n) {
        try {
            n.refresh();
        } catch (RuntimeException ignored) {
        }
        return Nodes.realRect(n);
    }

    private void later(Runnable r, long ms) {
        h.postDelayed(() -> {
            if (alive.getAsBoolean()) r.run();
        }, ms);
    }
}
