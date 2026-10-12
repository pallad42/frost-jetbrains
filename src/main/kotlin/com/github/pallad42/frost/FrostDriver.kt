package com.github.pallad42.frost

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.Desktop
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.MenuElement
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Development aid, started only when the IDE runs with `-Dfrost.driver=<dir>` (`./gradlew runIde -Pfrost.driver=<dir>`):
 * executes commands written to `<dir>/cmd.txt` one line at a time and answers in `<dir>/out.txt`, so the effect can be
 * checked on real windows without touching the mouse or other applications. Commands:
 * `project <path>`, `open <file>`, `menu <action group id>`, `hover <item index>`, `action <id>`, `escape`,
 * `shot <name>` (captures this IDE's showing windows into `<dir>/<name>.png`), `windows`, `exit`.
 */
class FrostDriver(private val dir: File) {
    private val log = logger<FrostDriver>()
    private val cmd = File(dir, "cmd.txt")
    private val out = File(dir, "out.txt")
    private var popup: JPopupMenu? = null

    fun start() {
        dir.mkdirs()
        Timer(300) { poll() }.start()
        log.info("Frost driver: listening on $cmd")
    }

    private fun poll() {
        if (!cmd.exists()) return
        val lines = cmd.readLines().filter { it.isNotBlank() }
        cmd.delete()
        for (line in lines) {
            val result = runCatching { execute(line.trim()) }.getOrElse { "error: $it" }
            out.appendText("$line -> $result\n")
        }
    }

    private fun execute(line: String): String {
        val name = line.substringBefore(' ')
        val arg = line.substringAfter(' ', "")
        return when (name) {
            "project" -> ProjectUtil.openOrImport(arg, null, true)?.name ?: "not opened"
            "open" -> {
                val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return "no project"
                val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(arg) ?: return "no file"
                FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)?.let { "opened" } ?: "no editor"
            }
            "menu" -> {
                val group = ActionManager.getInstance().getAction(arg) as? ActionGroup ?: return "no group $arg"
                val owner = focusedComponent() ?: return "no component"
                val menu = ActionManager.getInstance().createActionPopupMenu("frost-driver", group).component
                menu.show(owner, 40, 40)
                popup = menu
                "shown ${menu.componentCount} items"
            }
            "hover" -> {
                val menu = popup ?: return "no menu"
                val items = menu.components.filterIsInstance<JMenuItem>()
                val item = items.getOrNull(arg.toInt()) ?: return "no item ${arg.toInt()} of ${items.size}"
                MenuSelectionManager.defaultManager().selectedPath = arrayOf<MenuElement>(menu, item)
                "hovered ${item.text}"
            }
            "action" -> {
                val action = ActionManager.getInstance().getAction(arg) ?: return "no action $arg"
                ActionManager.getInstance().tryToExecute(action, null, focusedComponent(), "frost-driver", true)
                "executed"
            }
            "escape" -> {
                MenuSelectionManager.defaultManager().clearSelectedPath()
                popup = null
                "cleared"
            }
            // Normal quit, so the IDE saves its settings the way it does for a user.
            "exit" -> {
                ApplicationManager.getApplication().invokeLater { ApplicationManager.getApplication().exit() }
                "exiting"
            }
            "windows" -> showingWindows().joinToString("; ") { "${it.javaClass.simpleName} ${it.bounds}" }
            "shot" -> shot(arg)
            else -> "unknown command"
        }
    }

    private fun focusedComponent(): JComponent? {
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner as? JComponent
        if (focused != null) return focused
        val window = showingWindows().firstOrNull { it is javax.swing.JFrame } as? javax.swing.JFrame
        return window?.rootPane
    }

    private fun showingWindows() = Window.getWindows().filter { it.isShowing && it.width > 0 }

    /**
     * Brings the IDE forward and captures each showing window of it separately a moment later
     * (`<name>-0.png` is the frontmost frame, popups and dialogs follow), never the screen between them.
     */
    private fun shot(name: String): String {
        runCatching { Desktop.getDesktop().requestForeground(true) }
        showingWindows().filterIsInstance<java.awt.Frame>().forEach { it.toFront() }
        val windows = showingWindows().sortedBy { if (it is java.awt.Frame) 0 else 1 }
        if (windows.isEmpty()) return "no windows"
        val bounds = windows.map { Rectangle(it.locationOnScreen, it.size) }
        // Capture off the EDT once the windows have been brought forward and repainted.
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(700)
            val robot = Robot()
            bounds.forEachIndexed { i, r ->
                val image = robot.createMultiResolutionScreenCapture(r).resolutionVariants.maxBy { it.getWidth(null) }
                ImageIO.write(image as java.awt.image.RenderedImage, "png", File(dir, "$name-$i.png"))
            }
            SwingUtilities.invokeLater { out.appendText("shot $name saved ${windows.map { it.javaClass.simpleName }}\n") }
        }
        return "capturing ${bounds.size} windows"
    }
}
