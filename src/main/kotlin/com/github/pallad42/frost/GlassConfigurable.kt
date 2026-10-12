package com.github.pallad42.frost

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.JSlider

/** Settings | Appearance & Behavior | Frost. Every change is previewed live; Cancel reverts to the saved values. */
class GlassConfigurable : Configurable {
    private lateinit var enabled: JBCheckBox
    private lateinit var uiOpacity: JSlider
    private lateinit var editorOpacity: JSlider
    private lateinit var blurRadius: JSlider
    private lateinit var highlightOpacity: JSlider
    private lateinit var popupOpacity: JSlider
    private lateinit var stickyLinesOpacity: JSlider
    private lateinit var popupBlurRadius: JSlider

    override fun getDisplayName() = "Frost"

    override fun createComponent(): JComponent {
        val content = panel {
            row {
                enabled = checkBox("Enable Frost (macOS only for now)").component
            }
            row("UI opacity (%):") {
                uiOpacity = slider(0, 100, 5, 25).applyToComponent { paintLabels = true }.component
            }
            row("Floating windows opacity (%):") {
                popupOpacity = slider(0, 100, 5, 25).applyToComponent { paintLabels = true }.component
            }
            row("Editor opacity (%):") {
                editorOpacity = slider(0, 100, 5, 25).applyToComponent { paintLabels = true }.component
            }
            row("Sticky lines opacity (%):") {
                stickyLinesOpacity = slider(0, 100, 5, 25).applyToComponent { paintLabels = true }.component
            }
            row("Blur radius:") {
                blurRadius = slider(0, 60, 5, 15).applyToComponent { paintLabels = true }.component
            }
            row("Floating windows blur radius:") {
                popupBlurRadius = slider(0, 60, 5, 15).applyToComponent { paintLabels = true }.component
            }
            row("Highlight opacity (%):") {
                highlightOpacity = slider(0, 100, 5, 25).applyToComponent { paintLabels = true }.component
            }
            row {
                button("Reset to Defaults") { show(GlassSettings.DEFAULTS) }
                    .comment("Previews the default values; press Apply or OK to keep them.")
            }
            row {
                comment("UI opacity is the tint of the main window, floating windows opacity of popups, tooltips, notifications and dialogs, editor opacity is added on top of it. Highlight opacity weakens coloured backgrounds (selection, file colours, current line...). Blur radius is for the main window, floating windows blur radius for the rest. Blur 0 = no blur.")
            }
        }
        reset()
        enabled.addItemListener { preview() }
        listOf(uiOpacity, popupOpacity, editorOpacity, stickyLinesOpacity, blurRadius, popupBlurRadius, highlightOpacity).forEach { it.addChangeListener { preview() } }
        return content
    }

    private fun current() =
        GlassConfig(
            enabled.isSelected, uiOpacity.value, editorOpacity.value, blurRadius.value, highlightOpacity.value,
            popupOpacity.value, stickyLinesOpacity.value, popupBlurRadius.value,
        )

    /**
     * Recolouring the whole UI is expensive: while a slider that needs it is dragged, only the cheap parts follow
     * and the theme colours catch up on release.
     */
    private fun preview() = GlassManager.getInstance().apply(
        current(),
        deferRecolour = popupOpacity.valueIsAdjusting || highlightOpacity.valueIsAdjusting,
    )

    override fun isModified() = current() != GlassSettings.getInstance().config

    override fun apply() {
        GlassSettings.getInstance().config = current()
        preview()
    }

    override fun reset() = show(GlassSettings.getInstance().config)

    /** Puts [config] into the controls and previews it. */
    private fun show(config: GlassConfig) {
        enabled.isSelected = config.enabled
        uiOpacity.value = config.uiOpacity
        editorOpacity.value = config.editorOpacity
        blurRadius.value = config.blurRadius
        highlightOpacity.value = config.highlightOpacity
        popupOpacity.value = config.popupOpacity
        stickyLinesOpacity.value = config.stickyLinesOpacity
        popupBlurRadius.value = config.popupBlurRadius
        GlassManager.getInstance().apply(config)
    }

    override fun disposeUIResources() {
        GlassManager.getInstance().apply(GlassSettings.getInstance().config)
    }
}
