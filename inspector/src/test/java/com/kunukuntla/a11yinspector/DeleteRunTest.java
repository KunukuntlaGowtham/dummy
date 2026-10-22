package com.kunukuntla.a11yinspector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    @Test
    public void aFailedDeleteMovesTheLaterNumbersDown() {
        // Rows 5 and 11 not added -> delete 5, then 10 (11 once 5 has gone). 5 stays: 11 is 11.
        List<Integer> todo = new ArrayList<>(Arrays.asList(10));
        DeleteRun.shiftAfterFailure(todo, 5);
        assertEquals(Arrays.asList(11), todo);
        // Rows 5, 6 -> 5, 5: the first 5 stays, so the old 6 is still 6.
        todo = new ArrayList<>(Arrays.asList(5));
        DeleteRun.shiftAfterFailure(todo, 5);
        assertEquals(Arrays.asList(6), todo);
        // A number above the one that stayed is untouched.
        todo = new ArrayList<>(Arrays.asList(2));
        DeleteRun.shiftAfterFailure(todo, 5);
        assertEquals(Arrays.asList(2), todo);
    }

    @Test
    public void sameNameAnyCaseOrPartly() {
        assertTrue(DeleteRun.sameName("Mellacheruvu Greeshma", "MELLACHERUVU GREESHMA"));
        assertTrue(DeleteRun.sameName("Mellacheruvu Greeshma", "Mellacheruvu Greeshma (F, 23)"));
        assertFalse(DeleteRun.sameName("Mellacheruvu Greeshma", "Mellacheruvu Tejasree"));
        assertFalse(DeleteRun.sameName("Ab", "Ab"));
    }
}
