package com.kunukuntla.binpress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class NumbersTest {

    @Test
    public void highestFirstAndEachOnce() {
        assertEquals(Arrays.asList(11, 7, 3), Numbers.parse("3, 7 11"));
        assertEquals(Arrays.asList(5, 2), Numbers.parse("2,2,5"));
    }

    @Test
    public void anythingBetweenTheNumbersIsFine() {
        assertEquals(Arrays.asList(9, 4), Numbers.parse("4 and 9."));
    }

    @Test
    public void zeroAndHugeNumbersAreLeftOut() {
        assertEquals(Arrays.asList(6), Numbers.parse("0, 6, 123"));
    }

    @Test
    public void nothingTypedIsNothing() {
        assertTrue(Numbers.parse("").isEmpty());
        assertTrue(Numbers.parse(null).isEmpty());
    }
}
