package org.ovirt.idea.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
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
