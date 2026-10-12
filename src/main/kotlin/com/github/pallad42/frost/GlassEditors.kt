package com.github.pallad42.frost

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.DelegateColorScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.options.colors.ColorSettingsPages
import com.intellij.openapi.util.Key
import java.awt.Color

/**
 * Per-editor translucency. Everything goes into the editor's own scheme delegate, never into the user's scheme:
 * the editor background gets [GlassConfig.editorOpacity], every other background colour (caret row, selection,
 * gutter, search results, highlighted usages...) goes through [Translucency.glass] against the editor background.
 */
internal object GlassEditors {
    /**
     * What to put back on restore. Values the editor only inherited from the theme are not stored: after a theme
     * switch they would bring back the previous theme's colours. [INHERITED_COLOR] and a `null` attribute mean
     * "inherit again".
     */
    private val ORIGINAL_COLORS = Key.create<Map<ColorKey, Color?>>("frost.originalColors")
    private val ORIGINAL_ATTRIBUTES = Key.create<Map<TextAttributesKey, TextAttributes?>>("frost.originalAttributes")
    private val INHERITED_COLOR: Color = AbstractColorsScheme.INHERITED_COLOR_MARKER

    fun apply(editor: Editor, config: GlassConfig) {
        val ex = editor as? EditorEx ?: return
        restore(ex)
        val scheme = ex.colorsScheme
        val theme = themeOf(scheme)
        val base = scheme.defaultBackground
        val factor = config.highlightOpacity / 100f

        val colors = HashMap<ColorKey, Color?>()
        for (key in colorKeys()) {
            if (!isBackgroundKey(key.externalName)) continue
            val color = scheme.getColor(key) ?: continue
            if (color.alpha < 255) continue
            colors[key] = if (theme != null && theme.getColor(key) == color) INHERITED_COLOR else color
            scheme.setColor(key, glass(color, base, factor, key.externalName))
        }

        // Overlays painted on top of scrolling text (sticky lines) cannot show the window blur, only what is under
        // them in the editor: give them an editor-coloured background of their own opacity.
        for (key in OVERLAY_KEYS) {
            if (key !in colors) colors[key] = scheme.getColor(key).let { if (theme != null && theme.getColor(key) == it) INHERITED_COLOR else it }
            scheme.setColor(key, Translucency.withAlphaPercent(base, config.stickyLinesOpacity))
        }

        val attributes = HashMap<TextAttributesKey, TextAttributes?>()
        for (key in attributeKeys) {
            if (TextAttributesKey.isTemp(key)) continue
            val original = scheme.getAttributes(key) ?: continue
            val background = original.backgroundColor ?: continue
            if (background.alpha < 255) continue
            attributes[key] = if (theme != null && theme.getAttributes(key) == original) null else original
            scheme.setAttributes(key, original.clone().apply { backgroundColor = glass(background, base, factor, key.externalName) })
        }

        ex.putUserData(ORIGINAL_COLORS, colors)
        ex.putUserData(ORIGINAL_ATTRIBUTES, attributes)
        ex.setBackgroundColor(Translucency.withAlphaPercent(base, config.editorOpacity))
        ex.reinitSettings()
        GlassTerminal.apply(ex, config)
    }

    /**
     * The scheme an editor inherits from, when its scheme is the editor's own delegate: only that one keeps overrides
     * per editor and can drop them again (other delegates would write through to the shared scheme).
     */
    private fun themeOf(scheme: EditorColorsScheme): EditorColorsScheme? =
        (scheme as? DelegateColorScheme)?.takeIf { it.javaClass.name == EDITOR_SCHEME_DELEGATE }?.delegate

    private const val EDITOR_SCHEME_DELEGATE = "com.intellij.openapi.editor.impl.EditorColorSchemeDelegate"

    /** An owner (e.g. EditorTextField) replaced the background with a solid one: make that one translucent too. */
    fun keepBackgroundTranslucent(editor: Editor, config: GlassConfig) {
        val ex = editor as? EditorEx ?: return
        val background = ex.backgroundColor
        if (background.alpha == 255) ex.setBackgroundColor(Translucency.withAlphaPercent(background, config.editorOpacity))
    }

    fun restore(editor: Editor) {
        val ex = editor as? EditorEx ?: return
        GlassTerminal.restore(ex)
        val colors = ex.getUserData(ORIGINAL_COLORS)
        val attributes = ex.getUserData(ORIGINAL_ATTRIBUTES)
        if (colors == null && attributes == null) return
        val scheme = ex.colorsScheme
        val theme = themeOf(scheme)
        colors?.forEach { (key, color) -> scheme.setColor(key, color) }
        // The scheme delegate cannot drop an attribute override: inherit by copying the current theme's value.
        attributes?.forEach { (key, value) -> (value ?: theme?.getAttributes(key))?.let { scheme.setAttributes(key, it) } }
        ex.putUserData(ORIGINAL_COLORS, null)
        ex.putUserData(ORIGINAL_ATTRIBUTES, null)
        ex.setBackgroundColor(null)
        ex.reinitSettings()
    }

    /** In the editor almost every background is a highlight on top of the text area: keep them visible. */
    private fun glass(color: Color, base: Color, factor: Float, name: String): Color {
        val surface = name.uppercase().let { n -> SURFACES.any { n.contains(it) } }
        return Translucency.glass(color, base, factor, if (surface) 0f else Translucency.HIGHLIGHT_MIN_ALPHA)
    }

    private val OVERLAY_KEYS = listOf(EditorColors.STICKY_LINES_BACKGROUND)

    /** Editor colours that are surfaces (whole areas) rather than highlights. */
    private val SURFACES = listOf("GUTTER", "READONLY", "DOCUMENTATION", "LOOKUP", "NOTIFICATION", "TOOLTIP")

    /** Every highlighting key a colour settings page knows about (public API; the plugin set rarely changes). */
    private val attributeKeys: List<TextAttributesKey> by lazy {
        ColorSettingsPages.getInstance().registeredPages
            .flatMap { page -> page.attributeDescriptors.map { it.key } }
            .distinct()
    }

    private fun colorKeys(): Collection<ColorKey> =
        (EditorColorsManager.getInstance().globalScheme as? AbstractColorsScheme)?.colorKeys ?: emptyList()

    private fun isBackgroundKey(name: String): Boolean {
        val n = name.uppercase()
        if (n.contains("FOREGROUND")) return false
        return n.contains("BACKGROUND") || n.contains("_ROW") || n.contains("BG") ||
            n == "DOCUMENTATION_COLOR" || n == "LOOKUP_COLOR"
    }
}
