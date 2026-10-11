package com.github.pallad42.frost

import com.intellij.codeInsight.hint.HintManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.GotItTooltip
import com.intellij.ui.awt.RelativePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Point

/**
 * Development aid, off unless the IDE runs with `-Dfrost.selftest=true` (`./gradlew runIde -Pfrost.selftest`):
 * shows every kind of overlay one after another, so a screen recording can check each of them.
 */
class FrostSelfTest : ProjectActivity {
    private val log = logger<FrostSelfTest>()

    override suspend fun execute(project: Project) {
        if (System.getProperty("frost.selftest") != "true") return
        delay(STEP_MS * 3)
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

    private fun openEditor(project: Project): Editor? {
        val file = project.guessProjectDir()?.findChild("README.md") ?: return null
        return FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)
    }

    private companion object {
        const val STEP_MS = 2500L
    }
}
