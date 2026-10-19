package com.kunukuntla.a11yinspector;

import java.util.Collection;

/**
 * Which day to book when the one asked for isn't open (not green), by the preference chosen
 * in the app:
 * <ul>
 *   <li>{@link #WEEK} - the nearest open day in the same week (Sunday to Saturday), else the
 *       nearest open day before or after;</li>
 *   <li>{@link #AFTER} - the nearest open day after it, else the nearest before;</li>
 *   <li>{@link #BEFORE} - the nearest open day before it, else the nearest after;</li>
 *   <li>{@link #NEAREST} - the nearest open day either way (a tie: the later one).</li>
 * </ul>
 * Days are counted as days since 1 January 1970.
 */
final class DayPick {

    static final String WEEK = "week", AFTER = "after", BEFORE = "before", NEAREST = "nearest";
    static final String[] MODES = {WEEK, AFTER, BEFORE, NEAREST};

    private DayPick() {
    }

    /** The day to take: {@code want} when open, else by {@code mode}; -1 when no day is open. */
    static long choose(long want, Collection<Long> open, String mode) {
        if (open.contains(want)) return want;
        if (open.isEmpty()) return -1;
        if (WEEK.equals(mode)) {
            long start = want - dayOfWeek(want), best = -1;
            for (long d : open) {
                if (d < start || d > start + 6) continue;
                if (best < 0 || closer(d, best, want)) best = d;
            }
            if (best >= 0) return best;
            mode = NEAREST;
        }
        long after = -1, before = -1;
        for (long d : open) {
            if (d > want && (after < 0 || d < after)) after = d;
            if (d < want && (before < 0 || d > before)) before = d;
        }
        if (AFTER.equals(mode)) return after >= 0 ? after : before;
        if (BEFORE.equals(mode)) return before >= 0 ? before : after;
        if (after < 0) return before;
        if (before < 0) return after;
        return after - want <= want - before ? after : before;
    }

    /** {@code a} is nearer {@code want} than {@code b} (a tie: the later day). */
    private static boolean closer(long a, long b, long want) {
        long da = Math.abs(a - want), db = Math.abs(b - want);
        return da < db || da == db && a > b;
    }

    /** 0 Sunday ... 6 Saturday (1 January 1970 was a Thursday). */
    static int dayOfWeek(long epochDay) {
        return (int) Math.floorMod(epochDay + 4, 7L);
    }

    static long epochDay(int day, int month, int year) {
        return java.time.LocalDate.of(year, month, day).toEpochDay();
    }

    /** {day, month, year}. */
    static int[] date(long epochDay) {
        java.time.LocalDate d = java.time.LocalDate.ofEpochDay(epochDay);
        return new int[] {d.getDayOfMonth(), d.getMonthValue(), d.getYear()};
    }

    /** A screenshot's colour that is the calendar's green (an open day). */
    static boolean isGreen(int r, int g, int b) {
        return g >= 100 && g - r >= 40 && g - b >= 20;
    }

    /** What the app shows for each preference. */
    static String label(String mode) {
        switch (mode) {
            case AFTER: return "Nearest after";
            case BEFORE: return "Nearest before";
            case NEAREST: return "Nearest either way";
            default: return "Same week, then nearest";
        }
    }
}
