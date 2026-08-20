package com.codequest.ui.settings

import com.codequest.core.workspace.ChallengeTemplates
import com.codequest.core.workspace.WorkspaceLanguage

/**
 * Templates 区草稿状态(WP39,纯 Kotlin 可测)——面板「语言下拉 + 单编辑器」形态下
 * 的逻辑核:[CodeQuestSettingsConfigurable] 只做组件接线,修改判定/归一化/持久化
 * 全部在这里(单测见 `TemplateDraftsStateTest`)。
 *
 * 归一化语义:草稿**空白**或**与内置默认一致** → 视为无覆盖(持久化时丢弃);
 * 否则按原文存储。未访问语言的编辑器文本 = 已存覆盖 ?: 内置默认(默认文本作为
 * 编辑起点,原样不动则不产生修改——「恢复默认」按钮同样落在该语义上)。
 *
 * @param initial 已持久化的覆盖表(languageId → 模板;blank 条目视同缺失)
 * @param defaults 内置默认模板(测试可注入;生产固定 [ChallengeTemplates])
 */
class TemplateDraftsState(
    initial: Map<String, String> = emptyMap(),
    private val defaults: (String) -> String = ChallengeTemplates::entryTemplate,
) {
    private val stored: Map<String, String> =
        WorkspaceLanguage.ALL.associate { it.id to (initial[it.id] ?: "") }

    private val drafts = LinkedHashMap<String, String>()

    init {
        WorkspaceLanguage.ALL.forEach { drafts[it.id] = stored.getValue(it.id) }
    }

    /** 编辑器应展示的文本:草稿非空白 → 草稿;否则内置默认。 */
    fun editorText(languageId: String): String = overrideOf(languageId) ?: defaults(languageId)

    /** 草稿的非空白覆盖值;空白视同未覆盖(返回 null)。 */
    private fun overrideOf(languageId: String): String? = drafts.getValue(languageId).takeIf { it.isNotBlank() }

    /** 记录当前编辑器文本(语言切换/apply 前由面板 flush)。 */
    fun onEditorText(
        languageId: String,
        text: String,
    ) {
        drafts[languageId] = text
    }

    /** 恢复默认:草稿清空(编辑器回填内置默认文本)。 */
    fun restoreDefault(languageId: String) {
        drafts[languageId] = ""
    }

    /**
     * 草稿的归一化覆盖值:空白或与内置一致 → ""(无覆盖);否则原文。
     *
     * @param text 覆盖当前草稿的实时文本(面板 isModified 未 flush 场景);缺省用草稿
     */
    fun normalizedOverride(
        languageId: String,
        text: String? = null,
    ): String {
        val raw = text ?: drafts.getValue(languageId)
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed == defaults(languageId).trim()) return ""
        return raw
    }

    /** 是否相对持久化状态有修改;[languageId]/[editorText] 覆盖该语言的实时编辑器文本。 */
    fun isModified(
        languageId: String? = null,
        editorText: String? = null,
    ): Boolean =
        WorkspaceLanguage.ALL.any { language ->
            val live =
                if (language.id == languageId) {
                    normalizedOverride(language.id, editorText)
                } else {
                    normalizedOverride(language.id)
                }
            live != stored.getValue(language.id)
        }

    /** 持久化覆盖表:丢弃无覆盖条目(空 map = 全部用内置)。 */
    fun toPersisted(): Map<String, String> =
        WorkspaceLanguage.ALL
            .map { it.id to normalizedOverride(it.id) }
            .toMap()
            .filterValues { it.isNotEmpty() }
}
