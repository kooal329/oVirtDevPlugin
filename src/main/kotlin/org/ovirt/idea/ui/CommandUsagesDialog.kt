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

    private val tableModel = object : DefaultTableModel(arrayOf("Line", "File", "Code"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    private val table = JBTable(tableModel).apply {
        setStriped(true)
        autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        emptyText.text = "No usages found"
        columnModel.getColumn(0).preferredWidth = 70
        columnModel.getColumn(0).maxWidth = 90
        columnModel.getColumn(1).preferredWidth = 420
    }

    init {
        title = "oVirt-Command-manager: Usages of $commandName"
        setSize(1280, 760)
        usages.forEach { usage ->
            tableModel.addRow(
                arrayOf(
                    usage.line,
                    toDisplayPath(usage.filePath),
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
        if (!basePath.isNullOrBlank() && fullPath.startsWith(basePath)) {
            return fullPath.removePrefix(basePath).trimStart('/')
        }
        val srcIndex = fullPath.indexOf("/src/")
        return if (srcIndex >= 0) fullPath.substring(srcIndex + 1) else fullPath
    }
}
