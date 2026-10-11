package com.github.pallad42.frost

import java.awt.Color
import javax.swing.plaf.ColorUIResource

/**
 * A translucent replacement for an opaque colour: same RGB as [original], painted with `paintAlpha`, but it reports
 * full alpha, so code that insists on opaque colours (e.g. `Frame.setBackground` on decorated frames, "is this colour
 * opaque" checks) keeps treating it like the original. Keeping the original RGB matters: IntelliJ derives
 * hover/gradient/border shades from background colours, and those must stay sane.
 */
internal class GlassColor(val original: Color, val paintAlpha: Int, val factor: Float) :
    ColorUIResource(Color(original.red, original.green, original.blue, paintAlpha)) {
    override fun getAlpha() = if (revealAlpha) super.getAlpha() else 255

    companion object {
        /**
         * While set, glass colours report their real alpha. `Window.setBackground` reads the alpha right after firing
         * the "background" property change: answering honestly there keeps the window translucent instead of flipping
         * it to opaque (which swaps the native surface for a black one for a frame).
         */
        @Volatile
        var revealAlpha = false
    }
}

internal object Translucency {
    /** How strongly the distance from the base colour turns into opacity. */
    private const val CONTRAST_BOOST = 2.5f

    /** Below this strength a colour counts as a neutral surface and becomes fully transparent. */
    private const val NEUTRAL_CUTOFF = 0.5f

    /**
     * Translucent version of [color] painted over [base]: the further the colour is from the base, the more opaque it
     * stays, so neutral surfaces (panels, toolbars, popups) almost vanish while coloured ones keep their hue.
     * [minAlpha] keeps subtle highlights (current line, hover) visible. [factor] (0..1) weakens everything.
     */
    fun glass(color: Color, base: Color, factor: Float, minAlpha: Float = 0f): GlassColor {
        val distance = maxOf(
            channelDistance(color.red, base.red),
            channelDistance(color.green, base.green),
            channelDistance(color.blue, base.blue),
        )
        val strength = distance * CONTRAST_BOOST
        // Neutral surfaces (panels, toolbars, popups, the area under the editor) must not paint anything: containers
        // are nested, and stacked translucent fills make the result depend on which component repaints (pulsing).
        // The window's single native tint is their background.
        if (strength < NEUTRAL_CUTOFF && minAlpha == 0f) return GlassColor(color, 0, factor)
        val alpha = strength.coerceIn(minAlpha, 1f) * factor
        return GlassColor(color, (alpha * 255).toInt().coerceIn(0, 255), factor)
    }

    /** Smallest alpha with which a colour could reproduce [value] over [base] in this channel. */
    private fun channelDistance(value: Int, base: Int): Float {
        val d = value - base
        return when {
            d > 0 -> d / (255f - base).coerceAtLeast(1f)
            d < 0 -> -d / base.toFloat().coerceAtLeast(1f)
            else -> 0f
        }
    }

    /** Keys / names of backgrounds that mark state rather than surfaces: they must never fully disappear. */
    fun isHighlight(name: String): Boolean {
        val n = name.lowercase()
        return HIGHLIGHT_WORDS.any { n.contains(it) }
    }

    const val HIGHLIGHT_MIN_ALPHA = 0.45f

    /** Backgrounds of things floating over other UI: notifications, balloons, tooltips, hints, popups. */
    fun isOverlay(key: String): Boolean {
        val k = key.lowercase()
        return OVERLAY_WORDS.any { k.contains(it) }
    }

    private val OVERLAY_WORDS = listOf("notification", "balloon", "tooltip", "hint", "toast", "gotit", "popup")

    fun percentToAlpha(percent: Int): Int = (percent.coerceIn(0, 100) * 255 + 50) / 100

    private val HIGHLIGHT_WORDS = listOf("select", "hover", "press", "focus", "highlight", "caret", "_row", "match", "search")

    fun withAlphaPercent(color: Color, percent: Int): Color =
        Color(color.red, color.green, color.blue, (percent.coerceIn(0, 100) * 255 + 50) / 100)
}
