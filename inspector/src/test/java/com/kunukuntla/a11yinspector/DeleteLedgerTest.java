package com.kunukuntla.a11yinspector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class DeleteLedgerTest {

    private static List<DeleteLedger.Entry> two() {
        return DeleteLedger.withMissed(new ArrayList<>(), Arrays.asList(3, 7),
                Arrays.asList("Vemuri Anitha", "Kandukuri Bhavana"), 1000);
    }

    @Test
    public void saysWhoIsToBeDeleted() {
        List<DeleteLedger.Entry> l = two();
        assertEquals(2, l.size());
        assertEquals("Vemuri Anitha", l.get(0).name);
        assertEquals(DeleteLedger.PENDING, l.get(1).state);
    }

    @Test
    public void survivesSavingAndReading() {
        List<DeleteLedger.Entry> l = two();
        l.get(1).why = "no profiles named\tX\non the page";
        List<DeleteLedger.Entry> back = DeleteLedger.parse(DeleteLedger.format(l));
        assertEquals(2, back.size());
        assertEquals(3, back.get(0).row);
        assertEquals("Kandukuri Bhavana", back.get(1).name);
        assertEquals("no profiles named X on the page", back.get(1).why);
    }

    @Test
    public void oneDeletedOneFailedLeavesOnlyTheFailedToDo() {
        List<DeleteLedger.Entry> l = two();
        DeleteLedger.mark(l, "Vemuri Anitha", 3, true, "", 2000);
        DeleteLedger.mark(l, "Kandukuri Bhavana", 6, false, "still there", 2100);
        assertEquals(DeleteLedger.DELETED, l.get(0).state);
        assertEquals(DeleteLedger.FAILED, l.get(1).state);
        List<DeleteLedger.Entry> todo = DeleteLedger.todo(l);
        assertEquals(1, todo.size());
        assertEquals("Kandukuri Bhavana", todo.get(0).name);
    }

    @Test
    public void aNewTickReplacesWhatWasToDoButKeepsWhatWasDeleted() {
        List<DeleteLedger.Entry> l = two();
        DeleteLedger.mark(l, "Vemuri Anitha", 3, true, "", 2000);
        List<DeleteLedger.Entry> next = DeleteLedger.withMissed(l, Arrays.asList(5), Arrays.asList("Ravi Kumar"), 3000);
        assertEquals(2, next.size());
        assertEquals(DeleteLedger.DELETED, next.get(0).state);
        assertEquals("Ravi Kumar", next.get(1).name);
    }

    @Test
    public void aRowWithoutAReadableNameIsLeftForYou() {
        List<DeleteLedger.Entry> l = DeleteLedger.withMissed(new ArrayList<>(), Arrays.asList(-1, 4),
                Arrays.asList("", "Ravi Kumar"), 1);
        assertEquals(1, DeleteLedger.todo(l).size());
        assertEquals(1, DeleteLedger.byHand(l).size());
        assertTrue(DeleteLedger.text(l).contains("by hand"));
    }

    @Test
    public void theSamePersonIsFoundWithAShorterOrReorderedName() {
        List<DeleteLedger.Entry> l = two();
        DeleteLedger.mark(l, "Anitha Vemuri", 3, true, "", 5);
        assertEquals(DeleteLedger.DELETED, l.get(0).state);
    }

    @Test
    public void peopleStillToDeleteAreDroppedAfterFiveMinutesButDeletedOnesStay() {
        List<DeleteLedger.Entry> l = two(); // saved at 1000
        DeleteLedger.mark(l, "Vemuri Anitha", 3, true, "", 1500);
        List<DeleteLedger.Entry> soon = DeleteLedger.expire(l, 1000 + 4 * 60 * 1000);
        assertEquals(2, soon.size());
        List<DeleteLedger.Entry> later = DeleteLedger.expire(l, 1000 + 6 * 60 * 1000);
        assertEquals(1, later.size());
        assertEquals(DeleteLedger.DELETED, later.get(0).state);
        assertEquals("Vemuri Anitha", later.get(0).name);
        // ...and even the record goes after half an hour
        assertEquals(0, DeleteLedger.expire(l, 1500 + 31 * 60 * 1000).size());
    }

    @Test
    public void aLaterNoOverturnsADeleted() {
        List<DeleteLedger.Entry> l = two();
        DeleteLedger.mark(l, "Vemuri Anitha", 3, true, "", 2000);
        DeleteLedger.mark(l, "Vemuri Anitha", -1, false, "still on the list when checked at the end", 2100);
        assertEquals(DeleteLedger.FAILED, l.get(0).state);
        assertEquals(1, DeleteLedger.todo(l).size() - 1); // it is to do again, besides the other one
    }
}
