package com.kunukuntla.ttdfiller;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The TTD Special Entry Darshan slot page ("Darshan Slots"), read from accessibility:
 * <ul>
 * <li>the calendar - each day a cell with the day as its words and "M/YYYY" as its id;</li>
 * <li>"Select any 1 Slot: Dated 22 Dec, 2026" - the date chosen;</li>
 * <li>"Number of Tickets" - a list box like the pilgrim page's;</li>
 * <li>the slot cards - "112" "Available" "Slot Time 1:00 pm" "1 Person" "₹200" and a round button;</li>
 * <li>Refresh, Continue; on the review page after the pilgrims, Pay Now.</li>
 * </ul>
 */
final class SlotPage {

    private SlotPage() {
    }

    private static final Pattern MONTH_ID = Pattern.compile("(\\d{1,2})/(\\d{4})");
    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug",
            "sep", "oct", "nov", "dec"};

    // ---- the calendar ----

    /** Every day cell shown: the cell and {day, month, year}. */
    static Map<AccessibilityNodeInfo, int[]> dayCells(List<AccessibilityNodeInfo> nodes) {
        Map<AccessibilityNodeInfo, int[]> out = new HashMap<>();
        for (AccessibilityNodeInfo n : nodes) {
            String id = n.getViewIdResourceName();
            String text = Nodes.label(n);
            if (id == null || !text.matches("\\d{1,2}")) continue;
            Matcher m = MONTH_ID.matcher(id);
            if (!m.find()) continue;
            out.put(n, new int[]{Integer.parseInt(text), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
        }
        return out;
    }

    /** The month most cells belong to (year*12 + month-1), -1 with no calendar. */
    static int shownMonth(Map<AccessibilityNodeInfo, int[]> cells) {
        Map<Integer, Integer> count = new HashMap<>();
        int best = -1, bestCount = 0;
        for (int[] d : cells.values()) {
            int k = d[2] * 12 + d[1] - 1;
            int c = count.merge(k, 1, Integer::sum);
            if (c > bestCount) {
                best = k;
                bestCount = c;
            }
        }
        return best;
    }

    static AccessibilityNodeInfo cell(Map<AccessibilityNodeInfo, int[]> cells, int[] date) {
        for (Map.Entry<AccessibilityNodeInfo, int[]> e : cells.entrySet()) {
            int[] d = e.getValue();
            if (d[0] == date[0] && d[1] == date[1] && d[2] == date[2]) return e.getKey();
        }
        return null;
    }

    /** The next (or previous) month arrow ("ic_chevron_right_circle" / "..._left_..."). */
    static AccessibilityNodeInfo arrow(List<AccessibilityNodeInfo> nodes, boolean forward) {
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isClickable()) continue;
            String l = Nodes.label(n).toLowerCase(Locale.ROOT);
            if (l.contains("back_arrow") || l.contains("back arrow")) continue;
            boolean right = l.contains("right") || l.contains("next") || l.equals("›") || l.equals(">");
            boolean left = l.contains("left") || l.contains("prev") || l.equals("‹") || l.equals("<");
            if (forward ? right : left) return n;
        }
        return null;
    }

    /** The date the page has chosen ("Select any 1 Slot: Dated 22 Dec, 2026"), or null. */
    static int[] chosenDate(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) {
            String l = Nodes.label(n);
            if (!l.toLowerCase(Locale.ROOT).contains("dated")) continue;
            int[] d = fullDate(l);
            if (d != null) return d;
        }
        return null;
    }

    static int[] fullDate(String s) {
        Matcher m = Pattern.compile("(\\d{1,2})\\s+([A-Za-z]{3,})\\.?,?\\s+(\\d{4})").matcher(s);
        if (m.find() && monthOf(m.group(2)) > 0) {
            return new int[]{Integer.parseInt(m.group(1)), monthOf(m.group(2)), Integer.parseInt(m.group(3))};
        }
        return null;
    }

    private static int monthOf(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.length() < 3) return 0;
        for (int i = 0; i < 12; i++) if (w.startsWith(MONTHS[i])) return i + 1;
        return 0;
    }

    // ---- the boxes and buttons ----

    /** The "Number of Tickets" box, or null. */
    static AccessibilityNodeInfo ticketsBox(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) {
            if (!Nodes.isField(n)) continue;
            String l = Match.squash(Form.labelOf(n));
            if (l.contains("ticket")) return n;
        }
        return null;
    }

    static AccessibilityNodeInfo button(List<AccessibilityNodeInfo> nodes, String words) {
        String w = Match.squash(words);
        for (AccessibilityNodeInfo n : nodes) {
            if (Nodes.isButton(n) && Match.squash(Nodes.label(n)).equals(w)) return n;
        }
        return null;
    }

    /** The review page's "No. of Tickets" (the number after those words), -1 when not shown. */
    static int reviewTickets(List<AccessibilityNodeInfo> nodes) {
        boolean after = false;
        for (AccessibilityNodeInfo n : nodes) {
            String l = Nodes.label(n);
            if (l.isEmpty()) continue;
            String q = Match.squash(l);
            if (q.equals("nooftickets") || q.equals("numberoftickets")) {
                after = true;
                continue;
            }
            if (after) return l.trim().matches("\\d{1,2}") ? Integer.parseInt(l.trim()) : -1;
        }
        return -1;
    }

    static boolean isSlotPage(List<AccessibilityNodeInfo> nodes) {
        return !dayCells(nodes).isEmpty();
    }

    // ---- the slot cards ----

    static final class Slot {
        AccessibilityNodeInfo card, round;
        /** Minutes after midnight the slot starts; -1 unknown. */
        int minutes = -1;
        /** Tickets left; -1 when the card shows no number. */
        int left = -1;
        String time = "", status = "";

        boolean full() {
            String st = status.toLowerCase(Locale.ROOT);
            return left == 0 || st.contains("full") || st.contains("not available") || st.contains("sold");
        }

        String describe() {
            return time + " (" + (left >= 0 ? left + " " : "") + (status.isEmpty() ? "?" : status.toLowerCase(Locale.ROOT)) + ")";
        }
    }

    private static final Pattern TIME = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*([ap])\\.?\\s*m", Pattern.CASE_INSENSITIVE);

    /** Every slot card on the page, in page order: a clickable box whose words hold "Slot Time …". */
    static List<Slot> slots(List<AccessibilityNodeInfo> nodes) {
        List<Slot> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isClickable() || Nodes.isButton(n) || Nodes.isField(n)) continue;
            Slot sl = null;
            List<String> words = new ArrayList<>();
            AccessibilityNodeInfo round = null;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c == null) continue;
                if (Nodes.isButton(c) && round == null) round = c;
                String l = Nodes.label(c);
                if (!l.isEmpty()) words.add(l);
            }
            for (String w : words) {
                if (!w.toLowerCase(Locale.ROOT).contains("slot time")) continue;
                Matcher m = TIME.matcher(w);
                sl = new Slot();
                sl.time = w.replaceAll("(?i)slot time", "").trim();
                if (m.find()) {
                    int h = Integer.parseInt(m.group(1)) % 12, mi = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                    boolean pm = m.group(3).equalsIgnoreCase("p");
                    sl.minutes = (pm ? 12 * 60 : 0) + h * 60 + mi;
                }
                break;
            }
            if (sl == null) continue;
            sl.card = n;
            sl.round = round;
            for (int i = 0; i < words.size(); i++) {
                String w = words.get(i);
                if (sl.left < 0 && w.matches("\\d{1,6}")) {
                    sl.left = Integer.parseInt(w);
                    if (i + 1 < words.size()) sl.status = words.get(i + 1);
                }
                String lw = w.toLowerCase(Locale.ROOT);
                if (sl.status.isEmpty() && (lw.contains("available") || lw.contains("full") || lw.contains("fast"))) sl.status = w;
            }
            out.add(sl);
        }
        return out;
    }

    /**
     * The slot to book: starting from {@code fromH}:00 to {@code toH}:00, with {@code need} tickets
     * left (or no number shown), not full; the most tickets left (or the earliest). {@code any}:
     * when none in those hours fits, the best of the others. Null when none fits.
     */
    static Slot best(List<Slot> slots, int need, int fromH, int toH, boolean earliest, boolean any) {
        Slot pick = choose(slots, need, fromH * 60, toH * 60, earliest);
        if (pick == null && any) pick = choose(slots, need, 0, 24 * 60, earliest);
        return pick;
    }

    private static Slot choose(List<Slot> slots, int need, int from, int to, boolean earliest) {
        Slot best = null;
        for (Slot s : slots) {
            if (s.full() || s.left >= 0 && s.left < need) continue;
            if (s.minutes < 0 || s.minutes < from || s.minutes > to) continue;
            if (best == null) {
                best = s;
            } else if (earliest ? s.minutes < best.minutes : s.left > best.left) {
                best = s;
            }
        }
        return best;
    }

    // ---- the dates you asked for ----

    /** "22/12/2026, 23/12" → [{22,12,2026}, {23,12,year}] in your order (a date without a year: the next one to come). */
    static List<int[]> parseDates(String text, long nowMs) {
        List<int[]> out = new ArrayList<>();
        java.util.Calendar now = java.util.Calendar.getInstance();
        now.setTimeInMillis(nowMs);
        Matcher m = Pattern.compile("(\\d{1,2})\\s*[/.-]\\s*(\\d{1,2})(?:\\s*[/.-]\\s*(\\d{2,4}))?").matcher(text == null ? "" : text);
        while (m.find()) {
            int d = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2));
            if (d < 1 || d > 31 || mo < 1 || mo > 12) continue;
            int y;
            if (m.group(3) != null) {
                y = Integer.parseInt(m.group(3));
                if (y < 100) y += 2000;
            } else {
                y = now.get(java.util.Calendar.YEAR);
                int today = (now.get(java.util.Calendar.MONTH) + 1) * 100 + now.get(java.util.Calendar.DAY_OF_MONTH);
                if (mo * 100 + d < today) y++;
            }
            out.add(new int[]{d, mo, y});
        }
        return out;
    }

    static String text(int[] d) {
        return d[0] + "/" + d[1] + "/" + d[2];
    }
}
