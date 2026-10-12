package com.github.pallad42.frost

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class GlassStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater { GlassManager.getInstance().start() }
        // Development aids, started only by `./gradlew runIde -Pfrost.selftest` / `-Pfrost.perftest`.
        if (System.getProperty("frost.selftest") == "true") FrostSelfTest().run(project)
        if (System.getProperty("frost.perftest") == "true") FrostPerfTest().run(project)
    }
}

class GlassAppListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        ApplicationManager.getApplication().invokeLater {
            GlassManager.getInstance().start()
            // Development aid, started only by `./gradlew runIde -Pfrost.driver=<dir>`.
            System.getProperty("frost.driver")?.let { FrostDriver(java.io.File(it)).start() }
        }
    }

    override fun appWillBeClosed(isRestart: Boolean) {
        GlassManager.getInstance().exiting = true
    }
}

class GlassThemeListener : LafManagerListener, EditorColorsListener {
    override fun lookAndFeelChanged(source: LafManager) = GlassManager.getInstance().onThemeChanged()

    override fun globalSchemeChange(scheme: EditorColorsScheme?) = GlassManager.getInstance().onThemeChanged()
}
