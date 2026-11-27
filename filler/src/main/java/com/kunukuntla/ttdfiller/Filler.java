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
import java.util.Set;

/**
 * One fill of the page in front from one booking, as fast as the page takes it:
 * <ol>
 * <li>every pilgrim's Name and Age typed in at once;</li>
 * <li>each pilgrim's Gender and Photo ID Proof chosen in their lists;</li>
 * <li>the Photo Id Numbers typed (unlocked by then).</li>
 * </ol>
 * A list is opened by an accessibility click - never by scrolling the page first: on the TTD
 * page every box reports as out of view, and a scroll closes an open list. When that doesn't
 * open it, a real tap on the box; then a click on its arrow. The option is clicked the same
 * way, and a real tap when the box didn't take it. Each box is kept and refreshed, not
 * looked up in the whole page again. Boxes already right are left alone; at the end
 * everything is read back, what didn't take is done once more, and the rest is reported.
 */
final class Filler {

    interface Listener {
        void progress(String text);

        void finished(boolean ok, String report);
    }

    private static final long POLL = 40;

    private static final class Step {
        final String title;
        final int pilgrim, slot;
        final String contact;
        final String want;
        final String[] options;
        final boolean list;
        AccessibilityNodeInfo box;
        String problem, detail;
        /** Can't be done on this page (not in the list, the list won't open): not tried again. */
        boolean hopeless;
        boolean retry;
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
    private final List<String> notes = new ArrayList<>();
    private List<Step> list = new ArrayList<>();
    private int at;
    private Runnable after;
    private long started;
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

    void start() {
        running = true;
        gen++;
        started = SystemClock.uptimeMillis();
        form = Form.read(s);
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
        List<Step> typed = new ArrayList<>(), lists = new ArrayList<>(), ids = new ArrayList<>();
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
        plan.addAll(typed);
        plan.addAll(lists);
        plan.addAll(ids);
        // Every box taken from this one read of the page (each is only refreshed from now on).
        for (Step st : plan) st.box = Form.box(form, st.pilgrim, st.slot);
        for (String key : Booking.CONTACTS) {
            AccessibilityNodeInfo n = form.contact.get(key);
            String v = Match.contact(key, booking.s(key));
            if (n == null || v.isEmpty()) continue;
            boolean isList = n.isEnabled() && !Form.typable(n);
            String title = Character.toUpperCase(key.charAt(0)) + key.substring(1);
            Step c = new Step(title, -1, -1, key, v, isList ? new String[]{v} : null, isList);
            c.box = n;
            plan.add(c);
        }
        out.progress("Filling " + filledPeople + " pilgrim" + (filledPeople == 1 ? "" : "s") + "…");
        run(new ArrayList<>(plan), () -> later(() -> verify(0), 120));
    }

    void cancel() {
        if (!running) return;
        end(false, "⏹ Stopped");
    }

    // ---- the steps, one after another ----

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
        st.item = null;
        st.reopened = false;
        st.pressFrom = 0;
        if (st.list) {
            out.progress(st.title + "…");
            choose(st, 0);
        } else {
            type(st, 0);
        }
    }

    private void next() {
        at++;
        h.post(() -> {
            if (running) step();
        });
    }

    /** The step's box: the one kept (refreshed), else looked up again in the page. */
    private AccessibilityNodeInfo box(Step st) {
        if (st.box != null) {
            try {
                if (st.box.refresh()) return st.box;
            } catch (RuntimeException ignored) {
            }
        }
        form = Form.read(s);
        st.box = st.contact != null ? form.contact.get(st.contact) : Form.box(form, st.pilgrim, st.slot);
        return st.box;
    }

    /** The pilgrim boxes all went (another page came): stopped at once rather than box by box. */
    private boolean pageGone(int tries) {
        if (tries < 3 || !Form.read(s).pilgrims.isEmpty()) return false;
        end(false, "✗ The pilgrim page went away - stopped. Open it again and press 📝 Fill.");
        return true;
    }

    // ---- a box typed into ----

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
                st.hopeless = true;
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
        if (Match.same(Form.value(n), st.want)) {
            st.problem = null;
            next();
            return;
        }
        boolean done = st.retry ? paste(n, st.want) || setText(n, st.want) : setText(n, st.want) || paste(n, st.want);
        st.problem = done ? null : "the page didn't take the text";
        next();
    }

    private boolean proofFailed(Step idStep) {
        for (Step p : plan) {
            if (p.pilgrim == idStep.pilgrim && p.slot == Form.PROOF) return p.problem != null;
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

    // ---- a box chosen in its list ----

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
        if (Match.fits(Form.shown(f), st.options)) {
            st.problem = null;
            next();
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
            st.hopeless = true;
            next();
            return;
        }
        if (way > 0) out.progress(st.title + " (way " + (way + 1) + ")…");
        st.openedAt = SystemClock.uptimeMillis();
        st.polls = 0;
        if (way == 0) {
            if (!f.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                open(st, 1);
                return;
            }
            watch(st, way, st.openedAt + 450);
        } else if (way == 1) {
            tapReal(f, () -> {
                st.openedAt = SystemClock.uptimeMillis();
                watch(st, 1, st.openedAt + 600);
            });
        } else {
            f.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            AccessibilityNodeInfo o = Form.opener(f);
            if (o != null) Nodes.click(s, o);
            watch(st, way, st.openedAt + 600);
        }
    }

    /** Looks every 40 ms for the list's lines next to the box (now and then on the whole page). */
    private void watch(Step st, int way, long until) {
        AccessibilityNodeInfo f = box(st);
        if (f == null) {
            st.problem = "box not found";
            next();
            return;
        }
        if (Match.fits(Form.shown(f), st.options)) {
            st.problem = null;
            next();
            return;
        }
        st.polls++;
        List<AccessibilityNodeInfo> items = Form.options(s, f, st.polls % 5 == 4);
        if (!items.isEmpty()) {
            pick(st, items);
            return;
        }
        // Lines the page doesn't mark as options: the words that came next to the box.
        List<AccessibilityNodeInfo> fresh = Form.newWordsNear(f, st.before);
        if (!fresh.isEmpty()) {
            List<AccessibilityNodeInfo> fit = new ArrayList<>();
            for (AccessibilityNodeInfo n : fresh) if (Match.fits(Nodes.label(n), st.options)) fit.add(n);
            if (!fit.isEmpty()) {
                pick(st, fit);
                return;
            }
            // A list has two lines or more (one new line alone may be an error message under the box).
            if (fresh.size() >= 2 && SystemClock.uptimeMillis() - st.openedAt > 250) {
                notInList(st, f, Form.labels(fresh));
                return;
            }
        }
        long now = SystemClock.uptimeMillis();
        if (now < until) {
            later(() -> watch(st, way, until), POLL);
            return;
        }
        open(st, way + 1);
    }

    private void notInList(Step st, AccessibilityNodeInfo f, List<String> texts) {
        st.problem = "\"" + st.want + "\" isn't in its list (" + String.join(" | ", texts) + ")";
        st.hopeless = true;
        f.performAction(AccessibilityNodeInfo.ACTION_CLICK); // its list closed again
        later(this::next, 150);
    }

    private void pick(Step st, List<AccessibilityNodeInfo> items) {
        List<String> texts = Form.labels(items);
        int i = Match.best(texts, st.options);
        if (i < 0) {
            AccessibilityNodeInfo f = box(st);
            if (f != null) notInList(st, f, texts);
            else next();
            return;
        }
        st.item = items.get(i);
        st.itemText = texts.get(i);
        press(st, st.pressFrom);
    }

    /** Way 0: an accessibility click on the line; 1: a real tap on it; 2: focus, select, click. */
    private void press(Step st, int way) {
        if (way >= 3) {
            st.problem = "chose \"" + st.itemText + "\" but the box didn't take it";
            st.detail = st.box == null ? null : Form.around(st.box);
            next();
            return;
        }
        AccessibilityNodeInfo item = st.item;
        if (way == 0) {
            if (!item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                press(st, 1);
                return;
            }
            confirm(st, way, SystemClock.uptimeMillis() + 400);
        } else if (way == 1) {
            tapReal(item, () -> confirm(st, 1, SystemClock.uptimeMillis() + 450));
        } else {
            // Focused and selected first (some lists take the choice that way), then clicked.
            item.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            item.performAction(AccessibilityNodeInfo.ACTION_SELECT);
            Nodes.click(s, item);
            confirm(st, 2, SystemClock.uptimeMillis() + 450);
        }
    }

    /** Until the box shows the choice; if it doesn't, the next way on the same line (opened again when it closed). */
    private void confirm(Step st, int way, long until) {
        AccessibilityNodeInfo f = box(st);
        String[] chosen = {st.itemText};
        if (f != null && (Match.fits(Form.shown(f), chosen) || Match.fits(Form.shown(f), st.options))) {
            st.problem = null;
            next();
            return;
        }
        if (SystemClock.uptimeMillis() < until) {
            later(() -> confirm(st, way, until), POLL);
            return;
        }
        boolean stillOpen;
        try {
            stillOpen = st.item.refresh();
        } catch (RuntimeException e) {
            stillOpen = false;
        }
        if (stillOpen) {
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

    // ---- read back, once more, report ----

    private void verify(int round) {
        if (!running) return;
        form = Form.read(s);
        List<Step> bad = new ArrayList<>();
        for (Step st : plan) {
            if (st.hopeless) continue;
            st.box = st.contact != null ? form.contact.get(st.contact) : Form.box(form, st.pilgrim, st.slot);
            AccessibilityNodeInfo n = st.box;
            String v = st.list ? Form.shown(n) : Form.value(n);
            boolean good = n != null && (st.list ? Match.fits(v, st.options) : Match.same(v, st.want));
            if (good) {
                st.problem = null;
                continue;
            }
            if (st.problem == null) st.problem = n == null ? "box not found" : "shows \"" + v + "\"";
            bad.add(st);
        }
        if (!bad.isEmpty() && round == 0) {
            for (Step st : bad) st.retry = true;
            out.progress("Once more: " + bad.size() + " box" + (bad.size() == 1 ? "" : "es") + "…");
            run(bad, () -> later(() -> verify(1), 150));
            return;
        }
        finish();
    }

    private void finish() {
        List<String> problems = new ArrayList<>();
        for (Step st : plan) {
            if (st.problem == null) continue;
            problems.add(st.title + ": " + st.problem + (st.detail == null ? "" : "\n    [next to it: " + st.detail + "]"));
        }
        double secs = (SystemClock.uptimeMillis() - started) / 1000.0;
        boolean ok = problems.isEmpty();
        StringBuilder r = new StringBuilder();
        r.append(ok ? "✅ " : "⚠ ").append("Filled ").append(filledPeople).append(" of ").append(boxes)
                .append(" pilgrim").append(boxes == 1 ? "" : "s").append(" from ").append(booking.code())
                .append(String.format(java.util.Locale.ROOT, " in %.1f s", secs));
        for (String p : problems) r.append("\n✗ ").append(p);
        for (String n : notes) r.append("\n• ").append(n);
        if (pressContinue) {
            AccessibilityNodeInfo c = form.continueButton;
            if (!ok || people < boxes) {
                r.append("\n➡ Continue not pressed (fix the boxes above first)");
            } else if (c != null && c.isEnabled()) {
                if (!c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) Nodes.click(s, c);
                r.append("\n➡ Pressed Continue");
            } else {
                r.append("\n➡ Continue not found or not on yet");
            }
        }
        end(ok, r.toString());
    }

    private void end(boolean ok, String report) {
        running = false;
        gen++;
        out.finished(ok, report);
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
