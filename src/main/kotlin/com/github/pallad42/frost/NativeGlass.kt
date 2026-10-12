package com.github.pallad42.frost

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.ID
import com.intellij.ui.mac.foundation.MacUtil
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import java.awt.Color
import java.awt.Window

/**
 * AppKit side of the effect: makes the NSWindow non-opaque, gives it a translucent tint (window background for
 * titled windows, content layer for borderless ones) and asks the window server to blur whatever is behind the window.
 * The blur is the same private CoreGraphics call Ghostty uses for `background-blur`.
 */
internal object NativeGlass {
    private val LOG = logger<NativeGlass>()

    private val blurRadiusFn: Function? by lazy { cgFunction("CGSSetWindowBackgroundBlurRadius") }
    private val connectionFn: Function? by lazy { cgFunction("CGSDefaultConnectionForThread") }

    /**
     * What was last sent to each native window. Re-sending identical state makes the window server redraw the window
     * (flicker), but JBR may also replace the content layer (window moved to another display, surface recreated),
     * which silently drops our tint - so the check compares against the live layer, on the main thread.
     */
    private data class Applied(val layer: Long, val cgTint: Long, val windowTint: Long, val tint: Int, val blurRadius: Int)

    private val applied = java.util.concurrent.ConcurrentHashMap<Long, Applied>()

    /** Cheap to call repeatedly: only touches the native window when its state actually differs. */
    fun apply(window: Window, tint: Color, blurRadius: Int, force: Boolean = false) {
        val nsWindow = nsWindowOf(window) ?: return
        val key = nsWindow.toLong()
        Foundation.executeOnMainThread(true, false) {
            if (!window.isDisplayable) return@executeOnMainThread
            val layer = contentLayer(nsWindow)
            val last = applied[key]
            if (!force && last != null && layer != null && last.layer == layer.toLong() && last.tint == tint.rgb &&
                last.blurRadius == blurRadius && isStillOurs(nsWindow, layer, last)
            ) return@executeOnMainThread
            Foundation.invoke(nsWindow, "setOpaque:", false)
            setContentLayerOpaque(nsWindow, false)
            // Titled windows draw their background colour clipped to the rounded window shape; a tint on the content
            // layer would fill the square corners outside it. Borderless windows (popups, tooltips) never draw the
            // window background colour, so they keep the tint on the content layer.
            val titled = isTitled(nsWindow)
            Foundation.invoke(nsWindow, "setBackgroundColor:", if (titled) nsColor(tint) else Foundation.invoke("NSColor", "clearColor"))
            val windowTint = Foundation.invoke(nsWindow, "backgroundColor").toLong()
            val cgTint = setContentLayerTint(nsWindow, if (titled) null else tint)
            setBlurRadius(nsWindow, blurRadius)
            // Only remember windows that are really on screen; others get re-applied once they are shown.
            if (isOnScreen(nsWindow)) applied[key] = Applied(layer?.toLong() ?: 0L, cgTint, windowTint, tint.rgb, blurRadius)
            else applied.remove(key)
            if (LOG.isDebugEnabled) LOG.debug("Frost: ${window.javaClass.simpleName} ${if (last == null) "glass applied" else "glass re-applied"} ${describe(nsWindow)}")
        }
    }

    /** The window and its layer still carry the tint we gave them and the layer is still non-opaque. */
    private fun isStillOurs(nsWindow: ID, layer: ID, last: Applied): Boolean {
        val opaque = (Foundation.invoke(layer, "isOpaque").toLong() and 0xff) != 0L
        return !opaque && Foundation.invoke(layer, "backgroundColor").toLong() == last.cgTint &&
            Foundation.invoke(nsWindow, "backgroundColor").toLong() == last.windowTint
    }

    private fun isTitled(nsWindow: ID) = (Foundation.invoke(nsWindow, "styleMask").toLong() and NS_WINDOW_STYLE_MASK_TITLED) != 0L

    private const val NS_WINDOW_STYLE_MASK_TITLED = 1L

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
            setContentLayerTint(nsWindow, null)
            setContentLayerOpaque(nsWindow, true)
            if (isTitled(nsWindow)) Foundation.invoke(nsWindow, "setBackgroundColor:", Foundation.invoke("NSColor", "windowBackgroundColor"))
        }
    }

    /**
     * The AWT content view renders Swing into a Metal layer that stays opaque unless told otherwise.
     * Depending on the JBR version the Metal layer is the view's own layer or one of its sublayers.
     */
    private fun setContentLayerOpaque(nsWindow: ID, opaque: Boolean) {
        val contentView = Foundation.invoke(nsWindow, "contentView")
        if (contentView.isNil()) return
        val layer = Foundation.invoke(contentView, "layer")
        if (layer.isNil()) return
        Foundation.invoke(layer, "setOpaque:", opaque)
        forEachSublayer(layer) { Foundation.invoke(it, "setOpaque:", opaque) }
    }

    /**
     * The tint lives on the content layer itself (drawn under the Swing pixels), not on the NSWindow: borderless
     * windows - popups, tooltips - never draw the window background colour.
     */
    private fun setContentLayerTint(nsWindow: ID, tint: Color?): Long {
        val layer = contentLayer(nsWindow) ?: return 0L
        val cgColor = if (tint == null) ID.NIL else Foundation.invoke(nsColor(tint), "CGColor")
        Foundation.invoke(layer, "setBackgroundColor:", cgColor)
        // Read back what the layer holds (it retains its own reference) to compare against later.
        return Foundation.invoke(layer, "backgroundColor").toLong()
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
        fun flag(o: ID, selector: String) = !o.isNil() && (Foundation.invoke(o, selector).toLong() and 0xff) != 0L
        val contentView = Foundation.invoke(nsWindow, "contentView")
        val layer = if (contentView.isNil()) ID.NIL else Foundation.invoke(contentView, "layer")
        val superview = if (contentView.isNil()) ID.NIL else Foundation.invoke(contentView, "superview")
        val sublayers = buildList { if (!layer.isNil()) forEachSublayer(layer) { add("${cls(it)}(opaque=${flag(it, "isOpaque")})") } }
        return "nsWindow=${cls(nsWindow)} opaque=${flag(nsWindow, "isOpaque")} contentView=${cls(contentView)} " +
            "layer=${cls(layer)}(opaque=${flag(layer, "isOpaque")}) sublayers=$sublayers superview=${cls(superview)}"
    }

    private fun ID.isNil() = toLong() == 0L

    private fun nsWindowOf(window: Window): ID? {
        val id = runCatching { MacUtil.getWindowFromJavaWindow(window) }.getOrNull()
        return id?.takeIf { it.toLong() != 0L }
    }

    private fun nsColor(c: Color): ID = Foundation.invoke(
        "NSColor", "colorWithSRGBRed:green:blue:alpha:",
        c.red / 255.0, c.green / 255.0, c.blue / 255.0, c.alpha / 255.0,
    )

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
