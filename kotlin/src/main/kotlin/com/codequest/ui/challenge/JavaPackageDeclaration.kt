package com.codequest.ui.challenge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Java 入口文件 package 声明(1.5.0 注入;1.5.1 增模板变量与空包名清理):
 *
 * - **模板变量**(1.5.1):模板可写 `package ${challenge.packageName};`,渲染时经
 *   [packageNameFor] 按工作区目录相对最近 source root 的路径算出(如
 *   `codequest.roman_to_integer_321bb012`);目录不在任何 source root 下 → 空串;
 * - **后处理注入**(1.5.0):模板没写 package 变量的 Java 文件,创建后自动注入首行
 *   (同一包名逻辑);已声明 → 不动;
 * - **空包名清理**(1.5.1):模板写了 `package ${challenge.packageName};` 而包名算不出
 *   (空串)会渲染成 `package ;` 非法行——后处理先删此类行,再决定是否注入。
 *
 * 提交提取按区域标记行扫描(package 行在类外,不受影响)。
 */
object JavaPackageDeclaration {
    private val PACKAGE_REGEX = Regex("""(?m)^\s*package\s+[\w.]+\s*;""")
    private val EMPTY_PACKAGE_LINE = Regex("""(?m)^[ \t]*package\s*;[ \t]*\r?\n?""")

    /**
     * 纯函数核心(可单测):目录 [dir] 相对某个 source root 路径的包名。
     * source root 依深度取最长匹配;包段须为合法标识符,任一段不合法返回 null。
     */
    internal fun relativePackageName(
        sourceRoots: List<Path>,
        dir: Path,
    ): String? {
        val normalized = dir.toAbsolutePath().normalize()
        val matched =
            sourceRoots
                .map { it.toAbsolutePath().normalize() }
                .filter { it != normalized && normalized.startsWith(it) }
                .maxByOrNull { it.nameCount }
                ?: return null
        val segments =
            (matched.nameCount until normalized.nameCount).map { normalized.getName(it).toString() }
        if (segments.any { !it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) return null
        return segments.joinToString(".")
    }

    /**
     * 项目侧包名计算(渲染前,文件/目录尚不存在):工作区目录在项目某 source root 下 →
     * 相对路径包名;不在(配置目录在项目外)→ null。供模板变量 `challenge.packageName`。
     */
    fun packageNameFor(
        project: Project,
        workspaceRoot: Path,
    ): String? =
        ApplicationManager
            .getApplication()
            .runReadAction<List<Path>> {
                com.intellij.openapi.roots.ProjectRootManager
                    .getInstance(project)
                    .contentSourceRoots
                    .map { it.toNioPath() }
            }.let { roots -> relativePackageName(roots, workspaceRoot) }

    /** [entryFile] 为 Java 且无 package 声明且位于某 source root 下 → 注入对应包名首行。 */
    fun ensureDeclared(
        project: Project,
        entryFile: Path,
    ) {
        if (!entryFile.fileName.toString().endsWith(".java")) return
        val virtualFile = VfsUtil.findFileByIoFile(entryFile.toFile(), true) ?: return
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
        // 先清空包名行(模板变量算不出时渲染的 `package ;`),再判是否需要注入
        if (EMPTY_PACKAGE_LINE.containsMatchIn(document.text)) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                WriteCommandAction.runWriteCommandAction(project) {
                    replaceDocument(document, EMPTY_PACKAGE_LINE.replace(document.text, ""))
                    FileDocumentManager.getInstance().saveDocument(document)
                }
            }
            return
        }
        if (PACKAGE_REGEX.containsMatchIn(document.text)) return

        val packageName = packageNameFor(project, entryFile.toAbsolutePath().normalize().parent) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            WriteCommandAction.runWriteCommandAction(project) {
                insertPackageLine(document, packageName)
            }
        }
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

    private fun replaceDocument(
        document: Document,
        newText: String,
    ) {
        WriteAction.compute<Unit, Throwable> {
            document.setText(newText)
        }
    }
}
