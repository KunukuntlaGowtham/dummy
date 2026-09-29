package com.kunukuntla.a11yinspector;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The user-configurable node budget for a scan. Higher = deeper/more complete
 * reports on huge screens, at the cost of a longer report. Stored on-device
 * only. Depth is not capped here (only guarded against looping trees in the
 * service); node count is the practical limit.
 */
final class Limits {

    private static final String PREFS = "inspector";
    private static final String KEY_MAX_NODES = "maxNodes";

    /** Presets the button cycles through. */
    static final int[] PRESETS = {3000, 12000, 50000};

    private Limits() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int maxNodes(Context c) {
        return prefs(c).getInt(KEY_MAX_NODES, PRESETS[0]);
    }

    /** Advance to the next preset and return the new value. */
    static int cycle(Context c) {
        int current = maxNodes(c);
        int next = PRESETS[0];
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i] == current) {
                next = PRESETS[(i + 1) % PRESETS.length];
                break;
            }
        }
        prefs(c).edit().putInt(KEY_MAX_NODES, next).apply();
        return next;
    }
}
