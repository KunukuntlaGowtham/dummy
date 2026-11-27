package com.kunukuntla.ttdfiller;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One fill of the page in front from one booking. Pilgrim by pilgrim: Name and Age typed in,
 * Gender and Photo ID Proof opened and the option clicked, then the Photo Id Number typed once
 * the page unlocks it. Boxes already holding the right value are left alone (so a second press
 * only fixes what is wrong). At the end every box is read back; whatever didn't take is done
 * once more (typed by paste this time), and what still isn't right is reported.
 */
final class Filler {

    interface Listener {
        void progress(String text);

        void finished(boolean ok, String report);
    }

    private static final class Step {
        final String title;
        final int pilgrim, slot;
        final String contact;
        final String want;
        final String[] options;
        final boolean list;
        boolean retry, impossible;
        String problem;
        // the list's state while this step runs
        boolean opened, shown;
        long openedAt;
        int opens;
        /** What the screen showed before the list was opened (its new lines are the options). */
        java.util.Set<String> before;

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
        Form f = Form.read(s);
        if (f.pilgrims.isEmpty()) {
            end(false, "✗ No pilgrim boxes on this screen.\nOpen the Pilgrim Details page, then press 📝 Fill.");
            return;
        }
        List<Booking.Person> ps = booking.people();
        boxes = f.pilgrims.size();
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
            plan.add(new Step(who + "Name", i, Form.NAME, null, Match.name(p.name), null, false));
            String age = Match.age(p.age);
            if (!age.isEmpty()) plan.add(new Step(who + "Age", i, Form.AGE, null, age, null, false));
            else notes.add(who + "has no age in ttd_db");
            if (!p.gender.isEmpty()) {
                plan.add(new Step(who + "Gender", i, Form.GENDER, null, p.gender, Match.genders(p.gender), true));
            } else {
                notes.add(who + "has no gender in ttd_db");
            }
            String proof = Match.proofName(p.idType);
            plan.add(new Step(who + "ID proof", i, Form.PROOF, null, proof, Match.proofs(proof), true));
            String id = Match.idNumber(p.idNumber);
            if (!id.isEmpty()) plan.add(new Step(who + "ID number", i, Form.IDNUM, null, id, null, false));
            else notes.add(who + "has no ID number in ttd_db");
        }
        for (String key : Booking.CONTACTS) {
            AccessibilityNodeInfo n = f.contact.get(key);
            String v = Match.contact(key, booking.s(key));
            if (n == null || v.isEmpty()) continue;
            boolean list = n.isEnabled() && !Form.typable(n);
            String title = Character.toUpperCase(key.charAt(0)) + key.substring(1);
            plan.add(new Step(title, -1, -1, key, v, list ? new String[]{v} : null, list));
        }
        out.progress("Filling " + filledPeople + " pilgrim" + (filledPeople == 1 ? "" : "s") + "…");
        run(new ArrayList<>(plan), () -> later(() -> verify(0), 250));
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
        out.progress(st.title + "…");
        st.opened = false;
        st.shown = false;
        st.opens = 0;
        st.before = null;
        if (st.list) choose(st, 0);
        else type(st, 0);
    }

    private void next() {
        at++;
        later(this::step, 15);
    }

    private AccessibilityNodeInfo node(Form f, Step st) {
        if (st.contact != null) return f.contact.get(st.contact);
        return Form.box(f, st.pilgrim, st.slot);
    }

    private AccessibilityNodeInfo node(Step st) {
        return node(Form.read(s), st);
    }

    // ---- a box typed into ----

    private void type(Step st, int tries) {
        AccessibilityNodeInfo n = node(st);
        if (n == null) {
            if (pageGone(tries)) return;
            if (tries < 15) {
                later(() -> type(st, tries + 1), 100);
                return;
            }
            st.problem = "box not found";
            next();
            return;
        }
        if (!n.isEnabled()) {
            // The ID number unlocks once a proof is chosen: up to 3 s.
            if (tries < 40) {
                later(() -> type(st, tries + 1), 80);
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
        if (!done) st.problem = "the page didn't take the text";
        next();
    }

    /** The pilgrim boxes all went (another page came): stopped at once rather than box by box. */
    private boolean pageGone(int tries) {
        if (tries < 4 || !Form.read(s).pilgrims.isEmpty()) return false;
        end(false, "✗ The pilgrim page went away - stopped. Open it again and press 📝 Fill.");
        return true;
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
        AccessibilityNodeInfo f = node(st);
        if (f == null) {
            if (pageGone(tries)) return;
            if (tries < 15) {
                later(() -> choose(st, tries + 1), 100);
                return;
            }
            st.problem = "box not found";
            next();
            return;
        }
        if (Match.fits(Form.shown(f), st.options)) {
            st.problem = null;
            if (st.opened) waitFor(() -> Form.options(s, null).isEmpty(), 700, gone -> next());
            else next();
            return;
        }
        List<AccessibilityNodeInfo> items = Form.options(s, f);
        if (st.opened && !items.isEmpty()) {
            List<String> texts = Form.labels(items);
            int pick = Match.best(texts, st.options);
            if (pick < 0) {
                st.problem = "\"" + st.want + "\" isn't in its list (" + String.join(" | ", texts) + ")";
                st.impossible = true;
                Nodes.click(s, f); // the list closed again
                later(this::next, 250);
                return;
            }
            Nodes.clickOption(s, items.get(pick));
            chosen(st, texts.get(pick));
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (st.opened && items.isEmpty() && st.before != null && now - st.openedAt > 350) {
            // A list whose lines the page doesn't mark as options: the matching words that came with it.
            List<AccessibilityNodeInfo> loose = Form.newWords(s, st.before, st.options);
            if (!loose.isEmpty()) {
                List<String> texts = Form.labels(loose);
                int pick = Match.best(texts, st.options);
                if (pick >= 0) {
                    Nodes.click(s, loose.get(pick));
                    chosen(st, texts.get(pick));
                    return;
                }
            }
        }
        if (tries >= 35) {
            st.problem = "its list didn't open";
            next();
            return;
        }
        if (!st.opened || items.isEmpty() && now - st.openedAt > 800 && st.opens < 3) {
            if (!st.shown && !f.isVisibleToUser()) {
                // Out of view: brought on screen first (the list opens where the box is).
                st.shown = true;
                f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                later(() -> choose(st, tries + 1), 220);
                return;
            }
            // One click opens it (a second would close it): a real tap only if that didn't open it.
            if (st.opens == 0) st.before = Form.signatures(s);
            if (st.opens == 0 || !Nodes.tapOn(s, f)) Nodes.click(s, f);
            st.opened = true;
            st.openedAt = now;
            st.opens++;
        }
        later(() -> choose(st, tries + 1), 70);
    }

    /** An option was clicked: until the box shows it (1.5 s), then until the list has closed. */
    private void chosen(Step st, String text) {
        String[] chosen = {text};
        waitFor(() -> {
            AccessibilityNodeInfo g = node(st);
            return g != null && Match.fits(Form.shown(g), chosen);
        }, 1500, ok -> {
            st.problem = ok ? null : "chose \"" + text + "\" but the box didn't take it";
            waitFor(() -> Form.options(s, null).isEmpty(), 700, gone -> next());
        });
    }

    // ---- read back, once more, report ----

    private void verify(int round) {
        if (!running) return;
        Form f = Form.read(s);
        List<Step> bad = new ArrayList<>();
        for (Step st : plan) {
            if (st.impossible) continue;
            AccessibilityNodeInfo n = node(f, st);
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
            run(bad, () -> later(() -> verify(1), 300));
            return;
        }
        finish(f);
    }

    private void finish(Form f) {
        List<String> problems = new ArrayList<>();
        for (Step st : plan) if (st.problem != null) problems.add(st.title + ": " + st.problem);
        double secs = (SystemClock.uptimeMillis() - started) / 1000.0;
        boolean ok = problems.isEmpty();
        StringBuilder r = new StringBuilder();
        r.append(ok ? "✅ " : "⚠ ").append("Filled ").append(filledPeople).append(" of ").append(boxes)
                .append(" pilgrim").append(boxes == 1 ? "" : "s").append(" from ").append(booking.code())
                .append(String.format(java.util.Locale.ROOT, " in %.1f s", secs));
        for (String p : problems) r.append("\n✗ ").append(p);
        for (String n : notes) r.append("\n• ").append(n);
        if (pressContinue) {
            if (!ok || people < boxes) {
                r.append("\n➡ Continue not pressed (fix the boxes above first)");
            } else if (f.continueButton != null && f.continueButton.isEnabled()) {
                Nodes.click(s, f.continueButton);
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

    // ---- timing ----

    private void waitFor(Supplier<Boolean> test, long ms, Consumer<Boolean> then) {
        boolean ok;
        try {
            ok = test.get();
        } catch (RuntimeException e) {
            ok = false;
        }
        if (ok || ms <= 0) {
            then.accept(ok);
            return;
        }
        later(() -> waitFor(test, ms - 60, then), 60);
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
