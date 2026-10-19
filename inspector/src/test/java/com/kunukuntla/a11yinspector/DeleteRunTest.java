package com.kunukuntla.a11yinspector;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Test;

public class DeleteRunTest {

    @Test
    public void numbersInTheOrderTyped() {
        assertEquals(Arrays.asList(2, 5), DeleteRun.parse("2,5"));
        assertEquals(Arrays.asList(5, 2), DeleteRun.parse("5 2"));
        assertEquals(Arrays.asList(2, 2), DeleteRun.parse("2, 2"));
        assertEquals(Arrays.asList(3), DeleteRun.parse(" 3 "));
        assertEquals(new ArrayList<Integer>(), DeleteRun.parse("x 0"));
    }
}
