package org.ovirt.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import org.ovirt.idea.index.CommandIndexService

class ShowCommandUsagesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commandName = e.selectedClassName() ?: run {
            Messages.showInfoMessage(project, "Open command class to show usages", "oVirt Commands")
            return
        }

        runInBackground(project, "Searching command usages") {
            CommandIndexService.getInstance(project).commandByName(commandName)
        } onDone@{ command ->
            if (command == null) {
                Messages.showInfoMessage(project, "Selected class is not indexed as command", "oVirt Commands")
                return@onDone
            }

            val message = buildString {
                append("$commandName используется в:\n\n")
                command.usages.sortedBy { it.filePath }.forEach {
                    append("${it.filePath}:${it.line}\n")
                }
            }
            Messages.showInfoMessage(project, message, "Show Command Usages")
        }
    }
}
