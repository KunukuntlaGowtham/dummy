package com.kunukuntla.a11yinspector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** The dropdown choices, with the real lists of the TTD slot page (page 2's four dropdowns). */
public class BookerPickTest {

    private static final List<String> TYPE = Arrays.asList(
            "Tirumala (Both Female and Male)", "Tirumala (Male Only)",
            "Tirumala - Bramhostavam (Both Female and Male)", "Tirumala - Bramhostavam (Male Only)",
            "Tirumala - Vaikunta Ekadasi (Both Female and Male)", "Tirumala - Vaikunta Ekadasi (Male Only)");
    private static final List<String> STATE = Arrays.asList(
            "Andhra Pradesh", "Telangana", "Tamil Nadu", "Karnataka", "Odissa");
    private static final List<String> LOCATION = Arrays.asList(
            "Tirumala", "Tirupati", "Rushikonda", "Vontimitta", "Upamaka");
    private static final List<String> NAME = Arrays.asList("Srivari Seva", "Navneetha Seva");

    @Test
    public void aFewWordsFindTheOption() {
        assertEquals(1, Booker.bestOption(TYPE, "Tirumala Male Only"));
        assertEquals(3, Booker.bestOption(TYPE, "Bramhostavam Male"));
        assertEquals(5, Booker.bestOption(TYPE, "vaikunta male only"));
        assertEquals(4, Booker.bestOption(STATE, "odissa"));
        assertEquals(1, Booker.bestOption(STATE, "telangana"));
        assertEquals(1, Booker.bestOption(NAME, "navneetha"));
    }

    @Test
    public void theSameTextBeatsOneThatOnlyHasTheWords() {
        // "Tirumala" is a Location by itself - and a word in every Type.
        assertEquals(0, Booker.bestOption(LOCATION, "Tirumala"));
        // Several options have the words: the shortest (most exact) one.
        assertEquals(1, Booker.bestOption(TYPE, "Tirumala Male"));
    }

    @Test
    public void noOptionHasThemAll() {
        assertEquals(-1, Booker.bestOption(STATE, "kerala"));
        assertEquals(-1, Booker.bestOption(TYPE, "Tirumala Female Only"));
        assertEquals(-1, Booker.bestOption(NAME, ""));
    }

    @Test
    public void aDropdownHoldingYourWordsIsLeftAlone() {
        assertTrue(Booker.satisfied("Andhra Pradesh", "andhra"));
        assertTrue(Booker.satisfied("Tirumala (Both Female and Male)", "Tirumala"));
        assertTrue(Booker.satisfied("Srivari Seva", "Srivari Seva"));
        assertFalse(Booker.satisfied("Tirumala (Both Female and Male)", "Tirumala Male Only"));
        assertFalse(Booker.satisfied("Tirumala", "Tirupati"));
        assertFalse(Booker.satisfied("", "Tirumala"));
    }

    @Test
    public void thePlanKnowsWhatIsAsked() {
        Booker.Plan p = new Booker.Plan();
        assertFalse(p.hasPick());
        assertEquals("", p.picksText());
        p.pick[1] = "Tirupati";
        p.pick[3] = "Male Only";
        assertTrue(p.hasPick());
        assertEquals("Tirupati · Male Only", p.picksText());
    }
}
