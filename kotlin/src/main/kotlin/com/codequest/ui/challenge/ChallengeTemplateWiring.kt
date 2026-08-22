package com.codequest.ui.challenge

import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.workspace.ChallengeWorkspaceService
import com.codequest.core.workspace.TemplateStyle
import com.codequest.infra.settings.CodeQuestSettings
import com.codequest.spi.OJPlatformRegistry
import java.nio.file.Path

/**
 * 平台 → 模版风格解析(WP47):注册表查平台读 `PlatformInfo.templateStyle`;
 * 未知平台兜底 [TemplateStyle.LEETCODE](WP16 以来的历史默认,与未注册平台串
 * 手填创建工作区的既有行为兼容)。
 */
internal fun templateStyleOf(platformId: PlatformId): TemplateStyle =
    OJPlatformRegistry.find(platformId)?.info?.templateStyle ?: TemplateStyle.LEETCODE

/**
 * 创建链路的模板接线(WP39 风格化,WP47):创建 challenge 前按**平台风格**读项目设置
 * `templates[style:languageId]`,非空白则作为 `template=` 传入
 * [ChallengeWorkspaceService.createChallenge](core 零改动,参数已有)。
 *
 * 两条创建路径(NewChallengeDialog 手填 / ChallengeCreateBridge 一键开刷)共用本函数,
 * 保证「改设置 → 下次创建生效」行为一致;设置读取在 ui 层(接线点本就在 ui,
 * 经 project 服务读取合法,见分层规则)。
 *
 * template=null(未配置)场景由调用方在 UI 层拦截(NewChallengeDialog 语言下拉只列
 * 可用语言、ChallengeCreateBridge 优先选可用语言);本函数如实传 null,core 内置
 * 模板是最后防线。
 */
suspend fun createChallengeWithTemplate(
    service: ChallengeWorkspaceService,
    problem: Problem,
    languageId: String,
    settings: CodeQuestSettings,
    code: String? = null,
): ChallengeWorkspaceService.CreatedChallenge =
    service.createChallenge(
        problem = problem,
        languageId = languageId,
        code = code,
        template = settings.entryTemplateOverride(templateStyleOf(problem.platformId), languageId),
    )

/** [createChallengeWithTemplate] 的入口文件路径形态(调用方直接拿 entryFile 打开编辑器);1.2.4 起透传平台起始代码。 */
suspend fun createEntryFileWithTemplate(
    service: ChallengeWorkspaceService,
    problem: Problem,
    languageId: String,
    settings: CodeQuestSettings,
    code: String? = null,
): Path = createChallengeWithTemplate(service, problem, languageId, settings, code).entryFile
