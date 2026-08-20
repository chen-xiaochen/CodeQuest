package com.codequest.ui.challenge

import com.codequest.core.model.Challenge
import com.codequest.core.workspace.Naming
import com.codequest.infra.settings.CodeQuestSettings
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 工作区目录解析(WP16;修复#44:生成位置跟随「数据库文件夹」设置)。
 *
 * - 设置了数据库文件夹 → `<配置目录>/codequest/<slugified-name>-<id前缀>/`
 *   (代码文件与 `codequest.db` 同根,配置目录即"数据都放这里");
 * - 未设置(默认态)→ `<项目根>/codequest/<slugified-name>-<id前缀>/`
 *   (项目无本地路径时回退临时目录,保证功能仍可用);
 * - 兼容:改设置前创建的旧工作区按**实际存在位置**解析(候选顺序:新位置 → 旧布局),
 *   切换设置不会"丢"既有工作区;全新 challenge(目录尚不存在)落在主位置。
 */
object ChallengeWorkspaceLocations {
    /** 工作区基目录名(配置目录/项目根下的子目录)。 */
    const val WORKSPACE_BASE_DIR = "codequest"

    /** 目录名中携带的 challenge id 前缀长度(避免同名题目互相覆盖)。 */
    private const val ID_PREFIX_LENGTH = 8

    fun workspaceRoot(
        project: Project,
        challenge: Challenge,
    ): Path {
        val dirName = workspaceDirName(challenge)
        return workspaceRoot(
            dirName = dirName,
            configuredFolder = CodeQuestSettings.getInstance(project).state.databaseFolder,
            projectBasePath = project.basePath,
            directoryExists = { Files.isDirectory(it) },
        )
    }

    /** 工作区目录名:`<slugified-name>-<id前缀>`。 */
    fun workspaceDirName(challenge: Challenge): String = "${Naming.slugify(challenge.name)}-${challenge.id.value.take(ID_PREFIX_LENGTH)}"

    /**
     * 纯函数核心(可单测):主位置 = 候选基目录之首;目录在任一候选下已存在(旧工作区)
     * → 按实际存在位置解析,否则主位置(新 challenge 落点)。
     */
    internal fun workspaceRoot(
        dirName: String,
        configuredFolder: String?,
        projectBasePath: String?,
        directoryExists: (Path) -> Boolean,
    ): Path {
        val bases = candidateBaseDirs(configuredFolder, projectBasePath)
        val primary = bases.first().resolve(dirName)
        if (directoryExists(primary)) return primary
        for (base in bases.drop(1)) {
            val candidate = base.resolve(dirName)
            if (directoryExists(candidate)) return candidate
        }
        return primary
    }

    /**
     * 候选基目录(去重,按优先级):
     * 1. `<配置目录>/codequest`(设置了数据库文件夹时);
     * 2. `<项目根>/codequest`(默认落点;无项目路径 → 临时目录 `codequest/`);
     * 3. `<项目根>`(v1.1.0 旧布局:工作区目录直接在项目根下,仅作存在性回退)。
     */
    private fun candidateBaseDirs(
        configuredFolder: String?,
        projectBasePath: String?,
    ): List<Path> {
        val trimmed = configuredFolder?.trim().orEmpty()
        val result = mutableListOf<Path>()
        if (trimmed.isNotEmpty()) result.add(Paths.get(trimmed).resolve(WORKSPACE_BASE_DIR))
        result.add(
            projectBasePath
                ?.let { Paths.get(it, WORKSPACE_BASE_DIR) }
                ?: Paths.get(System.getProperty("java.io.tmpdir"), WORKSPACE_BASE_DIR),
        )
        projectBasePath?.let { result.add(Paths.get(it)) }
        return result.distinct()
    }
}
