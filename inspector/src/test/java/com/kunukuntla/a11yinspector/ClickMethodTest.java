package com.kunukuntla.a11yinspector;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

import com.kunukuntla.a11yinspector.ClickMethod.Way;

public class ClickMethodTest {
    private ClickMethod m;

    @Before public void setUp() {
        m = new ClickMethod();
    }

    @Test public void startsWithTheDirectBoxClick() {
        assertEquals(Way.BOX, m.first(true));
        assertEquals(Way.BOX, m.first(false));
    }

    @Test public void directFirstSuccessKeepsDirect() {
        assertFalse(m.verified(m.first(true), true));
        for (int row = 0; row < 3; row++) assertEquals(Way.BOX, m.first(true));
    }

    @Test public void labelSuccessIsLearnedForTheFollowingProfiles() {
        Way first = m.first(true);
        Way second = m.fallback(first, true, false);   // direct click didn't tick it
        assertEquals(Way.LABEL, second);
        assertTrue(m.verified(second, true));          // learned only once verified
        for (int row = 0; row < 5; row++) {
            assertEquals(Way.LABEL, m.first(true));
            assertFalse(m.verified(Way.LABEL, true));   // stays learned, no change logged again
        }
    }

    @Test public void nothingIsLearnedWithoutAVerifiedTick() {
        Way second = m.fallback(m.first(true), true, false);
        assertEquals(Way.LABEL, second);               // label click sent, box never read ☑
        assertEquals(Way.BOX, m.preferred());
        assertEquals(Way.BOX, m.first(true));
    }

    @Test public void learnedLabelFailingFallsBackToDirectOnce() {
        m.verified(Way.LABEL, true);
        assertEquals(Way.LABEL, m.first(true));
        Way second = m.fallback(Way.LABEL, true, false);
        assertEquals(Way.BOX, second);
        assertTrue(m.verified(second, true));          // direct worked: direct first again
        assertEquals(Way.BOX, m.first(true));
    }

    @Test public void rowWithoutLabelClicksTheBoxAndKeepsTheLearnedLabel() {
        m.verified(Way.LABEL, true);
        assertEquals(Way.BOX, m.first(false));         // no label on this row
        assertNull(m.fallback(Way.BOX, false, false)); // and no label to fall back to
        assertFalse(m.verified(Way.BOX, false));       // says nothing against labels
        assertEquals(Way.LABEL, m.first(true));        // next row with a label: label first
    }

    @Test public void noLabelAnywhereNeverLearnsLabel() {
        assertNull(m.fallback(m.first(false), false, false));
        assertEquals(Way.BOX, m.first(true));
    }

    @Test public void lateTickGetsNoSecondClick() {
        assertNull(m.fallback(Way.BOX, true, true));   // box already ☑ when re-read
        m.verified(Way.LABEL, true);
        assertNull(m.fallback(Way.LABEL, true, true));
        assertFalse(m.verified(Way.LABEL, true));      // the late tick confirms the first way
    }

    @Test public void resetAtTheStartOfEveryRun() {
        m.verified(Way.LABEL, true);
        assertEquals(Way.LABEL, m.first(true));
        m.reset();
        assertEquals(Way.BOX, m.preferred());
        assertEquals(Way.BOX, m.first(true));
    }

    @Test public void separateRunsDoNotShareThePreference() {
        new ClickMethod().verified(Way.LABEL, true);
        assertEquals(Way.BOX, new ClickMethod().first(true));
    }
}
