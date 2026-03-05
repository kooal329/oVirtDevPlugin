package org.ovirt.idea.index

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.FileTypeIndex
import org.ovirt.idea.model.CommandInfo
import org.ovirt.idea.model.UsageLocation

@Service(Service.Level.PROJECT)
class CommandIndexService(private val project: Project) {

    fun allCommands(): List<CommandInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val javaFiles = FileTypeIndex.getFiles(com.intellij.ide.highlighter.JavaFileType.INSTANCE, scope)

        return javaFiles.mapNotNull { parseCommandInfo(it) }.sortedBy { it.name }
    }

    fun commandByName(name: String): CommandInfo? = allCommands().firstOrNull { it.name == name }

    fun commandsUsingParameters(parameterClass: String): List<CommandInfo> {
        return allCommands().filter { it.parametersClass == parameterClass }
    }

    private fun parseCommandInfo(file: VirtualFile): CommandInfo? {
        val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(file) ?: return null
        val psiClass = (psiFile as? com.intellij.psi.PsiJavaFile)?.classes?.firstOrNull() ?: return null
        if (!isCommandClass(psiClass)) return null

        val text = psiFile.text
        val called = runInternalActionRegex.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.removeSuffix("Command") + "Command" }
            .toSet()

        val params = commandParametersRegex.find(text)?.groupValues?.getOrNull(1)
        val usages = collectUsages(psiClass.name ?: return null)

        return CommandInfo(
            name = psiClass.name ?: return null,
            qualifiedName = psiClass.qualifiedName ?: psiClass.name ?: return null,
            filePath = file.path,
            parametersClass = params,
            calledCommands = called,
            usages = usages
        )
    }

    private fun collectUsages(commandName: String): Set<UsageLocation> {
        val scope = GlobalSearchScope.projectScope(project)
        val files = FilenameIndex.getAllFilesByExt(project, "java", scope)
        return files.flatMap { file ->
            val text = String(file.contentsToByteArray())
            text.lineSequence().mapIndexedNotNull { idx, line ->
                if (line.contains(commandName) || line.contains("VdcActionType.${commandName.removeSuffix("Command")}")) {
                    UsageLocation(file.path, idx + 1, line.trim())
                } else {
                    null
                }
            }
        }.toSet()
    }

    private fun isCommandClass(psiClass: PsiClass): Boolean {
        return psiClass.supers.any {
            it.name?.contains("CommandBase") == true || it.qualifiedName?.contains("CommandBase") == true
        }
    }

    companion object {
        private val runInternalActionRegex =
            Regex("runInternalAction\\s*\\(\\s*VdcActionType\\.([A-Za-z0-9_]+)")
        private val commandParametersRegex =
            Regex("extends\\s+CommandBase\\s*<\\s*([A-Za-z0-9_]+)\\s*>")

        fun getInstance(project: Project): CommandIndexService = project.service()
    }
}
