package com.github.pallad42.frost

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction

/** View | Frost - also reachable from the native macOS menu bar if the effect makes the UI unusable. */
class GlassToggleAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent) = GlassSettings.getInstance().config.enabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val settings = GlassSettings.getInstance()
        settings.config = settings.config.copy(enabled = state)
        GlassManager.getInstance().apply(settings.config)
    }
}
