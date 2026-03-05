package org.ovirt.idea.wizard

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager

class NewOvirtCommandAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        val commandName = Messages.showInputDialog(project, "Command Name", "New oVirt Command", null) ?: return
        val baseClass = Messages.showInputDialog(project, "Base Class", "New oVirt Command", null, "CommandBase", null) ?: "CommandBase"
        val parametersClass = Messages.showInputDialog(project, "Parameters Class", "New oVirt Command", null, "${commandName.removeSuffix("Command")}Parameters", null)
            ?: return
        val packageName = Messages.showInputDialog(project, "Package", "New oVirt Command", null) ?: return

        val srcRoot = VfsUtil.createDirectories("$basePath/src/main/java/${packageName.replace('.', '/')}")
        val psiDir = PsiManager.getInstance(project).findDirectory(srcRoot) ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            createJavaClass(
                psiDir,
                commandName,
                packageName,
                """
                public class $commandName extends $baseClass<$parametersClass> {

                    public $commandName($parametersClass parameters) {
                        super(parameters);
                    }

                    @Override
                    protected void executeCommand() {
                    }

                    @Override
                    protected boolean validate() {
                        return true;
                    }
                }
                """.trimIndent()
            )

            createJavaClass(
                psiDir,
                parametersClass,
                packageName,
                """
                public class $parametersClass {
                }
                """.trimIndent()
            )
        }

        Messages.showInfoMessage(project, "Created $commandName and $parametersClass", "oVirt Command Wizard")
    }

    private fun createJavaClass(directory: PsiDirectory, className: String, packageName: String, body: String) {
        val file = directory.virtualFile.createChildData(this, "$className.java")
        file.setBinaryContent(
            """
            package $packageName;

            $body
            """.trimIndent().toByteArray()
        )
    }
}
