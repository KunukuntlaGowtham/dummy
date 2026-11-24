package com.kunukuntla.a11yinspector;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Who is to be deleted and what became of each, kept on the phone (survives the service being
 * restarted and a delete that stopped half way). One line per person:
 * pending (Tick didn't add them), deleted ✓, or failed ✗ with the reason.
 * <p>
 * A later 🗑 with nothing new from Tick deletes the people still pending / failed - found by their
 * NAME (their number has moved on once others were deleted). A row whose name couldn't be read
 * is listed as "by hand": it is never deleted by a number that may now be someone else.
 */
final class DeleteLedger {

    static final String PENDING = "pending", DELETED = "deleted", FAILED = "failed";
    private static final int KEEP = 120;
    private static final String PREFS = "ledger", KEY = "list";

    static final class Entry {
        int row;           // as Tick numbered it on page 3 (-1: not known)
        String name;       // page 3's name, or "" when it couldn't be read
        String state;
        String why = "";
        long at;

        Entry(int row, String name, String state, long at) {
            this.row = row;
            this.name = name == null ? "" : name;
            this.state = state;
            this.at = at;
        }
    }

    private DeleteLedger() {
    }

    // ---- pure (tested) ------------------------------------------------------------------

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    static String format(List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) {
            sb.append(e.state).append('\t').append(e.row).append('\t').append(e.at).append('\t')
                    .append(clean(e.name)).append('\t').append(clean(e.why)).append('\n');
        }
        return sb.toString();
    }

    static List<Entry> parse(String text) {
        List<Entry> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 5) continue;
            try {
                Entry e = new Entry(Integer.parseInt(f[1]), f[3], f[0], Long.parseLong(f[2]));
                e.why = f[4];
                out.add(e);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /**
     * Tick's rows not added become the pending list. The earlier pending / failed ones are
     * replaced (a new Tick is a new look at the page); what was already deleted stays as history.
     */
    static List<Entry> withMissed(List<Entry> old, List<Integer> rows, List<String> names, long now) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : old) if (DELETED.equals(e.state)) out.add(e);
        for (int i = 0; i < rows.size(); i++) {
            out.add(new Entry(rows.get(i), i < names.size() ? names.get(i) : "", PENDING, now));
        }
        while (out.size() > KEEP) out.remove(0);
        return out;
    }

    /** The result for one person: found by name (the same person), else by the row. */
    static boolean mark(List<Entry> list, String name, int row, boolean ok, String why, long now) {
        Entry hit = null;
        if (name != null && !name.isEmpty()) {
            for (Entry e : list) {
                if (!DELETED.equals(e.state) && !e.name.isEmpty() && DeleteRun.sameName(e.name, name)) {
                    hit = e;
                    break;
                }
            }
        }
        if (hit == null) {
            for (Entry e : list) {
                if (!DELETED.equals(e.state) && e.row == row) {
                    hit = e;
                    break;
                }
            }
        }
        if (hit == null) {
            hit = new Entry(row, name, PENDING, now);
            list.add(hit);
        }
        if (hit.name.isEmpty() && name != null) hit.name = name;
        hit.state = ok ? DELETED : FAILED;
        hit.why = ok ? "" : clean(why);
        hit.at = now;
        return true;
    }

    /** The people still to delete: not deleted, with a readable name. */
    static List<Entry> todo(List<Entry> list) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) if (!DELETED.equals(e.state) && !e.name.isEmpty()) out.add(e);
        return out;
    }

    /** Pending / failed rows whose name couldn't be read: left for you. */
    static List<Entry> byHand(List<Entry> list) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) if (!DELETED.equals(e.state) && e.name.isEmpty()) out.add(e);
        return out;
    }

    static String text(List<Entry> list) {
        if (list.isEmpty()) return "Nothing saved yet.";
        StringBuilder sb = new StringBuilder();
        java.text.SimpleDateFormat t = new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.ROOT);
        for (int i = list.size() - 1; i >= 0; i--) {
            Entry e = list.get(i);
            String who = e.name.isEmpty() ? "row " + (e.row < 0 ? "?" : e.row) + " (name not read - by hand)" : e.name;
            String mark = DELETED.equals(e.state) ? "🗑 deleted ✓" : FAILED.equals(e.state) ? "✗ not deleted" : "⏳ to delete";
            sb.append(mark).append("  ").append(who);
            if (!e.why.isEmpty()) sb.append("  - ").append(e.why);
            sb.append("  (").append(t.format(new java.util.Date(e.at))).append(")\n");
        }
        return sb.toString().trim();
    }

    // ---- on the phone ---------------------------------------------------------------------

    static List<Entry> load(Context c) {
        return parse(c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, ""));
    }

    private static void save(Context c, List<Entry> list) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, format(list)).apply();
    }

    static void saveMissed(Context c, List<Integer> rows, List<String> names) {
        save(c, withMissed(load(c), rows, names, System.currentTimeMillis()));
    }

    static void result(Context c, String name, int row, boolean ok, String why) {
        List<Entry> l = load(c);
        mark(l, name, row, ok, why, System.currentTimeMillis());
        save(c, l);
    }

    static void clear(Context c) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
