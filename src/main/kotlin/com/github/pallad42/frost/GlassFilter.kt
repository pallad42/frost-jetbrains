package com.github.pallad42.frost

import java.awt.Color

/** A background colour of the UI that turns into glass, and how opaque that glass is (0..1). */
internal data class Surface(val color: Color, val opacity: Float)

/**
 * The whole effect as one colour lookup table, applied by Core Animation to the finished pixels of a window
 * ([NativeGlass]). The IDE paints itself fully opaque, exactly as designed; the table then "un-mixes" every pixel from
 * the nearest [Surface] colour (colour-to-alpha): a pixel of the surface colour becomes glass with the surface's
 * opacity, a pixel far from it stays solid, and everything in between (antialiased text, hover and selection shades)
 * keeps its contrast to the surface. No component, colour or image of the IDE is touched.
 */
internal object GlassFilter {
    /** Cube edge length; Core Image interpolates between the entries. */
    const val SIZE = 64

    /**
     * Premultiplied RGBA floats in the layout `CIColorCube` expects: red changes fastest, then green, then blue.
     * Each entry is the pixel as it should leave the window, before the window server blends it over the blur.
     */
    fun cube(surfaces: List<Surface>): FloatArray {
        val n = SIZE
        val keys = surfaces.map { floatArrayOf(it.color.red / 255f, it.color.green / 255f, it.color.blue / 255f) }
        val opacities = surfaces.map { it.opacity.coerceIn(0f, 1f) }
        val out = FloatArray(n * n * n * 4)
        var i = 0
        for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
            val r = ri / (n - 1f)
            val g = gi / (n - 1f)
            val b = bi / (n - 1f)
            // The surface the pixel is closest to: the one needing the least alpha to reproduce it.
            var alpha = 1f
            var key = -1
            for (k in keys.indices) {
                val a = alphaOver(r, g, b, keys[k])
                if (a < alpha || key < 0) {
                    alpha = a
                    key = k
                }
            }
            if (key < 0) {
                out[i] = r; out[i + 1] = g; out[i + 2] = b; out[i + 3] = 1f
            } else {
                // Colour-to-alpha leaves (pixel - (1 - a) * surface) premultiplied; the surface part that was taken out
                // comes back at the surface's opacity, which is the tint of the glass.
                val k = keys[key]
                val keep = (1 - alpha) * (1 - opacities[key])
                out[i] = r - keep * k[0]
                out[i + 1] = g - keep * k[1]
                out[i + 2] = b - keep * k[2]
                out[i + 3] = 1 - keep
            }
            i += 4
        }
        return out
    }

    /** Smallest alpha with which some colour painted over [key] gives exactly (r, g, b). */
    private fun alphaOver(r: Float, g: Float, b: Float, key: FloatArray): Float =
        maxOf(channel(r, key[0]), channel(g, key[1]), channel(b, key[2]))

    private fun channel(value: Float, base: Float): Float = when {
        value > base -> (value - base) / (1 - base).coerceAtLeast(1e-6f)
        value < base -> (base - value) / base.coerceAtLeast(1e-6f)
        else -> 0f
    }
}
