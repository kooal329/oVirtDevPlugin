package org.ovirt.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiJavaFile
import org.ovirt.idea.index.CommandIndexService

class ShowParameterConsumersAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) as? PsiJavaFile ?: return
        val parameterName = psiFile.classes.firstOrNull()?.name ?: return

        runInBackground(project, "Searching parameter consumers") {
            CommandIndexService.getInstance(project).commandsUsingParameters(parameterName)
        } onDone@{ commands ->
            val message = if (commands.isEmpty()) {
                "Не найдено команд для параметров $parameterName"
            } else {
                buildString {
                    append("Used by:\n")
                    commands.forEach { append(" - ${it.name}\n") }
                }
            }
            Messages.showInfoMessage(project, message, "Parameter Consumers")
        }
    }
}
