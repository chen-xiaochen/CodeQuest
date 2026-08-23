package com.codequest.ui.challenge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Java 入口文件 package 声明注入(1.5.0,替代已删除的 1.2.5 Excluded 方案):
 *
 * 挑战目录名 1.5.0 起为合法 Java 包段(连字符→下划线,如 `shortest_palindrome_f2beabaa`),
 * 且**不再 Excluded**(Excluded 会禁用语法检查);为让 IDE 按「包结构对齐」无告警地
 * 索引挑战文件,新建 Java 入口文件时按「文件相对最近 source root 的目录路径」自动
 * 注入首行 `package a.b.c;`。
 *
 * - 文件已有 package 声明(含用户模板自写)→ 不动;
 * - 文件不在任何 source root 下(配置目录在项目外)→ 不注入(该文件本就不被索引);
 * - 包段以目录名生成的下划线形态为合法标识符,不再出现
 *   「package name 'codequest.shortest-palindrome-...' is invalid」;
 * - 提交提取按区域标记行扫描(package 行在类外,不受影响)。
 */
object JavaPackageDeclaration {
    private val LOGGER = Logger.getLogger(JavaPackageDeclaration::class.java.name)

    private val PACKAGE_REGEX = Regex("""(?m)^\s*package\s+[\w.]+\s*;""")

    /** [entryFile] 为 Java 且无 package 声明且位于某 source root 下 → 注入对应包名首行。 */
    fun ensureDeclared(
        project: Project,
        entryFile: Path,
    ) {
        if (!entryFile.fileName.toString().endsWith(".java")) return
        val virtualFile = VfsUtil.findFileByIoFile(entryFile.toFile(), true) ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
        if (PACKAGE_REGEX.containsMatchIn(document.text)) return

        val packageName = packageNameFor(project, virtualFile) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            WriteCommandAction.runWriteCommandAction(project) {
                insertPackageLine(document, packageName)
            }
        }
    }

    /** source root 直下文件(相对路径无目录段)不注入(无 package 合法)。 */
    private fun packageNameFor(
        project: Project,
        file: VirtualFile,
    ): String? {
        val sourceRoot =
            ProjectFileIndex.getInstance(project).getSourceRootForFile(file) ?: return null
        val relative = VfsUtil.getRelativePath(file, sourceRoot, '/') ?: return null
        val dir = relative.substringBeforeLast('/', "")
        if (dir.isEmpty()) return null
        val segments = dir.split('/').filter { it.isNotEmpty() }
        // 防御:历史目录段(中文/连字符)不是合法包段,任一段不合法则放弃注入
        if (segments.any { !it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) {
            LOGGER.info("skip package injection, illegal dir segments: $dir")
            return null
        }
        return segments.joinToString(".")
    }

    private fun insertPackageLine(
        document: Document,
        packageName: String,
    ) {
        WriteAction.compute<Unit, Throwable> {
            document.setText("package $packageName;\n\n${document.text}")
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }
}
