package com.codequest.ui.challenge

import com.codequest.infra.settings.CodeQuestSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 工作区基目录 Excluded 标记(1.2.5):`<项目根>/codequest/`(或数据库文件夹下的
 * `codequest/` 基目录)位于项目内容根内时,其中的挑战文件——目录名含中文/短 id
 * (如 `回文数-0525d717`),非法 Java package 名——会触发 IDE package 检查告警
 * (「Missing package statement ... invalid」)。
 *
 * 把基目录标记 **Excluded**:IDE 不再索引/检查该目录,告警消除;挑战文件的编辑器
 * 打开、进程 javac/java 本地运行**均不受影响**(本地运行器走外部进程,不依赖
 * IDE 索引;提交提取按标记行扫描,同样无关)。
 *
 * 幂等:已 Excluded 则跳过;基目录尚不存在(从未建过挑战)或不在任何模块内容根下
 * (数据库目录在项目外)时为无害 no-op。任意线程可调,内部转 EDT+写动作。
 */
object WorkspaceExclusions {
    /** 确保全部候选基目录(数据库目录下 + 项目根下)均已标记 Excluded。 */
    fun ensureBaseExcluded(project: Project) {
        val configured =
            CodeQuestSettings
                .getInstance(project)
                .state.databaseFolder
                .trim()
        val bases =
            buildList {
                if (configured.isNotEmpty()) {
                    add(Paths.get(configured).resolve(ChallengeWorkspaceLocations.WORKSPACE_BASE_DIR))
                }
                project.basePath?.let { add(Paths.get(it, ChallengeWorkspaceLocations.WORKSPACE_BASE_DIR)) }
            }
        if (bases.isEmpty()) return
        ApplicationManager.getApplication().invokeLater {
            bases.forEach { excludeIfInsideContent(project, it) }
        }
    }

    /** EDT 内:基目录存在于磁盘且位于某模块内容根下、尚未排除 → addExcludeFolder。 */
    private fun excludeIfInsideContent(
        project: Project,
        base: Path,
    ) {
        val baseDir = VfsUtil.findFileByIoFile(base.toFile(), false) ?: return
        // 基目录只会属于一个模块的内容根,取首个命中即可
        val target =
            ModuleManager
                .getInstance(project)
                .modules
                .asSequence()
                .map { module -> ModuleRootManager.getInstance(module) }
                .flatMap { rootManager -> rootManager.contentEntries.map { rootManager to it } }
                .firstOrNull { (_, content) ->
                    content.file?.let { VfsUtil.isAncestor(it, baseDir, false) } == true
                } ?: return
        val (rootManager, entry) = target
        if (entry.excludeFolders.any { it.url == baseDir.url }) return
        ApplicationManager.getApplication().runWriteAction {
            val model = rootManager.modifiableModel
            model.contentEntries
                .firstOrNull { it.file?.url == entry.file!!.url }
                ?.addExcludeFolder(baseDir.url)
            model.commit()
        }
    }
}
