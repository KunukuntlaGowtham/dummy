package com.kunukuntla.ttdfiller;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One fill of the page in front from one booking, in two passes.
 * <p>
 * <b>Fast pass</b> - no waiting on anything that isn't needed: every Name and Age typed at once;
 * each list opened (an accessibility click: no scrolling, a scroll closes the TTD list), its
 * option clicked the moment it shows, a short look that the box took it, and on to the next;
 * then the ID numbers (unlocked by then).
 * <p>
 * <b>Check</b> - every box read back (each kept from the start and only refreshed).
 * <p>
 * <b>Careful pass</b> - only for what didn't take: a list opened again (also by a real tap, or
 * its arrow), its option clicked and given time, then tapped where it really is only while the
 * list is still open (never a tap where a closed list was - that lands on another box); text
 * pasted. Then the last check and the report, with each box's time in the details.
 */
final class Filler {

    interface Listener {
        void progress(String text);

        void finished(boolean ok, String report, String details);
    }

    private static final long POLL = 30;

    private static final class Step {
        final String title;
        final int pilgrim, slot;
        final String contact;
        final String want;
        final String[] options;
        final boolean list;
        final String name;
        AccessibilityNodeInfo box;
        String problem, detail, how = "";
        boolean hopeless, careful;
        long t0, ms;
        // the list while this step works it
        Set<String> before;
        long openedAt;
        AccessibilityNodeInfo item;
        String itemText, tapInfo;
        int pressWay, wayAt, pressStart;
        boolean reopened;
        int[] ways;
        java.util.Set<AccessibilityNodeInfo> optsBefore = new java.util.HashSet<>();
        Runnable then;

        Step(String title, int pilgrim, int slot, String contact, String want, String[] options, boolean list) {
            this.title = title;
            this.pilgrim = pilgrim;
            this.slot = slot;
            this.contact = contact;
            this.want = want;
            this.options = options;
            this.list = list;
            this.name = Match.squash(slot >= 0 ? Form.SLOT_NAMES[slot] : contact == null ? "" : contact);
        }
    }

    private final AccessibilityService s;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Booking booking;
    private final boolean pressContinue;
    private final Listener out;

    private boolean running;
    private int gen;
    private Form form;
    private final List<Step> plan = new ArrayList<>();
    private final List<Step> typed = new ArrayList<>(), lists = new ArrayList<>(), ids = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final StringBuilder phases = new StringBuilder();
    private List<Step> list = new ArrayList<>();
    private int at;
    private Runnable after;
    private long started, phaseAt;
    private int boxes, filledPeople, people;

    Filler(AccessibilityService s, Booking booking, boolean pressContinue, Listener out) {
        this.s = s;
        this.booking = booking;
        this.pressContinue = pressContinue;
        this.out = out;
    }

    boolean running() {
        return running;
    }

    /** {@code recent}: the page as the service read it a moment ago (saves reading it again), or null. */
    void start(Form recent) {
        running = true;
        gen++;
        started = SystemClock.uptimeMillis();
        form = recent != null && fresh(recent) ? recent : Form.read(s);
        if (form.pilgrims.isEmpty()) {
            end(false, "✗ No pilgrim boxes on this screen.\nOpen the Pilgrim Details page, then press 📝 Fill.");
            return;
        }
        List<Booking.Person> ps = booking.people();
        boxes = form.pilgrims.size();
        people = ps.size();
        if (people == 0) {
            end(false, "✗ " + booking.code() + " has nobody in it (no names in ttd_db).");
            return;
        }
        filledPeople = Math.min(boxes, people);
        if (people < boxes) {
            notes.add("Booking has " + people + (people == 1 ? " person" : " people") + ", page has " + boxes
                    + " boxes: boxes " + (people + 1) + (boxes > people + 1 ? "–" + boxes : "") + " are left for you");
        } else if (people > boxes) {
            notes.add("Booking has " + people + " people, page has " + boxes + " boxes: filled the first " + boxes);
        }
        for (int i = 0; i < filledPeople; i++) {
            Booking.Person p = ps.get(i);
            String who = "Pilgrim " + (i + 1) + " ";
            typed.add(new Step(who + "Name", i, Form.NAME, null, Match.name(p.name), null, false));
            String age = Match.age(p.age);
            if (!age.isEmpty()) typed.add(new Step(who + "Age", i, Form.AGE, null, age, null, false));
            else notes.add(who + "has no age in ttd_db");
            if (!p.gender.isEmpty()) {
                lists.add(new Step(who + "Gender", i, Form.GENDER, null, p.gender, Match.genders(p.gender), true));
            } else {
                notes.add(who + "has no gender in ttd_db");
            }
            String proof = Match.proofName(p.idType);
            lists.add(new Step(who + "ID proof", i, Form.PROOF, null, proof, Match.proofs(proof), true));
            String id = Match.idNumber(p.idNumber);
            if (!id.isEmpty()) ids.add(new Step(who + "ID number", i, Form.IDNUM, null, id, null, false));
            else notes.add(who + "has no ID number in ttd_db");
        }
        for (String key : Booking.CONTACTS) {
            AccessibilityNodeInfo n = form.contact.get(key);
            String v = Match.contact(key, booking.s(key));
            if (n == null || v.isEmpty()) continue;
            boolean isList = n.isEnabled() && !Form.typable(n);
            String title = Character.toUpperCase(key.charAt(0)) + key.substring(1);
            Step c = new Step(title, -1, -1, key, v, isList ? new String[]{v} : null, isList);
            (isList ? lists : typed).add(c);
        }
        plan.addAll(typed);
        plan.addAll(lists);
        plan.addAll(ids);
        // Every box taken from this one read of the page (each is only refreshed from now on).
        for (Step st : plan) st.box = locate(form, st);

        out.progress("Filling " + filledPeople + " pilgrim" + (filledPeople == 1 ? "" : "s") + "…");
        phaseAt = SystemClock.uptimeMillis();
        for (Step st : typed) typeNow(st);
        phase("names+ages");
        fastList(0);
    }

    private boolean fresh(Form f) {
        if (f.pilgrims.isEmpty() || f.pilgrims.get(0)[Form.NAME] == null) return false;
        try {
            return f.pilgrims.get(0)[Form.NAME].refresh();
        } catch (RuntimeException e) {
            return false;
        }
    }

    void cancel() {
        if (!running) return;
        end(false, "⏹ Stopped");
    }

    private static AccessibilityNodeInfo locate(Form f, Step st) {
        return st.contact != null ? f.contact.get(st.contact) : Form.box(f, st.pilgrim, st.slot);
    }

    /** The step's box: the one kept (refreshed); when it went, the page read again for every step. */
    private AccessibilityNodeInfo box(Step st) {
        if (st.box != null) {
            try {
                if (st.box.refresh()) return st.box;
            } catch (RuntimeException ignored) {
            }
        }
        form = Form.read(s);
        for (Step x : plan) x.box = locate(form, x);
        return st.box;
    }

    private void phase(String what) {
        long now = SystemClock.uptimeMillis();
        phases.append(phases.length() > 0 ? " · " : "").append(what).append(' ').append(secs(now - phaseAt));
        phaseAt = now;
    }

    private static String secs(long ms) {
        return String.format(Locale.ROOT, "%.2f s", ms / 1000.0);
    }

    // ---- fast pass ----

    /** Typed now, no waiting (a box still locked is left for the careful pass). */
    private void typeNow(Step st) {
        long t = SystemClock.uptimeMillis();
        AccessibilityNodeInfo n = box(st);
        if (n == null) {
            st.problem = "box not found";
        } else if (!n.isEnabled()) {
            st.problem = "the box was locked";
        } else if (Match.same(Form.value(n), st.want)) {
            st.problem = null;
            st.how = "already right";
        } else {
            boolean done = setText(n, st.want) || paste(n, st.want);
            st.problem = done ? null : "the page didn't take the text";
            st.how = "typed";
        }
        st.ms += SystemClock.uptimeMillis() - t;
    }

    private void fastList(int i) {
        if (!running) return;
        if (i >= lists.size()) {
            phase("lists");
            for (Step st : ids) typeNow(st);
            phase("ID numbers");
            later(() -> verify(0), 60);
            return;
        }
        Step st = lists.get(i);
        out.progress(st.title + "…");
        st.t0 = SystemClock.uptimeMillis();
        listStep(st, () -> {
            st.ms += SystemClock.uptimeMillis() - st.t0;
            fastList(i + 1);
        });
    }

    // ---- the check, the careful pass ----

    private void verify(int round) {
        if (!running) return;
        List<Step> bad = new ArrayList<>();
        for (Step st : plan) {
            if (st.hopeless) continue;
            AccessibilityNodeInfo n = box(st);
            String v = st.list ? Form.chosen(n, st.name) : Form.value(n);
            boolean good = n != null && (st.list ? Match.fits(v, st.options) : Match.same(v, st.want));
            if (good) {
                st.problem = null;
                continue;
            }
            if (st.problem == null) st.problem = n == null ? "box not found" : "shows \"" + v + "\"";
            bad.add(st);
        }
        phase(round == 0 ? "check" : "second pass");
        if (!bad.isEmpty() && round == 0) {
            for (Step st : bad) st.careful = true;
            out.progress("Once more, carefully: " + bad.size() + " box" + (bad.size() == 1 ? "" : "es") + "…");
            run(bad, () -> later(() -> verify(1), 100));
            return;
        }
        finish();
    }

    private void run(List<Step> steps, Runnable then) {
        list = steps;
        at = 0;
        after = then;
        step();
    }

    private void step() {
        if (!running) return;
        if (at >= list.size()) {
            after.run();
            return;
        }
        Step st = list.get(at);
        st.t0 = SystemClock.uptimeMillis();
        st.item = null;
        st.pressWay = 0;
        st.wayAt = 0;
        st.pressStart = 0;
        st.reopened = false;
        st.problem = null;
        if (st.list) {
            out.progress(st.title + " (carefully)…");
            st.careful = true;
            listStep(st, this::next);
        } else {
            type(st, 0);
        }
    }

    private void next() {
        Step st = list.get(at);
        st.ms += SystemClock.uptimeMillis() - st.t0;
        at++;
        h.post(() -> {
            if (running) step();
        });
    }

    private boolean pageGone(int tries) {
        if (tries < 3 || !Form.read(s).pilgrims.isEmpty()) return false;
        end(false, "✗ The pilgrim page went away - stopped. Open it again and press 📝 Fill.");
        return true;
    }

    private void type(Step st, int tries) {
        AccessibilityNodeInfo n = box(st);
        if (n == null) {
            if (pageGone(tries)) return;
            if (tries < 8) {
                later(() -> type(st, tries + 1), 80);
                return;
            }
            st.problem = "box not found";
            next();
            return;
        }
        if (!n.isEnabled()) {
            if (st.slot == Form.IDNUM && proofFailed(st)) {
                st.problem = "locked - its ID proof isn't chosen";
                next();
                return;
            }
            if (tries < 30) { // the ID number unlocks once its proof is chosen: up to ~1.5 s
                later(() -> type(st, tries + 1), 50);
                return;
            }
            st.problem = "the box stayed locked";
            next();
            return;
        }
        if (!Match.same(Form.value(n), st.want)) {
            boolean done = paste(n, st.want) || setText(n, st.want);
            st.problem = done ? null : "the page didn't take the text";
            st.how = "pasted";
        }
        next();
    }

    private boolean proofFailed(Step idStep) {
        for (Step p : lists) {
            if (p.pilgrim == idStep.pilgrim && p.slot == Form.PROOF) return p.problem != null || p.hopeless;
        }
        return false;
    }

    private static boolean setText(AccessibilityNodeInfo n, String v) {
        Bundle a = new Bundle();
        a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, v);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
    }

    /** Typed the way a person pastes: what is in the box selected, then replaced from the clipboard. */
    private boolean paste(AccessibilityNodeInfo n, String v) {
        try {
            ClipboardManager cm = (ClipboardManager) s.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("fill", v));
            n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle sel = new Bundle();
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Form.value(n).length());
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
            return n.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- a list ----
    //
    // On the TTD pilgrim page an accessibility click on a list box only puts the focus in it
    // (Chrome does that for every text box); its list opens on a real tap. So the box is tapped
    // where it really is, and the way that opened a list is remembered for the next ones.
    // Only list lines that came AFTER the box was opened count: a list left open by another
    // box is never read as this box's list (that is how "Aadhaar Card isn't in its list
    // (Female | Male | Transgender)" happened), and it is closed first - it may lie over the box.

    private static final int CLICK = 0, TAP = 1, FOCUS = 2;
    private static final String[] WAY_NAMES = {"click", "tap", "focus + arrow"};

    /** The box whose list was opened last, and how (to close it again when it was left open). */
    private AccessibilityNodeInfo lastListBox;
    private int lastOpenWay = TAP;

    private int[] ways(boolean careful) {
        int first = Booking.prefs(s).getInt("open_way", TAP);
        if (first != CLICK) first = TAP;
        int second = first == TAP ? CLICK : TAP;
        return careful ? new int[]{first, second, FOCUS} : new int[]{first, second};
    }

    private void listStep(Step st, Runnable then) {
        st.then = then;
        AccessibilityNodeInfo f = box(st);
        if (f == null) {
            st.problem = "box not found";
            then.run();
            return;
        }
        if (Match.fits(Form.chosen(f, st.name), st.options)) {
            st.problem = null;
            if (st.how.isEmpty()) st.how = "already right";
            then.run();
            return;
        }
        if (lastListBox != null && !lastListBox.equals(f) && !Form.optionsAll(s).isEmpty()) {
            // A list left open by the box before: closed first.
            toggle(lastListBox, lastOpenWay);
            lastListBox = null;
            later(() -> beginOpen(st), 250);
            return;
        }
        beginOpen(st);
    }

    private void beginOpen(Step st) {
        AccessibilityNodeInfo f = box(st);
        if (f == null) {
            st.problem = "box not found";
            st.then.run();
            return;
        }
        st.optsBefore = new java.util.HashSet<>(Form.optionsAll(s));
        st.before = Form.wordsNear(f);
        st.ways = ways(st.careful);
        st.wayAt = 0;
        st.tapInfo = null;
        tryWay(st);
    }

    private void tryWay(Step st) {
        AccessibilityNodeInfo f = box(st);
        if (f == null) {
            st.problem = "box not found";
            st.then.run();
            return;
        }
        if (st.wayAt >= st.ways.length) {
            st.problem = "its list didn't open";
            st.detail = (st.tapInfo == null ? "" : st.tapInfo + "; ") + Form.around(f);
            st.then.run();
            return;
        }
        int way = st.ways[st.wayAt];
        long wait = way == CLICK ? (st.careful ? 500 : 300) : (st.careful ? 900 : 700);
        if (way == CLICK) {
            st.openedAt = SystemClock.uptimeMillis();
            if (!f.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                st.wayAt++;
                tryWay(st);
                return;
            }
            watchNew(st, st.openedAt + wait);
        } else if (way == TAP) {
            tapNode(f, true, info -> {
                st.tapInfo = info;
                st.openedAt = SystemClock.uptimeMillis();
                watchNew(st, st.openedAt + wait);
            });
        } else {
            st.openedAt = SystemClock.uptimeMillis();
            f.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            AccessibilityNodeInfo o = Form.opener(f);
            if (o != null) o.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            watchNew(st, st.openedAt + wait);
        }
    }

    /** Every 30 ms: list lines that weren't on the page before this box was opened. */
    private void watchNew(Step st, long until) {
        AccessibilityNodeInfo f = box(st);
        if (f == null) {
            st.problem = "box not found";
            st.then.run();
            return;
        }
        List<AccessibilityNodeInfo> fresh = new ArrayList<>();
        for (AccessibilityNodeInfo n : Form.optionsAll(s)) if (!st.optsBefore.contains(n)) fresh.add(n);
        if (fresh.isEmpty()) fresh = Form.newFitting(Form.near(f), st.before, st.options);
        if (!fresh.isEmpty()) {
            int way = st.ways[st.wayAt];
            lastListBox = f;
            lastOpenWay = way;
            if (way != FOCUS) Booking.prefs(s).edit().putInt("open_way", way).apply();
            st.how = "opened by " + WAY_NAMES[way] + " in " + (SystemClock.uptimeMillis() - st.openedAt) + " ms";
            pickNew(st, fresh);
            return;
        }
        if (SystemClock.uptimeMillis() < until) {
            later(() -> watchNew(st, until), POLL);
            return;
        }
        st.wayAt++;
        tryWay(st);
    }

    private void pickNew(Step st, List<AccessibilityNodeInfo> fresh) {
        List<String> texts = Form.labels(fresh);
        int i = Match.best(texts, st.options);
        if (i < 0) {
            st.problem = "\"" + st.want + "\" isn't in its list (" + String.join(" | ", texts) + ")";
            st.hopeless = true;
            closeOwn(st);
            return;
        }
        st.item = fresh.get(i);
        st.itemText = texts.get(i);
        st.pressWay = st.pressStart;
        pressItem(st);
    }

    /** Way 0: an accessibility click on the line; 1: a real tap on it (only while it is there); 2: focus, select, click. */
    private void pressItem(Step st) {
        int way = st.pressWay;
        AccessibilityNodeInfo item = st.item;
        if (way > 0 && way < 3 && st.careful && !st.reopened && !stillThere(item)) {
            // The click closed the list without the choice: opened again, and its line tapped.
            st.reopened = true;
            st.pressStart = 1;
            beginOpen(st);
            return;
        }
        if (way >= (st.careful ? 3 : 2) || way > 0 && !stillThere(item)) {
            st.problem = "chose \"" + st.itemText + "\" but the box didn't take it";
            st.detail = st.tapInfo;
            closeOwn(st);
            return;
        }
        long wait = st.careful ? 700 : 400;
        if (way == 0) {
            if (!item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                st.pressWay++;
                pressItem(st);
                return;
            }
            confirmItem(st, SystemClock.uptimeMillis() + wait);
        } else if (way == 1) {
            tapNode(item, false, info -> confirmItem(st, SystemClock.uptimeMillis() + wait));
        } else {
            item.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            item.performAction(AccessibilityNodeInfo.ACTION_SELECT);
            Nodes.click(s, item);
            confirmItem(st, SystemClock.uptimeMillis() + wait);
        }
    }

    private void confirmItem(Step st, long until) {
        AccessibilityNodeInfo f = box(st);
        String[] chosen = {st.itemText};
        if (f != null && (Match.fits(Form.chosen(f, st.name), chosen) || Match.fits(Form.chosen(f, st.name), st.options))) {
            st.problem = null;
            st.how += ", \"" + st.itemText + "\" by " + (st.pressWay == 0 ? "click" : st.pressWay == 1 ? "tap" : "select");
            lastListBox = null; // it closed with the choice
            st.then.run();
            return;
        }
        if (SystemClock.uptimeMillis() < until) {
            later(() -> confirmItem(st, until), POLL);
            return;
        }
        st.pressWay++;
        pressItem(st);
    }

    /** The box's own list, when still open, closed the way it was opened; then the step ends. */
    private void closeOwn(Step st) {
        AccessibilityNodeInfo f = box(st);
        boolean open = false;
        for (AccessibilityNodeInfo n : Form.optionsAll(s)) if (!st.optsBefore.contains(n)) open = true;
        if (open && f != null) {
            toggle(f, lastOpenWay);
            lastListBox = null;
            later(st.then, 200);
            return;
        }
        st.then.run();
    }

    private void toggle(AccessibilityNodeInfo box, int way) {
        if (way == TAP) tapNode(box, false, info -> {
        });
        else box.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static boolean stillThere(AccessibilityNodeInfo n) {
        try {
            return n != null && n.refresh();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * A real tap in it where it really is, clear of the 📝 button. {@code bringUp}: when it is out
     * of view it is scrolled into view first (a box - never a list's line: a scroll closes the list).
     * {@code then} gets where it tapped (for the report).
     */
    private void tapNode(AccessibilityNodeInfo n, boolean bringUp, java.util.function.Consumer<String> then) {
        try {
            n.refresh();
        } catch (RuntimeException ignored) {
        }
        Rect r = Nodes.realRect(n);
        if (Nodes.onScreen(s, r)) {
            then.accept(tapIn(r));
            return;
        }
        if (!bringUp) {
            then.accept("not on screen " + (r == null ? Nodes.bounds(n).toShortString() : r.toShortString()));
            return;
        }
        n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        later(() -> {
            try {
                n.refresh();
            } catch (RuntimeException ignored) {
            }
            Rect r2 = Nodes.realRect(n);
            if (Nodes.onScreen(s, r2)) then.accept("scrolled, " + tapIn(r2));
            else then.accept("no place on screen " + (r2 == null ? Nodes.bounds(n).toShortString() : r2.toShortString()));
        }, 320);
    }

    private String tapIn(Rect r) {
        int x = r.centerX(), y = r.centerY();
        Rect avoid = FillService.instance == null ? null : FillService.instance.pillRect();
        if (avoid != null && avoid.contains(x, y)) x = r.right - Math.max(10, r.width() / 8);
        Nodes.tapAt(s, x, y);
        return "tapped " + x + "," + y + " of " + r.toShortString();
    }

    // ---- report ----

    private void finish() {
        List<String> problems = new ArrayList<>();
        for (Step st : plan) {
            if (st.problem == null) continue;
            problems.add(st.title + ": " + st.problem + (st.detail == null ? "" : "\n    [next to it: " + st.detail + "]"));
        }
        long total = SystemClock.uptimeMillis() - started;
        boolean ok = problems.isEmpty();
        StringBuilder r = new StringBuilder();
        r.append(ok ? "✅ " : "⚠ ").append("Filled ").append(filledPeople).append(" of ").append(boxes)
                .append(" pilgrim").append(boxes == 1 ? "" : "s").append(" from ").append(booking.code())
                .append(" in ").append(secs(total));
        for (String p : problems) r.append("\n✗ ").append(p);
        for (String n : notes) r.append("\n• ").append(n);
        if (pressContinue) {
            AccessibilityNodeInfo c = form.continueButton;
            if (!ok || people < boxes) {
                r.append("\n➡ Continue not pressed (fix the boxes above first)");
            } else if (c != null && stillThere(c) && c.isEnabled()) {
                if (!c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) Nodes.click(s, c);
                r.append("\n➡ Pressed Continue");
            } else {
                r.append("\n➡ Continue not found or not on yet");
            }
        }
        StringBuilder d = new StringBuilder("Times: ").append(phases);
        for (Step st : plan) {
            d.append('\n').append(st.problem == null ? "✓ " : "✗ ").append(st.title).append(' ').append(st.ms).append(" ms");
            if (!st.how.isEmpty()) d.append(" - ").append(st.how);
            if (st.careful) d.append(" (2nd pass)");
        }
        end(ok, r.toString(), d.toString());
    }

    private void end(boolean ok, String report) {
        end(ok, report, "");
    }

    private void end(boolean ok, String report, String details) {
        running = false;
        gen++;
        out.finished(ok, report, details);
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
