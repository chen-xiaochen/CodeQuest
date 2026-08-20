package com.codequest.ui.toolwindow

import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemId
import com.codequest.core.workspace.ChallengeWorkspaceService
import com.codequest.core.workspace.WorkspaceLanguage
import com.codequest.infra.db.DatabaseManager
import com.codequest.infra.settings.CodeQuestSettings
import com.codequest.spi.LanguageInfo
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import com.codequest.ui.challenge.ChallengeWorkspaceLocations
import com.codequest.ui.challenge.createEntryFileWithTemplate
import com.codequest.ui.jcef.CQBridge
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

/**
 * 一键开刷(1.0.x,参考原版 OpenChallengeActionGroup 交互):`challenge:create` 桥命令。
 *
 * ## 动机
 *
 * 此前开刷要去底部工具窗手填 New Challenge 表单(平台/题目 ID/标题/难度/URL 全靠用户抄);
 * 原版交互是「题目信息全来自列表选中行,用户只选语言」。本命令把题面页已加载的数据
 * 全量自动带入:题面页 Start coding 按钮 → 语言选择(单语言免选,多语言 EDT 模态菜单)
 * → 同题同语言已存在直接打开(幂等,原版 currentSelectedCanBeOpened 语义),否则创建
 * 工作区并在编辑器打开入口文件。底部手填 New Challenge 保留为兜底。
 *
 * 逻辑核(payload 解析/[languagePlanForPlatform]/[createOrOpen])为纯函数式注入,
 * 单测不依赖 IntelliJ 运行时;[register] 只做桥与 IDE 组件接线。
 */
object ChallengeCreateBridge {
    /** 页面 → Kotlin:请求开刷;payload 见 [ChallengeCreateRequest]。 */
    const val TOPIC_CHALLENGE_CREATE = "challenge:create"

    /** Kotlin → 页面:结果;payload `{"ok":bool,"error":"..."}`。 */
    const val TOPIC_CHALLENGE_CREATE_RESULT = "challenge:create-result"

    private val LOGGER = Logger.getLogger(ChallengeCreateBridge::class.java.name)

    /** 在工具窗口的桥上注册开刷命令 handler(工厂 configure 回调调用)。 */
    fun register(
        project: Project,
        bridge: CQBridge,
    ) {
        bridge.on(TOPIC_CHALLENGE_CREATE) { payload ->
            val request = parseChallengeCreatePayload(payload)
            if (request == null) {
                LOGGER.warning("dropping malformed $TOPIC_CHALLENGE_CREATE payload: $payload")
                bridge.postMessage(TOPIC_CHALLENGE_CREATE_RESULT, ChallengeCreateResult(ok = false, error = "malformed request").toJson())
                return@on
            }
            // 语言菜单/DB/文件 IO 均不能占用桥分发线程
            ApplicationManager.getApplication().executeOnPooledThread {
                val result =
                    runBlocking { performCreate(project, request) }
                bridge.postMessage(TOPIC_CHALLENGE_CREATE_RESULT, result.toJson())
            }
        }
    }
}

/** challenge:create 的载荷(题面页已加载的数据全量自动带入)。 */
data class ChallengeCreateRequest(
    val platform: String,
    val remoteId: String,
    val title: String,
    val difficulty: String,
    val url: String,
)

/** 解析 `challenge:create` payload(纯函数,单测锁定协议);缺 platform/remoteId/title 或非法 JSON 返回 null。 */
fun parseChallengeCreatePayload(payload: String): ChallengeCreateRequest? =
    runCatching {
        val element = Json.parseToJsonElement(payload).jsonObject

        fun field(name: String) = element[name]?.jsonPrimitive?.content?.trim()
        val platform = field("platform")
        val remoteId = field("remoteId")
        val title = field("title")
        if (platform.isNullOrEmpty() || remoteId.isNullOrEmpty() || title.isNullOrEmpty()) {
            null
        } else {
            ChallengeCreateRequest(
                platform = platform,
                remoteId = remoteId,
                title = title,
                difficulty = field("difficulty").orEmpty(),
                url = field("url").orEmpty(),
            )
        }
    }.getOrNull()

/** 语言决策:单语言自动选(原版 setPopupGroup(false) 语义),多语言返回候选交由 UI 菜单。 */
sealed interface ChallengeLanguagePlan {
    data class Auto(
        val languageId: String,
    ) : ChallengeLanguagePlan

    data class Choice(
        val languageIds: List<String>,
    ) : ChallengeLanguagePlan
}

/** 语言决策逻辑核(纯函数):平台不存在/无工作区语言映射→失败;否则 Auto 或 Choice。 */
fun languagePlanForPlatform(platform: OJPlatform?): Result<ChallengeLanguagePlan> {
    if (platform == null) return Result.failure(IllegalArgumentException("unknown platform"))
    val languages =
        platform.info.supportedLanguages
            .mapNotNull(LanguageInfo::workspaceLanguageId)
            .distinct()
    if (languages.isEmpty()) return Result.failure(IllegalStateException("platform has no workspace languages"))
    return Result.success(
        if (languages.size == 1) ChallengeLanguagePlan.Auto(languages.single()) else ChallengeLanguagePlan.Choice(languages),
    )
}

/** challenge:create-result 的载荷。 */
data class ChallengeCreateResult(
    val ok: Boolean,
    val error: String? = null,
) {
    fun toJson(): String =
        buildJsonObject {
            put("ok", ok)
            if (error != null) put("error", error)
        }.toString()
}

/**
 * 幂等创建/打开逻辑核(纯函数式注入,单测友好):已有同题同语言工作区→复用入口
 * 文件且不创建;否则创建并返回新入口文件。
 */
suspend fun createOrOpen(
    languageId: String,
    findExisting: suspend (String) -> Path?,
    doCreate: suspend (String) -> Path,
): Path = findExisting(languageId) ?: doCreate(languageId)

/** glue:执行开刷(语言决策→主档复用/补建→幂等创建→EDT 打开编辑器)。 */
private suspend fun performCreate(
    project: Project,
    request: ChallengeCreateRequest,
): ChallengeCreateResult {
    val platform = OJPlatformRegistry.find(PlatformId(request.platform))
    val plan =
        languagePlanForPlatform(platform).getOrElse { error ->
            return ChallengeCreateResult(ok = false, error = error.message)
        }
    val languageId =
        when (plan) {
            is ChallengeLanguagePlan.Auto -> {
                plan.languageId
            }

            is ChallengeLanguagePlan.Choice -> {
                chooseLanguageSync(plan.languageIds)
                    ?: return ChallengeCreateResult(ok = false, error = "cancelled")
            }
        }
    return runCatching {
        val entryFile = createOrOpenInProject(project, request, languageId)
        openInEditor(project, entryFile)
        ChallengeCreateResult(ok = true)
    }.getOrElse { error ->
        ChallengeCreateResult(ok = false, error = error.message ?: error.javaClass.simpleName)
    }
}

/** 多语言选择:pooled 线程内经 invokeAndWait 弹 EDT 模态选择(UiCookiePrompt 同模式)。 */
private fun chooseLanguageSync(languageIds: List<String>): String? {
    val displayNames = languageIds.map { WorkspaceLanguage.fromId(it)?.displayName ?: it }
    var chosen: Int = -1
    ApplicationManager.getApplication().invokeAndWait {
        chosen = Messages.showChooseDialog("Language:", "Start Coding", displayNames.toTypedArray(), displayNames.first(), null)
    }
    return languageIds.getOrNull(chosen)
}

/** 项目侧 createOrOpen 实现:主档复用/补建 → 幂等查找 → 创建。 */
private suspend fun createOrOpenInProject(
    project: Project,
    request: ChallengeCreateRequest,
    languageId: String,
): Path {
    val manager = project.getService(DatabaseManager::class.java)
    val platformId = PlatformId(request.platform)
    // 主档:题面缓存已有则复用(保留 tags/fetchedAt),否则按请求数据补建
    val problem =
        manager.problemStore.findByRemoteId(platformId, request.remoteId)
            ?: Problem(
                id = ProblemId(UUID.randomUUID().toString()),
                platformId = platformId,
                remoteId = request.remoteId,
                title = request.title,
                difficulty = Difficulty(request.difficulty.ifEmpty { "unknown" }),
                url = request.url,
                tags = emptyList(),
                fetchedAt = Instant.now(),
            ).also { manager.problemRepository.save(it) }

    return createOrOpen(
        languageId = languageId,
        findExisting = { lang -> findExistingEntryFile(project, manager, problem.id, lang) },
        // WP39:创建前读设置,自定义模板覆盖内置(无覆盖传 null=内置)
        doCreate = { lang ->
            createEntryFileWithTemplate(
                service = workspaceService(project, manager),
                problem = problem,
                languageId = lang,
                settings = CodeQuestSettings.getInstance(project),
            )
        },
    )
}

/** 幂等查找:同题已建且含该语言的 challenge → 重算工作区根定位入口文件(存在即复用)。 */
private suspend fun findExistingEntryFile(
    project: Project,
    manager: DatabaseManager,
    problemId: ProblemId,
    languageId: String,
): Path? {
    val challenges = manager.challengeRepository.findByProblem(problemId)
    for (challenge in challenges) {
        val config =
            manager.challengeRepository.findLanguages(challenge.id).firstOrNull { it.language == languageId }
                ?: continue
        val entry = ChallengeWorkspaceLocations.workspaceRoot(project, challenge).resolve(config.entryFilePath)
        if (Files.exists(entry)) return entry
    }
    return null
}

private fun openInEditor(
    project: Project,
    file: Path,
) {
    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.toFile().absolutePath)
    if (virtualFile != null) {
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
    } else {
        Logger.getLogger(ChallengeCreateBridge::class.java.name).warning("Workspace file not found on disk: $file")
    }
}

private fun workspaceService(
    project: Project,
    manager: DatabaseManager,
): ChallengeWorkspaceService =
    ChallengeWorkspaceService(
        challengeRepository = manager.challengeRepository,
        solutionRepository = manager.solutionRepository,
        testCaseRepository = manager.testCaseRepository,
        workspaceRoot = { ChallengeWorkspaceLocations.workspaceRoot(project, it) },
    )
