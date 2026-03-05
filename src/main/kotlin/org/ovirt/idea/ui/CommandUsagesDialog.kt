package org.ovirt.idea.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.table.JBTable
import org.ovirt.idea.model.UsageLocation
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.table.DefaultTableModel

class CommandUsagesDialog(
    project: Project,
    private val commandName: String,
    usages: List<UsageLocation>,
    private val basePath: String?
) : DialogWrapper(project) {

    private val tableModel = object : DefaultTableModel(arrayOf("File", "Line", "Code"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    private val table = JBTable(tableModel).apply {
        setStriped(true)
        autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        emptyText.text = "No usages found"
    }

    init {
        title = "Usages: $commandName"
        setSize(1200, 700)
        usages.forEach { usage ->
            tableModel.addRow(
                arrayOf(
                    toDisplayPath(usage.filePath),
                    usage.line,
                    usage.preview
                )
            )
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            add(JScrollPane(table), BorderLayout.CENTER)
        }
    }

    private fun toDisplayPath(fullPath: String): String {
        if (basePath.isNullOrBlank()) return fullPath
        return if (fullPath.startsWith(basePath)) fullPath.removePrefix(basePath).trimStart('/') else fullPath
    }
}
