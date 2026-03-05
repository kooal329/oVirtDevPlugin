package org.ovirt.idea.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import org.ovirt.idea.index.CommandIndexService
import java.awt.BorderLayout
import javax.swing.JPanel

class CommandGraphPanel(
    project: Project,
    service: CommandIndexService,
    rootCommand: String?
) : JPanel(BorderLayout()) {

    init {
        val output = JBTextArea()
        output.isEditable = false
        output.text = buildGraphText(service, rootCommand)
        add(JBScrollPane(output), BorderLayout.CENTER)
    }

    private fun buildGraphText(service: CommandIndexService, rootCommand: String?): String {
        val commands = service.allCommands().associateBy { it.name }
        if (commands.isEmpty()) return "No commands indexed"

        val roots = if (rootCommand != null && commands.containsKey(rootCommand)) {
            listOf(rootCommand)
        } else {
            commands.keys.sorted()
        }

        return buildString {
            roots.forEach { root ->
                appendLine(root)
                renderNode(root, commands, 1, mutableSetOf())
                appendLine()
            }
        }
    }

    private fun StringBuilder.renderNode(
        command: String,
        commands: Map<String, org.ovirt.idea.model.CommandInfo>,
        depth: Int,
        seen: MutableSet<String>
    ) {
        if (!seen.add(command)) return
        val node = commands[command] ?: return
        node.calledCommands.sorted().forEach { called ->
            append("  ".repeat(depth)).append("└─ ").appendLine(called)
            renderNode(called, commands, depth + 1, seen)
        }
    }
}
