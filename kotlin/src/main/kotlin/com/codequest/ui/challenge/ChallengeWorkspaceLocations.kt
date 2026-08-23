package com.codequest.ui.challenge

import com.codequest.core.model.Challenge
import com.codequest.core.workspace.Naming
import com.codequest.infra.db.DatabaseManager
import com.codequest.infra.settings.CodeQuestSettings
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 工作区目录解析(WP16;修复#44:生成位置跟随「数据库文件夹」设置;1.3.2:目录名 ASCII 化)。
 *
 * - 设置了数据库文件夹 → `<配置目录>/codequest/<目录名>/`
 *   (代码文件与 `codequest.db` 同根,配置目录即"数据都放这里");
 * - 未设置(默认态)→ `<项目根>/codequest/<目录名>/`
 *   (项目无本地路径时回退临时目录,保证功能仍可用);
 * - **目录名(1.3.2)**:`<asciiSlug(题目 slug)>-<id前缀>`,如 `palindrome-number-0525d717`——
 *   非_ascii 字符(中文标题)一律过滤,题目 slug(remoteId,如 two-sum)天然 ASCII 故首选;
 *   此前中文目录名(如 `回文数-0525d717`)是非法 Java package 名,需 1.2.5 的 Excluded
 *   标记兜底;ASCII 目录名根因消除该告警(Excluded 保留,兼容旧目录与手填路径);
 * - 兼容:改设置/改命名规则前创建的旧工作区按**实际存在位置与旧目录名**解析
 *   (候选:新名 → 旧命名规则名,逐基目录探测),存量挑战不"丢"。
 */
object ChallengeWorkspaceLocations {
    /** 工作区基目录名(配置目录/项目根下的子目录)。 */
    const val WORKSPACE_BASE_DIR = "codequest"

    /** 目录名中携带的 challenge id 前缀长度(避免同名题目互相覆盖)。 */
    private const val ID_PREFIX_LENGTH = 8

    /** 全 ASCII 化失败时的目录名前缀兜底(中文题且无可用 slug)。 */
    private const val FALLBACK_DIR_NAME = "challenge"

    /**
     * 工作区根定位(项目侧入口):目录名源 = 题目 slug(problem.remoteId,查主档),
     * 主档缺失回退挑战名。
     */
    suspend fun workspaceRoot(
        project: Project,
        challenge: Challenge,
    ): Path {
        val remoteId =
            project
                .getService(DatabaseManager::class.java)
                .problemRepository
                .findById(challenge.problemId)
                ?.remoteId
        return workspaceRoot(
            challenge = challenge,
            slugSource = remoteId,
            configuredFolder = CodeQuestSettings.getInstance(project).state.databaseFolder,
            projectBasePath = project.basePath,
            directoryExists = { Files.isDirectory(it) },
        )
    }

    /**
     * 目录名(1.5.0):`<javaPackageDirName>-<id前缀>`——连字符转**下划线**
     * (如 `shortest_palindrome-f2beabaa` 的目录段必须是合法 Java 包段
     * `shortest_palindrome`,配合 package 声明注入消除 package 检查告警);
     * slug 源 ASCII 化为空时回退 [FALLBACK_DIR_NAME]。
     */
    internal fun workspaceDirName(
        challenge: Challenge,
        slugSource: String?,
    ): String = "${dirNamePrefix(challenge, slugSource)}_${challenge.id.value.take(ID_PREFIX_LENGTH)}"

    /**
     * 纯函数核心(可单测):候选目录名 = 新名(slug 源) → 旧命名规则名(历史中文目录兼容);
     * 逐候选 × 逐候选基目录探测存在性(旧工作区按实际位置解析),全不存在 → 新名 + 主基目录
     * (新 challenge 落点)。
     */
    internal fun workspaceRoot(
        challenge: Challenge,
        slugSource: String?,
        configuredFolder: String?,
        projectBasePath: String?,
        directoryExists: (Path) -> Boolean,
    ): Path {
        val bases = candidateBaseDirs(configuredFolder, projectBasePath)
        // 候选目录名:新名(合法包段) → 1.3.2-1.4.0 连字符 slug 名 → 1.2.x 中文/旧 slugify 名
        val dirNames = listOf(workspaceDirName(challenge, slugSource), legacyHyphenDirName(challenge, slugSource), legacyDirName(challenge))
        for (dirName in dirNames) {
            for (base in bases) {
                val candidate = base.resolve(dirName)
                if (directoryExists(candidate)) return candidate
            }
        }
        return bases.first().resolve(dirNames.first())
    }

    /** 1.3.2-1.4.0 命名:ASCII slug 连字符形态(`two-sum-a1b2c3d4`)。 */
    private fun legacyHyphenDirName(
        challenge: Challenge,
        slugSource: String?,
    ): String =
        (asciiSlugify(slugSource).ifEmpty { asciiSlugify(challenge.name) }.ifEmpty { FALLBACK_DIR_NAME }) +
            "-" + challenge.id.value.take(ID_PREFIX_LENGTH)

    /** 旧命名规则(< 1.3.2):slugify(挑战名)-id 前缀,挑战名中文时目录含中文。 */
    private fun legacyDirName(challenge: Challenge): String =
        "${Naming.slugify(challenge.name)}-${challenge.id.value.take(ID_PREFIX_LENGTH)}"

    /** 目录名前缀(1.5.0):slug 源 ASCII 化后连字符→下划线(合法 Java 包段);空回退固定名。 */
    private fun dirNamePrefix(
        challenge: Challenge,
        slugSource: String?,
    ): String =
        (asciiSlugify(slugSource).ifEmpty { asciiSlugify(challenge.name) })
            .replace('-', '_')
            .ifEmpty { FALLBACK_DIR_NAME }

    /**
     * ASCII slug(1.3.2):slugify 后仅保留 `[a-z0-9-]`,压缩连续连字符、去首尾——
     * 中文等非 ASCII 字符全部过滤(目录名必须是合法的各语言标识符成分)。
     */
    internal fun asciiSlugify(source: String?): String =
        Naming
            .slugify(source)
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            .replace(Regex("-{2,}"), "-")
            .trim('-')

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
