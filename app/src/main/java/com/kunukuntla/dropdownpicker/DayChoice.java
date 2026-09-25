package com.kunukuntla.dropdownpicker;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The calendar day the user wants, how strict to be about it, and day-colour reading. */
final class DayChoice {

    static final int MODE_OFF = 0;
    static final int MODE_EXACT = 1;
    static final int MODE_BEST = 2;

    private static final String PREFS = "settings";
    private static final Pattern DATE =
            Pattern.compile("^\\s*(\\d{1,2})\\s*[/.-]\\s*(\\d{1,2})(?:\\s*[/.-]\\s*(\\d{2,4}))?\\s*$");
    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec"};
    private static final Pattern MONTH_YEAR = Pattern.compile(
            "^\\s*([A-Za-z]{3,9})\\.?\\s*,?\\s*(\\d{4})\\s*$");

    private DayChoice() {}

    static String loadDate(Context c) {
        return prefs(c).getString("day", "");
    }

    static void saveDate(Context c, String text) {
        prefs(c).edit().putString("day", text).apply();
    }

    static int loadMode(Context c) {
        return prefs(c).getInt("day_mode", MODE_OFF);
    }

    static void saveMode(Context c, int mode) {
        prefs(c).edit().putInt("day_mode", mode).apply();
    }

    /** Parses "21/10/2026", "21-10-26" or "21/10" (this year, or next if already past). */
    static LocalDate parse(String text) {
        Matcher m = DATE.matcher(text == null ? "" : text);
        if (!m.matches()) return null;
        try {
            int d = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            LocalDate today = LocalDate.now();
            if (m.group(3) == null) {
                LocalDate date = LocalDate.of(today.getYear(), mo, d);
                return date.isBefore(today) ? date.plusYears(1) : date;
            }
            int y = Integer.parseInt(m.group(3));
            if (y < 100) y += 2000;
            return LocalDate.of(y, mo, d);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reads "October 2026" / "Oct 2026" as year*12 + month index, or -1. */
    static int monthOf(String text) {
        Matcher m = MONTH_YEAR.matcher(text);
        if (!m.matches()) return -1;
        String name = m.group(1).toLowerCase(Locale.ROOT);
        for (int i = 0; i < 12; i++) {
            if (name.startsWith(MONTHS[i])) return Integer.parseInt(m.group(2)) * 12 + i;
        }
        return -1;
    }

    static int monthOf(LocalDate date) {
        return date.getYear() * 12 + date.getMonthValue() - 1;
    }

    /** A colour name for a pixel: GREEN, YELLOW, RED, GREY, BLUE, PURPLE, WHITE or BLACK. */
    static String colourName(int pixel) {
        float[] hsv = new float[3];
        Color.colorToHSV(pixel, hsv);
        float h = hsv[0], s = hsv[1], v = hsv[2];
        if (s < 0.15f || v < 0.2f) {
            if (v > 0.9f) return "WHITE";
            if (v < 0.25f) return "BLACK";
            return "GREY";
        }
        if (h < 15 || h >= 340) return "RED";
        if (h < 70) return "YELLOW"; // yellow and orange ("filling fast")
        if (h < 170) return "GREEN";
        if (h < 260) return "BLUE";
        return "PURPLE";
    }

    static boolean isOpen(String colour) {
        return "GREEN".equals(colour) || "YELLOW".equals(colour);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
