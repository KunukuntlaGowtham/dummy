package com.example.checkboxticker

import org.junit.Assert.assertTrue
import org.junit.Test

class BoxShapeTest {
    private val w = 40
    private val white = 0xFFFFFF

    private fun outlined(): IntArray {
        val px = IntArray(w * w) { white }
        for (i in 4 until 36) for (t in 0 until 2) {
            px[(4 + t) * w + i] = 0x999999
            px[(35 - t) * w + i] = 0x999999
            px[i * w + 4 + t] = 0x999999
            px[i * w + 35 - t] = 0x999999
        }
        return px
    }

    @Test
    fun anEmptyBoxMatchesItselfAndAFlatPatchDoesNot() {
        val box = BoxShape.gray(outlined(), w, 0, 0, w, w)
        assertTrue(BoxShape.ncc(box, box) > 0.99)
        val flat = BoxShape.gray(IntArray(w * w) { white }, w, 0, 0, w, w)
        assertTrue(BoxShape.ncc(box, flat) < BoxShape.MATCH)
    }

    @Test
    fun aFilledBoxIsNotAnEmptyOne() {
        val filled = IntArray(w * w) { white }
        for (y in 4 until 36) for (x in 4 until 36) filled[y * w + x] = 0x6A2C91
        for (y in 18 until 22) for (x in 10 until 30) filled[y * w + x] = white
        val box = BoxShape.gray(outlined(), w, 0, 0, w, w)
        assertTrue(BoxShape.ncc(box, BoxShape.gray(filled, w, 0, 0, w, w)) < BoxShape.MATCH)
    }
}
