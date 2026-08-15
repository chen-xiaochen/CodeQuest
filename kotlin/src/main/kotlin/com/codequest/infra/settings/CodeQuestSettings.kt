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

    data class State(
        /** 数据库文件夹绝对路径;空 = 使用默认目录 `~/.codequest/`。 */
        var databaseFolder: String = "",
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
