package org.ovirt.idea.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import org.ovirt.idea.index.CommandIndexService
import org.ovirt.idea.model.CommandInfo
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.datatransfer.StringSelection
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent

class CommandGraphPanel(
    private val project: Project,
    service: CommandIndexService,
    rootCommand: String?
) : JPanel(BorderLayout()) {

    private val commands = service.allCommands().sortedBy { it.name }
    private val commandMap = commands.associateBy { it.name }
    private val listModel = DefaultListModel<String>()
    private val list = JBList(listModel)
    private val details = JEditorPane("text/html", "")

    init {
        val search = SearchTextField()
        details.isEditable = false

        commands.forEach { listModel.addElement(it.name) }

        search.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refreshList(search.text)
            override fun removeUpdate(e: DocumentEvent?) = refreshList(search.text)
            override fun changedUpdate(e: DocumentEvent?) = refreshList(search.text)
        })

        details.addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val commandName = event.description.removePrefix("command://")
                list.setSelectedValue(commandName, true)
                openCommand(commandName)
            }
        }

        list.addListSelectionListener {
            if (!it.valueIsAdjusting) renderCommand(list.selectedValue)
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelectedCommand()
            }
        })

        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK), "copyCommand")
        list.actionMap.put("copyCommand", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                val selected = list.selectedValuesList
                if (selected.isNotEmpty()) {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(selected.joinToString("\n")), null)
                }
            }
        })

        val leftPanel = JPanel(BorderLayout()).apply {
            add(search, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, JBScrollPane(details)).apply { resizeWeight = 0.35 }
        add(split, BorderLayout.CENTER)

        val initial = rootCommand?.takeIf { commandMap.containsKey(it) } ?: listModel.elements().toList().firstOrNull()
        if (initial != null) {
            list.setSelectedValue(initial, true)
            renderCommand(initial)
        } else {
            details.text = "<html><body>No commands indexed</body></html>"
        }
    }

    private fun refreshList(filter: String) {
        val normalized = filter.trim().lowercase()
        listModel.removeAllElements()
        commands.asSequence().map { it.name }
            .filter { normalized.isEmpty() || it.lowercase().contains(normalized) }
            .forEach { listModel.addElement(it) }
        if (listModel.size() > 0) list.selectedIndex = 0
    }

    private fun renderCommand(commandName: String?) {
        if (commandName == null) {
            details.text = "<html><body>Select command</body></html>"
            return
        }
        val command = commandMap[commandName] ?: run {
            details.text = "<html><body>Command not found: $commandName</body></html>"
            return
        }
        details.text = buildDetailsHtml(command)
    }

    private fun buildDetailsHtml(command: CommandInfo): String {
        val graphLines = mutableListOf<String>()
        graphLines += escape(command.name)
        renderNode(
            commandName = command.name,
            depth = 1,
            currentPath = mutableListOf(command.name),
            globallyRendered = mutableSetOf(command.name),
            graphLines = graphLines,
            budget = NodeBudget(MAX_GRAPH_LINES)
        )

        val calls = if (command.calledCommands.isEmpty()) {
            "<li><i>none</i></li>"
        } else {
            command.calledCommands.sorted().joinToString("") { called ->
                "<li>${link(called)}</li>"
            }
            appendLine()
            appendLine("Call Graph (cycle-safe):")
            appendLine(command.name)
            renderNode(command.name, 1, mutableListOf(command.name), this)
        }

        return """
            <html><body style='font-family:Segoe UI, sans-serif;'>
            <h2>oVirt Command Inspector</h2>
            <p><b>Command:</b> ${escape(command.name)}<br/>
               <b>Parameter:</b> ${escape(command.parametersClass ?: "n/a")}<br/>
               <b>File:</b> ${escape(toRelativeSrcPath(command.filePath))}<br/>
               <b>Direct calls:</b> ${command.calledCommands.size}</p>
            <h3>Calls</h3>
            <ul>$calls</ul>
            <h3>Call Graph (cycle-safe, capped)</h3>
            <pre>${graphLines.joinToString("\n")}</pre>
            </body></html>
        """.trimIndent()
    }

    private fun renderNode(
        commandName: String,
        depth: Int,
        currentPath: MutableList<String>,
        globallyRendered: MutableSet<String>,
        graphLines: MutableList<String>,
        budget: NodeBudget
    ) {
        if (!budget.take()) return
        if (depth > MAX_GRAPH_DEPTH) {
            graphLines += "${"  ".repeat(depth)}└─ ... (depth limit)"
            return
        }

        val node = commandMap[commandName] ?: return
        node.calledCommands.sorted().forEach { called ->
            if (!budget.take()) return
            val prefix = "  ".repeat(depth) + "└─ "
            when {
                called in currentPath -> graphLines += "$prefix${escape(called)}  ↺ cycle"
                called in globallyRendered -> graphLines += "$prefix${escape(called)}  ↪ already shown"
                else -> {
                    graphLines += "$prefix${link(called)}"
                    currentPath += called
                    globallyRendered += called
                    renderNode(called, depth + 1, currentPath, globallyRendered, graphLines, budget)
                    currentPath.removeAt(currentPath.lastIndex)
                }
            }
        }
    }

    private fun openSelectedCommand() {
        val selected = list.selectedValue ?: return
        openCommand(selected)
    }

    private fun openCommand(commandName: String) {
        val info = commandMap[commandName] ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(info.filePath) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    private fun toRelativeSrcPath(filePath: String): String {
        val idx = filePath.indexOf("/src/")
        return if (idx >= 0) filePath.substring(idx + 1) else filePath
    }

    private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun link(command: String): String = "<a href='command://$command'>${escape(command)}</a>"

    private data class NodeBudget(var left: Int) {
        fun take(): Boolean {
            if (left <= 0) return false
            left -= 1
            return true
        }
    }

    private fun <T> java.util.Enumeration<T>.toList(): List<T> {
        val result = mutableListOf<T>()
        while (hasMoreElements()) result.add(nextElement())
        return result
    }

    companion object {
        private const val MAX_GRAPH_DEPTH = 8
        private const val MAX_GRAPH_LINES = 500
    }
}
