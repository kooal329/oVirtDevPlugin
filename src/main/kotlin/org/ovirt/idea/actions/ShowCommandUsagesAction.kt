package org.ovirt.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import org.ovirt.idea.index.CommandIndexService
import org.ovirt.idea.ui.CommandUsagesDialog

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

                val usages = command.usages
                    .mapNotNull { usage ->
                        runCatching {
                            usage.filePath
                            usage
                        }.getOrNull()
                    }
                    .distinctBy { Triple(it.filePath, it.line, it.preview) }
                    .sortedWith(compareBy({ it.filePath }, { it.line }))

                if (usages.isEmpty()) {
                    Messages.showInfoMessage(project, "Usages not found for $commandName", "Show Command Usages")
                    return@onDone
                }

                CommandUsagesDialog(project, commandName, usages, project.basePath).show()
            }
        )
    }
}
