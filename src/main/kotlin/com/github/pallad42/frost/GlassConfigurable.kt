package com.github.pallad42.frost

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.JSlider

/** Settings | Appearance & Behavior | Frost. Every change is previewed live; Cancel reverts to the saved values. */
class GlassConfigurable : Configurable {
    private lateinit var enabled: JBCheckBox
    private lateinit var uiOpacity: JSlider
    private lateinit var blurRadius: JSlider
    private lateinit var popupOpacity: JSlider
    private lateinit var popupBlurRadius: JSlider
    private lateinit var rendering: ComboBox<String>

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
            row("Blur radius:") {
                blurRadius = slider(0, 60, 5, 15).applyToComponent { paintLabels = true }.component
            }
            row("Floating windows blur radius:") {
                popupBlurRadius = slider(0, 60, 5, 15).applyToComponent { paintLabels = true }.component
            }
            row("Rendering:") {
                rendering = comboBox(listOf(FAST, EXACT)).component
            }.rowComment("Fast is several times lighter for macOS but uses a private macOS filter and approximates how see-through coloured pixels are. Exact uses public Core Image with exact colours, but makes scrolling and animations noticeably heavier.")
            row {
                button("Reset to Defaults") { show(GlassSettings.DEFAULTS) }
                    .comment("Previews the default values; press Apply or OK to keep them.")
            }
            row {
                comment("UI opacity is how solid the backgrounds of the main window stay, floating windows opacity the same for popups, menus, tooltips and dialogs (notifications inside the main window follow UI opacity). Text, icons and coloured backgrounds stay as they are. Blur radius is for the main window, floating windows blur radius for the rest. Blur 0 = no blur.")
            }
        }
        reset()
        enabled.addItemListener { preview() }
        listOf(uiOpacity, popupOpacity, blurRadius, popupBlurRadius).forEach { it.addChangeListener { preview() } }
        rendering.addActionListener { preview() }
        return content
    }

    private fun current() =
        GlassConfig(
            enabled.isSelected, uiOpacity.value, blurRadius.value, popupOpacity.value,
            popupBlurRadius.value, rendering.selectedItem == EXACT,
        )

    private fun preview() = GlassManager.getInstance().apply(current())

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
        blurRadius.value = config.blurRadius
        popupOpacity.value = config.popupOpacity
        popupBlurRadius.value = config.popupBlurRadius
        rendering.selectedItem = if (config.exact) EXACT else FAST
        GlassManager.getInstance().apply(config)
    }

    override fun disposeUIResources() {
        GlassManager.getInstance().apply(GlassSettings.getInstance().config)
    }

    private companion object {
        const val FAST = "Fast"
        const val EXACT = "Exact"
    }
}
