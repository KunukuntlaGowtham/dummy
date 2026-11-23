package com.kunukuntla.a11yinspector;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;

/**
 * The place you taught for a pop-up's OK, kept per app. Tick and Delete tap it the moment a
 * pop-up's cover comes - no screenshot. When a tap there doesn't close the pop-up, the run
 * stops using it (for that run) and closes this pop-up the way it did before: the OK found on
 * a screenshot, then the place that run kept.
 */
final class OkTeach {

    private OkTeach() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("popup", Context.MODE_PRIVATE);
    }

    /** The taught place for this app, or null. */
    static Rect get(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        String s = prefs(c).getString("taught_" + pkg, null);
        return s == null ? null : Rect.unflattenFromString(s);
    }

    /** Keeps the tapped point (with a little room round it) as this app's OK place. */
    static void put(Context c, String pkg, int x, int y, int room) {
        prefs(c).edit().putString("taught_" + pkg,
                new Rect(x - room, y - room, x + room, y + room).flattenToString()).apply();
    }

    static void clear(Context c, String pkg) {
        prefs(c).edit().remove("taught_" + pkg).apply();
    }
}
