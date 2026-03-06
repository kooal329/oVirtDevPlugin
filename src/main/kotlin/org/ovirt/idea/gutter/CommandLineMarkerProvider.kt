package org.ovirt.idea.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
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
import org.ovirt.idea.ui.OvirtIcons

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
        if (qualifierText != "VdcActionType" && qualifierText != "ActionType" && qualifierText != "VDSCommandType") return null

        val service = CommandIndexService.getInstance(element.project)
        val command = service.commandByActionName(element.text, qualifierText) ?: return null
        val target = findClassElementByPath(command.filePath, element.project) ?: return null

        return NavigationGutterIconBuilder.create(OvirtIcons.Command)
            .setTooltipText("oVirt-Command-manager: Go to ${command.name}")
            .setPopupTitle("oVirt command target")
            .setTargets(listOf(target))
            .createLineMarkerInfo(element)
    }

    private fun buildCommandMarker(element: PsiIdentifier): LineMarkerInfo<*>? {
        val psiClass = element.parent as? PsiClass ?: return null
        val className = psiClass.name ?: return null
        if (!className.endsWith("Command")) return null

        val service = CommandIndexService.getInstance(element.project)
        val command = service.commandByName(className) ?: return null
        val usageClasses = resolveUsageClasses(command.usages, element.project)
        if (usageClasses.isEmpty()) return null

        return NavigationGutterIconBuilder.create(OvirtIcons.Usage)
            .setTooltipText("oVirt-Command-manager: Go to call classes of $className")
            .setPopupTitle("Classes calling $className")
            .setTargets(usageClasses)
            .createLineMarkerInfo(element)
    }

    private fun resolveUsageClasses(usages: Set<UsageLocation>, project: com.intellij.openapi.project.Project): List<PsiElement> {
        return usages.mapNotNull { usage ->
            val file = LocalFileSystem.getInstance().findFileByPath(usage.filePath) ?: return@mapNotNull null
            val psiJavaFile = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile ?: return@mapNotNull null
            psiJavaFile.classes.firstOrNull()?.nameIdentifier ?: psiJavaFile
        }.distinctBy { it.containingFile?.virtualFile?.path }
    }

    private fun findClassElementByPath(path: String, project: com.intellij.openapi.project.Project): PsiElement? {
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile ?: return null
        return psiFile.classes.firstOrNull()?.nameIdentifier ?: psiFile
    }
}
