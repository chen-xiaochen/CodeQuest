package com.codequest.infra.settings

import com.codequest.core.workspace.TemplateStyle
import com.codequest.infra.db.DatabaseFolders
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level.PROJECT
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.nio.file.Path

/**
 * CodeQuest 项目级设置(WP15):持久化「数据库文件夹」路径。
 *
 * 分层归属:设置**存取**(state + 变更 topic)放 `infra.settings`——它是被数据层
 * (`infra.db.DatabaseManager`)消费的基础设施,而 infra 禁止依赖 ui;设置**面板**
 * (Kotlin UI DSL)放 `ui.settings`,ui 可依赖 infra,通过本服务读写与发布。
 *
 * state 默认 `databaseFolder = ""` = 默认态:数据库落在 `~/.codequest/`(见
 * [DatabaseFolders]);构造不依赖 Project,便于纯 JVM 单测。
 */
@Service(PROJECT)
@State(name = "CodeQuestSettings", storages = [Storage("codequest.xml")])
class CodeQuestSettings : PersistentStateComponent<CodeQuestSettings.State> {
    private var state = State()

    override fun getState(): State = state

    override fun loadState(newState: State) {
        state = newState.copy(templates = normalizeTemplateKeys(newState.templates))
    }

    /**
     * 写入新的数据库文件夹(空串 = 回到默认目录)。
     *
     * @return 生效的数据库文件路径,调用方(设置面板)用它发布 [DATABASE_FOLDER_TOPIC]。
     */
    fun updateDatabaseFolder(folder: String): Path {
        state = state.copy(databaseFolder = folder)
        return DatabaseFolders.databaseFile(folder)
    }

    /**
     * WP45:按风格读语言入口模板覆盖(键 = [TemplateStyle.storageKey])。
     *
     * @return 该 (风格,语言) 的自定义模板;**非空白 = 启用返回原文,空白/缺失 = null**
     * (运行时无内置兜底——内置默认由创建链路在 template=null 时另行决定,WP47 接线)
     */
    fun entryTemplateOverride(
        style: TemplateStyle,
        languageId: String,
    ): String? = state.templates[style.storageKey(languageId)]?.takeIf { it.isNotBlank() }

    /**
     * WP45:模版键归一化——旧格式键(不含冒号的纯语言键,WP39-44)一次性**双写**迁移,
     * 放在 loadState 内保证对 PersistentStateComponent 反序列化结果自动生效。
     *
     * 规则(幂等,归一化后再次 loadState 无变化):
     * - 键含冒号(已是 "style:languageId" 新格式)→ 原样保留(值不动,空白条目也如实保留);
     * - 键不含冒号且值非空白 → 双写到 `acm:<L>` 与 `leetcode:<L>`(沿用旧值)并移除旧键;
     *   同语言新格式键已存在时**新键优先**,旧键值不覆盖(降级再升级的混合态以已迁移数据为准);
     * - 键不含冒号且值空白 → 未配置,直接丢弃(与 [entryTemplateOverride] 空白 = null 语义一致,
     *   不把噪声写进新键)。
     */
    private fun normalizeTemplateKeys(templates: Map<String, String>): LinkedHashMap<String, String> {
        val normalized = LinkedHashMap<String, String>()
        templates.forEach { (key, value) ->
            when {
                key.contains(':') -> normalized[key] = value
                value.isNotBlank() -> doubleWriteLegacyKey(key, value, normalized)
            }
        }
        return normalized
    }

    /** 旧格式键双写到全风格 storageKey;已存在的新格式键不覆盖(新键优先)。 */
    private fun doubleWriteLegacyKey(
        legacyLanguageId: String,
        value: String,
        normalized: LinkedHashMap<String, String>,
    ) {
        TemplateStyle.entries.forEach { style ->
            val storageKey = style.storageKey(legacyLanguageId)
            if (storageKey !in normalized) normalized[storageKey] = value
        }
    }

    /**
     * WP39:写入设置面板提交的模板覆盖表与题面自定义 CSS(WP45 起 templates 键 =
     * "style:languageId" 新格式,由 TemplateDraftsState.toPersisted 产出)。
     *
     * 无变更 topic:模板在**下次创建** challenge 时读取,CSS 在题面页**下次加载**时
     * 经 `/api/custom.css` 现读(端点按请求取值,改设置即刻对新请求生效),无需广播。
     */
    fun updateTemplatesAndCustomCss(
        templates: Map<String, String>,
        customCss: String,
    ) {
        state = state.copy(templates = LinkedHashMap(templates), customCss = customCss)
    }

    /**
     * 1.6.0:记录最近打开的题目(题面会话恢复;串格式与编解码见
     * `ui.toolwindow.encodeLastOpenedProblem`,空串 = 未记录)。
     */
    fun updateLastOpenedProblem(encoded: String) {
        state = state.copy(lastOpenedProblem = encoded)
    }

    data class State(
        /** 数据库文件夹绝对路径;空 = 使用默认目录 `~/.codequest/`。 */
        var databaseFolder: String = "",
        /**
         * WP45:风格×语言入口文件模板覆盖,键 = "style:languageId"(如 "acm:java",
         * 见 [TemplateStyle.storageKey];值结构不变,仅键格式约定升级);值非空白 =
         * 启用该模板,空白条目 = 未配置(仅旧状态迁移遗留,loadState 归一化时旧格式
         * 空白条目会被清除,新格式空白条目如实保留)。
         */
        var templates: MutableMap<String, String> = LinkedHashMap(),
        /** WP39:题面页注入的自定义 CSS(空 = 不注入)。 */
        var customCss: String = "",
        /** 1.6.0:最近打开的题目持久化串(`platformId\tremoteId`,空 = 未记录),启动时恢复题面。 */
        var lastOpenedProblem: String = "",
    )

    /**
     * 数据库文件夹变更监听器:设置面板 apply 成功后以**生效文件夹**发布
     * [companion object][Companion] 中的 [DATABASE_FOLDER_TOPIC];
     * `infra.db.DatabaseManager` 订阅并重建数据库连接。
     */
    fun interface DatabaseFolderListener {
        /** @param folder 生效的数据库文件夹(空设置时为默认目录)。 */
        fun databaseFolderChanged(folder: Path)
    }

    companion object {
        /** 数据库文件夹变更 topic。 */
        val DATABASE_FOLDER_TOPIC: Topic<DatabaseFolderListener> =
            Topic("database_folder_changed", DatabaseFolderListener::class.java)

        fun getInstance(project: Project): CodeQuestSettings = project.getService(CodeQuestSettings::class.java)
    }
}
