package com.kunukuntla.dropdownpicker;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The user's option keywords and the last run's report, saved between runs. */
final class Keywords {

    private static final String PREFS = "settings";
    private static final String KEY = "keywords";

    /** What to do once the dropdown is open. */
    static final int PICK_BELOW = 0;
    static final int PICK_CONTAINS = 1;
    static final int PICK_EXACT = 2;

    private Keywords() {}

    static int loadPickMode(Context context) {
        return prefs(context).getInt("pick_mode", PICK_CONTAINS);
    }

    static void savePickMode(Context context, int mode) {
        prefs(context).edit().putInt("pick_mode", mode).apply();
    }

    /** Whether to tick the radio button and checkbox and press Continue after the date. */
    static boolean loadFinish(Context context) {
        return prefs(context).getBoolean("finish_form", true);
    }

    static void saveFinish(Context context, boolean on) {
        prefs(context).edit().putBoolean("finish_form", on).apply();
    }

    /** Back button: wait after the first Back (ms) and how far to scroll (mm, 0 = none). */
    static int loadBackWait(Context context) {
        return prefs(context).getInt("back_wait", 1000);
    }

    static void saveBackWait(Context context, int ms) {
        prefs(context).edit().putInt("back_wait", ms).apply();
    }

    static int loadBackScroll(Context context) {
        return prefs(context).getInt("back_scroll", 30);
    }

    static void saveBackScroll(Context context, int mm) {
        prefs(context).edit().putInt("back_scroll", mm).apply();
    }

    /** Links between the buttons: each starts the next one 1 s after it finishes. */
    static final String CHAIN_DROP_CAL = "chain_drop_cal";
    static final String CHAIN_CAL_TICK = "auto_tick"; // the earlier "Tick after Continue" switch
    static final String CHAIN_TICK_BACK = "chain_tick_back";
    static final String CHAIN_BACK_DROP = "chain_back_drop";

    static boolean loadChain(Context context, String link) {
        return prefs(context).getBoolean(link, CHAIN_CAL_TICK.equals(link));
    }

    static void saveChain(Context context, String link, boolean on) {
        prefs(context).edit().putBoolean(link, on).apply();
    }

    /** Whether to start Tick by itself 1 s after Continue is pressed. */
    static boolean loadAutoTick(Context context) {
        return prefs(context).getBoolean("auto_tick", true);
    }

    static void saveAutoTick(Context context, boolean on) {
        prefs(context).edit().putBoolean("auto_tick", on).apply();
    }

    static String load(Context context) {
        return prefs(context).getString(KEY, "");
    }

    static void save(Context context, String text) {
        prefs(context).edit().putString(KEY, text).apply();
    }

    static String loadLastRun(Context context) {
        return prefs(context).getString("last_run", "");
    }

    static void saveLastRun(Context context, String text) {
        prefs(context).edit().putString("last_run", text).apply();
    }

    /**
     * The keywords in order, normalised (see {@link #norm}). The whole box
     * comes first, so an option name that itself contains commas still
     * matches; then each part split on new lines, "|", ";" or ",".
     */
    static List<String> list(Context context) {
        List<String> out = new ArrayList<>();
        String all = load(context);
        String whole = norm(all);
        if (!whole.isEmpty()) out.add(whole);
        for (String part : all.split("[\\n|;,]")) {
            String k = norm(part);
            if (!k.isEmpty() && !out.contains(k)) out.add(k);
        }
        return out;
    }

    /** Lower case, punctuation and repeated spaces removed: "Seva, Tirumala (Male)" -> "seva tirumala male". */
    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
