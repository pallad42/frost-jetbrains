package com.github.pallad42.frost

import com.intellij.openapi.project.Project
import java.io.File

/** Starts the development aids asked for on the command line; loaded by [FrostDevHooks] in runIde builds only. */
@Suppress("unused")
class FrostDev : FrostDevHooks {
    override suspend fun projectOpened(project: Project) {
        // ./gradlew runIde -Pfrost.selftest / -Pfrost.perftest
        if (System.getProperty("frost.selftest") == "true") FrostSelfTest().run(project)
        if (System.getProperty("frost.perftest") == "true") FrostPerfTest().run(project)
    }

    override fun appFrameCreated() {
        // ./gradlew runIde -Pfrost.driver=<dir>
        System.getProperty("frost.driver")?.let { FrostDriver(File(it)).start() }
    }
}
