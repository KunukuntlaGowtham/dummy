package com.kunukuntla.a11yinspector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class DayPickTest {

    /** October 2026: the 15th is a Thursday; its week is Sunday 11 - Saturday 17. */
    private static long oct(int day) {
        return DayPick.epochDay(day, 10, 2026);
    }

    private static Set<Long> open(int... days) {
        Set<Long> s = new HashSet<>();
        for (int d : days) s.add(oct(d));
        return s;
    }

    @Test
    public void theDayItselfWhenOpen() {
        assertEquals(oct(15), DayPick.choose(oct(15), open(15, 16), DayPick.WEEK));
    }

    @Test
    public void sameWeekFirstThenNearest() {
        assertEquals(4, DayPick.dayOfWeek(oct(15))); // Thursday
        // 18 is nearer but in the next week: 12 (same week) wins.
        assertEquals(oct(12), DayPick.choose(oct(15), open(12, 18), DayPick.WEEK));
        // Nothing open that week: the nearest either way.
        assertEquals(oct(19), DayPick.choose(oct(15), open(9, 19), DayPick.WEEK));
        // Two as near in the week: the later.
        assertEquals(oct(16), DayPick.choose(oct(15), open(14, 16), DayPick.WEEK));
    }

    @Test
    public void afterOrBefore() {
        assertEquals(oct(20), DayPick.choose(oct(15), open(14, 20), DayPick.AFTER));
        assertEquals(oct(14), DayPick.choose(oct(15), open(14, 20), DayPick.BEFORE));
        // None after: the nearest before (and the other way round).
        assertEquals(oct(10), DayPick.choose(oct(15), open(3, 10), DayPick.AFTER));
        assertEquals(oct(22), DayPick.choose(oct(15), open(22, 28), DayPick.BEFORE));
    }

    @Test
    public void nearestEitherWay() {
        assertEquals(oct(13), DayPick.choose(oct(15), open(13, 18), DayPick.NEAREST));
        assertEquals(oct(17), DayPick.choose(oct(15), open(13, 17), DayPick.NEAREST)); // tie: later
    }

    @Test
    public void noneOpen() {
        assertEquals(-1, DayPick.choose(oct(15), new HashSet<Long>(), DayPick.WEEK));
    }

    @Test
    public void dateBack() {
        assertEquals(Arrays.toString(new int[] {15, 10, 2026}), Arrays.toString(DayPick.date(oct(15))));
    }

    @Test
    public void greenPixels() {
        assertTrue(DayPick.isGreen(60, 170, 80));
        assertFalse(DayPick.isGreen(240, 240, 240)); // white
        assertFalse(DayPick.isGreen(106, 44, 145)); // purple
        assertFalse(DayPick.isGreen(200, 200, 60)); // yellow
        assertTrue(DayPick.isGreen(200, 247, 197)); // pale green
        assertTrue(DayPick.isYellow(255, 213, 79)); // amber
        assertTrue(DayPick.isYellow(253, 224, 133)); // pale yellow (the note box)
        assertFalse(DayPick.isYellow(240, 240, 240)); // white
        assertFalse(DayPick.isYellow(60, 170, 80)); // green
        assertTrue(DayPick.isPurple(106, 44, 145)); // the app's purple
        assertFalse(DayPick.isPurple(240, 240, 240));
    }
}
