package com.example.checkboxticker

/**
 * Tells a real empty checkbox from other squares the shape finder picks up (bits of text,
 * icons, blank patches, ticked boxes): the square is shrunk to a 16 x 16 grey grid and compared
 * with the page's own empty box (assets/box_empty.png, from samples of page 2) by normalised
 * cross-correlation - it compares where the outline is, not how bright the square is. On the
 * samples: every empty box 0.72 or more (almost all 0.99), everything else 0.45 or less.
 */
object BoxShape {
    const val GRID = 16
    const val MATCH = 0.6

    /** The box l..r x t..b of [rgb] (0xRRGGBB, [w] wide) as a GRID x GRID grey grid. */
    fun gray(rgb: IntArray, w: Int, l: Int, t: Int, r: Int, b: Int): DoubleArray {
        val out = DoubleArray(GRID * GRID)
        val cw = r - l
        val ch = b - t
        if (cw <= 0 || ch <= 0) return out
        for (gy in 0 until GRID) {
            val ya = gy * ch / GRID
            val yb = maxOf(ya + 1, (gy + 1) * ch / GRID)
            for (gx in 0 until GRID) {
                val xa = gx * cw / GRID
                val xb = maxOf(xa + 1, (gx + 1) * cw / GRID)
                var sum = 0L
                var n = 0
                for (y in ya until yb) {
                    val row = (t + y) * w + l
                    for (x in xa until xb) {
                        val c = rgb[row + x]
                        sum += ((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)
                        n++
                    }
                }
                out[gy * GRID + gx] = sum / (3.0 * n)
            }
        }
        return out
    }

    /** Normalised cross-correlation: 1 the same shape, 0 unrelated (a flat patch is 0). */
    fun ncc(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            val x = a[i] - ma
            val y = b[i] - mb
            ab += x * y
            aa += x * x
            bb += y * y
        }
        val d = Math.sqrt(aa * bb)
        return if (d < 1e-6) 0.0 else ab / d
    }
}
