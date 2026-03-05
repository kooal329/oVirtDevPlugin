package org.ovirt.idea.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import org.ovirt.idea.index.CommandIndexService

class CommandToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CommandGraphPanel(project, CommandIndexService.getInstance(project), null)
        val content = ContentFactory.getInstance().createContent(panel, "Inspector", false)
        toolWindow.contentManager.addContent(content)
    }
}
