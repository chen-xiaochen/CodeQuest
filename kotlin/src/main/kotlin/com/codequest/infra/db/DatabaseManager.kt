package com.codequest.infra.db

import com.codequest.core.repository.ChallengeRepository
import com.codequest.core.repository.ContestReminderRepository
import com.codequest.core.repository.ProblemRepository
import com.codequest.core.repository.SolutionRepository
import com.codequest.core.repository.TagRepository
import com.codequest.core.repository.TestCaseRepository
import com.codequest.infra.settings.CodeQuestSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level.PROJECT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.file.Path
import java.util.logging.Logger

/**
 * 当前数据库 + 全部仓库实例的持有者(WP15,纯 Kotlin,可脱离 IntelliJ Platform 测试)。
 *
 * [switchTo] 即 WP14 约定的切库方式:close 旧 [CodeQuestDatabase] → 新路径重建;
 * 仓库 getter 永远取自当前 handle,切库后自然指向新库(旧 handle 关闭后不可再用)。
 */
class ManagedDatabase(
    folder: String?,
) : AutoCloseable {
    private val logger = Logger.getLogger(ManagedDatabase::class.java.name)

    @Volatile
    private var handle: Handle = open(folder)

    /** 当前数据库文件所在的文件夹。 */
    val databaseFolder: Path
        get() = handle.folder

    val database: CodeQuestDatabase
        get() = handle.db

    val problemRepository: ProblemRepository
        get() = handle.problem

    /** 端点用存取端口(WP20),与 [problemRepository] 同一实现实例。 */
    val problemStore: ProblemStore
        get() = handle.problem

    /** 跨题全文检索端口(WP30),独立实现实例(不改既有仓库)。 */
    val searchStore: SearchStore
        get() = handle.search

    /** 题目刷题状态聚合端口(WP37),独立实现实例(不改既有仓库)。 */
    val problemStatusStore: ProblemStatusStore
        get() = handle.statuses

    /** 具体类型(WP21):除 [SubmissionRepository] 契约外提供 submissions 端点用的分页查询。 */
    val submissionRepository: ExposedSubmissionRepository
        get() = handle.submission

    val challengeRepository: ChallengeRepository
        get() = handle.challenge

    val solutionRepository: SolutionRepository
        get() = handle.solution

    val testCaseRepository: TestCaseRepository
        get() = handle.testCase

    val tagRepository: TagRepository
        get() = handle.tag

    val contestReminderRepository: ContestReminderRepository
        get() = handle.contestReminder

    /** 切库:新库就绪后再关旧库,窗口期极短;切库不迁移数据。 */
    @Synchronized
    fun switchTo(folder: String?) {
        val target = DatabaseFolders.databaseFile(folder).parent
        if (target == handle.folder) return
        val old = handle
        handle = open(folder)
        old.close()
        logger.info("CodeQuest database switched to $target")
    }

    override fun close() {
        handle.close()
    }

    private fun open(folder: String?): Handle {
        val resolved = DatabaseFolders.databaseFile(folder)
        val db = CodeQuestDatabase(DatabaseConfigProvider { resolved })
        return Handle(db, requireNotNull(resolved.parent))
    }

    private class Handle(
        val db: CodeQuestDatabase,
        val folder: Path,
    ) : AutoCloseable {
        // 具体类型暴露(WP20):同一实例同时充当 ProblemRepository 与 ProblemStore
        val problem: ExposedProblemRepository by lazy { ExposedProblemRepository(db) }
        val search: ExposedSearchStore by lazy { ExposedSearchStore(db) }
        val statuses: ExposedProblemStatusStore by lazy { ExposedProblemStatusStore(db) }
        val submission by lazy { ExposedSubmissionRepository(db) }
        val challenge by lazy { ExposedChallengeRepository(db) }
        val solution by lazy { ExposedSolutionRepository(db) }
        val testCase by lazy { ExposedTestCaseRepository(db) }
        val tag by lazy { ExposedTagRepository(db) }
        val contestReminder by lazy { ExposedContestReminderRepository(db) }

        override fun close() {
            db.close()
        }
    }
}

/**
 * 项目级服务(WP15):按设置持有 [ManagedDatabase],订阅数据库文件夹变更 topic。
 *
 * - 项目打开:由 [DatabaseManagerStartupActivity] 触发实例化,按当前设置开库;
 * - 设置变更:收到 [CodeQuestSettings.DATABASE_FOLDER_TOPIC] → [ManagedDatabase.switchTo];
 * - 项目关闭:随服务销毁 close 数据库。
 */
@Service(PROJECT)
class DatabaseManager(
    project: Project,
) : Disposable {
    private val settings = CodeQuestSettings.getInstance(project)

    private val managed = ManagedDatabase(settings.state.databaseFolder)

    val databaseFolder: Path
        get() = managed.databaseFolder

    val problemRepository: ProblemRepository
        get() = managed.problemRepository

    /** 端点用题目存取端口(WP20):ProblemRepository + 按 remoteId 精确查;随切库指向新句柄。 */
    val problemStore: ProblemStore
        get() = managed.problemStore

    /** 跨题全文检索端口(WP30):三源聚合 + 标签/平台过滤;随切库指向新句柄。 */
    val searchStore: SearchStore
        get() = managed.searchStore

    /** 题目刷题状态聚合端口(WP37):题目 → AC/TRIED/UNSOLVED;随切库指向新句柄。 */
    val problemStatusStore: ProblemStatusStore
        get() = managed.problemStatusStore

    val submissionRepository: ExposedSubmissionRepository
        get() = managed.submissionRepository

    val challengeRepository: ChallengeRepository
        get() = managed.challengeRepository

    val solutionRepository: SolutionRepository
        get() = managed.solutionRepository

    val testCaseRepository: TestCaseRepository
        get() = managed.testCaseRepository

    val tagRepository: TagRepository
        get() = managed.tagRepository

    val contestReminderRepository: ContestReminderRepository
        get() = managed.contestReminderRepository

    init {
        project.messageBus
            .connect(this)
            .subscribe(
                CodeQuestSettings.DATABASE_FOLDER_TOPIC,
                CodeQuestSettings.DatabaseFolderListener { folder -> managed.switchTo(folder.toString()) },
            )
    }

    override fun dispose() {
        managed.close()
    }
}

/**
 * 项目启动后活动:主动实例化 [DatabaseManager](projectService 懒加载,
 * touch 一次以保证消息总线订阅在项目打开时即生效)。
 */
class DatabaseManagerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(DatabaseManager::class.java)
    }
}
