package com.codequest.ui.challenge

import com.codequest.core.model.Problem
import com.codequest.core.workspace.ChallengeWorkspaceService
import com.codequest.infra.settings.CodeQuestSettings
import java.nio.file.Path

/**
 * 创建链路的模板接线(WP39,原版 LanguageSettingsPanel 语义):创建 challenge 前
 * 读项目设置,`templates[languageId]` 非空白则作为 `template=` 传入
 * [ChallengeWorkspaceService.createChallenge](core 零改动,参数已有);否则传 null
 * 用内置模板。
 *
 * 两条创建路径(NewChallengeDialog 手填 / ChallengeCreateBridge 一键开刷)共用本函数,
 * 保证「改设置 → 下次创建生效」行为一致;设置读取在 ui 层(接线点本就在 ui,
 * 经 project 服务读取合法,见分层规则)。
 */
@Suppress("DEPRECATION") // WP47 迁移:创建链路改按平台风格读双参签名 entryTemplateOverride(style, languageId)
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
        template = settings.entryTemplateOverride(languageId),
    )

/** [createChallengeWithTemplate] 的入口文件路径形态(调用方直接拿 entryFile 打开编辑器)。 */
suspend fun createEntryFileWithTemplate(
    service: ChallengeWorkspaceService,
    problem: Problem,
    languageId: String,
    settings: CodeQuestSettings,
): Path = createChallengeWithTemplate(service, problem, languageId, settings).entryFile
