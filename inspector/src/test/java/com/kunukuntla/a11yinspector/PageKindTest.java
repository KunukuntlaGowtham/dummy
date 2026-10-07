package com.kunukuntla.a11yinspector;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PageKindTest {

    private static PageKind.Facts facts(int days, int dropdowns, int radios, int boxes, int sevakWords, boolean cont) {
        PageKind.Facts f = new PageKind.Facts();
        f.days = days;
        f.dropdowns = dropdowns;
        f.radios = radios;
        f.boxes = boxes;
        f.sevakWords = sevakWords;
        f.cont = cont;
        return f;
    }

    @Test
    public void calendarWithItsCheckboxAndRadiosIsPage2() {
        assertEquals(PageKind.Kind.CALENDAR, PageKind.decide(facts(35, 1, 3, 1, 0, true)));
    }

    @Test
    public void dropdownAndRadiosBeforeTheCalendarDrawsIsPage2() {
        assertEquals(PageKind.Kind.CALENDAR, PageKind.decide(facts(0, 1, 2, 0, 0, false)));
    }

    @Test
    public void aFewDatesInTheTextAreNotACalendar() {
        assertEquals(PageKind.Kind.TICKING, PageKind.decide(facts(1, 0, 0, 15, 2, true)));
    }

    @Test
    public void sevakRowsWithCheckboxesArePage3() {
        assertEquals(PageKind.Kind.TICKING, PageKind.decide(facts(0, 0, 0, 15, 0, true)));
    }

    @Test
    public void oneSevakRowWithContinueIsPage3() {
        assertEquals(PageKind.Kind.TICKING, PageKind.decide(facts(0, 0, 0, 1, 0, true)));
    }

    @Test
    public void sevaksNamedWithNothingToTickArePage1() {
        assertEquals(PageKind.Kind.SEVAK_LIST, PageKind.decide(facts(0, 0, 0, 0, 3, false)));
    }

    @Test
    public void anyOtherPageIsNotABookingPage() {
        assertEquals(PageKind.Kind.OTHER, PageKind.decide(facts(0, 0, 0, 0, 0, false)));
        assertEquals(PageKind.Kind.OTHER, PageKind.decide(facts(0, 0, 0, 1, 0, false)));
    }

    @Test
    public void titles() {
        assertEquals("Page 3 · Ticking sevaks", PageKind.Kind.TICKING.title());
        assertEquals("Not a booking page", PageKind.Kind.OTHER.title());
    }
}
