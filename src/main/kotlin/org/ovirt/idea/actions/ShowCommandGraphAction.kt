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
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("oVirt Commands") ?: return
        toolWindow.show()

        val panel = CommandGraphPanel(
            project,
            CommandIndexService.getInstance(project),
            commandName
        )
        toolWindow.contentManager.removeAllContents(true)
        val content = toolWindow.contentManager.factory.createContent(panel, "Command Graph", false)
        toolWindow.contentManager.addContent(content)
    }
}
