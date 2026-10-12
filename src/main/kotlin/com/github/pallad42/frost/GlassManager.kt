package com.github.pallad42.frost

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.registry.Registry
import java.awt.AWTEvent
import java.awt.Color
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import java.awt.event.ContainerEvent
import java.awt.event.HierarchyEvent
import java.awt.event.WindowEvent
import java.util.WeakHashMap
import javax.swing.JRootPane
import javax.swing.UIManager

/**
 * Applies the glass effect to every IDE window. Must be used on the EDT.
 *
 * The IDE keeps painting itself fully opaque, exactly as its themes and components intend; nothing in Swing is
 * changed. Each native window gets a colour table ([GlassFilter]) that Core Animation applies to the window's finished
 * pixels, turning the theme's surface colours into translucent glass over a window-server blur ([NativeGlass]).
 * Because the effect works on the final pixels, it does not matter how anything was painted: images without alpha,
 * cached cells, `clearRect`, stacked backgrounds, animations and other plugins' components all come out right.
 */
@Service(Service.Level.APP)
class GlassManager : Disposable {
    private val log = logger<GlassManager>()

    /** Currently applied config, `null` when the effect is off. */
    private var active: GlassConfig? = null
    private var started = false

    /** Windows we gave the effect. */
    private val windows = WeakHashMap<Window, Unit>()

    /** Colour tables for the main window and for floating windows (popups, tooltips, dialogs). */
    private var frameCube: NativeGlass.Cube? = null
    private var floatingCube: NativeGlass.Cube? = null

    /** What the colour tables were built from: rebuilt only when this changes. */
    private var cubeSignature: String? = null

    /** Set when the IDE is closing: windows go away anyway, no need to restore them. */
    @Volatile
    var exiting = false

    private val awtListener = AWTEventListener { event ->
        val config = active ?: return@AWTEventListener
        when (event) {
            // A new native window, or a known window whose native window was rebuilt (switching projects reuses
            // the frame but recreates its peer).
            is HierarchyEvent -> {
                val source = event.source as? Window ?: return@AWTEventListener
                if ((event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L && source.isDisplayable) {
                    attach(source, config, force = true)
                }
            }
            // Switching projects reuses the frame with a new root pane; IntelliJ then re-applies the root pane's
            // client properties to the native window, which can make it opaque again.
            is ContainerEvent -> if (event.id == ContainerEvent.COMPONENT_ADDED && event.child is JRootPane) {
                (event.container as? Window)?.let(::reapplySoon)
            }
            is WindowEvent -> if (event.id == WindowEvent.WINDOW_ACTIVATED) attach(event.window, config)
            is ComponentEvent -> when (event.id) {
                // Popups get the blur only once they are on screen; AppKit orders them in asynchronously,
                // so check again right after showing and a moment later.
                ComponentEvent.COMPONENT_SHOWN -> (event.component as? Window)?.let { window ->
                    attach(window, config)
                    javax.swing.Timer(120) { active?.let { attach(window, it) } }.apply { isRepeats = false }.start()
                }
                // JBR may recreate the native layer on resize or when moving to another display.
                ComponentEvent.COMPONENT_RESIZED, ComponentEvent.COMPONENT_MOVED ->
                    (event.component as? Window)?.let { attach(it, config) }
            }
        }
    }

    fun start() {
        if (started || !SystemInfoRt.isMac) return
        started = true
        Toolkit.getDefaultToolkit().addAWTEventListener(
            awtListener,
            AWTEvent.HIERARCHY_EVENT_MASK or AWTEvent.CONTAINER_EVENT_MASK or AWTEvent.COMPONENT_EVENT_MASK or AWTEvent.WINDOW_EVENT_MASK,
        )
        restoreLegacyRegistry()
        apply(GlassSettings.getInstance().config)
    }

    /**
     * Frost 0.1.x switched these off while it was active, meant to be temporary, but the IDE saved the values on exit
     * and kept them off for good. The current engine needs none of them: give them back their defaults, once.
     */
    private fun restoreLegacyRegistry() {
        val state = GlassSettings.getInstance().state
        if (state.legacyRegistryRestored) return
        for (value in Registry.getAll()) {
            if (value.key !in LEGACY_REGISTRY_KEYS || !value.isChangedFromDefault() || value.asBoolean()) continue
            runCatching { value.resetToDefault() }
                .onSuccess { log.info("Frost: restored registry key ${value.key} switched off by an older Frost") }
                .onFailure { log.warn("Frost: cannot restore registry key ${value.key}", it) }
        }
        state.legacyRegistryRestored = true
    }

    /** Applies [config] right away; used both for saved settings and for live preview from the settings page. */
    fun apply(config: GlassConfig) {
        if (!started) return
        if (!config.enabled) {
            if (active != null) deactivate()
            return
        }
        active = config
        val rebuilt = rebuildCubes(config)
        Window.getWindows().forEach { attach(it, config, force = rebuilt) }
    }

    /**
     * Theme or editor colour scheme changed: the surface colours may be different now. IntelliJ fires these events in
     * bursts, mostly without any actual change; coalesce them and only rebuild when the colours really changed.
     */
    fun onThemeChanged() {
        if (active == null || themeCheckPending) return
        themeCheckPending = true
        ApplicationManager.getApplication().invokeLater {
            themeCheckPending = false
            active?.let(::apply)
        }
    }

    private var themeCheckPending = false

    /** Builds the colour tables for [config] and the current theme unless they are already up to date. */
    private fun rebuildCubes(config: GlassConfig): Boolean {
        val frameSurfaces = frameSurfaces(config)
        val floatingSurfaces = floatingSurfaces(config)
        val signature = "$frameSurfaces|$floatingSurfaces"
        if (signature == cubeSignature && frameCube != null) return false
        cubeSignature = signature
        val oldCubes = listOfNotNull(frameCube, floatingCube)
        // The colour matrix keys on one colour: the editor's (and panels') in the main window, the popups' elsewhere.
        val editorRgb = editorBackground().rgb and 0xffffff
        val frameBase = frameSurfaces.firstOrNull { (it.color.rgb and 0xffffff) == editorRgb } ?: frameSurfaces.first()
        frameCube = GlassStats.measure("buildCube") { NativeGlass.upload(GlassFilter.cube(frameSurfaces), GlassFilter.matrix(frameBase)) }
        floatingCube = GlassStats.measure("buildCube") { NativeGlass.upload(GlassFilter.cube(floatingSurfaces), GlassFilter.matrix(floatingSurfaces.first())) }
        // Queued after the windows are switched to the new tables (all native work runs in order on the main thread).
        ApplicationManager.getApplication().invokeLater { oldCubes.forEach(NativeGlass::release) }
        log.debug("Frost: colour tables rebuilt, main window $frameSurfaces, floating windows $floatingSurfaces")
        return true
    }

    /**
     * Main window: the window background, panels, tool windows and the editor, all at UI opacity. Popups drawn inside
     * the window (notifications, balloons, hints) need nothing extra: Swing paints their background over the code
     * first, so their glass shows the blurred desktop like the rest of the window, not the code under them.
     */
    private fun frameSurfaces(config: GlassConfig): List<Surface> {
        val ui = config.uiOpacity / 100f
        return distinct((SURFACE_KEYS.mapNotNull(::themeColor) + editorBackground()).map { Surface(it, ui) })
    }

    /** Popups, tooltips and dialogs float over other glass: every surface gets the stronger floating windows opacity. */
    private fun floatingSurfaces(config: GlassConfig): List<Surface> {
        val popup = config.popupOpacity / 100f
        return distinct((POPUP_KEYS + SURFACE_KEYS).mapNotNull(::themeColor).plus(editorBackground()).map { Surface(it, popup) })
    }

    private fun distinct(surfaces: List<Surface>) = surfaces.distinctBy { it.color.rgb and 0xffffff }

    private fun themeColor(key: String): Color? = UIManager.getColor(key)?.takeIf { it.alpha == 255 }

    private fun editorBackground(): Color = EditorColorsManager.getInstance().globalScheme.defaultBackground

    private fun deactivate() {
        active = null
        for (window in windows.keys.toList()) {
            runCatching { NativeGlass.reset(window) }.onFailure { log.warn("Frost: failed to restore window", it) }
        }
        windows.clear()
    }

    /** Runs after IntelliJ has finished updating the native window for the new root pane, and once more a moment later. */
    private fun reapplySoon(window: Window) {
        ApplicationManager.getApplication().invokeLater { active?.let { attach(window, it, force = true) } }
        javax.swing.Timer(120) { active?.let { attach(window, it, force = true) } }.apply { isRepeats = false }.start()
    }

    private fun attach(window: Window, config: GlassConfig, force: Boolean = false) = GlassStats.measure("attachWindow") {
        if (!window.isDisplayable) return@measure
        // Exclusive full screen has no desktop behind it to show.
        if (window.graphicsConfiguration?.device?.fullScreenWindow === window) return@measure
        val cube = (if (window is Frame) frameCube else floatingCube) ?: return@measure
        try {
            windows[window] = Unit
            NativeGlass.apply(window, cube, blur(window, config), config.exact, force)
        } catch (e: Throwable) {
            log.warn("Frost: failed to apply to ${window.javaClass.name}", e)
        }
    }

    private fun blur(window: Window, config: GlassConfig): Int =
        if (window is Frame) config.blurRadius else config.popupBlurRadius

    override fun dispose() {
        if (!started) return
        Toolkit.getDefaultToolkit().removeAWTEventListener(awtListener)
        // On IDE exit the windows are going away anyway; only restore on plugin unload.
        val app = ApplicationManager.getApplication()
        if (active != null && !exiting && !app.isDisposed) {
            if (app.isDispatchThread) deactivate()
            else app.invokeAndWait(::deactivate)
        }
    }

    companion object {
        fun getInstance(): GlassManager = service()

        /** Backgrounds the window is made of: the window itself, tool windows / islands, panels, dialogs. */
        private val SURFACE_KEYS = listOf("MainWindow.background", "ToolWindow.background", "Panel.background")

        /** Registry keys Frost 0.1.x switched off (see [restoreLegacyRegistry]). */
        private val LEGACY_REGISTRY_KEYS = setOf(
            "ide.experimental.ui.animations",
            "editor.zero.latency.rendering.double.buffering",
            "editor.skeleton.animation.enabled",
            "database.grid.render.cache",
            "database.grid.render.cache.fast.scroll",
        )

        /** Backgrounds of popups, tooltips and notifications, the main surfaces of floating windows. */
        private val POPUP_KEYS = listOf("Popup.background", "PopupMenu.background", "ToolTip.background", "Notification.background")
    }
}

