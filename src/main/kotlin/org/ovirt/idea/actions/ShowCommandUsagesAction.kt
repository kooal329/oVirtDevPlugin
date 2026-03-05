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

        runInBackground(
            project = project,
            title = "Searching command usages",
            compute = { CommandIndexService.getInstance(project).commandByName(commandName) },
            onDone = onDone@{ command ->
                if (command == null) {
                    Messages.showInfoMessage(project, "Selected class is not indexed as command", "oVirt Commands")
                    return@onDone
                }

                val sanitizedUsages = command.usages.mapNotNull { usage ->
                    runCatching {
                        usage.filePath
                        usage
                    }.getOrNull()
                }

                val sortedUsages = sanitizedUsages.sortedWith(compareBy({ it.filePath }, { it.line }))
                val cappedUsages = sortedUsages.take(MAX_LINES_IN_DIALOG)
                val message = buildString {
                    append("$commandName используется в:\n\n")
                    cappedUsages.forEach {
                        append("${it.filePath}:${it.line}\n")
                    }
                    if (sortedUsages.size > MAX_LINES_IN_DIALOG) {
                        append("\nПоказаны первые $MAX_LINES_IN_DIALOG из ${sortedUsages.size} результатов")
                    }
                }
                Messages.showInfoMessage(project, message, "Show Command Usages")
            }
        )
    }

    companion object {
        private const val MAX_LINES_IN_DIALOG = 500
    }
}
