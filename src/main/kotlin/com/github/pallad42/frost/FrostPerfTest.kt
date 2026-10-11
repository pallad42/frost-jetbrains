package com.github.pallad42.frost

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import javax.swing.Timer

/**
 * Development aid, off unless the IDE runs with `-Dfrost.perftest=true` (`./gradlew runIde -Pfrost.perftest`):
 * maximises the window and scrolls an editor in several configurations, logging how many scroll steps the EDT
 * managed in each phase. Combined with a screen recording this gives the real frame rate per configuration.
 */
class FrostPerfTest : ProjectActivity {
    private val log = logger<FrostPerfTest>()

    override suspend fun execute(project: Project) {
        if (System.getProperty("frost.perftest") != "true") return
        delay(8000)
        val editor = withContext(Dispatchers.EDT) {
            WindowManager.getInstance().getFrame(project)?.bounds =
                GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            openEditor(project)
        } ?: return
        delay(3000)
        val saved = GlassSettings.getInstance().config
        val phases = listOf(
            "off" to saved.copy(enabled = false),
            "blur5" to saved.copy(enabled = true, blurRadius = 5),
            "blur0" to saved.copy(enabled = true, blurRadius = 0),
            "off-again" to saved.copy(enabled = false),
        )
        for ((name, config) in phases) {
            withContext(Dispatchers.EDT) { GlassManager.getInstance().apply(config) }
            delay(2000)
            val edt = withContext(Dispatchers.EDT) { Thread.currentThread() }
            val sampler = EdtSampler(edt).also { it.start() }
            val ticks = withContext(Dispatchers.EDT) { startScrolling(editor) }
            log.info("Frost perf: phase $name start")
            delay(SCROLL_MS)
            val (count, paintNanos) = withContext(Dispatchers.EDT) { ticks.stop() }
            sampler.stopAndReport(name)
            log.info("Frost perf: phase $name end, ${count} scroll steps in ${SCROLL_MS}ms, avg step+paint ${paintNanos / count.coerceAtLeast(1) / 1000}us")
        }
        withContext(Dispatchers.EDT) { GlassManager.getInstance().apply(saved) }
        log.info("Frost perf: done")
    }

    /** Samples the EDT stack every 2 ms; reports where it spends time (leaf methods and the deepest painting frames). */
    private inner class EdtSampler(private val edt: Thread) : Thread("Frost EDT sampler") {
        @Volatile private var running = true
        private val leaves = HashMap<String, Int>()
        private val painters = HashMap<String, Int>()
        private var samples = 0

        override fun run() {
            while (running) {
                val stack = edt.stackTrace
                if (stack.isNotEmpty()) {
                    samples++
                    val leaf = stack[0]
                    leaves.merge("${leaf.className}.${leaf.methodName}", 1, Int::plus)
                    // First frame that is not JDK internals: who asked for the expensive work.
                    stack.firstOrNull { !it.className.startsWith("java.") && !it.className.startsWith("sun.") && !it.className.startsWith("jdk.") }
                        ?.let { painters.merge("${it.className}.${it.methodName}", 1, Int::plus) }
                }
                sleep(2)
            }
        }

        fun stopAndReport(phase: String) {
            running = false
            join()
            fun top(m: Map<String, Int>) = m.entries.sortedByDescending { it.value }.take(12)
                .joinToString("\n    ") { "${it.value * 100 / samples.coerceAtLeast(1)}% ${it.key}" }
            log.info("Frost perf: phase $phase EDT samples $samples\n  leaf:\n    ${top(leaves)}\n  first non-JDK:\n    ${top(painters)}")
        }
    }

    private class Ticks(val timer: Timer, val counter: IntArray, val nanos: LongArray) {
        fun stop(): Pair<Int, Long> {
            timer.stop()
            return counter[0] to nanos[0]
        }
    }

    /** Scrolls a few pixels every 8 ms and paints immediately, measuring how long each step takes on the EDT. */
    private fun startScrolling(editor: Editor): Ticks {
        val counter = IntArray(1)
        val nanos = LongArray(1)
        val scrolling = editor.scrollingModel
        scrolling.disableAnimation()
        val maxY = (editor.contentComponent.height - scrolling.visibleArea.height).coerceAtLeast(1)
        var y = 0
        var dir = 1
        val timer = Timer(8) {
            val start = System.nanoTime()
            y += dir * 6
            if (y >= maxY || y <= 0) dir = -dir
            scrolling.scrollVertically(y.coerceIn(0, maxY))
            editor.contentComponent.paintImmediately(editor.contentComponent.visibleRect)
            nanos[0] += System.nanoTime() - start
            counter[0]++
        }
        timer.start()
        return Ticks(timer, counter, nanos)
    }

    private fun openEditor(project: Project): Editor? {
        val file = project.guessProjectDir()?.findFileByRelativePath("src/main/kotlin/com/github/pallad42/frost/GlassManager.kt")
            ?: return null
        return FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)
    }

    private companion object {
        const val SCROLL_MS = 5000L
    }
}
