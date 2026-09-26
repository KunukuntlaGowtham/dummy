package com.kunukuntla.dropdownpicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BinFinderTest {

    private val w = 1080
    private val h = 300
    private val card = 0xFFFFFFFF.toInt()
    private val purple = 0xFF6A2C91.toInt()

    private fun page(): IntArray = IntArray(w * h) { card }

    private fun fill(px: IntArray, l: Int, t: Int, r: Int, b: Int, c: Int) {
        for (y in t until b) for (x in l until r) px[y * w + x] = c
    }

    /** A "v" of two 3-pixel strokes, like the expand arrow. */
    private fun chevron(px: IntArray, l: Int, t: Int, size: Int) {
        for (i in 0 until size) {
            for (s in 0 until 3) {
                px[(t + i / 2) * w + l + i + s] = purple
                px[(t + i / 2) * w + l + 2 * size - i + s] = purple
            }
        }
    }

    @Test
    fun picksTheBinNotTheArrow() {
        val px = page()
        fill(px, 100, 90, 125, 130, purple)          // the card number, left of the band
        fill(px, 750, 85, 800, 140, purple)          // the bin
        fill(px, 760, 95, 765, 130, card)            // its slots
        fill(px, 780, 95, 785, 130, card)
        chevron(px, 880, 100, 20)                    // the arrow
        val bin = BinFinder.find(px, w, h, 60, 170, 500, 1040, 8)
        assertNotNull(bin)
        assertEquals(750, bin!![0])
        assertEquals(800, bin[2])
        assertEquals(85, bin[1])
        assertEquals(140, bin[3])
    }

    @Test
    fun anEmojiBinOnAGreyCardIsFound() {
        val px = IntArray(w * h) { 0xFFF7F7F7.toInt() }
        fill(px, 830, 40, 885, 100, 0xFF7FB8C9.toInt())
        chevron(px, 945, 70, 12)
        val bin = BinFinder.find(px, w, h, 20, 130, 500, 1040, 8)
        assertNotNull(bin)
        assertEquals(830, bin!![0])
        assertEquals(885, bin[2])
    }

    @Test
    fun anEmptyRowHasNoBin() {
        assertNull(BinFinder.find(page(), w, h, 60, 170, 500, 1040, 8))
    }
}
