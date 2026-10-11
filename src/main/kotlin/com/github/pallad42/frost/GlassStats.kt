package com.github.pallad42.frost

import com.intellij.openapi.diagnostic.logger

/** Cheap EDT timing of the plugin's own work, summarised in idea.log every 30 s while there is activity. */
internal object GlassStats {
    private val LOG = logger<GlassStats>()
    private val counts = HashMap<String, Int>()
    private val nanos = HashMap<String, Long>()
    private var lastReport = System.nanoTime()

    inline fun <T> measure(name: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, System.nanoTime() - start)
        }
    }

    fun record(name: String, elapsed: Long) {
        counts.merge(name, 1, Int::plus)
        nanos.merge(name, elapsed, Long::plus)
        val now = System.nanoTime()
        if (now - lastReport > 30_000_000_000L) {
            lastReport = now
            val summary = counts.keys.sorted().joinToString { "$it: ${counts[it]}x ${nanos[it]!! / 1_000_000}ms" }
            LOG.debug("Frost stats (30s): $summary")
            counts.clear()
            nanos.clear()
        }
    }
}
