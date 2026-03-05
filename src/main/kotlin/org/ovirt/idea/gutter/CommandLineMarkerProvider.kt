package org.ovirt.idea.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.daemon.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceExpression
import org.ovirt.idea.index.CommandIndexService
import org.ovirt.idea.model.UsageLocation

class CommandLineMarkerProvider : LineMarkerProvider, DumbAware {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null
        val project = element.project
        if (DumbService.isDumb(project)) return null

        buildCallSiteMarker(element)?.let { return it }
        buildCommandMarker(element)?.let { return it }
        return null
    }

    private fun buildCallSiteMarker(element: PsiIdentifier): LineMarkerInfo<*>? {
        val referenceExpression = element.parent as? PsiReferenceExpression ?: return null
        val qualifierText = referenceExpression.qualifierExpression?.text ?: return null
        if (qualifierText != "VdcActionType" && qualifierText != "ActionType") return null

        val service = CommandIndexService.getInstance(element.project)
        val command = service.commandByActionName(element.text) ?: return null
        val target = findClassElementByPath(command.filePath, element.project) ?: return null

        val builder = NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
            .setTooltipText("Go to command ${command.name}")
            .setTargets(listOf(target))
        return builder.createLineMarkerInfo(element)
    }

    private fun buildCommandMarker(element: PsiIdentifier): LineMarkerInfo<*>? {
        val psiClass = element.parent as? PsiClass ?: return null
        val className = psiClass.name ?: return null
        if (!className.endsWith("Command")) return null

        val service = CommandIndexService.getInstance(element.project)
        val command = service.commandByName(className) ?: return null
        val usageTargets = resolveUsageTargets(command.usages, element.project)
        if (usageTargets.isEmpty()) return null

        val builder = NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridenMethod)
            .setTooltipText("Go to usages of $className")
            .setTargets(usageTargets)
        return builder.createLineMarkerInfo(element)
    }

    private fun resolveUsageTargets(usages: Set<UsageLocation>, project: com.intellij.openapi.project.Project): List<PsiElement> {
        return usages.mapNotNull { usage ->
            val file = LocalFileSystem.getInstance().findFileByPath(usage.filePath) ?: return@mapNotNull null
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@mapNotNull null
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull psiFile
            val lineIndex = usage.line - 1
            if (lineIndex !in 0 until document.lineCount) return@mapNotNull psiFile
            val startOffset = document.getLineStartOffset(lineIndex)
            psiFile.findElementAt(startOffset) ?: psiFile
        }.distinctBy { Pair(it.containingFile?.virtualFile?.path, it.textRange?.startOffset ?: -1) }
    }

    private fun findClassElementByPath(path: String, project: com.intellij.openapi.project.Project): PsiElement? {
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile ?: return null
        return psiFile.classes.firstOrNull()?.nameIdentifier ?: psiFile
    }
}
