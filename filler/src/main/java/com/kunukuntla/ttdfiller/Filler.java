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
        int polls;
        AccessibilityNodeInfo item;
        String itemText;
        boolean reopened;
        int pressFrom;

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
        quick(st, () -> {
            st.ms += SystemClock.uptimeMillis() - st.t0;
            fastList(i + 1);
        });
    }

    private void quick(Step st, Runnable then) {
        AccessibilityNodeInfo f = box(st);
        if (f == null) {
            st.problem = "box not found";
            then.run();
            return;
        }
        if (Match.fits(Form.chosen(f, st.name), st.options)) {
            st.problem = null;
            st.how = "already right";
            then.run();
            return;
        }
        st.before = Form.wordsNear(f);
        if (!f.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            st.problem = "its list didn't open";
            then.run();
            return;
        }
        st.openedAt = SystemClock.uptimeMillis();
        st.polls = 0;
        quickWatch(st, then);
    }

    private void quickWatch(Step st, Runnable then) {
        AccessibilityNodeInfo f = st.box;
        st.polls++;
        List<AccessibilityNodeInfo> near = Form.near(f);
        List<AccessibilityNodeInfo> items = Form.optionsIn(near);
        if (items.isEmpty() && st.polls % 6 == 0) items = Form.optionsAll(s);
        if (items.isEmpty()) items = Form.newFitting(near, st.before, st.options);
        if (!items.isEmpty()) {
            List<String> texts = Form.labels(items);
            int i = Match.best(texts, st.options);
            if (i < 0) {
                notInList(st, f, texts);
                later(then, 120);
                return;
            }
            st.item = items.get(i);
            st.itemText = texts.get(i);
            st.how = "chose \"" + st.itemText + "\" after " + (SystemClock.uptimeMillis() - st.openedAt) + " ms";
            if (!st.item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) Nodes.click(s, st.item);
            quickConfirm(st, SystemClock.uptimeMillis() + 350, then);
            return;
        }
        if (SystemClock.uptimeMillis() - st.openedAt < 700) {
            later(() -> quickWatch(st, then), POLL);
            return;
        }
        st.problem = "its list didn't open";
        then.run();
    }

    /** A short look that the box took the choice (a slow one is caught by the check). */
    private void quickConfirm(Step st, long until, Runnable then) {
        AccessibilityNodeInfo f = box(st);
        if (f != null && Match.fits(Form.chosen(f, st.name), st.options)) {
            st.problem = null;
            then.run();
            return;
        }
        if (SystemClock.uptimeMillis() < until) {
            later(() -> quickConfirm(st, until, then), POLL);
            return;
        }
        st.problem = "chose \"" + st.itemText + "\" but the box didn't take it";
        then.run();
    }

    private void notInList(Step st, AccessibilityNodeInfo f, List<String> texts) {
        st.problem = "\"" + st.want + "\" isn't in its list (" + String.join(" | ", texts) + ")";
        st.hopeless = true;
        if (f != null) f.performAction(AccessibilityNodeInfo.ACTION_CLICK); // its list closed again
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
        st.reopened = false;
        st.pressFrom = 0;
        st.problem = null;
        if (st.list) {
            out.progress(st.title + " (carefully)…");
            choose(st, 0);
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

    // ---- a list, carefully ----

    private void choose(Step st, int tries) {
        AccessibilityNodeInfo f = box(st);
        if (f == null) {
            if (pageGone(tries)) return;
            if (tries < 8) {
                later(() -> choose(st, tries + 1), 80);
                return;
            }
            st.problem = "box not found";
            next();
            return;
        }
        if (Match.fits(Form.chosen(f, st.name), st.options)) {
            next();
            return;
        }
        // Its list may still be open from the fast pass: chosen from as it is (a click would close it).
        List<AccessibilityNodeInfo> open = Form.optionsIn(Form.near(f));
        if (!open.isEmpty()) {
            st.openedAt = SystemClock.uptimeMillis();
            pick(st, open);
            return;
        }
        st.before = Form.wordsNear(f);
        open(st, 0);
    }

    /** Way 0: an accessibility click on the box; 1: a real tap on it; 2: focus, then a click on its arrow. */
    private void open(Step st, int way) {
        AccessibilityNodeInfo f = box(st);
        if (f == null) {
            st.problem = "box not found";
            next();
            return;
        }
        if (way >= 3) {
            st.problem = "its list didn't open";
            st.detail = Form.around(f);
            next();
            return;
        }
        st.openedAt = SystemClock.uptimeMillis();
        st.polls = 0;
        if (way == 0) {
            if (!f.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                open(st, 1);
                return;
            }
            watch(st, way, st.openedAt + 700);
        } else if (way == 1) {
            tapReal(f, () -> {
                st.openedAt = SystemClock.uptimeMillis();
                watch(st, 1, st.openedAt + 800);
            });
        } else {
            f.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            AccessibilityNodeInfo o = Form.opener(f);
            if (o != null) Nodes.click(s, o);
            watch(st, way, st.openedAt + 800);
        }
    }

    private void watch(Step st, int way, long until) {
        AccessibilityNodeInfo f = box(st);
        if (f == null) {
            st.problem = "box not found";
            next();
            return;
        }
        if (Match.fits(Form.chosen(f, st.name), st.options)) {
            next();
            return;
        }
        st.polls++;
        List<AccessibilityNodeInfo> near = Form.near(f);
        List<AccessibilityNodeInfo> items = Form.optionsIn(near);
        if (items.isEmpty() && st.polls % 5 == 0) items = Form.optionsAll(s);
        if (!items.isEmpty()) {
            pick(st, items);
            return;
        }
        List<AccessibilityNodeInfo> fit = Form.newFitting(near, st.before, st.options);
        if (!fit.isEmpty()) {
            pick(st, fit);
            return;
        }
        // Two new lines or more next to the box and none fits: its list, without the choice.
        List<AccessibilityNodeInfo> fresh = Form.newWordsNear(f, st.before);
        if (fresh.size() >= 2 && SystemClock.uptimeMillis() - st.openedAt > 300) {
            notInList(st, f, Form.labels(fresh));
            later(this::next, 120);
            return;
        }
        if (SystemClock.uptimeMillis() < until) {
            later(() -> watch(st, way, until), POLL);
            return;
        }
        open(st, way + 1);
    }

    private void pick(Step st, List<AccessibilityNodeInfo> items) {
        List<String> texts = Form.labels(items);
        int i = Match.best(texts, st.options);
        if (i < 0) {
            notInList(st, box(st), texts);
            later(this::next, 120);
            return;
        }
        st.item = items.get(i);
        st.itemText = texts.get(i);
        press(st, st.pressFrom);
    }

    /** Way 0: an accessibility click on the line; 1: a real tap on it (only while it is still there); 2: focus, select, click. */
    private void press(Step st, int way) {
        if (way >= 3) {
            st.problem = "chose \"" + st.itemText + "\" but the box didn't take it";
            st.detail = st.box == null ? null : Form.around(st.box);
            next();
            return;
        }
        AccessibilityNodeInfo item = st.item;
        st.how = "careful way " + (way + 1) + " on \"" + st.itemText + "\"";
        if (way == 0) {
            if (!item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                press(st, 1);
                return;
            }
            confirm(st, way, SystemClock.uptimeMillis() + 900);
        } else if (way == 1) {
            if (!stillThere(item)) {
                press(st, 2);
                return;
            }
            tapReal(item, () -> confirm(st, 1, SystemClock.uptimeMillis() + 900));
        } else {
            item.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            item.performAction(AccessibilityNodeInfo.ACTION_SELECT);
            Nodes.click(s, item);
            confirm(st, 2, SystemClock.uptimeMillis() + 900);
        }
    }

    private void confirm(Step st, int way, long until) {
        AccessibilityNodeInfo f = box(st);
        if (f != null && Match.fits(Form.chosen(f, st.name), st.options)) {
            st.problem = null;
            next();
            return;
        }
        if (SystemClock.uptimeMillis() < until) {
            later(() -> confirm(st, way, until), POLL);
            return;
        }
        if (stillThere(st.item)) {
            press(st, way + 1);
        } else if (!st.reopened && f != null && way < 2) {
            // The list closed without the choice: opened again, and the next way on its line.
            st.reopened = true;
            st.pressFrom = way + 1;
            st.before = Form.wordsNear(f);
            open(st, 0);
        } else {
            press(st, 3);
        }
    }

    private static boolean stillThere(AccessibilityNodeInfo n) {
        try {
            return n != null && n.refresh();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A real tap on it where it really is (brought into view first when it isn't). */
    private void tapReal(AccessibilityNodeInfo n, Runnable then) {
        Rect r = Nodes.realRect(n);
        if (Nodes.onScreen(s, r)) {
            Nodes.tapAt(s, r.centerX(), r.centerY());
            later(then, 60);
            return;
        }
        n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
        later(() -> {
            try {
                n.refresh();
            } catch (RuntimeException ignored) {
            }
            Rect r2 = Nodes.realRect(n);
            if (Nodes.onScreen(s, r2)) Nodes.tapAt(s, r2.centerX(), r2.centerY());
            later(then, 60);
        }, 350);
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
