package org.ovirt.idea.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.table.JBTable
import org.ovirt.idea.model.UsageLocation
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

class CommandUsagesDialog(
    private val project: Project,
    private val commandName: String,
    usages: List<UsageLocation>,
    private val basePath: String?
) : DialogWrapper(project) {

    private val tableModel = object : DefaultTableModel(arrayOf("Line", "Class", "File", "Code"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    private val table = JBTable(tableModel).apply {
        setStriped(true)
        autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        rowSorter = TableRowSorter(tableModel)
        emptyText.text = "No usages found"
        columnModel.getColumn(0).preferredWidth = 64
        columnModel.getColumn(0).maxWidth = 80
        columnModel.getColumn(1).preferredWidth = 260
        columnModel.getColumn(2).preferredWidth = 420
        rowSorter.setSortKeys(listOf(RowSorter.SortKey(2, SortOrder.ASCENDING), RowSorter.SortKey(0, SortOrder.ASCENDING)))
    }

    init {
        title = "oVirt-Command-manager: Usages of $commandName"
        setSize(1320, 780)
        usages.forEach { usage ->
            tableModel.addRow(arrayOf(usage.line, classNameFromPath(usage.filePath), toDisplayPath(usage.filePath), usage.preview))
        }

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelectedUsage()
            }
        })

        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.preferredSize = Dimension(1300, 760)
        root.add(
            JLabel("${usagesCountText()} • Double-click row to navigate"),
            BorderLayout.NORTH
        )
        root.add(JScrollPane(table), BorderLayout.CENTER)
        return root
    }

    private fun openSelectedUsage() {
        val viewRow = table.selectedRow
        if (viewRow < 0) return
        val modelRow = table.convertRowIndexToModel(viewRow)
        val filePath = tableModel.getValueAt(modelRow, 2).toString()
        val line = (tableModel.getValueAt(modelRow, 0) as? Number)?.toInt() ?: return

        val absPath = if (filePath.startsWith("src/") && !basePath.isNullOrBlank()) "$basePath/$filePath" else filePath
        val file = LocalFileSystem.getInstance().findFileByPath(absPath) ?: return
        OpenFileDescriptor(project, file, maxOf(line - 1, 0), 0).navigate(true)
    }

    private fun classNameFromPath(path: String): String {
        val name = path.substringAfterLast('/')
        return name.substringBeforeLast('.')
    }

    private fun usagesCountText(): String = "Found ${tableModel.rowCount} call sites"

    private fun toDisplayPath(fullPath: String): String {
        if (!basePath.isNullOrBlank() && fullPath.startsWith(basePath)) {
            return fullPath.removePrefix(basePath).trimStart('/')
        }
        val srcIndex = fullPath.indexOf("/src/")
        return if (srcIndex >= 0) fullPath.substring(srcIndex + 1) else fullPath
    }
}
