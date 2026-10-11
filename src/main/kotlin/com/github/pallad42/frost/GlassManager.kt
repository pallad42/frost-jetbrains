package com.github.pallad42.frost

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.registry.Registry
import java.awt.AWTEvent
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dialog
import java.awt.Frame
import java.awt.Graphics2D
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import java.awt.event.ContainerEvent
import java.awt.event.WindowEvent
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.WeakHashMap
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.UIManager

/**
 * Applies the glass effect to every IDE window. Must be used on the EDT.
 *
 * One rule for everything: every background colour, whatever its source, is replaced by a translucent colour that
 * keeps its hue but lets the blur through ([Translucency.glass]). Neutral backgrounds become (almost) fully transparent,
 * coloured ones (selection, file colours, project gradient, highlights) keep their hue but let the blur through.
 *
 *  1. Windows get a transparent AWT background, so Swing renders into a translucent buffer.
 *  2. Natively the NSWindow gets one tint layer (theme panel colour at [GlassConfig.uiOpacity])
 *     and a window-server blur of what is behind it - see [NativeGlass].
 *  3. Background colours come from three places, all handled:
 *     theme (UIManager), colours set directly on components, and editor colour schemes ([GlassEditors]).
 *  4. IDE animations that snapshot UI into images without alpha (black frames) are switched off while active.
 */
@Service(Service.Level.APP)
class GlassManager : Disposable {
    private val log = logger<GlassManager>()

    /** Currently applied config, `null` when the effect is off. */
    private var active: GlassConfig? = null
    private var started = false

    /** Windows we made translucent, mapped to their original background. */
    private val windows = WeakHashMap<Window, Color?>()
    private val overriddenKeys = mutableSetOf<String>()
    private var registryOverrides: Disposable? = null

    /** Set when the IDE is closing: windows go away anyway, no need to restore them. */
    @Volatile
    var exiting = false

    /**
     * IntelliJ sets the frame background on startup and on every UI refresh. `Window.setBackground` fires this event
     * and only afterwards decides opaque vs translucent from the colour's alpha: reveal the real alpha of glass colours
     * for that moment so the window never flips to opaque. Anything else that still made it opaque is re-applied.
     */
    private val windowBackgroundListener = PropertyChangeListener { event ->
        val window = event.source as? Window ?: return@PropertyChangeListener
        if (active == null) return@PropertyChangeListener
        GlassColor.revealAlpha = true
        ApplicationManager.getApplication().invokeLater {
            // Still revealing: a glass colour set as window background is fine, only a really opaque one is not.
            GlassColor.revealAlpha = false
            val reallyOpaque = isReallyOpaque(window)
            if (reallyOpaque) {
                log.debug("Frost: ${window.javaClass.simpleName} was made opaque by ${describe(event.newValue)}, re-applying")
                active?.let { attach(window, it) }
            }
        }
    }

    /** `Window.isOpaque` as AWT's peer sees it: glass colours count as translucent here. */
    private fun isReallyOpaque(window: Window): Boolean {
        val revealing = GlassColor.revealAlpha
        GlassColor.revealAlpha = true
        try {
            return window.isOpaque
        } finally {
            GlassColor.revealAlpha = revealing
        }
    }

    private fun describe(value: Any?) = value?.let { "${it.javaClass.simpleName}($it)" } ?: "null"

    /** Colours set directly on components, often after they are shown (documentation popups, banners...). */
    private val componentBackgroundListener = PropertyChangeListener { event ->
        val config = active ?: return@PropertyChangeListener
        (event.source as? JComponent)?.let { GlassStats.measure("backgroundChanged") { glassifyComponent(it, config) } }
    }

    private val awtListener = AWTEventListener { event ->
        val config = active ?: return@AWTEventListener
        when (event) {
            // The native window was just created and is not on screen yet: switching it to translucent now
            // avoids a black first frame on every popup, tooltip and dialog.
            is HierarchyEvent -> {
                val source = event.source as? Component ?: return@AWTEventListener
                val flags = event.changeFlags
                when {
                    source is Window && (flags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L && source.isDisplayable ->
                        attach(source, config)
                    (flags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L && source.isShowing -> skipLoadingSnapshot(source)
                }
            }
            is ContainerEvent -> if (event.id == ContainerEvent.COMPONENT_ADDED) GlassStats.measure("componentAdded") { glassifyTree(event.child, config) }
            is WindowEvent -> if (event.id == WindowEvent.WINDOW_ACTIVATED) refreshNative(event.window, config)
            is ComponentEvent -> when (event.id) {
                ComponentEvent.COMPONENT_SHOWN -> dropLoadingSnapshot(event.component)
                // JBR may recreate the native layer on resize or when moving to another display.
                ComponentEvent.COMPONENT_RESIZED, ComponentEvent.COMPONENT_MOVED ->
                    (event.component as? Window)?.let { refreshNative(it, config) }
            }
        }
    }

    fun start() {
        if (started || !SystemInfoRt.isMac) return
        started = true
        Toolkit.getDefaultToolkit().addAWTEventListener(awtListener, AWTEvent.HIERARCHY_EVENT_MASK or AWTEvent.CONTAINER_EVENT_MASK or AWTEvent.COMPONENT_EVENT_MASK or AWTEvent.WINDOW_EVENT_MASK)
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                active?.let { GlassStats.measure("editorCreated") { GlassEditors.apply(event.editor, it) } }
                // The terminal installs its own highlighter right after creating the editor.
                ApplicationManager.getApplication().invokeLater {
                    active?.let { if (!event.editor.isDisposed) GlassTerminal.apply(event.editor, it) }
                }
            }
        }, this)
        apply(GlassSettings.getInstance().config)
    }

    /** Applies [config] right away; used both for saved settings and for live preview from the settings page. */
    fun apply(config: GlassConfig) {
        if (!started) return
        val previous = active
        if (!config.enabled) {
            if (previous != null) deactivate()
            return
        }
        active = config
        if (previous == null) disableSnapshotAnimations()
        val coloursChanged = previous == null || previous.highlightOpacity != config.highlightOpacity ||
            previous.popupOpacity != config.popupOpacity
        if (coloursChanged) {
            installColorOverrides(config)
            refreshUi()
        }
        Window.getWindows().forEach { attach(it, config) }
        if (coloursChanged || previous?.editorOpacity != config.editorOpacity || previous?.stickyLinesOpacity != config.stickyLinesOpacity) {
            EditorFactory.getInstance().allEditors.forEach { GlassEditors.apply(it, config) }
        }
    }

    /**
     * Theme or editor colour scheme changed: recompute colours that depend on it. IntelliJ fires these events in
     * bursts (project opening, plugins like Database loading, our own UI refresh) mostly without any actual change,
     * and recolouring every window and editor each time makes the UI flicker. Coalesce the burst into one check
     * and only recolour when the colours really changed.
     */
    fun onThemeChanged() {
        if (active == null || themeCheckPending) return
        themeCheckPending = true
        ApplicationManager.getApplication().invokeLater {
            themeCheckPending = false
            val config = active ?: return@invokeLater
            val signature = themeSignature()
            if (signature == appliedThemeSignature) return@invokeLater
            log.debug("Frost: theme changed, recolouring")
            installColorOverrides(config)
            Window.getWindows().forEach { attach(it, config) }
            EditorFactory.getInstance().allEditors.forEach { GlassEditors.apply(it, config) }
        }
    }

    private var themeCheckPending = false
    private var appliedThemeSignature: String? = null

    private fun themeSignature(): String {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return "${UIManager.getLookAndFeel()?.name}|${panelBackground().rgb}|${scheme.name}|${scheme.defaultBackground.rgb}"
    }

    private fun deactivate() {
        active = null
        registryOverrides?.let(Disposer::dispose)
        registryOverrides = null
        removeColorOverrides()
        for ((window, original) in windows.entries.toList()) {
            runCatching {
                window.removePropertyChangeListener("background", windowBackgroundListener)
                NativeGlass.reset(window)
                setWindowBackground(window, original ?: UIManager.getColor("Panel.background"))
                restoreTree(window)
            }.onFailure { log.warn("Frost: failed to restore window", it) }
        }
        windows.clear()
        refreshUi()
        EditorFactory.getInstance().allEditors.forEach(GlassEditors::restore)
    }

    /** Re-checks only the native side (tint, blur) of a window we already handle. */
    private fun refreshNative(window: Window, config: GlassConfig) {
        if (window in windows && window.isDisplayable) NativeGlass.apply(window, tint(window, config), config.blurRadius)
    }

    private fun attach(window: Window, config: GlassConfig) = GlassStats.measure("attachWindow") { attachImpl(window, config) }

    private fun attachImpl(window: Window, config: GlassConfig) {
        if (!window.isDisplayable) return
        try {
            // A new window may already carry a glass colour as background while its peer was created opaque
            // (the colour reported full alpha then), so always switch windows we see for the first time.
            val first = window !in windows
            var switched = false
            if (first || isReallyOpaque(window)) {
                if (window.graphicsConfiguration?.device?.fullScreenWindow === window) return
                if (first) {
                    windows[window] = window.background
                    window.addPropertyChangeListener("background", windowBackgroundListener)
                }
                makeTranslucent(window)
                ApplicationManager.getApplication().invokeLater { clearAndRepaint(window) }
                switched = true
            }
            glassifyTree(window, config)
            NativeGlass.apply(window, tint(window, config), config.blurRadius, force = switched)
        } catch (e: Throwable) {
            log.warn("Frost: failed to apply to ${window.javaClass.name}", e)
        }
    }

    /**
     * The peer must become translucent, but `Window.isOpaque()` should keep answering true: for a non-opaque window
     * `Window.paint` first wipes the whole window (JDK-8303950) and the display can show that empty frame before the
     * content is painted back - the flash when tool windows open. A glass colour with zero paint alpha does both:
     * while it is set, its real alpha is revealed so AWT switches the peer to translucent; afterwards it reports
     * full alpha again.
     */
    private fun makeTranslucent(window: Window) {
        val revealing = GlassColor.revealAlpha
        GlassColor.revealAlpha = true
        try {
            // Black, not the panel colour: Window.setBackground skips everything when the new colour equals the old
            // one, and IntelliJ may already have set the (glass) panel colour.
            setWindowBackground(window, GlassColor(Color.BLACK, 0, 1f))
        } finally {
            GlassColor.revealAlpha = revealing
        }
    }

    /**
     * Pixels painted while the window was still opaque stay solid black: normal painting blends over them.
     * Wipe the whole surface to transparent once and let Swing repaint everything through its translucent path.
     */
    private fun clearAndRepaint(window: Window) {
        if (!window.isDisplayable || isReallyOpaque(window)) return
        (window.graphics as? Graphics2D)?.let { g ->
            try {
                g.composite = AlphaComposite.Clear
                g.fillRect(0, 0, window.width, window.height)
            } finally {
                g.dispose()
            }
        }
        window.repaint()
    }

    private fun glassifyTree(component: Component, config: GlassConfig) {
        if (component is JComponent) {
            if (component.getClientProperty(GLASS_TRACKED) == null) {
                component.putClientProperty(GLASS_TRACKED, true)
                component.addPropertyChangeListener("background", componentBackgroundListener)
            }
            glassifyComponent(component, config)
        }
        if (component is Container) component.components.forEach { glassifyTree(it, config) }
    }

    private fun glassifyComponent(component: JComponent, config: GlassConfig) {
        if (component.javaClass.name in EDITOR_COMPONENTS || !component.isBackgroundSet) return
        val bg = component.background ?: return
        val factor = config.highlightOpacity / 100f
        val overlay = isOverlayRoot(component)
        val overlayAlpha = Translucency.percentToAlpha(config.popupOpacity)
        when {
            bg is GlassColor -> when {
                overlay && bg.paintAlpha < overlayAlpha -> component.background = GlassColor(bg.original, overlayAlpha, factor)
                !overlay && bg.factor != factor -> component.background = Translucency.glass(bg.original, panelBackground(), factor)
            }
            bg.alpha < 255 -> return
            overlay -> component.background = GlassColor(bg, overlayAlpha, factor)
            else -> component.background = Translucency.glass(bg, panelBackground(), factor)
        }
    }

    /**
     * Lightweight overlays - notifications, balloons, hints, in-window popups, also ones from other plugins with their
     * own colours - live on the upper layers of a layered pane, over the window content. Nothing blurred is under
     * them, only other UI, so their own background must stay solid like a floating window's.
     */
    private fun isOverlayRoot(component: JComponent): Boolean {
        val pane = component.parent as? JLayeredPane ?: return false
        return pane.getLayer(component) >= JLayeredPane.PALETTE_LAYER
    }

    private fun restoreTree(component: Component) {
        if (component is JComponent && component.getClientProperty(GLASS_TRACKED) != null) {
            component.removePropertyChangeListener("background", componentBackgroundListener)
            component.putClientProperty(GLASS_TRACKED, null)
            (component.background as? GlassColor)?.let { component.background = it.original }
        }
        if (component is Container) component.components.forEach(::restoreTree)
    }

    /**
     * Tool windows (Terminal, Git, Database...) show a loading layer that snapshots the panel into an image without
     * alpha: on glass that snapshot is black and flashes until loading ends. The layer only snapshots when it has a
     * size, and this runs synchronously right after it is made visible, before the snapshot: collapse it to 0x0.
     * The layout gives the size back on the next validation, so the spinner still shows.
     */
    private fun skipLoadingSnapshot(component: Component) {
        if (component.javaClass.name != LOADING_LAYER) return
        log.debug("Frost: loading layer shown (${component.width}x${component.height}), skipping its snapshot")
        component.setSize(0, 0)
        (component as? JComponent)?.revalidate()
    }

    /** Fallback for [skipLoadingSnapshot]: drop a snapshot that was taken anyway. */
    private fun dropLoadingSnapshot(component: Component) {
        if (component.javaClass.name != LOADING_LAYER) return
        runCatching {
            val field = component.javaClass.getDeclaredField("snapshot").apply { isAccessible = true }
            val snapshot = field.get(component) as? java.awt.image.BufferedImage ?: return@runCatching
            log.debug("Frost: loading layer snapshot was taken anyway, dropping it")
            snapshot.flush()
            field.set(component, null)
        }.onFailure { log.debug("Frost: cannot drop loading snapshot", it) }
    }

    private fun panelBackground(): Color =
        UIManager.getLookAndFeelDefaults().getColor("Panel.background") ?: Color.DARK_GRAY

    /** Popups, tooltips and dialogs float over other glass: a stronger tint keeps them readable. */
    private fun tint(window: Window, config: GlassConfig): Color =
        Translucency.withAlphaPercent(panelBackground(), if (window is Frame) config.uiOpacity else config.popupOpacity)

    private fun installColorOverrides(config: GlassConfig) {
        appliedThemeSignature = themeSignature()
        removeColorOverrides()
        val defaults = UIManager.getLookAndFeelDefaults()
        val base = panelBackground()
        val factor = config.highlightOpacity / 100f
        // Raw entries: avoids resolving lazy values (icons, borders) just to inspect them.
        for ((key, value) in defaults.entries.toList()) {
            if (key !is String || value !is Color || value.alpha < 255 || !isBackgroundKey(key)) continue
            val glass = if (Translucency.isOverlay(key)) {
                // Drawn on top of other UI (often inside the main window, with no blur underneath): keep it readable.
                GlassColor(value, Translucency.percentToAlpha(config.popupOpacity), factor)
            } else {
                val minAlpha = if (Translucency.isHighlight(key)) Translucency.HIGHLIGHT_MIN_ALPHA else 0f
                Translucency.glass(value, base, factor, minAlpha)
            }
            UIManager.put(key, glass)
            overriddenKeys += key
        }
        log.debug("Frost: ${overriddenKeys.size} theme background colours made translucent")
    }

    private fun removeColorOverrides() {
        overriddenKeys.forEach { UIManager.put(it, null) }
        overriddenKeys.clear()
    }

    private fun refreshUi() {
        LafManager.getInstance().updateUI()
        LafManager.getInstance().repaintUI()
    }

    /** These animations render parts of the UI into opaque images, which show up as black frames on glass. */
    private fun disableSnapshotAnimations() {
        val disposable = Disposer.newDisposable(this, "Frost registry overrides")
        registryOverrides = disposable
        for (key in SNAPSHOT_REGISTRY_KEYS) {
            runCatching { Registry.get(key).setValue(false, disposable) }
                .onFailure { log.debug("Frost: registry key $key not available") }
        }
    }

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

        private const val GLASS_TRACKED = "frost.tracked"
        private const val LOADING_LAYER = "com.intellij.openapi.ui.LoadingDecorator\$LoadingLayer"
        /** Editors get their background from [GlassEditors]; leave their components alone. */
        private val EDITOR_COMPONENTS = setOf(
            "com.intellij.openapi.editor.impl.EditorComponentImpl",
            "com.intellij.openapi.editor.impl.EditorGutterComponentImpl",
        )

        private val SNAPSHOT_REGISTRY_KEYS = listOf(
            "ide.experimental.ui.animations",
            "editor.zero.latency.rendering.double.buffering",
            "editor.skeleton.animation.enabled",
        )

        /** Theme keys holding a fill colour: any kind of background, file colours, gradients. */
        private fun isBackgroundKey(key: String): Boolean {
            val k = key.lowercase()
            if (k.contains("foreground") || k.contains("border")) return false
            return k.contains("background") || k.endsWith("bg") || k.contains(".bg") ||
                k.startsWith("filecolor.") || k.contains("gradient")
        }

        /**
         * `Frame`/`Dialog.setBackground` refuse translucent colours on decorated windows, while the underlying
         * `Window.setBackground` (which switches the peer to per-pixel translucency) supports them on macOS.
         * Call the `Window` implementation directly, skipping the override.
         */
        private fun setWindowBackground(window: Window, color: Color?) {
            val caller: Class<*> = when (window) {
                is Frame -> Frame::class.java
                is Dialog -> Dialog::class.java
                else -> {
                    window.background = color
                    return
                }
            }
            val setter = MethodHandles.privateLookupIn(caller, MethodHandles.lookup()).findSpecial(
                Window::class.java, "setBackground",
                MethodType.methodType(Void.TYPE, Color::class.java), caller,
            )
            synchronized(window.treeLock) {
                setter.invokeWithArguments(window, color)
            }
        }
    }
}
