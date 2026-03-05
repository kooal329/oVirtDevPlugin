package org.ovirt.idea.index

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
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

    fun commandByName(name: String): CommandInfo? = allCommands().firstOrNull { it.name == name }

    fun commandsUsingParameters(parameterClass: String): List<CommandInfo> {
        return allCommands().filter { it.parametersClass == parameterClass }
    }

    private fun buildCommandIndex(): List<CommandInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val javaFiles = FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope)

        val commandSeeds = javaFiles.mapNotNull { parseCommandSeed(it) }
        if (commandSeeds.isEmpty()) {
            return emptyList()
        }

        val usageMap = collectUsages(javaFiles, commandSeeds)

        return commandSeeds.map { seed ->
            CommandInfo(
                name = seed.name,
                qualifiedName = seed.qualifiedName,
                filePath = seed.filePath,
                parametersClass = seed.parametersClass,
                calledCommands = seed.calledCommands,
                usages = usageMap[seed.name].orEmpty()
            )
        }.sortedBy { it.name }
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

    private fun collectUsages(
        javaFiles: Collection<VirtualFile>,
        seeds: List<CommandSeed>
    ): Map<String, Set<UsageLocation>> {
        val actionTypeToCommand = seeds.associateBy { it.name.removeSuffix("Command") }
        val commandNames = seeds.map { it.name }.toSet()
        val usages = mutableMapOf<String, MutableSet<UsageLocation>>()

        javaFiles.forEach { file ->
            val text = runCatching { String(file.contentsToByteArray()) }.getOrDefault("")
            if (text.isEmpty()) return@forEach

            text.lineSequence().forEachIndexed { index, line ->
                val lineTrimmed = line.trim()
                commandNames.forEach { commandName ->
                    if (line.contains(commandName)) {
                        usages.getOrPut(commandName) { mutableSetOf() }
                            .add(UsageLocation(file.path, index + 1, lineTrimmed))
                    }
                }

                actionTypeRegex.findAll(line).forEach { match ->
                    val actionName = match.groupValues[1]
                    val command = actionTypeToCommand[actionName] ?: return@forEach
                    usages.getOrPut(command.name) { mutableSetOf() }
                        .add(UsageLocation(file.path, index + 1, lineTrimmed))
                }
            }
        }

        return usages
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
