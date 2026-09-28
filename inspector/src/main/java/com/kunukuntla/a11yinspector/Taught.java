package com.kunukuntla.a11yinspector;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What you showed the app by tapping it yourself (Teach): the dustbin, and each pop-up's
 * button. For each: where it is, how it looks, the page's button there (if the page reports
 * one) and, for a pop-up, what it adds to the page - so Tick and Del can press them straight
 * away, watching the page instead of taking screenshots.
 */
final class Taught {

    static final String TICK_POPUP = "tick_popup";
    static final String DEL_BIN = "del_bin";
    static final String DEL_POPUP_1 = "del_popup_1";
    static final String DEL_POPUP_2 = "del_popup_2";

    static final class Button {
        /** Where it was tapped: a small box around the point. */
        Rect spot;
        /** How the spot looked with the button up, and after it was tapped (it went). */
        double[] look, gone;
        /** The page's elements that were only there while it was up (a pop-up). */
        Set<String> shapes = new HashSet<>();
        /** The page's own button under the point, if any: its kind and name. */
        String cls = "", label = "";
        /** For the dustbin: how far below its row's number (row numbers line up with it). */
        int dy;
        boolean hasDy;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("spot", spot.flattenToString());
            if (look != null) o.put("look", arr(look));
            if (gone != null) o.put("gone", arr(gone));
            o.put("shapes", new JSONArray(shapes));
            o.put("cls", cls);
            o.put("label", label);
            o.put("dy", dy);
            o.put("hasDy", hasDy);
            return o;
        }

        static Button fromJson(JSONObject o) throws JSONException {
            Button b = new Button();
            b.spot = Rect.unflattenFromString(o.getString("spot"));
            if (b.spot == null) return null;
            if (o.has("look")) b.look = doubles(o.getJSONArray("look"));
            if (o.has("gone")) b.gone = doubles(o.getJSONArray("gone"));
            JSONArray s = o.optJSONArray("shapes");
            if (s != null) for (int i = 0; i < s.length(); i++) b.shapes.add(s.getString(i));
            b.cls = o.optString("cls");
            b.label = o.optString("label");
            b.dy = o.optInt("dy");
            b.hasDy = o.optBoolean("hasDy");
            return b;
        }

        private static JSONArray arr(double[] d) throws JSONException {
            JSONArray a = new JSONArray();
            for (double v : d) a.put(Math.round(v * 10) / 10.0);
            return a;
        }

        private static double[] doubles(JSONArray a) throws JSONException {
            double[] d = new double[a.length()];
            for (int i = 0; i < d.length; i++) d[i] = a.getDouble(i);
            return d;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("taught", Context.MODE_PRIVATE);
    }

    static Button get(Context c, String what) {
        String s = prefs(c).getString(what, null);
        if (s == null) return null;
        try {
            return Button.fromJson(new JSONObject(s));
        } catch (JSONException e) {
            return null;
        }
    }

    static void put(Context c, String what, Button b) {
        try {
            prefs(c).edit().putString(what, b.toJson().toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    static void remove(Context c, String what) {
        prefs(c).edit().remove(what).apply();
    }

    static void forgetAll(Context c) {
        prefs(c).edit().clear().apply();
    }

    // ---- reading the page -----------------------------------------------------------

    /** The page's nodes (not our own windows, not the status bar), in page order. */
    static List<AccessibilityNodeInfo> nodes(AccessibilityService service) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        String own = service.getPackageName();
        List<AccessibilityWindowInfo> windows;
        try {
            windows = service.getWindows();
        } catch (RuntimeException e) {
            windows = new ArrayList<>();
        }
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || own.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
            List<AccessibilityNodeInfo> stack = new ArrayList<>();
            stack.add(root);
            while (!stack.isEmpty() && out.size() < 6000) {
                AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
                if (n == null) continue;
                out.add(n);
                for (int i = n.getChildCount() - 1; i >= 0; i--) stack.add(n.getChild(i));
            }
        }
        return out;
    }

    /**
     * What the page's elements are (kind and place, not text): a pop-up adds its own ones
     * (a cover over the page, its box), even when it doesn't report its words or buttons.
     */
    static Set<String> shapes(AccessibilityService service) {
        Set<String> out = new HashSet<>();
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (AccessibilityNodeInfo n : nodes(service)) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) continue;
            // How many children too: a pop-up added inside the page changes its parent's
            // count, even when the pop-up itself is an empty box the same size as another.
            String k = n.getClassName() + "|" + role(n) + "|" + r.toShortString() + "|" + n.getChildCount()
                    + (n.isClickable() ? "|clk" : "");
            // A second element exactly like another counts as its own (the cover over the page
            // can be the same size as the page's own full-screen box).
            int times = seen.merge(k, 1, Integer::sum);
            out.add(times == 1 ? k : k + "#" + times);
        }
        return out;
    }

    /** A pop-up taught with {@code shapes} is up: all it adds is on the page now. */
    static boolean showing(Set<String> now, Button b) {
        if (b.shapes.isEmpty()) return false;
        int have = 0;
        for (String k : b.shapes) if (now.contains(k)) have++;
        return have * 10 >= b.shapes.size() * 8; // 80%: a small part may redraw differently
    }

    /** The smallest clickable element of the page under a point, or null. */
    static AccessibilityNodeInfo buttonAt(AccessibilityService service, int x, int y) {
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : nodes(service)) {
            if (!n.isClickable() || !n.isVisibleToUser()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (!r.contains(x, y)) continue;
            long area = (long) r.width() * r.height();
            if (area < bestArea) {
                best = n;
                bestArea = area;
            }
        }
        return best;
    }

    static String role(AccessibilityNodeInfo n) {
        try {
            CharSequence c = n.getExtras().getCharSequence("AccessibilityNodeInfo.chromeRole");
            return c == null ? "" : c.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString().replace('\n', ' ').trim();
    }
}
