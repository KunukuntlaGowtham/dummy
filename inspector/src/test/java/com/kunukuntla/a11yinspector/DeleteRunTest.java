package com.kunukuntla.a11yinspector;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class DeleteRunTest {

    @Test
    public void numbersAnyWayTyped() {
        assertEquals(Arrays.asList(2, 5), DeleteRun.parse("2,5"));
        assertEquals(Arrays.asList(2, 5, 7), DeleteRun.parse("7 2, 5 5"));
        assertEquals(Arrays.asList(3), DeleteRun.parse(" 3 "));
        assertEquals(new ArrayList<Integer>(), DeleteRun.parse("x"));
    }

    @Test
    public void laterNumbersMoveUpForEachDeletedAboveThem() {
        List<Integer> deleted = new ArrayList<>();
        assertEquals(2, DeleteRun.nowNumber(2, deleted));
        deleted.add(2);
        assertEquals(4, DeleteRun.nowNumber(5, deleted)); // 2, 5 -> 2 then 4
        deleted.add(5);
        assertEquals(5, DeleteRun.nowNumber(7, deleted)); // 2, 5, 7 -> 2, 4, 5
    }

    @Test
    public void aProfileNotDeletedMovesNothing() {
        // 2 failed (not in deleted): 5 is still 5.
        assertEquals(5, DeleteRun.nowNumber(5, new ArrayList<Integer>()));
    }
}
