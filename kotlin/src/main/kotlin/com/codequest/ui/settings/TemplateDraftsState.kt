package com.codequest.ui.settings

import com.codequest.core.workspace.ChallengeTemplates
import com.codequest.core.workspace.TemplateStyle
import com.codequest.core.workspace.WorkspaceLanguage

/**
 * Templates 区草稿状态(WP39;WP45 升级为风格×语言双键)——面板「语言下拉 + 单编辑器」
 * 形态下的逻辑核:[CodeQuestSettingsConfigurable] 只做组件接线,修改判定/归一化/持久化
 * 全部在这里(单测见 `TemplateDraftsStateTest`)。
 *
 * 双键草稿空间:[TemplateStyle.entries] × [WorkspaceLanguage.ALL] 全组合;持久化键
 * 形态 = [TemplateStyle.storageKey](如 "acm:java")。
 *
 * 显式启用语义(WP45,**废除** WP39「与内置一致 → 视为无覆盖」):
 * - 草稿**非空白**——即使与内置默认逐字一致——= 已配置,持久化保留:用户打开设置
 *   点「应用」即显式启用,哪怕没改默认文本;
 * - 草稿**空白** = 未配置,持久化丢弃(该 (风格,语言) 不可用,见
 *   `core.workspace.TemplateAvailability`)。
 *
 * 编辑起点:未访问 (风格,语言) 的编辑器文本 = 已存覆盖 ?: 对应风格内置默认。
 * 「恢复默认」(WP45 语义变更)= 把该 (风格,语言) 草稿**回填为对应风格内置默认文本**
 * (编辑起点重置;不改启用状态——回填后非空白,点应用即按已配置持久化)。
 *
 * @param initial 已持久化的覆盖表(键 = storageKey 新格式;blank 条目视同缺失)
 * @param defaults (风格,语言) → 内置默认模版(测试可注入;生产固定 [ChallengeTemplates])
 */
class TemplateDraftsState(
    initial: Map<String, String> = emptyMap(),
    private val defaults: (TemplateStyle, String) -> String = { style, languageId ->
        ChallengeTemplates.entryTemplate(style, languageId)
    },
) {
    /** 草稿双键(风格×语言);data class 以获得值语义的 equals/hashCode。 */
    private data class DraftKey(
        val style: TemplateStyle,
        val languageId: String,
    )

    /** 全部 (风格,语言) 组合,风格外层 × 语言内层,插入序即草稿遍历序。 */
    private val keys: List<DraftKey> =
        TemplateStyle.entries.flatMap { style ->
            WorkspaceLanguage.ALL.map { DraftKey(style, it.id) }
        }

    /** 持久化基线(storageKey 键下的非空白值;空白/缺失统一记 "",否则 isModified 误报)。 */
    private val stored: Map<DraftKey, String> =
        keys.associate { key ->
            key to (initial[key.style.storageKey(key.languageId)]?.takeIf { it.isNotBlank() } ?: "")
        }

    private val drafts = LinkedHashMap<DraftKey, String>()

    init {
        keys.forEach { drafts[it] = stored.getValue(it) }
    }

    /** 编辑器应展示的文本:草稿非空白 → 草稿;否则该风格内置默认。 */
    fun editorText(
        style: TemplateStyle,
        languageId: String,
    ): String = overrideOf(style, languageId) ?: defaults(style, languageId)

    /** 草稿的非空白覆盖值;空白视同未配置(返回 null)。 */
    private fun overrideOf(
        style: TemplateStyle,
        languageId: String,
    ): String? = drafts.getValue(DraftKey(style, languageId)).takeIf { it.isNotBlank() }

    /** 记录当前编辑器文本(风格/语言切换/apply 前由面板 flush)。 */
    fun onEditorText(
        style: TemplateStyle,
        languageId: String,
        text: String,
    ) {
        drafts[DraftKey(style, languageId)] = text
    }

    /** 恢复默认(WP45):草稿回填该 (风格,语言) 的内置默认文本(编辑起点重置,不改启用状态)。 */
    fun restoreDefault(
        style: TemplateStyle,
        languageId: String,
    ) {
        drafts[DraftKey(style, languageId)] = defaults(style, languageId)
    }

    /**
     * 选择切换时序(WP46 面板接线核):风格或语言任一切换时,先把**旧** (风格,语言) 的
     * 编辑器实时文本落草稿(flush),再返回**新** (风格,语言) 应展示的编辑器文本。
     *
     * 抽到本类的原因:面板 Swing 接线无法脱离 IDE 单测,切换 flush 时序在此由
     * `TemplateDraftsStateTest` 锁定;同 (风格,语言) 调用 = flush 即回填(文本原样)。
     */
    fun switchSelection(
        oldStyle: TemplateStyle,
        oldLanguageId: String,
        editorText: String,
        newStyle: TemplateStyle,
        newLanguageId: String,
    ): String {
        onEditorText(oldStyle, oldLanguageId, editorText)
        return editorText(newStyle, newLanguageId)
    }

    /**
     * 草稿的归一化覆盖值:空白 → ""(未配置);非空白 → 原文(与内置默认一致也算已配置,
     * WP45 显式启用语义)。
     *
     * @param text 覆盖当前草稿的实时文本(面板 isModified 未 flush 场景);缺省用草稿
     */
    fun normalizedOverride(
        style: TemplateStyle,
        languageId: String,
        text: String? = null,
    ): String {
        val raw = text ?: drafts.getValue(DraftKey(style, languageId))
        return if (raw.isBlank()) "" else raw
    }

    /**
     * 是否相对持久化状态有修改;[style]+[languageId]+[editorText] 三者同时给出时,
     * 覆盖该 (风格,语言) 的实时编辑器文本(未 flush 场景)。
     */
    fun isModified(
        style: TemplateStyle? = null,
        languageId: String? = null,
        editorText: String? = null,
    ): Boolean =
        keys.any { key ->
            val live =
                if (style != null && languageId != null && key == DraftKey(style, languageId)) {
                    normalizedOverride(key.style, key.languageId, editorText)
                } else {
                    normalizedOverride(key.style, key.languageId)
                }
            live != stored.getValue(key)
        }

    /** 持久化覆盖表(键 = storageKey 新格式):丢弃空白(未配置)条目,空 map = 全部未配置。 */
    fun toPersisted(): Map<String, String> =
        keys
            .map { key -> key.style.storageKey(key.languageId) to normalizedOverride(key.style, key.languageId) }
            .toMap()
            .filterValues { it.isNotEmpty() }
}
