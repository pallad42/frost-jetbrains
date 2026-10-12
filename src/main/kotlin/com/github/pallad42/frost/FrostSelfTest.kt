package com.github.pallad42.frost

import com.intellij.codeInsight.hint.HintManager
import com.intellij.ide.ui.LafManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.GotItTooltip
import com.intellij.ui.awt.RelativePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.Point
import java.awt.Window
import javax.swing.JComponent

/**
 * Development aid, started by [GlassStartup] only when the IDE runs with `-Dfrost.selftest=true`
 * (`./gradlew runIde -Pfrost.selftest`):
 * shows every kind of overlay one after another, so a screen recording can check each of them.
 */
class FrostSelfTest {
    private val log = logger<FrostSelfTest>()

    suspend fun run(project: Project) {
        delay(STEP_MS * 3)
        DumbService.getInstance(project).waitForSmartMode()  // quick documentation needs indexes
        val editor = withContext(Dispatchers.EDT) { openEditor(project) } ?: return
        val steps = listOf<Pair<String, () -> Unit>>(
            "notification balloon" to {
                NotificationGroupManager.getInstance().getNotificationGroup("Frost")
                    .createNotification("Frost self-test", "Notification balloon drawn over the editor.", NotificationType.INFORMATION)
                    .notify(project)
            },
            "information hint" to { HintManager.getInstance().showInformationHint(editor, "Information hint over the code") },
            "error hint" to { HintManager.getInstance().showErrorHint(editor, "Error hint over the code") },
            "html balloon" to {
                JBPopupFactory.getInstance().createHtmlTextBalloonBuilder("Balloon over the code", MessageType.INFO, null)
                    .setFadeoutTime(STEP_MS - 300)
                    .createBalloon()
                    .show(RelativePoint(editor.contentComponent, Point(300, 120)), Balloon.Position.below)
            },
            "got it tooltip" to {
                GotItTooltip("frost.selftest.${System.nanoTime()}", "Got it tooltip over the code", project)
                    .show(editor.contentComponent) { _, _ -> Point(300, 200) }
            },
            "message popup" to { JBPopupFactory.getInstance().createMessage("Message popup over the code").showInBestPositionFor(editor) },
            "quick documentation" to { showQuickDoc(project) },
            "dump popup windows" to { dumpPopupWindows() },
            // Theme switch while running: the colours must follow, not keep the previous theme's.
            "switch to light theme" to { switchTheme("Islands Light") },
            "message popup in light theme" to { showMessageInCurrentEditor(project, "Message popup, light theme") },
            "switch to dark theme" to { switchTheme("Islands Dark") },
            "message popup in dark theme" to { showMessageInCurrentEditor(project, "Message popup, dark theme") },
        )
        for ((name, show) in steps) {
            withContext(Dispatchers.EDT) {
                HintManager.getInstance().hideAllHints()
                log.info("Frost selftest: $name")
                show()
            }
            delay(STEP_MS)
        }
        log.info("Frost selftest: done")
    }

    /** Quick Documentation for `Float` in GlassFilter.kt, like hovering it. */
    private fun showQuickDoc(project: Project) {
        val file = project.guessProjectDir()?.findFileByRelativePath("src/main/kotlin/com/github/pallad42/frost/GlassFilter.kt") ?: return
        val text = String(file.contentsToByteArray())
        val offset = text.indexOf("Float)").takeIf { it >= 0 } ?: return
        val docEditor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file, offset), true) ?: return
        val action = ActionManager.getInstance().getAction("QuickJavaDoc") ?: return
        ActionManager.getInstance().tryToExecute(action, null, docEditor.contentComponent, "frost-selftest", true)
    }

    /** After a theme switch the editor from the start may be gone: use whichever one is showing now. */
    private fun showMessageInCurrentEditor(project: Project, text: String) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return log.warn("Frost selftest: no editor")
        JBPopupFactory.getInstance().createMessage(text).showInBestPositionFor(editor)
    }

    private fun switchTheme(name: String) {
        val lafManager = LafManager.getInstance()
        val theme = lafManager.installedThemes.firstOrNull { it.name == name } ?: return log.warn("Frost selftest: no theme $name")
        lafManager.setCurrentLookAndFeel(theme, false)
        lafManager.updateUI()
    }

    /** Logs every visible popup window's component tree: class, own background and opacity. */
    private fun dumpPopupWindows() {
        for (window in Window.getWindows().filter { it.isShowing && it !is Frame }) {
            val sb = StringBuilder("Frost selftest: window ${window.javaClass.name} ${window.width}x${window.height}\n")
            fun walk(c: Component, depth: Int) {
                val bg = if (c.isBackgroundSet) c.background else null
                val alpha = bg?.alpha
                sb.append("  ".repeat(depth)).append(c.javaClass.name.substringAfterLast('.'))
                    .append(" ${c.width}x${c.height}")
                    .append(if (bg != null) " bg=#%06x a=%d".format(bg.rgb and 0xffffff, alpha) else "")
                    .append(if (c is JComponent && c.isOpaque) " opaque" else "")
                    .append('\n')
                if (c is Container) c.components.forEach { walk(it, depth + 1) }
            }
            walk(window, 0)
            log.info(sb.toString())
        }
    }

    private fun openEditor(project: Project): Editor? {
        val file = project.guessProjectDir()?.findChild("README.md") ?: return null
        return FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)
    }

    private companion object {
        const val STEP_MS = 2500L
    }
}
