package com.github.pallad42.frost

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.ID
import com.intellij.ui.mac.foundation.MacUtil
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import java.awt.Window

/**
 * AppKit side of the effect: makes the NSWindow and its content layer non-opaque, puts the [GlassFilter] colour table
 * on the content layer as a Core Image filter (Core Animation runs it on the window's finished pixels, on the GPU)
 * and asks the window server to blur whatever is behind the window. The blur is the same private CoreGraphics call
 * Ghostty uses for `background-blur`.
 */
internal object NativeGlass {
    private val LOG = logger<NativeGlass>()

    private val blurRadiusFn: Function? by lazy { cgFunction("CGSSetWindowBackgroundBlurRadius") }
    private val connectionFn: Function? by lazy { cgFunction("CGSDefaultConnectionForThread") }

    /** CIFilter lives in CoreImage, which AppKit does not always load up front. */
    private val coreImageLoaded by lazy {
        runCatching { NativeLibrary.getInstance("/System/Library/Frameworks/CoreImage.framework/CoreImage") }
            .onFailure { LOG.warn("Frost: CoreImage not available", it) }
            .isSuccess
    }

    /** A colour table uploaded once as NSData; every window using it gets its own filter object. */
    class Cube internal constructor(internal val data: Long) {
        @Volatile internal var released = false
    }

    /** Copies [values] (from [GlassFilter.cube]) into a retained NSData. Release it with [release]. */
    fun upload(values: FloatArray): Cube {
        val bytes = values.size.toLong() * 4
        val memory = Memory(bytes)
        memory.write(0, values, 0, values.size)
        val data = Foundation.invoke(Foundation.invoke("NSData", "alloc"), "initWithBytes:length:", memory, bytes)
        return Cube(data.toLong())
    }

    /** Frees [cube] once every window switched to another one (runs after the main thread work queued so far). */
    fun release(cube: Cube) {
        Foundation.executeOnMainThread(true, false) {
            if (!cube.released) {
                cube.released = true
                Foundation.invoke(ID(cube.data), "release")
            }
        }
    }

    /**
     * What was last sent to each native window. Re-sending identical state makes the window server redraw the window
     * (flicker), but AppKit/JBR may also reset the layer (window moved to another display, peer recreated, opacity
     * re-applied by AWT), which silently drops the effect - so the check compares against the live layer.
     */
    private data class Applied(val layer: Long, val filter: Long, val data: Long, val blurRadius: Int)

    private val applied = java.util.concurrent.ConcurrentHashMap<Long, Applied>()

    /** Cheap to call repeatedly: only touches the native window when its state actually differs. */
    fun apply(window: Window, cube: Cube, blurRadius: Int, force: Boolean = false) {
        val nsWindow = nsWindowOf(window) ?: return
        val key = nsWindow.toLong()
        Foundation.executeOnMainThread(true, false) {
            if (!window.isDisplayable || cube.released || !coreImageLoaded) return@executeOnMainThread
            val layer = contentLayer(nsWindow) ?: return@executeOnMainThread
            val last = applied[key]
            if (!force && last != null && last.layer == layer.toLong() && last.data == cube.data &&
                last.blurRadius == blurRadius && isStillOurs(nsWindow, layer, last)
            ) return@executeOnMainThread
            Foundation.invoke(nsWindow, "setOpaque:", false)
            Foundation.invoke(nsWindow, "setBackgroundColor:", Foundation.invoke("NSColor", "clearColor"))
            setLayerTreeOpaque(layer, false)
            val contentView = Foundation.invoke(nsWindow, "contentView")
            Foundation.invoke(contentView, "setLayerUsesCoreImageFilters:", true)
            val filter = createFilter(cube) ?: return@executeOnMainThread
            Foundation.invoke(layer, "setFilters:", Foundation.invoke("NSArray", "arrayWithObject:", filter))
            setBlurRadius(nsWindow, blurRadius)
            // Only remember windows that are really on screen; others get re-applied once they are shown.
            if (isOnScreen(nsWindow)) applied[key] = Applied(layer.toLong(), filter.toLong(), cube.data, blurRadius)
            else applied.remove(key)
            if (LOG.isDebugEnabled) LOG.debug("Frost: ${window.javaClass.simpleName} ${if (last == null) "glass applied" else "glass re-applied"} ${describe(nsWindow)}")
        }
    }

    private fun createFilter(cube: Cube): ID? {
        val filter = Foundation.invoke("CIFilter", "filterWithName:", Foundation.nsString("CIColorCubeWithColorSpace"))
        if (filter.isNil()) {
            LOG.warn("Frost: CIColorCubeWithColorSpace not available")
            return null
        }
        // The table is indexed by and returns sRGB values, like the colours Swing paints with.
        val srgb = Foundation.invoke(Foundation.invoke("NSColorSpace", "sRGBColorSpace"), "CGColorSpace")
        Foundation.invoke(filter, "setValue:forKey:", Foundation.invoke("NSNumber", "numberWithInt:", GlassFilter.SIZE), Foundation.nsString("inputCubeDimension"))
        Foundation.invoke(filter, "setValue:forKey:", ID(cube.data), Foundation.nsString("inputCubeData"))
        Foundation.invoke(filter, "setValue:forKey:", srgb, Foundation.nsString("inputColorSpace"))
        return filter
    }

    /** The layer is still non-opaque and still carries our filter. */
    private fun isStillOurs(nsWindow: ID, layer: ID, last: Applied): Boolean {
        if (flag(nsWindow, "isOpaque") || flag(layer, "isOpaque")) return false
        val filters = Foundation.invoke(layer, "filters")
        return !filters.isNil() && Foundation.invoke(filters, "count").toInt() == 1 &&
            Foundation.invoke(filters, "objectAtIndex:", 0L).toLong() == last.filter
    }

    private fun flag(o: ID, selector: String) = !o.isNil() && (Foundation.invoke(o, selector).toLong() and 0xff) != 0L

    private fun contentLayer(nsWindow: ID): ID? {
        val contentView = Foundation.invoke(nsWindow, "contentView")
        if (contentView.isNil()) return null
        return Foundation.invoke(contentView, "layer").takeIf { !it.isNil() }
    }

    fun reset(window: Window) {
        val nsWindow = nsWindowOf(window) ?: return
        applied.remove(nsWindow.toLong())
        Foundation.executeOnMainThread(true, false) {
            if (!window.isDisplayable) return@executeOnMainThread
            setBlurRadius(nsWindow, 0)
            contentLayer(nsWindow)?.let { layer ->
                Foundation.invoke(layer, "setFilters:", ID.NIL)
                setLayerTreeOpaque(layer, true)
            }
            Foundation.invoke(nsWindow, "setBackgroundColor:", Foundation.invoke("NSColor", "windowBackgroundColor"))
            Foundation.invoke(nsWindow, "setOpaque:", true)
        }
    }

    /**
     * The AWT content view renders Swing into a Metal layer that stays opaque unless told otherwise.
     * Depending on the JBR version the Metal layer is the view's own layer or one of its sublayers.
     */
    private fun setLayerTreeOpaque(layer: ID, opaque: Boolean) {
        Foundation.invoke(layer, "setOpaque:", opaque)
        forEachSublayer(layer) { Foundation.invoke(it, "setOpaque:", opaque) }
    }

    private fun forEachSublayer(layer: ID, action: (ID) -> Unit) {
        val sublayers = Foundation.invoke(layer, "sublayers")
        if (sublayers.isNil()) return
        val count = Foundation.invoke(sublayers, "count").toInt()
        for (i in 0 until count) action(Foundation.invoke(sublayers, "objectAtIndex:", i.toLong()))
    }

    /** Diagnostics for idea.log: what the native window / layer tree looks like after applying. */
    private fun describe(nsWindow: ID): String {
        fun cls(o: ID) = if (o.isNil()) "nil" else Foundation.toStringViaUTF8(Foundation.invoke(o, "className"))
        val contentView = Foundation.invoke(nsWindow, "contentView")
        val layer = if (contentView.isNil()) ID.NIL else Foundation.invoke(contentView, "layer")
        val sublayers = buildList { if (!layer.isNil()) forEachSublayer(layer) { add("${cls(it)}(opaque=${flag(it, "isOpaque")})") } }
        val filters = if (layer.isNil()) ID.NIL else Foundation.invoke(layer, "filters")
        val filterCount = if (filters.isNil()) 0 else Foundation.invoke(filters, "count").toInt()
        return "nsWindow=${cls(nsWindow)} opaque=${flag(nsWindow, "isOpaque")} contentView=${cls(contentView)} " +
            "layer=${cls(layer)}(opaque=${flag(layer, "isOpaque")}, filters=$filterCount) sublayers=$sublayers"
    }

    private fun ID.isNil() = toLong() == 0L

    private fun nsWindowOf(window: Window): ID? {
        val id = runCatching { MacUtil.getWindowFromJavaWindow(window) }.getOrNull()
        return id?.takeIf { it.toLong() != 0L }
    }

    private fun setBlurRadius(nsWindow: ID, radius: Int) {
        val blur = blurRadiusFn ?: return
        val connection = connectionFn ?: return
        val windowNumber = Foundation.invoke(nsWindow, "windowNumber").toInt()
        if (windowNumber <= 0) return
        blur.invokeInt(arrayOf(connection.invokeInt(emptyArray()), windowNumber, radius.coerceAtLeast(0)))
    }

    /** A window that is not on screen yet has no window-server number: the blur cannot be set on it yet. */
    private fun isOnScreen(nsWindow: ID) = Foundation.invoke(nsWindow, "windowNumber").toInt() > 0

    private fun cgFunction(name: String): Function? {
        for (path in LIBRARIES) {
            val fn = runCatching { NativeLibrary.getInstance(path).getFunction(name) }.getOrNull()
            if (fn != null) return fn
        }
        LOG.warn("Frost: native function $name not found, blur disabled")
        return null
    }

    private val LIBRARIES = listOf(
        "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
        "/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight",
    )
}
