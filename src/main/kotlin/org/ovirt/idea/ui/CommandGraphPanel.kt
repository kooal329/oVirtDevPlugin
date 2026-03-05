package org.ovirt.idea.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import org.ovirt.idea.index.CommandIndexService
import org.ovirt.idea.model.CommandInfo
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.datatransfer.StringSelection
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class CommandGraphPanel(
    private val project: Project,
    service: CommandIndexService,
    rootCommand: String?
) : JPanel(BorderLayout()) {

    private val commands = service.allCommands().sortedBy { it.name }
    private val commandMap = commands.associateBy { it.name }
    private val listModel = DefaultListModel<String>()
    private val list = JBList(listModel)
    private val details = JBTextArea()

    init {
        val search = SearchTextField()
        details.isEditable = false

        commands.forEach { listModel.addElement(it.name) }

        search.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refreshList(search.text)
            override fun removeUpdate(e: DocumentEvent?) = refreshList(search.text)
            override fun changedUpdate(e: DocumentEvent?) = refreshList(search.text)
        })

        list.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                renderCommand(list.selectedValue)
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    openSelectedCommand()
                }
            }
        })

        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK), "copyCommand")
        list.actionMap.put("copyCommand", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                val selected = list.selectedValuesList
                if (selected.isNotEmpty()) {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        StringSelection(selected.joinToString("\n")),
                        null
                    )
                }
            }
        })

        val leftPanel = JPanel(BorderLayout()).apply {
            add(search, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, JBScrollPane(details)).apply {
            resizeWeight = 0.35
        }

        add(split, BorderLayout.CENTER)

        val initial = rootCommand?.takeIf { commandMap.containsKey(it) } ?: listModel.elements().toList().firstOrNull()
        if (initial != null) {
            list.setSelectedValue(initial, true)
            renderCommand(initial)
        } else {
            details.text = "No commands indexed"
        }
    }

    private fun refreshList(filter: String) {
        val normalized = filter.trim().lowercase()
        listModel.removeAllElements()
        commands
            .asSequence()
            .map { it.name }
            .filter { normalized.isEmpty() || it.lowercase().contains(normalized) }
            .forEach { listModel.addElement(it) }
        if (listModel.size() > 0) list.selectedIndex = 0
    }

    private fun renderCommand(commandName: String?) {
        if (commandName == null) {
            details.text = "Select command"
            return
        }
        val command = commandMap[commandName] ?: run {
            details.text = "Command not found: $commandName"
            return
        }
        details.text = buildDetailsText(command)
    }

    private fun buildDetailsText(command: CommandInfo): String {
        val relativePath = toRelativeSrcPath(command.filePath)
        return buildString {
            appendLine("Command: ${command.name}")
            appendLine("Parameters: ${command.parametersClass ?: "n/a"}")
            appendLine("File: $relativePath")
            appendLine("Direct calls: ${command.calledCommands.size}")
            appendLine("Calls:")
            if (command.calledCommands.isEmpty()) {
                appendLine("  - none")
            } else {
                command.calledCommands.sorted().forEach { appendLine("  - $it") }
            }
            appendLine()
            appendLine("Call Graph:")
            appendLine(command.name)
            renderNode(command.name, 1, mutableSetOf(), this)
        }
    }

    private fun toRelativeSrcPath(filePath: String): String {
        val idx = filePath.indexOf("/src/")
        return if (idx >= 0) filePath.substring(idx + 1) else filePath
    }

    private fun renderNode(commandName: String, depth: Int, seen: MutableSet<String>, out: StringBuilder) {
        if (!seen.add(commandName)) return
        val node = commandMap[commandName] ?: return
        node.calledCommands.sorted().forEach { called ->
            out.append("  ".repeat(depth)).append("└─ ").appendLine(called)
            renderNode(called, depth + 1, seen, out)
        }
    }

    private fun openSelectedCommand() {
        val selected = list.selectedValue ?: return
        val info = commandMap[selected] ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(info.filePath) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    private fun <T> java.util.Enumeration<T>.toList(): List<T> {
        val result = mutableListOf<T>()
        while (hasMoreElements()) result.add(nextElement())
        return result
    }
}
