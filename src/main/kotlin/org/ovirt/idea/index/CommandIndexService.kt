package org.ovirt.idea.index

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
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

    fun allCommands(): List<CommandInfo> = ReadAction.compute<List<CommandInfo>, RuntimeException> { cachedCommands.value }

    fun commandByName(name: String): CommandInfo? {
        val command = allCommands().firstOrNull { it.name == name } ?: return null
        return command.copy(usages = collectUsagesForCommand(command.name, command.filePath))
    }

    fun commandByActionName(actionName: String): CommandInfo? {
        val candidates = listOf(
            actionName,
            "${actionName.removeSuffix("Command")}Command",
            "${actionName.removeSuffix("VDSCommand")}VDSCommand",
            "${actionName.removeSuffix("VdsCommand")}VdsCommand"
        ).distinct()
        val commands = allCommands()
        return candidates.firstNotNullOfOrNull { candidate -> commands.firstOrNull { it.name == candidate } }
    }

    fun commandsUsingParameters(parameterClass: String): List<CommandInfo> =
        allCommands().filter { it.parametersClass == parameterClass }

    private fun buildCommandIndex(): List<CommandInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val javaFiles = FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope)
        val seeds = javaFiles.mapNotNull { parseCommandSeed(it) }
        if (seeds.isEmpty()) return emptyList()

        val commandNames = resolveCommandClassNames(seeds)
        val commandSeeds = seeds.filter { it.name in commandNames }
        val actionToCommand = buildActionToCommandMap(commandSeeds)

        return commandSeeds.map { seed ->
            val resolvedCalls = seed.calledActionNames.map { action ->
                actionToCommand[action] ?: guessCommandName(action)
            }.toSet()
            CommandInfo(
                name = seed.name,
                qualifiedName = seed.qualifiedName,
                filePath = seed.filePath,
                parametersClass = seed.parametersClass,
                calledCommands = resolvedCalls,
                usages = emptySet()
            )
        }.sortedBy { it.name }
    }

    private fun parseCommandSeed(file: VirtualFile): CommandSeed? {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile ?: return null
        val psiClass = psiFile.classes.firstOrNull() ?: return null
        val text = psiFile.text

        val calledActions = actionCallRegex.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .toSet()

        return CommandSeed(
            name = psiClass.name ?: return null,
            qualifiedName = psiClass.qualifiedName ?: psiClass.name ?: return null,
            filePath = file.path,
            parametersClass = extractParametersClass(text),
            calledActionNames = calledActions,
            superClassName = extractSuperClassName(text)
        )
    }

    private fun resolveCommandClassNames(seeds: List<CommandSeed>): Set<String> {
        val result = mutableSetOf<String>()
        val baseNames = setOf("CommandBase", "VdsCommand", "VDSCommand", "CommandBaseWithScope")

        seeds.forEach { seed ->
            if (seed.superClassName in baseNames || seed.superClassName?.contains("CommandBase") == true || seed.superClassName?.contains("VDSCommand") == true) {
                result.add(seed.name)
            }
        }

        var changed = true
        while (changed) {
            changed = false
            seeds.forEach { seed ->
                if (seed.name in result) return@forEach
                val superName = seed.superClassName ?: return@forEach
                if (superName in result && result.add(seed.name)) changed = true
            }
        }

        return result
    }

    private fun buildActionToCommandMap(seeds: List<CommandSeed>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        seeds.forEach { seed ->
            map[seed.name] = seed.name
            val base = seed.name.removeSuffix("Command").removeSuffix("VDSCommand").removeSuffix("VdsCommand")
            map[base] = seed.name
            map["${base}Command"] = seed.name
            map["${base}VDSCommand"] = seed.name
            map["${base}VdsCommand"] = seed.name
        }
        return map
    }

    private fun guessCommandName(action: String): String = when {
        action.endsWith("Command") || action.endsWith("VDSCommand") || action.endsWith("VdsCommand") -> action
        else -> "${action}Command"
    }

    private fun extractSuperClassName(text: String): String? {
        val extendsToken = classExtendsRegex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (extendsToken.isBlank()) return null
        return extendsToken.substringBefore('<').substringAfterLast('.')
    }

    private fun extractParametersClass(text: String): String? {
        val typeBounds = extractTypeBounds(text)
        val extendsToken = classExtendsRegex.find(text)?.groupValues?.getOrNull(1) ?: return null
        val genericToken = extendsToken.substringAfter('<', "").substringBeforeLast('>', "").trim()
        if (genericToken.isBlank()) return null
        val cleaned = genericToken.substringAfter("?").trim().substringAfterLast('.').trim()
        return typeBounds[cleaned] ?: cleaned.ifBlank { null }
    }

    private fun extractTypeBounds(text: String): Map<String, String> {
        val declaration = classTypeParametersRegex.find(text)?.groupValues?.getOrNull(1) ?: return emptyMap()
        return declaration.split(',').map { it.trim() }.mapNotNull { entry ->
            val parts = entry.split("extends").map { it.trim() }
            if (parts.size != 2) null else parts[0].substringAfterLast(' ') to parts[1].substringAfterLast('.')
        }.toMap()
    }

    private fun collectUsagesForCommand(commandName: String, commandFilePath: String): Set<UsageLocation> {
        return ReadAction.compute<Set<UsageLocation>, RuntimeException> {
            val scope = GlobalSearchScope.projectScope(project)
            val results = LinkedHashSet<UsageLocation>()
            val actionName = commandName.removeSuffix("Command").removeSuffix("VDSCommand").removeSuffix("VdsCommand")
            collectUsagesForWord(actionName, scope) { location ->
                if (location.filePath == commandFilePath) return@collectUsagesForWord
                if (location.preview.contains("VdcActionType.$actionName") ||
                    location.preview.contains("ActionType.$actionName") ||
                    location.preview.contains("VDSCommandType.$actionName")
                ) results.add(location)
            }
            results
        }
    }

    private fun collectUsagesForWord(word: String, scope: GlobalSearchScope, onLocation: (UsageLocation) -> Unit) {
        if (word.isBlank()) return
        PsiSearchHelper.getInstance(project).processElementsWithWord({ element, _ ->
            ProgressManager.checkCanceled()
            element.toUsageLocation()?.let(onLocation)
            true
        }, scope, word, UsageSearchContext.IN_CODE, true)
    }

    private fun PsiElement.toUsageLocation(): UsageLocation? {
        val file = containingFile?.virtualFile ?: return null
        val doc = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val line = doc.getLineNumber(textOffset)
        val start = doc.getLineStartOffset(line)
        val end = doc.getLineEndOffset(line)
        return UsageLocation(file.path, line + 1, doc.charsSequence.subSequence(start, end).toString().trim())
    }

    private data class CommandSeed(
        val name: String,
        val qualifiedName: String,
        val filePath: String,
        val parametersClass: String?,
        val calledActionNames: Set<String>,
        val superClassName: String?
    )

    companion object {
        private val actionCallRegex = Regex("(?:runInternalAction|runVdsCommand)\\s*\\(\\s*(?:VdcActionType|ActionType|VDSCommandType)\\.([A-Za-z0-9_]+)")
        private val classExtendsRegex = Regex("class\\s+[A-Za-z0-9_]+(?:\\s*<[^>]+>)?\\s+extends\\s+([A-Za-z0-9_$.<>?,\\s]+)")
        private val classTypeParametersRegex = Regex("class\\s+[A-Za-z0-9_]+\\s*<([^>]+)>")

        fun getInstance(project: Project): CommandIndexService = project.service()
    }
}
