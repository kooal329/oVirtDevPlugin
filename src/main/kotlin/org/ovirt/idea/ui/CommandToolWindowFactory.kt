package org.ovirt.idea.ui

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import org.ovirt.idea.index.CommandIndexService

class CommandToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (DumbService.isDumb(project)) {
            val output = JBTextArea("Indexing in progress. Command graph will appear when indexing is finished.")
            output.isEditable = false
            val content = ContentFactory.getInstance().createContent(JBScrollPane(output), "Inspector", false)
            toolWindow.contentManager.addContent(content)

            DumbService.getInstance(project).runWhenSmart {
                if (project.isDisposed) return@runWhenSmart
                toolWindow.contentManager.removeAllContents(true)
                val panel = CommandGraphPanel(project, CommandIndexService.getInstance(project), null)
                val smartContent = ContentFactory.getInstance().createContent(panel, "Inspector", false)
                toolWindow.contentManager.addContent(smartContent)
            }
            return
        }

        val panel = CommandGraphPanel(project, CommandIndexService.getInstance(project), null)
        val content = ContentFactory.getInstance().createContent(panel, "Inspector", false)
        toolWindow.contentManager.addContent(content)
    }
}
