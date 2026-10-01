package com.tristinbaker.inkshelf.cover

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.pow

/**
 * Prepares a cover for the panel the way an e-reader does, rather than handing
 * it a colour JPEG and letting the panel guess.
 *
 * Left alone, the panel maps colour to grey with a tone curve far darker than an
 * LCD's, so a typical cover of saturated mid-tones lands as a near-black square.
 * Instead each cover is taken to luminance, its histogram stretched so the
 * darkest and lightest few percent become pure black and white, its mid-tones
 * lifted, and the result error-diffused to pure black and white. Two levels
 * render identically in every panel mode, so the cover looks the same in Speed
 * as in Contrast, and the dot density carries the shading the panel's own greys
 * could not.
 *
 * The output must be drawn at exactly its own pixel size: any scaling, even
 * nearest-neighbour, beats against the dither pattern.
 */
object CoverDither {

    /** Fraction of pixels clipped to black, and to white, by the levels stretch. */
    private const val CLIP = 0.02f

    /**
     * A histogram narrower than this is a flat or near-flat cover; stretching it
     * would only amplify JPEG noise into speckle.
     */
    private const val MIN_SPAN = 48

    /** Exponent below 1 lifts the mid-tones that the panel's dots darken. */
    private const val GAMMA = 0.8

    fun process(src: Bitmap, targetWidth: Int): Bitmap {
        val scaled = if (src.width != targetWidth && targetWidth > 0) {
            val h = (src.height.toLong() * targetWidth / src.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, targetWidth, h, true)
        } else {
            src
        }
        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        val luma = IntArray(pixels.size)
        val histogram = IntArray(256)
        for (i in pixels.indices) {
            val c = pixels[i]
            val y = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            luma[i] = y
            histogram[y]++
        }

        var lo = 0
        var hi = 255
        val clipCount = (pixels.size * CLIP).toInt()
        var seen = 0
        while (lo < 255 && seen + histogram[lo] <= clipCount) seen += histogram[lo++]
        seen = 0
        while (hi > 0 && seen + histogram[hi] <= clipCount) seen += histogram[hi--]
        if (hi - lo < MIN_SPAN) {
            lo = 0
            hi = 255
        }

        // Tone curve as a lookup, so the per-pixel work is one array read.
        val curve = FloatArray(256) { y ->
            val t = ((y - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
            t.toDouble().pow(GAMMA).toFloat()
        }

        // Floyd-Steinberg, serpentine so the error does not pile up in diagonal
        // streaks down one side of the cover.
        val value = FloatArray(pixels.size) { curve[luma[it]] }
        for (row in 0 until h) {
            val ltr = row % 2 == 0
            val dir = if (ltr) 1 else -1
            var col = if (ltr) 0 else w - 1
            repeat(w) {
                val i = row * w + col
                val old = value[i]
                val new = if (old >= 0.5f) 1f else 0f
                pixels[i] = if (new == 1f) Color.WHITE else Color.BLACK
                val err = old - new
                val ahead = col + dir
                val behind = col - dir
                if (ahead in 0 until w) value[i + dir] += err * 7 / 16
                if (row + 1 < h) {
                    val below = i + w
                    if (behind in 0 until w) value[below - dir] += err * 3 / 16
                    value[below] += err * 5 / 16
                    if (ahead in 0 until w) value[below + dir] += err * 1 / 16
                }
                col += dir
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== src) scaled.recycle()
        return out
    }
}
