package com.github.pallad42.frost

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.HighlighterClient
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.tree.IElementType
import java.awt.Color
import java.util.WeakHashMap

/**
 * Terminal output colours (ANSI backgrounds, e.g. diffs printed by CLI tools) come straight from the terminal's own
 * highlighter, not from any colour scheme. Wrap that highlighter so every background it reports is made translucent.
 * Everything else is passed through unchanged, including document events.
 */
internal object GlassTerminal {
    private const val TERMINAL_HIGHLIGHTER_PACKAGE = "org.jetbrains.plugins.terminal."

    fun apply(editor: Editor, config: GlassConfig) {
        val ex = editor as? EditorEx ?: return
        val current = ex.highlighter
        val original = (current as? TranslucentHighlighter)?.delegate ?: current
        if (!original.javaClass.name.startsWith(TERMINAL_HIGHLIGHTER_PACKAGE)) return
        if (current is TranslucentHighlighter && current.factor == config.highlightOpacity / 100f) return
        ex.highlighter = TranslucentHighlighter(original, ex.colorsScheme.defaultBackground, config.highlightOpacity / 100f)
    }

    fun restore(editor: Editor) {
        val ex = editor as? EditorEx ?: return
        (ex.highlighter as? TranslucentHighlighter)?.let { ex.highlighter = it.delegate }
    }

    private class TranslucentHighlighter(val delegate: EditorHighlighter, private val base: Color, val factor: Float) :
        EditorHighlighter {
        /** Terminal styles are few and reused: translate each once. */
        private val cache = WeakHashMap<TextAttributes, TextAttributes>()

        fun translucent(attributes: TextAttributes?): TextAttributes? {
            val background = attributes?.backgroundColor ?: return attributes
            if (background.alpha < 255) return attributes
            return cache.getOrPut(attributes) {
                attributes.clone().apply {
                    backgroundColor = Translucency.glass(background, base, factor, Translucency.HIGHLIGHT_MIN_ALPHA)
                }
            }
        }

        override fun createIterator(startOffset: Int): HighlighterIterator = Iterator(delegate.createIterator(startOffset))

        override fun setText(text: CharSequence) = delegate.setText(text)
        override fun setEditor(editor: HighlighterClient) = delegate.setEditor(editor)
        override fun setColorScheme(scheme: EditorColorsScheme) = delegate.setColorScheme(scheme)
        override fun beforeDocumentChange(event: DocumentEvent) = delegate.beforeDocumentChange(event)
        override fun documentChanged(event: DocumentEvent) = delegate.documentChanged(event)
        override fun bulkUpdateStarting(document: Document) = delegate.bulkUpdateStarting(document)
        override fun bulkUpdateFinished(document: Document) = delegate.bulkUpdateFinished(document)

        private inner class Iterator(private val it: HighlighterIterator) : HighlighterIterator {
            override fun getTextAttributes(): TextAttributes? = translucent(it.textAttributes)
            override fun getTextAttributesKeys(): Array<TextAttributesKey> = it.textAttributesKeys
            override fun getStart() = it.start
            override fun getEnd() = it.end
            override fun getTokenType(): IElementType? = it.tokenType
            override fun advance() = it.advance()
            override fun retreat() = it.retreat()
            override fun atEnd() = it.atEnd()
            override fun getDocument(): Document = it.document
        }
    }
}
