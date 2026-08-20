package com.codequest.infra.settings

import com.codequest.infra.db.DatabaseFolders
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level.PROJECT
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.nio.file.Path
import java.nio.file.Paths

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
        state = newState
    }

    /** 当前生效的数据库**文件夹**(空设置 → 默认 `~/.codequest/`)。 */
    fun effectiveDatabaseFolder(): Path {
        val configured = state.databaseFolder.trim()
        return if (configured.isEmpty()) DatabaseFolders.defaultFolder() else Paths.get(configured)
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
     * WP39:语言入口模板覆盖(languageId → 模板);**空白或缺失 = 用内置模板**
     * ([com.codequest.core.workspace.ChallengeTemplates])。
     *
     * @return 生效的自定义模板;无覆盖时 null(创建链路以此传 `template = null`)
     */
    fun entryTemplateOverride(languageId: String): String? = state.templates[languageId]?.takeIf { it.isNotBlank() }

    /**
     * WP39:写入设置面板提交的模板覆盖表与题面自定义 CSS。
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

    data class State(
        /** 数据库文件夹绝对路径;空 = 使用默认目录 `~/.codequest/`。 */
        var databaseFolder: String = "",
        /** WP39:每语言入口文件模板覆盖(languageId → 模板;空值条目 = 用内置)。 */
        var templates: MutableMap<String, String> = LinkedHashMap(),
        /** WP39:题面页注入的自定义 CSS(空 = 不注入)。 */
        var customCss: String = "",
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
