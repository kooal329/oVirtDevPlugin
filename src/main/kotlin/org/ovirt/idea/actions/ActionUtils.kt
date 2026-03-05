package org.ovirt.idea.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil

fun AnActionEvent.selectedClassName(): String? {
    val psiFile = getData(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE) ?: return null
    val editor = getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR)
    if (editor != null) {
        val element = psiFile.findElementAt(editor.caretModel.offset)
        val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        if (psiClass?.name != null) return psiClass.name
    }
    return (psiFile as? com.intellij.psi.PsiJavaFile)?.classes?.firstOrNull()?.name
}

fun <T> runInBackground(
    project: Project,
    title: String,
    compute: (ProgressIndicator) -> T,
    onDone: (T) -> Unit
) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, false) {
        override fun run(indicator: ProgressIndicator) {
            val result = compute(indicator)
            ApplicationManager.getApplication().invokeLater { onDone(result) }
        }
    })
}
