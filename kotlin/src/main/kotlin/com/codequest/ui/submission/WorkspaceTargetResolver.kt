package com.codequest.ui.submission

import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeLanguage
import com.codequest.core.workspace.WorkspaceLanguage
import java.nio.file.Path

/**
 * 编辑器文件 → challenge 工作区定位(WP21,纯 Kotlin)。
 *
 * 输入为「challenge + 其语言配置」列表与工作区根目录推导函数
 * (生产环境由 SubmissionCoordinator 从本地库加载、按项目路径推导),
 * 匹配规则:
 * 1. 文件落在某工作区根目录之下;
 * 2. 语言配置优先级:入口文件相对路径精确匹配 → 文件扩展名匹配 → 唯一语言兜底;
 *    多语言工作区无任何匹配时不猜(null),由 UI 提示。
 */
object WorkspaceTargetResolver {
    data class Resolved(
        val challenge: Challenge,
        val language: ChallengeLanguage,
    )

    fun resolve(
        filePath: Path,
        challenges: List<Pair<Challenge, List<ChallengeLanguage>>>,
        rootFor: (Challenge) -> Path,
    ): Resolved? {
        val normalized = filePath.toAbsolutePath().normalize()
        if (!normalized.isRegularFile()) return null

        val (challenge, languages) =
            challenges.firstOrNull { (candidate, _) ->
                normalized.startsWith(rootFor(candidate).toAbsolutePath().normalize())
            } ?: return null

        val root = rootFor(challenge).toAbsolutePath().normalize()
        val relative = root.relativize(normalized)
        if (relative.nameCount == 0) return null
        val relativePath = relative.toString().replace('\\', '/')

        val language =
            languages.firstOrNull { it.entryFilePath == relativePath }
                ?: languages.firstOrNull { language ->
                    WorkspaceLanguage
                        .fromId(language.language)
                        ?.fileExtension
                        .equals(normalized.extension, ignoreCase = true)
                }
                ?: languages.singleOrNull()
                ?: return null
        return Resolved(challenge, language)
    }

    private fun Path.isRegularFile(): Boolean =
        java.nio.file.Files
            .isRegularFile(this)

    private val Path.extension: String
        get() = fileName?.toString()?.substringAfterLast('.', "") ?: ""
}
