package org.ovirt.idea.index

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.ovirt.idea.model.CommandInfo
import org.ovirt.idea.model.UsageLocation

@Service(Service.Level.PROJECT)
class CommandIndexService(private val project: Project) {

    private val cachedCommands: CachedValue<List<CommandInfo>> = CachedValuesManager.getManager(project).createCachedValue {
        CachedValueProvider.Result.create(buildCommandIndex(), PsiModificationTracker.MODIFICATION_COUNT)
    }

    fun allCommands(): List<CommandInfo> = cachedCommands.value

    fun commandByName(name: String): CommandInfo? {
        val command = allCommands().firstOrNull { it.name == name } ?: return null
        return command.copy(usages = collectUsagesForCommand(command.name))
    }

    fun commandsUsingParameters(parameterClass: String): List<CommandInfo> {
        return allCommands().filter { it.parametersClass == parameterClass }
    }

    private fun buildCommandIndex(): List<CommandInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val javaFiles = FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope)

        return javaFiles.mapNotNull { parseCommandSeed(it) }
            .map { seed ->
                CommandInfo(
                    name = seed.name,
                    qualifiedName = seed.qualifiedName,
                    filePath = seed.filePath,
                    parametersClass = seed.parametersClass,
                    calledCommands = seed.calledCommands,
                    usages = emptySet()
                )
            }
            .sortedBy { it.name }
    }

    private fun parseCommandSeed(file: VirtualFile): CommandSeed? {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile ?: return null
        val psiClass = psiFile.classes.firstOrNull() ?: return null
        if (!isCommandClass(psiClass)) return null

        val text = psiFile.text
        val called = runInternalActionRegex.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.removeSuffix("Command") + "Command" }
            .toSet()

        return CommandSeed(
            name = psiClass.name ?: return null,
            qualifiedName = psiClass.qualifiedName ?: psiClass.name ?: return null,
            filePath = file.path,
            parametersClass = commandParametersRegex.find(text)?.groupValues?.getOrNull(1),
            calledCommands = called
        )
    }

    private fun collectUsagesForCommand(commandName: String): Set<UsageLocation> {
        val scope = GlobalSearchScope.projectScope(project)
        val results = LinkedHashSet<UsageLocation>()
        collectUsagesForWord(commandName, scope, results)
        collectUsagesForWord(commandName.removeSuffix("Command"), scope, results)
        return results
    }

    private fun collectUsagesForWord(
        word: String,
        scope: GlobalSearchScope,
        result: MutableSet<UsageLocation>
    ) {
        if (word.isBlank()) return

        val searchHelper = PsiSearchHelper.getInstance(project)
        searchHelper.processElementsWithWord({ element, _ ->
            ProgressManager.checkCanceled()
            val location = element.toUsageLocation() ?: return@processElementsWithWord true
            result.add(location)
            true
        }, scope, word, UsageSearchContext.IN_CODE, true)
    }

    private fun PsiElement.toUsageLocation(): UsageLocation? {
        val virtualFile = containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val lineNumber = document.getLineNumber(textOffset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        val preview = document.charsSequence.subSequence(lineStart, lineEnd).toString().trim()
        return UsageLocation(virtualFile.path, lineNumber + 1, preview)
    private fun collectUsagesForCommand(commandName: String): Set<UsageLocation> {
        val scope = GlobalSearchScope.projectScope(project)
        val javaFiles = FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope)
        val actionTypeName = commandName.removeSuffix("Command")

        return javaFiles.flatMap { file ->
            val text = runCatching { String(file.contentsToByteArray()) }.getOrDefault("")
            if (text.isEmpty()) return@flatMap emptyList()

            text.lineSequence().mapIndexedNotNull { index, line ->
                val isUsage = line.contains(commandName) || line.contains("VdcActionType.$actionTypeName")
                if (isUsage) UsageLocation(file.path, index + 1, line.trim()) else null
            }.toList()
        }.toSet()
    }

    private fun isCommandClass(psiClass: PsiClass): Boolean {
        return psiClass.supers.any {
            it.name?.contains("CommandBase") == true || it.qualifiedName?.contains("CommandBase") == true
        }
    }

    private data class CommandSeed(
        val name: String,
        val qualifiedName: String,
        val filePath: String,
        val parametersClass: String?,
        val calledCommands: Set<String>
    )

    companion object {
        private val runInternalActionRegex =
            Regex("runInternalAction\\s*\\(\\s*VdcActionType\\.([A-Za-z0-9_]+)")
        private val commandParametersRegex =
            Regex("extends\\s+CommandBase\\s*<\\s*([A-Za-z0-9_]+)\\s*>")
        private val actionTypeRegex = Regex("VdcActionType\\.([A-Za-z0-9_]+)")

        fun getInstance(project: Project): CommandIndexService = project.service()
    }
}
