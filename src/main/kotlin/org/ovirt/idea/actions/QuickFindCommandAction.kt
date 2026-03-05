package org.ovirt.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import org.ovirt.idea.index.CommandIndexService

class QuickFindCommandAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        runInBackground(
            project = project,
            title = "Loading oVirt commands",
            compute = { CommandIndexService.getInstance(project).allCommands().map { it.name } },
            onDone = onDone@{ commands ->
                if (commands.isEmpty()) {
                    Messages.showInfoMessage(project, "Команды не найдены", "Find oVirt Command")
                    return@onDone
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
                    runInBackground(
                        project = project,
                        title = "Loading command details",
                        compute = { CommandIndexService.getInstance(project).commandByName(selected) },
                        onDone = { command ->
                            val message = buildString {
                                append("${command?.name}\n")
                                append("Parameters: ${command?.parametersClass ?: "n/a"}\n")
                                append("Calls: ${command?.calledCommands?.joinToString() ?: "n/a"}")
                            }
                            Messages.showInfoMessage(project, message, "Command Inspector")
                        }
                    )
                }
            }
        )
    }
}
