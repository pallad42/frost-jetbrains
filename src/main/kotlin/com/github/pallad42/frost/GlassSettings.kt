package com.github.pallad42.frost

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** Snapshot of the effect parameters. Opacity values are percentages, blur radius is in points. */
data class GlassConfig(
    val enabled: Boolean,
    val uiOpacity: Int,
    val editorOpacity: Int,
    val blurRadius: Int,
    val highlightOpacity: Int,
    val popupOpacity: Int,
    val stickyLinesOpacity: Int,
    val popupBlurRadius: Int,
)

@Service(Service.Level.APP)
@State(name = "FrostSettings", storages = [Storage("frost.xml")])
class GlassSettings : SimplePersistentStateComponent<GlassSettings.Options>(Options()) {

    class Options : BaseState() {
        var enabled by property(true)
        var uiOpacity by property(85)
        var editorOpacity by property(0)
        var blurRadius by property(5)
        var highlightOpacity by property(70)
        var popupOpacity by property(95)
        var stickyLinesOpacity by property(85)

        /** -1: not set yet, floating windows keep the blur of the main window (as before this setting existed). */
        var popupBlurRadius by property(-1)
    }

    var config: GlassConfig
        get() = state.toConfig()
        set(value) {
            state.enabled = value.enabled
            state.uiOpacity = value.uiOpacity
            state.editorOpacity = value.editorOpacity
            state.blurRadius = value.blurRadius
            state.highlightOpacity = value.highlightOpacity
            state.popupOpacity = value.popupOpacity
            state.stickyLinesOpacity = value.stickyLinesOpacity
            state.popupBlurRadius = value.popupBlurRadius
        }

    companion object {
        fun getInstance(): GlassSettings = service()

        /** The values a fresh install starts with. */
        val DEFAULTS: GlassConfig get() = Options().toConfig()

        private fun Options.toConfig() =
            GlassConfig(
                enabled, uiOpacity, editorOpacity, blurRadius, highlightOpacity, popupOpacity, stickyLinesOpacity,
                popupBlurRadius.takeIf { it >= 0 } ?: blurRadius,
            )
    }
}
