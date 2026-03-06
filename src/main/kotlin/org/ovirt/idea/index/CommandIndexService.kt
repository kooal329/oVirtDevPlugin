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
        return command.copy(usages = collectUsagesForCommand(command.name, command.filePath, command.isVdsCommand))
    }

    fun commandByActionName(actionName: String, qualifier: String? = null): CommandInfo? {
        val commands = allCommands()
        val preferVds = qualifier == "VdsCommandType" || qualifier == "VDSCommandType"

        val candidates = listOf(
            actionName,
            "${actionName.removeSuffix("Command")}Command",
            "${actionName.removeSuffix("VDSCommand")}VDSCommand",
            "${actionName.removeSuffix("VdsCommand")}VdsCommand"
        ).distinct()

        val primary = candidates.firstNotNullOfOrNull { candidate ->
            commands.firstOrNull { it.name == candidate && it.isVdsCommand == preferVds }
        }
        if (primary != null) return primary

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
        val vdsNames = resolveVdsCommandNames(seeds)
        val commandSeeds = seeds.filter { it.name in commandNames }
        val actionToCommand = buildActionToCommandMap(commandSeeds)

        return commandSeeds.map { seed ->
            val resolvedCalls = seed.calledActions.map { action ->
                resolveCalledCommand(action, actionToCommand)
            }.toSet()

            CommandInfo(
                name = seed.name,
                qualifiedName = seed.qualifiedName,
                filePath = seed.filePath,
                parametersClass = seed.parametersClass,
                calledCommands = resolvedCalls,
                usages = emptySet(),
                isVdsCommand = seed.name in vdsNames
            )
        }.sortedBy { it.name }
    }

    private fun resolveCalledCommand(action: ActionCallRef, map: ActionToCommandMap): String {
        val actionName = action.actionName
        val isVdsAction = action.qualifier == "VdsCommandType" || action.qualifier == "VDSCommandType" || action.executor == "runVdsCommand"
        val targetMap = if (isVdsAction) map.vds else map.regular
        return targetMap[actionName] ?: guessCommandName(actionName, isVdsAction)
    }

    private fun parseCommandSeed(file: VirtualFile): CommandSeed? {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile ?: return null
        val psiClass = psiFile.classes.firstOrNull() ?: return null
        val text = psiFile.text

        val calledActions = actionCallRegex.findAll(text).mapNotNull { match ->
            val executor = match.groupValues.getOrNull(1) ?: return@mapNotNull null
            val qualifier = match.groupValues.getOrNull(2) ?: return@mapNotNull null
            val actionName = match.groupValues.getOrNull(3) ?: return@mapNotNull null
            ActionCallRef(executor, qualifier, actionName)
        }.toSet()

        return CommandSeed(
            name = psiClass.name ?: return null,
            qualifiedName = psiClass.qualifiedName ?: psiClass.name ?: return null,
            filePath = file.path,
            parametersClass = extractParametersClass(text),
            calledActions = calledActions,
            superClassName = extractSuperClassName(text)
        )
    }

    private fun resolveCommandClassNames(seeds: List<CommandSeed>): Set<String> {
        val result = mutableSetOf<String>()
        val baseNames = setOf("CommandBase", "VdsCommand", "VDSCommand", "CommandBaseWithScope")

        seeds.forEach { seed ->
            if (seed.superClassName in baseNames || seed.superClassName?.contains("CommandBase") == true || seed.superClassName?.contains("VDSCommand") == true || seed.superClassName?.contains("VdsCommand") == true) {
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

    private fun resolveVdsCommandNames(seeds: List<CommandSeed>): Set<String> {
        val result = mutableSetOf<String>()
        seeds.forEach { seed ->
            val superName = seed.superClassName ?: ""
            if (superName.contains("VDSCommand") || superName.contains("VdsCommand") || seed.name.contains("VDSCommand") || seed.name.contains("VdsCommand")) {
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

    private fun buildActionToCommandMap(seeds: List<CommandSeed>): ActionToCommandMap {
        val regular = mutableMapOf<String, String>()
        val vds = mutableMapOf<String, String>()

        val vdsNames = resolveVdsCommandNames(seeds)
        seeds.forEach { seed ->
            val base = seed.name.removeSuffix("Command").removeSuffix("VDSCommand").removeSuffix("VdsCommand")
            val target = if (seed.name in vdsNames) vds else regular
            target[base] = seed.name
            target["${base}Command"] = seed.name
            target["${base}VDSCommand"] = seed.name
            target["${base}VdsCommand"] = seed.name
            target[seed.name] = seed.name
        }
        return ActionToCommandMap(regular, vds)
    }

    private fun guessCommandName(action: String, vds: Boolean): String {
        if (action.endsWith("Command") || action.endsWith("VDSCommand") || action.endsWith("VdsCommand")) return action
        return if (vds) "${action}VDSCommand" else "${action}Command"
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

    private fun collectUsagesForCommand(commandName: String, commandFilePath: String, isVdsCommand: Boolean): Set<UsageLocation> {
        return ReadAction.compute<Set<UsageLocation>, RuntimeException> {
            val scope = GlobalSearchScope.projectScope(project)
            val results = LinkedHashSet<UsageLocation>()
            val actionName = commandName.removeSuffix("Command").removeSuffix("VDSCommand").removeSuffix("VdsCommand")
            collectUsagesForWord(actionName, scope) { location ->
                if (location.filePath == commandFilePath) return@collectUsagesForWord
                val p = location.preview
                val isCall = if (isVdsCommand) {
                    p.contains("VdsCommandType.$actionName") || p.contains("VDSCommandType.$actionName")
                } else {
                    p.contains("VdcActionType.$actionName") || p.contains("ActionType.$actionName")
                }
                if (isCall) results.add(location)
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
        val calledActions: Set<ActionCallRef>,
        val superClassName: String?
    )

    private data class ActionCallRef(
        val executor: String,
        val qualifier: String,
        val actionName: String
    )

    private data class ActionToCommandMap(
        val regular: Map<String, String>,
        val vds: Map<String, String>
    )

    companion object {
        private val actionCallRegex =
            Regex("(runInternalAction|runVdsCommand)\\s*\\(\\s*(VdcActionType|ActionType|VdsCommandType|VDSCommandType)\\.([A-Za-z0-9_]+)")
        private val classExtendsRegex = Regex("class\\s+[A-Za-z0-9_]+(?:\\s*<[^>]+>)?\\s+extends\\s+([A-Za-z0-9_$.<>?,\\s]+)")
        private val classTypeParametersRegex = Regex("class\\s+[A-Za-z0-9_]+\\s*<([^>]+)>")

        fun getInstance(project: Project): CommandIndexService = project.service()
    }
}
