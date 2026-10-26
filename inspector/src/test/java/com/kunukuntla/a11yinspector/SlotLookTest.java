package com.kunukuntla.a11yinspector;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SlotLookTest {

    @Test
    public void colouredPixels() {
        assertTrue(SlotLook.isColour(0x58, 0x56, 0xD6)); // the app's purple
        assertFalse(SlotLook.isColour(255, 255, 255)); // white
        assertFalse(SlotLook.isColour(120, 120, 120)); // grey outline
        assertFalse(SlotLook.isColour(20, 20, 20)); // black words
    }

    @Test
    public void aFilledSquareIsTicked() {
        assertTrue(SlotLook.ticked(1500, 47)); // a 47 px square, mostly filled
        assertFalse(SlotLook.ticked(200, 47)); // a few coloured pixels in the words
    }
}
