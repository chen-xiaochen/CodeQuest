package com.codequest.ui.challenge

import com.codequest.core.model.Challenge
import com.codequest.core.workspace.Naming
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 工作区目录解析(WP16):项目内 `codequest/<slugified-name>-<id前缀>/`。
 *
 * 统一入口,创建、打开、本地运行共用同一路径推导;项目无本地路径时
 * (如默认项目)回退临时目录,保证功能仍可用。
 */
object ChallengeWorkspaceLocations {
    /** 工作区在项目内的根目录名。 */
    const val WORKSPACE_BASE_DIR = "codequest"

    /** 目录名中携带的 challenge id 前缀长度(避免同名题目互相覆盖)。 */
    private const val ID_PREFIX_LENGTH = 8

    fun workspaceRoot(
        project: Project,
        challenge: Challenge,
    ): Path = baseDir(project).resolve("${Naming.slugify(challenge.name)}-${challenge.id.value.take(ID_PREFIX_LENGTH)}")

    private fun baseDir(project: Project): Path =
        project.basePath
            ?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("java.io.tmpdir"), WORKSPACE_BASE_DIR)
}
