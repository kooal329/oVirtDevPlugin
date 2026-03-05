package org.ovirt.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import org.ovirt.idea.index.CommandIndexService
import org.ovirt.idea.ui.CommandGraphPanel

class ShowCommandGraphAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commandName = e.selectedClassName()

        runInBackground(project, "Building command graph") {
            CommandGraphPanel(project, CommandIndexService.getInstance(project), commandName)
        } onDone@{ panel ->
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("oVirt Commands") ?: return@onDone
            toolWindow.show()
            toolWindow.contentManager.removeAllContents(true)
            val content = toolWindow.contentManager.factory.createContent(panel, "Command Graph", false)
            toolWindow.contentManager.addContent(content)
        }
    }
}
