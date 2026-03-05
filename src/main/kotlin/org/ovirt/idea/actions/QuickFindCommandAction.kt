package org.ovirt.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import org.ovirt.idea.index.CommandIndexService

class QuickFindCommandAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commands = CommandIndexService.getInstance(project).allCommands().map { it.name }
        if (commands.isEmpty()) {
            Messages.showInfoMessage(project, "Команды не найдены", "Find oVirt Command")
            return
        }

        val selected = Messages.showEditableChooseDialog(
            "Выберите команду",
            "Find oVirt Command",
            null,
            commands.toTypedArray(),
            commands.firstOrNull(),
            null
        )

        if (selected != null) {
            val command = CommandIndexService.getInstance(project).commandByName(selected)
            val message = buildString {
                append("${command?.name}\n")
                append("Parameters: ${command?.parametersClass ?: "n/a"}\n")
                append("Calls: ${command?.calledCommands?.joinToString() ?: "n/a"}")
            }
            Messages.showInfoMessage(project, message, "Command Inspector")
        }
    }
}
