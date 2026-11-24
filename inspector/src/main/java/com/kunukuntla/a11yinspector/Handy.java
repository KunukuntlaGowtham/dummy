package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The two handy buttons, one step at a time instead of a whole run:
 * <ul>
 *   <li>☑ Next - ticks the next unticked checkbox, once, and says whether it took (a pop-up then comes);</li>
 *   <li>🧹 OK - clears that pop-up and points at the next checkbox: it is brought on screen and ringed.</li>
 * </ul>
 * ☑ Next notes the page as it was just before its tick, so 🧹 OK can tell the pop-up that came from the
 * page itself (a new button, or a new cover). 🧹 OK then tries, in this order: the pop-up's own button when
 * the page reports it; the place its OK was last found (this app); the purple button on the screen. It says
 * which one worked, or that it found nothing.
 * A row the page refused (ticked, then read unticked after its pop-up) is not offered again until you
 * long-press ☑ Next.
 */
final class Handy {

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PurpleFinder finder;
    private View marker;
    /** The boxes (by their order on the page) the page refused. */
    private final Set<Integer> refused = new HashSet<>();
    /** The box just ticked by ☑ Next (-1: none): read at the next step - unticked then means refused. */
    private int pending = -1;
    /** The page just before the last ☑ Next tick: a button or cover not in it belongs to a pop-up. */
    private Page.Before before;
    private int gen;

    Handy(AccessibilityService service, WindowManager windowManager) {
        this.service = service;
        this.windowManager = windowManager;
        this.finder = new PurpleFinder(service);
    }

    private List<AccessibilityNodeInfo> boxes() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : Page.nodes(service)) if (Page.isCheckbox(n)) out.add(n);
        return out;
    }

    private boolean popupUp() {
        return Page.popup(service, before) != null || Page.coverCame(service, before);
    }

    /** The row just ticked, read now: unticked means the page refused it. */
    private void settlePending(List<AccessibilityNodeInfo> boxes) {
        if (pending >= 0 && pending < boxes.size() && !Page.isChecked(boxes.get(pending))) refused.add(pending);
        pending = -1;
    }

    private int next(List<AccessibilityNodeInfo> boxes) {
        for (int i = 0; i < boxes.size(); i++) {
            if (!Page.isChecked(boxes.get(i)) && !refused.contains(i)) return i;
        }
        return -1;
    }

    // ---- ☑ Next -----------------------------------------------------------------------------

    /** ☑ Next: the next unticked box is pressed once; {@code say} gets what happened (also later, when it is known). */
    void tick(Consumer<String> say) {
        gen++;
        hideMarker();
        List<AccessibilityNodeInfo> boxes = boxes();
        if (boxes.isEmpty()) {
            say.accept("No checkboxes on this page");
            return;
        }
        settlePending(boxes);
        int i = next(boxes);
        if (i < 0) {
            say.accept(refused.isEmpty() ? "Every box is ticked ✓"
                    : "Every other box is ticked ✓ - the page refused " + refused.size()
                    + " (long-press ☑ to try those again)");
            return;
        }
        before = new Page.Before(service); // the page as it is now, no pop-up up
        AccessibilityNodeInfo box = boxes.get(i);
        boolean sent = box.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!sent) sent = clickAround(box);
        if (!sent) {
            say.accept("Couldn't press box " + (i + 1));
            return;
        }
        pending = i;
        int g = gen, idx = i, total = boxes.size();
        // Did it take? A tick, or a pop-up (the page answered), within ~0.45 s; if not, its label is pressed once.
        handler.postDelayed(() -> {
            if (g != gen) return;
            if (tookAt(idx)) {
                say.accept("☑ Box " + (idx + 1) + " of " + total + " ticked ✓ - pop-up? tap 🧹");
                return;
            }
            List<AccessibilityNodeInfo> now = boxes();
            AccessibilityNodeInfo again = idx < now.size() ? now.get(idx) : null;
            boolean second = again != null && clickAround(again);
            handler.postDelayed(() -> {
                if (g != gen) return;
                say.accept(tookAt(idx) ? "☑ Box " + (idx + 1) + " of " + total + " ticked ✓ (by its label) - pop-up? tap 🧹"
                        : second ? "Box " + (idx + 1) + " pressed twice but not ticked - tap ☑ again, or tick it by hand"
                        : "Box " + (idx + 1) + " not ticked - tap ☑ again");
            }, 450);
        }, 450);
    }

    /** Ticked, or the page answered with a pop-up. */
    private boolean tookAt(int idx) {
        List<AccessibilityNodeInfo> now = boxes();
        if (idx < now.size() && Page.isChecked(now.get(idx))) return true;
        return popupUp();
    }

    /** A click on the box's label (the nearest pressable box around it), else a tap where it is drawn. */
    private boolean clickAround(AccessibilityNodeInfo box) {
        AccessibilityNodeInfo p = box.getParent();
        for (int up = 0; up < 3 && p != null; up++, p = p.getParent()) {
            if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        Rect r = place(box);
        if (r == null) return false;
        tap(r.centerX(), r.centerY());
        return true;
    }

    // ---- 🧹 OK ------------------------------------------------------------------------------

    /**
     * 🧹 OK: the pop-up is cleared, one way after another; {@code say} says which worked (or that none did);
     * {@code then} runs when it is done (the next box is pointed at).
     */
    void clear(Consumer<String> say, Runnable then) {
        gen++;
        hideMarker();
        int g = gen;
        // 1. The pop-up's own button, when the page reports it (or its dismiss).
        Page.Popup p = Page.popup(service, before);
        if (p != null && p.button != null && !p.crossOnly) {
            String label = Page.label(p.button);
            Rect r = Page.bounds(p.button);
            if (!p.button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(r.centerX(), r.centerY());
            say.accept("🧹 Pressed \"" + label + "\"");
            waitGone(g, then);
            return;
        }
        if (p != null && p.dismiss != null) {
            p.dismiss.performAction(AccessibilityNodeInfo.ACTION_DISMISS);
            say.accept("🧹 Pop-up dismissed");
            waitGone(g, then);
            return;
        }
        // 2. The place its OK was last found in this app, while the cover is up.
        Rect spot = savedSpot();
        if (spot != null && Page.coverCame(service, before)) {
            tap(spot.centerX(), spot.centerY());
            handler.postDelayed(() -> {
                if (g != gen) return;
                if (!Page.coverCame(service, before)) {
                    say.accept("🧹 Pressed OK where it was last time");
                    then.run();
                } else {
                    viaPurple(g, say, then); // that place didn't close it: found on the screen
                }
            }, 600);
            return;
        }
        // 3. The purple button on the screen.
        viaPurple(g, say, then);
    }

    private void viaPurple(int g, Consumer<String> say, Runnable then) {
        if (!PurpleFinder.available()) {
            say.accept("🧹 No pop-up button reported, and the purple-button search needs Android 11+");
            then.run();
            return;
        }
        long wait = finder.waitMs();
        handler.postDelayed(() -> {
            if (g != gen) return;
            finder.find(r -> {
                if (g != gen) return;
                if (r == null) {
                    say.accept("🧹 No pop-up found (no purple button on the screen)");
                    then.run();
                    return;
                }
                tap(r.centerX(), r.centerY());
                keepSpot(r);
                say.accept("🧹 Pressed the purple button");
                waitGone(g, then);
            }, why -> say.accept("🧹 " + why), before);
        }, wait);
    }

    /** After a press: up to 1.2 s for the cover to go (when it is known), then {@code then}. */
    private void waitGone(int g, Runnable then) {
        long since = SystemClock.uptimeMillis();
        Runnable[] look = new Runnable[1];
        look[0] = () -> {
            if (g != gen) return;
            boolean up = before != null && Page.coverCame(service, before);
            if (!up || SystemClock.uptimeMillis() - since >= 1200) {
                then.run();
            } else {
                handler.postDelayed(look[0], 60);
            }
        };
        handler.postDelayed(look[0], 120);
    }

    // ---- where OK was found (the same place Tick keeps, per app) ---------------------------

    private String spotKey() {
        for (android.view.accessibility.AccessibilityWindowInfo w : Page.windows(service)) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root != null && root.getPackageName() != null) return "ok_spot_" + root.getPackageName();
        }
        return "ok_spot";
    }

    private Rect savedSpot() {
        String s = service.getSharedPreferences("popup", Context.MODE_PRIVATE).getString(spotKey(), null);
        return s == null ? null : Rect.unflattenFromString(s);
    }

    private void keepSpot(Rect r) {
        service.getSharedPreferences("popup", Context.MODE_PRIVATE).edit()
                .putString(spotKey(), r.flattenToString()).putString("ok_spot", r.flattenToString()).apply();
    }

    // ---- pointing at the next box -------------------------------------------------------------

    /** The next unticked box is brought on screen (scrolled if it has to be) and ringed; {@code say} gets the words. */
    void point(Consumer<String> say) {
        gen++;
        int g = gen;
        hideMarker();
        List<AccessibilityNodeInfo> boxes = boxes();
        if (boxes.isEmpty()) {
            say.accept("No checkboxes on this page");
            return;
        }
        settlePending(boxes);
        int i = next(boxes);
        if (i < 0) {
            say.accept(refused.isEmpty() ? "Every box is ticked ✓"
                    : "Every other box is ticked ✓ - the page refused " + refused.size());
            return;
        }
        pointAt(g, i, boxes.size(), 0, say);
    }

    private void pointAt(int g, int idx, int total, int tries, Consumer<String> say) {
        if (g != gen) return;
        List<AccessibilityNodeInfo> boxes = boxes();
        if (idx >= boxes.size()) {
            say.accept("👉 The list changed - tap ☑");
            return;
        }
        AccessibilityNodeInfo box = boxes.get(idx);
        Rect r = place(box);
        if (r != null) {
            showMarker(r);
            say.accept("👉 Next: box " + (idx + 1) + " of " + total + " - tap ☑");
            return;
        }
        if (tries >= 4) {
            say.accept("👉 Next: box " + (idx + 1) + " of " + total + " is off the screen - scroll down, then tap ☑");
            return;
        }
        // Off screen: the page is asked to show it; the page itself is scrolled a little when that did not.
        box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        AccessibilityNodeInfo p = box.getParent();
        if (p != null) p.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        if (tries >= 1) swipeUp();
        handler.postDelayed(() -> pointAt(g, idx, total, tries + 1, say), 450);
    }

    /** Long press on ☑ Next: the rows the page refused are offered again. */
    void reset() {
        gen++;
        refused.clear();
        pending = -1;
        hideMarker();
    }

    void hideMarker() {
        if (marker == null) return;
        try {
            windowManager.removeView(marker);
        } catch (RuntimeException ignored) {
        }
        marker = null;
    }

    // ---- the ring ---------------------------------------------------------------------------

    private void showMarker(Rect r) {
        hideMarker();
        float d = service.getResources().getDisplayMetrics().density;
        int pad = Math.round(6 * d);
        View v = new View(service);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(12 * d);
        ring.setColor(0x33FFC107);
        ring.setStroke(Math.round(4 * d), 0xFFFFC107);
        v.setBackground(ring);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(r.width() + 2 * pad, r.height() + 2 * pad,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = r.left - pad;
        lp.y = r.top - pad;
        try {
            windowManager.addView(v, lp);
            marker = v;
            handler.postDelayed(() -> {
                if (marker == v) hideMarker();
            }, 20000);
        } catch (RuntimeException ignored) {
        }
    }

    /** Where the box is on screen: the box itself, or (hidden, 0 wide) the nearest parent with a real size. */
    private Rect place(AccessibilityNodeInfo box) {
        try {
            box.refresh();
        } catch (RuntimeException ignored) {
        }
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        long limit = (long) dm.widthPixels * dm.heightPixels / 8;
        int top = Math.round(80 * dm.density), bottom = dm.heightPixels - Math.round(110 * dm.density);
        AccessibilityNodeInfo p = box;
        for (int depth = 0; p != null && depth < 4; depth++, p = p.getParent()) {
            Rect r = new Rect();
            p.getBoundsInScreen(r);
            if (r.width() > 4 && r.height() > 4 && (long) r.width() * r.height() < limit
                    && r.top >= top && r.bottom <= bottom && p.isVisibleToUser()) return r;
        }
        return null;
    }

    private void tap(int x, int y) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    /** The page moved up a little (a steady drag, no fling): what is below comes into view. */
    private void swipeUp() {
        android.util.DisplayMetrics dm = service.getResources().getDisplayMetrics();
        Path p = new Path();
        p.moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.68f);
        p.lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.40f);
        service.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 350)).build(), null, null);
    }
}
