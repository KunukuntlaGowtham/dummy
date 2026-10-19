package com.kunukuntla.a11yinspector;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PageOrderTest {

    @Test
    public void pagesInOrderAreTakenAtOnce() {
        PageOrder o = new PageOrder();
        assertEquals(1, o.next(1));
        assertEquals(2, o.next(2));
        assertEquals(3, o.next(3));
        assertEquals(4, o.next(4));
    }

    @Test
    public void startsOnAnyPage() {
        PageOrder o = new PageOrder();
        assertEquals(3, o.next(3));
        assertEquals(4, o.next(4));
    }

    @Test
    public void backOnePageAtATimeAndPage1Always() {
        PageOrder o = new PageOrder();
        o.next(1);
        o.next(2);
        o.next(3);
        assertEquals(2, o.next(2));
        assertEquals(1, o.next(1));
        o.next(2);
        o.next(3);
        o.next(4);
        assertEquals(1, o.next(1));
    }

    @Test
    public void aJumpCountsOnlyOnceItHolds() {
        PageOrder o = new PageOrder();
        o.next(1);
        // Page 2 still drawing, read as page 3: not taken.
        assertEquals(0, o.next(3));
        assertEquals(2, o.next(2));
        assertEquals(2, o.pos());
        // A real jump 2 → 4 holds: taken on the third reading.
        assertEquals(0, o.next(4));
        assertEquals(0, o.next(4));
        assertEquals(4, o.next(4));
    }

    @Test
    public void aLoadingScreenKeepsThePlace() {
        PageOrder o = new PageOrder();
        o.next(1);
        o.next(2);
        assertEquals(0, o.next(0));
        assertEquals(2, o.pos());
        assertEquals(3, o.next(3));
    }

    @Test
    public void kindByNumber() {
        assertEquals(PageKind.Kind.CONFIRM, PageKind.Kind.of(4));
        assertEquals(PageKind.Kind.OTHER, PageKind.Kind.of(0));
    }
}
