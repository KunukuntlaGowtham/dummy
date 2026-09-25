package com.kunukuntla.dropdownpicker;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The user's option keywords, saved between runs. */
final class Keywords {

    private static final String PREFS = "settings";
    private static final String KEY = "keywords";

    private Keywords() {}

    static String load(Context context) {
        return prefs(context).getString(KEY, "");
    }

    static void save(Context context, String text) {
        prefs(context).edit().putString(KEY, text).apply();
    }

    /** The keywords in order, lower-cased, blanks dropped. */
    static List<String> list(Context context) {
        List<String> out = new ArrayList<>();
        for (String part : load(context).split(",")) {
            String k = part.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
