package com.kunukuntla.a11yinspector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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

    private static Set<String> words(String... w) {
        return new HashSet<>(Arrays.asList(w));
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
    public void aConfirmButtonIsPage4() {
        PageKind.Facts f = facts(0, 0, 0, 0, 0, false);
        f.confirm = true;
        assertEquals(PageKind.Kind.CONFIRM, PageKind.decide(f)); // below the screen, no checkboxes
        f.confirmShown = true;
        f.boxes = 1; // an "I agree" box on it
        assertEquals(PageKind.Kind.CONFIRM, PageKind.decide(f));
        // A hidden Confirm on page 3 (rows to tick) never makes it page 4.
        PageKind.Facts g = facts(0, 0, 0, 12, 0, true);
        g.confirm = true;
        assertEquals(PageKind.Kind.TICKING, PageKind.decide(g));
    }

    @Test
    public void page2StillDrawingIsNeverPage3() {
        // The declaration checkbox and Continue, before the calendar shows.
        PageKind.Facts f = facts(0, 0, 0, 1, 0, true);
        f.declare = true;
        assertEquals(PageKind.Kind.CALENDAR, PageKind.decide(f));
        // One checkbox and the dropdown's text field.
        PageKind.Facts g = facts(0, 0, 0, 1, 0, true);
        g.fields = 1;
        assertEquals(PageKind.Kind.CALENDAR, PageKind.decide(g));
        // Page 3 with many rows stays page 3.
        PageKind.Facts h = facts(0, 0, 0, 12, 0, true);
        assertEquals(PageKind.Kind.TICKING, PageKind.decide(h));
    }

    @Test
    public void page1ByItsOwnWords() {
        PageKind.Facts f = facts(0, 0, 0, 0, 2, true);
        f.texts.add("Srivari Seva");
        f.texts.add("Sevaks Profiles");
        assertEquals(PageKind.Kind.SEVAK_LIST, PageKind.decide(f));
        PageKind.Facts g = facts(0, 0, 0, 0, 0, true);
        g.texts.add("Add Sevak");
        assertEquals(PageKind.Kind.SEVAK_LIST, PageKind.decide(g));
    }

    @Test
    public void page1IsNotDecidedByWhatItHas() {
        assertEquals(PageKind.Kind.OTHER, PageKind.decide(facts(0, 0, 0, 0, 3, false)));
        assertEquals(PageKind.Kind.OTHER, PageKind.decide(facts(0, 0, 0, 0, 0, false)));
        assertEquals(PageKind.Kind.OTHER, PageKind.decide(facts(0, 0, 0, 1, 0, false)));
    }

    @Test
    public void confirmWords() {
        assertTrue(PageKind.isConfirm("Confirm"));
        assertTrue(PageKind.isConfirm("CONFIRM BOOKING"));
        assertTrue(PageKind.isConfirm("Confirm →"));
        assertFalse(PageKind.isConfirm("Confirmed"));
        assertFalse(PageKind.isConfirm("Please confirm your details before you pay for the seva"));
        assertTrue(PageKind.isContinue("Continue"));
        assertFalse(PageKind.isContinue("Continue shopping"));
    }

    @Test
    public void page1KnownByMostOfItsWords() {
        Set<String> kept = words("Sevaks", "Tejasri", "Ravi", "Add Sevak", "Next");
        assertTrue(PageKind.samePage(kept, words("Sevaks", "Tejasri", "Ravi", "Add Sevak", "Next", "Sita")));
        assertFalse(PageKind.samePage(kept, words("Select Date", "October 2026", "Continue", "Batch A")));
        assertFalse(PageKind.samePage(words("A", "B"), words("A", "B")));
    }

    @Test
    public void titles() {
        assertEquals("Page 3 · Ticking sevaks", PageKind.Kind.TICKING.title());
        assertEquals("Page 4 · Confirm", PageKind.Kind.CONFIRM.title());
        assertEquals("Not a booking page", PageKind.Kind.OTHER.title());
    }
}
