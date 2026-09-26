package com.example.checkboxticker

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The trained box model (tools/boxmodel/train.py, from the samples collected on page 2): a small
 * picture of a square with a third of its size around it -> empty box / ticked box / other.
 * A tiny network (20 x 20 colour pixels -> 24 -> 3), so no extra library is needed.
 */
class BoxModel private constructor(
    private val n: Int,
    private val hidden: Int,
    private val w1: FloatArray,
    private val b1: FloatArray,
    private val w2: FloatArray,
    private val b2: FloatArray
) {
    companion object {
        const val EMPTY = 0
        const val TICKED = 1
        const val OTHER = 2

        @Volatile
        private var loaded: BoxModel? = null

        /** The model from the app's assets (box_model.bin), or null if it can't be read. */
        fun get(context: Context): BoxModel? {
            loaded?.let { return it }
            return try {
                context.assets.open("box_model.bin").use { load(it.readBytes()) }.also { loaded = it }
            } catch (e: Exception) {
                android.util.Log.e("CheckboxTicker", "box model not loaded", e)
                null
            }
        }

        /** "BOXM", n, hidden, then W1 (n*n*3 x hidden), b1, W2 (hidden x 3), b2 - little endian. */
        fun load(bytes: ByteArray): BoxModel {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic) == "BOXM") { "not a box model" }
            val n = buf.int
            val hidden = buf.int
            val d = n * n * 3
            fun floats(k: Int) = FloatArray(k) { buf.float }
            return BoxModel(n, hidden, floats(d * hidden), floats(hidden), floats(hidden * 3), floats(3))
        }
    }

    /**
     * Chances for [EMPTY], [TICKED] and [OTHER] of the square left..bottom (in [w] x [h] pixels
     * of 0xRRGGBB colours), taken with a third of its size around it - as the samples were.
     */
    fun classify(rgb: IntArray, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int): FloatArray {
        val grow = maxOf(right - left, bottom - top) / 3
        val l = (left - grow).coerceIn(0, w)
        val t = (top - grow).coerceIn(0, h)
        val r = (right + grow).coerceIn(0, w)
        val b = (bottom + grow).coerceIn(0, h)
        if (r - l < 2 || b - t < 2) return floatArrayOf(0f, 0f, 1f)
        return run(shrink(rgb, w, l, t, r - l, b - t))
    }

    /** The crop box-averaged to n x n, r, g, b per pixel, 0..1. */
    private fun shrink(rgb: IntArray, w: Int, l: Int, t: Int, cw: Int, ch: Int): FloatArray {
        val out = FloatArray(n * n * 3)
        for (oy in 0 until n) {
            val y0 = oy * ch / n
            val y1 = maxOf(y0 + 1, (oy + 1) * ch / n)
            for (ox in 0 until n) {
                val x0 = ox * cw / n
                val x1 = maxOf(x0 + 1, (ox + 1) * cw / n)
                var sr = 0
                var sg = 0
                var sb = 0
                for (y in y0 until y1) {
                    val row = (t + y) * w + l
                    for (x in x0 until x1) {
                        val c = rgb[row + x]
                        sr += (c shr 16) and 0xff
                        sg += (c shr 8) and 0xff
                        sb += c and 0xff
                    }
                }
                val count = ((y1 - y0) * (x1 - x0)).toFloat() * 255f
                val o = (oy * n + ox) * 3
                out[o] = sr / count
                out[o + 1] = sg / count
                out[o + 2] = sb / count
            }
        }
        return out
    }

    private fun run(x: FloatArray): FloatArray {
        val hid = FloatArray(hidden)
        for (j in 0 until hidden) {
            var s = b1[j]
            for (i in x.indices) s += x[i] * w1[i * hidden + j]
            hid[j] = if (s > 0f) s else 0f
        }
        val z = FloatArray(3)
        for (k in 0 until 3) {
            var s = b2[k]
            for (j in 0 until hidden) s += hid[j] * w2[j * 3 + k]
            z[k] = s
        }
        val m = maxOf(z[0], z[1], z[2])
        var sum = 0f
        for (k in 0 until 3) {
            z[k] = Math.exp((z[k] - m).toDouble()).toFloat()
            sum += z[k]
        }
        for (k in 0 until 3) z[k] /= sum
        return z
    }
}
