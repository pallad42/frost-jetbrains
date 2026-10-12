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
    val blurRadius: Int,
    val popupOpacity: Int,
    val popupBlurRadius: Int,
    /** `false`: fast colour matrix (Core Animation); `true`: exact colour table (Core Image), slower. */
    val exact: Boolean,
)

@Service(Service.Level.APP)
@State(name = "FrostSettings", storages = [Storage("frost.xml")])
class GlassSettings : SimplePersistentStateComponent<GlassSettings.Options>(Options()) {

    class Options : BaseState() {
        var enabled by property(true)
        var uiOpacity by property(85)
        var blurRadius by property(5)
        var popupOpacity by property(95)

        /** -1: not set yet, floating windows keep the blur of the main window (as before this setting existed). */
        var popupBlurRadius by property(-1)

        /** Rendering: fast (false) or exact (true), see [GlassConfig.exact]. */
        var exact by property(false)

        /** Registry keys switched off by Frost 0.1.x have been restored ([GlassManager.restoreLegacyRegistry]). */
        var legacyRegistryRestored by property(false)
    }

    var config: GlassConfig
        get() = state.toConfig()
        set(value) {
            state.enabled = value.enabled
            state.uiOpacity = value.uiOpacity
            state.blurRadius = value.blurRadius
            state.popupOpacity = value.popupOpacity
            state.popupBlurRadius = value.popupBlurRadius
            state.exact = value.exact
        }

    companion object {
        fun getInstance(): GlassSettings = service()

        /** The values a fresh install starts with. */
        val DEFAULTS: GlassConfig get() = Options().toConfig()

        private fun Options.toConfig() =
            GlassConfig(
                enabled, uiOpacity, blurRadius, popupOpacity,
                popupBlurRadius.takeIf { it >= 0 } ?: blurRadius, exact,
            )
    }
}
