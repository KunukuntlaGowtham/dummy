package com.kunukuntla.ttdfiller;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The pilgrim details form on the screen in front. Each pilgrim on the TTD page is five boxes,
 * each with its name drawn in it: Name, Age, Gender (a list), Photo ID Proof (a list) and Photo Id
 * Number (locked until a proof is chosen). The boxes are found by those names, in page order;
 * every "Name" starts the next pilgrim. Boxes scrolled out of view count too.
 */
final class Form {

    static final int NAME = 0, AGE = 1, GENDER = 2, PROOF = 3, IDNUM = 4, SLOTS = 5;
    static final String[] SLOT_NAMES = {"Name", "Age", "Gender", "Photo ID Proof", "Photo Id Number"};

    /** Each pilgrim's boxes, by slot (null where the page has none). */
    final List<AccessibilityNodeInfo[]> pilgrims = new ArrayList<>();
    /** Contact boxes the page also has (mobile, email, gothram, city, state, country, district, pincode). */
    final Map<String, AccessibilityNodeInfo> contact = new LinkedHashMap<>();
    AccessibilityNodeInfo continueButton;

    static Form read(AccessibilityService s) {
        Form f = new Form();
        AccessibilityNodeInfo[] cur = null;
        for (AccessibilityNodeInfo n : Nodes.all(s)) {
            if (Nodes.isButton(n) && f.continueButton == null && Match.norm(Nodes.label(n)).equals("continue")) {
                f.continueButton = n;
                continue;
            }
            if (!Nodes.isField(n)) continue;
            String label = labelOf(n);
            int slot = slotOf(label);
            if (slot >= 0) {
                if (slot == NAME || cur == null || cur[slot] != null) {
                    cur = new AccessibilityNodeInfo[SLOTS];
                    f.pilgrims.add(cur);
                }
                cur[slot] = n;
                continue;
            }
            String key = contactOf(label);
            if (key != null && !f.contact.containsKey(key)) f.contact.put(key, n);
        }
        // A pilgrim is a name with at least one more of its boxes (a lone "Name" is something else).
        for (int i = f.pilgrims.size() - 1; i >= 0; i--) {
            AccessibilityNodeInfo[] p = f.pilgrims.get(i);
            int have = 0;
            for (AccessibilityNodeInfo x : p) if (x != null) have++;
            if (p[NAME] == null || have < 2) f.pilgrims.remove(i);
        }
        return f;
    }

    static AccessibilityNodeInfo box(Form f, int pilgrim, int slot) {
        return pilgrim < f.pilgrims.size() ? f.pilgrims.get(pilgrim)[slot] : null;
    }

    // ---- which box is which ----

    static int slotOf(String label) {
        String l = Match.squash(label);
        if (l.isEmpty()) return -1;
        if (l.contains("idnumber") || l.contains("idno") || l.contains("proofnumber") || l.contains("proofno")
                || l.equals("aadhaarnumber") || l.equals("cardnumber")) return IDNUM;
        if (l.contains("proof") || l.contains("idtype") || l.equals("photoid") || l.contains("typeofid")) return PROOF;
        if (l.equals("gender") || l.equals("sex")) return GENDER;
        if (l.equals("age") || l.startsWith("ageinyears") || l.equals("ageyears")) return AGE;
        if (l.equals("name") || l.equals("fullname") || l.equals("pilgrimname") || l.equals("nameofthepilgrim")
                || l.equals("devoteename")) return NAME;
        return -1;
    }

    static String contactOf(String label) {
        String l = Match.squash(label);
        if (l.isEmpty()) return null;
        if (l.contains("mobile") || l.contains("phone")) return "mobile";
        if (l.contains("email")) return "email";
        if (l.contains("gothram") || l.contains("gotram") || l.contains("gotra")) return "gothram";
        if (l.contains("pincode") || l.equals("pin") || l.contains("postalcode") || l.equals("zipcode")) return "pincode";
        if (l.equals("country")) return "country";
        if (l.equals("state")) return "state";
        if (l.equals("district")) return "district";
        if (l.equals("city") || l.equals("town") || l.equals("citytown") || l.equals("village")) return "city";
        return null;
    }

    // ---- a box's name ----

    /**
     * The name drawn in the box: on the TTD page a "labelText" beside the field inside the same
     * container (its "*" left out). Only a container holding just this one field is looked in,
     * so a neighbour's name is never taken. Else what the page reports as its hint or name.
     */
    static String labelOf(AccessibilityNodeInfo field) {
        AccessibilityNodeInfo box = field.getParent();
        for (int up = 0; box != null && up < 2; up++) {
            if (fieldsIn(box, 0) > 1) break;
            String[] first = {""};
            String t = findLabel(box, field, first, 0);
            if (t != null && !t.isEmpty()) return t;
            if (!first[0].isEmpty()) return first[0];
            box = box.getParent();
        }
        CharSequence h = field.getHintText();
        if (h != null && Match.useful(h.toString())) return clean(h.toString());
        AccessibilityNodeInfo by = field.getLabeledBy();
        if (by != null && Match.useful(Nodes.label(by))) return clean(Nodes.label(by));
        CharSequence d = field.getContentDescription();
        return d == null ? "" : clean(d.toString());
    }

    private static int fieldsIn(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 6) return 0;
        int c = Nodes.isField(n) ? 1 : 0;
        for (int i = 0; i < n.getChildCount() && c < 2; i++) c += fieldsIn(n.getChild(i), depth + 1);
        return c;
    }

    /** A labelText's words when there is one; the first other words go into first[0]. */
    private static String findLabel(AccessibilityNodeInfo n, AccessibilityNodeInfo field, String[] first, int depth) {
        if (n == null || depth > 6 || n.equals(field)) return null;
        if (Nodes.role(n).contains("labeltext")) {
            String t = wordsOf(n, 0);
            if (Match.useful(t)) return t;
        }
        String own = clean(Nodes.label(n));
        if (first[0].isEmpty() && Match.useful(own) && !Nodes.isField(n) && !Nodes.cls(n).startsWith("android.widget.Image")) {
            first[0] = own;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            String r = findLabel(n.getChild(i), field, first, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private static String wordsOf(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 4) return "";
        String own = clean(Nodes.label(n));
        if (!own.isEmpty()) return own;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.getChildCount(); i++) {
            String t = wordsOf(n.getChild(i), depth + 1);
            if (!t.isEmpty()) b.append(b.length() > 0 ? " " : "").append(t);
        }
        return b.toString().trim();
    }

    private static String clean(String s) {
        return s.replace("*", "").replaceAll("\\s+", " ").trim();
    }

    // ---- a box's value ----

    /** What the box holds now ("" when empty - a hint the page shows in it is not a value). */
    static String value(AccessibilityNodeInfo n) {
        if (n == null) return "";
        CharSequence t = n.getText();
        String v = t == null ? "" : t.toString().trim();
        CharSequence h = n.getHintText();
        if (h != null && v.equals(h.toString().trim())) return "";
        return v;
    }

    /**
     * What a list box shows as chosen: its own value, else the words drawn beside it in its
     * container (some pages show the choice there and keep the box itself empty). Its name
     * ("Gender") is not a choice.
     */
    static String shown(AccessibilityNodeInfo field) {
        if (field == null) return "";
        String v = value(field);
        String name = Match.squash(labelOf(field));
        if (!v.isEmpty() && !Match.squash(v).equals(name)) return v;
        AccessibilityNodeInfo box = field.getParent();
        if (box == null || fieldsIn(box, 0) > 1) return "";
        for (int i = 0; i < box.getChildCount(); i++) {
            AccessibilityNodeInfo c = box.getChild(i);
            if (c == null || c.equals(field)) continue;
            // Only a line of words (the TTD page keeps one under each box), never the list or the label.
            boolean words = Nodes.role(c).equals("statictext") || Nodes.cls(c).endsWith("TextView") && Nodes.role(c).isEmpty();
            if (!words || c.isClickable()) continue;
            String t = clean(Nodes.label(c));
            if (Match.useful(t) && !Match.squash(t).equals(name)) return t;
        }
        return "";
    }

    /** It can be typed into (else it is a list to choose from). */
    static boolean typable(AccessibilityNodeInfo n) {
        if (n.isEditable()) return true;
        for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
            if (a.getId() == AccessibilityNodeInfo.ACTION_SET_TEXT) return true;
        }
        return false;
    }

    // ---- an open list: looked for next to its box (the TTD list opens inside the box's own container) ----

    /** The box's own container, read fresh (its list is added there when it opens). */
    static AccessibilityNodeInfo container(AccessibilityNodeInfo field) {
        AccessibilityNodeInfo b = field.getParent();
        if (b == null) return null;
        try {
            b.refresh();
        } catch (RuntimeException ignored) {
        }
        return b;
    }

    /** Everything inside the box's container (the box itself left out). */
    static List<AccessibilityNodeInfo> near(AccessibilityNodeInfo field) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        AccessibilityNodeInfo b = container(field);
        if (b == null) return out;
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        for (int i = b.getChildCount() - 1; i >= 0; i--) stack.add(b.getChild(i));
        while (!stack.isEmpty() && out.size() < 300) {
            AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
            if (n == null || n.equals(field)) continue;
            out.add(n);
            for (int i = n.getChildCount() - 1; i >= 0; i--) stack.add(n.getChild(i));
        }
        return out;
    }
    /** The words next to the box now, to tell later which came with its list. */
    static java.util.Set<String> wordsNear(AccessibilityNodeInfo field) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (AccessibilityNodeInfo n : near(field)) {
            String l = Nodes.label(n);
            if (!l.isEmpty()) out.add(signature(n, l));
        }
        return out;
    }

    // Not where it is: the page reports places wrong on this screen.
    private static String signature(AccessibilityNodeInfo n, String label) {
        return label + "|" + Nodes.cls(n) + "|" + Nodes.role(n);
    }
    /** List lines anywhere on the page (a list the page draws away from its box). */
    static List<AccessibilityNodeInfo> optionsAll(AccessibilityService s) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : Nodes.all(s)) if (Nodes.isOption(n) && !Nodes.label(n).isEmpty()) out.add(n);
        return out;
    }

    /** Words among {@code near} that weren't there in {@code before} and fit one of {@code wants}. */
    static List<AccessibilityNodeInfo> newFitting(List<AccessibilityNodeInfo> near, java.util.Set<String> before, String[] wants) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : near) {
            if (Nodes.isField(n)) continue;
            String l = Nodes.label(n);
            if (l.isEmpty() || before.contains(signature(n, l)) || !Match.fits(l, wants)) continue;
            out.add(n);
        }
        return out;
    }

    /** The choice a list box shows: its value, else the line under it ({@code name}: the box's own name, not a choice). */
    static String chosen(AccessibilityNodeInfo field, String name) {
        if (field == null) return "";
        String v = value(field);
        if (!v.isEmpty() && !Match.squash(v).equals(name)) return v;
        return shown(field);
    }

    /** Something else to press that may open the list: a clickable part of its container, else its arrow. */
    static AccessibilityNodeInfo opener(AccessibilityNodeInfo field) {
        AccessibilityNodeInfo icon = null;
        for (AccessibilityNodeInfo n : near(field)) {
            if (Nodes.isField(n)) continue;
            if (n.isClickable()) return n;
            if (icon == null && Match.squash(Nodes.label(n)).contains("dropdown")) icon = n;
        }
        if (icon != null) return icon;
        return field.getParent();
    }

    /** What is next to the box, short (for the report when its list can't be worked). */
    static String around(AccessibilityNodeInfo field) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (AccessibilityNodeInfo x : near(field)) {
            String l = Nodes.label(x), r = Nodes.role(x);
            if (l.isEmpty() && !x.isClickable() && !Nodes.isOption(x)) continue;
            if (++n > 14) {
                b.append(" …");
                break;
            }
            String c = Nodes.cls(x);
            b.append(n > 1 ? "; " : "").append(r.isEmpty() ? c.substring(c.lastIndexOf('.') + 1) : r);
            if (!l.isEmpty()) b.append(" \"").append(l.length() > 24 ? l.substring(0, 24) + "…" : l).append('"');
            if (x.isClickable()) b.append(" clk");
        }
        return n == 0 ? "nothing next to it" : b.toString();
    }

    static List<String> labels(List<AccessibilityNodeInfo> nodes) {
        List<String> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : nodes) out.add(Nodes.label(n));
        return out;
    }

}
