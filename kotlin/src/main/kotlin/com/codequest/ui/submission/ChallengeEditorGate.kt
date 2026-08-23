package com.codequest.ui.submission

import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeLanguage
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.infra.db.DatabaseManager
import com.codequest.ui.challenge.ChallengeWorkspaceLocations
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

/** 编辑器文件定位到的挑战目标(WP55,即原 SubmissionCoordinator.Target 提为共享)。 */
data class ChallengeTarget(
    val challenge: Challenge,
    val language: ChallengeLanguage,
    val problem: Problem,
)

/**
 * 浮动工具栏 canShow 判定纯函数(WP55):文件是否落在任一挑战工作区根之下。
 *
 * `Path.startsWith` 按路径分量匹配(非字符串前缀),目录名仅前缀相同的兄弟
 * 工作区不会误命中;输入路径未归一化时先归一化再比对。
 */
object ChallengeFileMatcher {
    fun isChallengeFile(
        path: Path,
        workspaceRoots: Collection<Path>,
    ): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        return workspaceRoots.any { normalized.startsWith(it.toAbsolutePath().normalize()) }
    }
}

/**
 * 编辑器挑战上下文门(WP55,项目级服务):「本地库挑战集合 → 工作区根/语言/题目」
 * 的快照缓存,供浮动工具栏 canShow(suspend 判定)与 action.update(BGT 快照查询)
 * 复用,取代逐次查库。
 *
 * - 快照 TTL 失效(见 [TTL_MILLIS]):挑战增删后浮动栏最多滞后数秒出现/消失;
 *   不订阅落库点(创建/删除入口分散在对话框/面板/桥,接线成本高于收益)。
 * - 挑战枚举语义与提交流程一致(六平台聚合 → challengeRepository.findByProblem),
 *   SubmissionCoordinator 的 resolveTarget 已改为委托本服务,单一定位实现。
 * - 快照字段只读不可变,跨线程以 [volatile] 快照引用整体替换,读取无锁。
 */
@Service(Level.PROJECT)
class ChallengeEditorGate(
    private val project: Project,
) {
    private data class Snapshot(
        val entries: List<Pair<Challenge, List<ChallengeLanguage>>>,
        /** challengeId.value → 工作区根(刷新时一次算好,查询零文件系统访问)。 */
        val roots: Map<String, Path>,
        /** problemId.value → 题目主档。 */
        val problems: Map<String, Problem>,
        val expiresAt: Long,
    )

    @Volatile
    private var snapshot: Snapshot = Snapshot(emptyList(), emptyMap(), emptyMap(), 0L)

    private val refreshMutex = Mutex()

    /** 文件是否属于某挑战工作区(按需刷新快照;suspend,供浮动栏 canShow)。 */
    suspend fun isChallengeFile(path: Path): Boolean {
        ensureFresh()
        return ChallengeFileMatcher.isChallengeFile(path, snapshot.roots.values)
    }

    /** 文件 → 挑战目标(语言无法唯一判定时 null,同 [WorkspaceTargetResolver] 语义)。 */
    suspend fun targetFor(path: Path): ChallengeTarget? {
        ensureFresh()
        return resolveWith(snapshot, path)
    }

    /** 非挂起快照查询(action.update 用,无 IO,数据最多滞后一个 TTL)。 */
    fun cachedIsChallengeFile(path: Path): Boolean = ChallengeFileMatcher.isChallengeFile(path, snapshot.roots.values)

    /** 非挂起快照查询;快照未装载/文件不在工作区时 null。 */
    fun cachedTargetFor(path: Path): ChallengeTarget? = resolveWith(snapshot, path)

    private fun resolveWith(
        state: Snapshot,
        path: Path,
    ): ChallengeTarget? {
        val resolved =
            WorkspaceTargetResolver.resolve(path, state.entries) { challenge ->
                state.roots[challenge.id.value]
                    ?: ChallengeWorkspaceLocations.workspaceRoot(project, challenge)
            } ?: return null
        val problem = state.problems[resolved.challenge.problemId.value] ?: return null
        return ChallengeTarget(resolved.challenge, resolved.language, problem)
    }

    private suspend fun ensureFresh() {
        if (System.currentTimeMillis() < snapshot.expiresAt) return
        refreshMutex.withLock {
            if (System.currentTimeMillis() < snapshot.expiresAt) return
            snapshot = loadSnapshot()
        }
    }

    /** 全量枚举本地挑战集合(本地 SQLite,毫秒级;TTL 内最多一次)。 */
    private suspend fun loadSnapshot(): Snapshot {
        val manager = project.getService(DatabaseManager::class.java)
        val problems =
            KNOWN_PLATFORMS.flatMap { platform -> manager.problemRepository.search(PlatformId(platform), "") }
        val challenges = problems.flatMap { problem -> manager.challengeRepository.findByProblem(problem.id) }
        val entries = challenges.map { it to manager.challengeRepository.findLanguages(it.id) }
        val roots =
            challenges.associate { it.id.value to ChallengeWorkspaceLocations.workspaceRoot(project, it) }
        return Snapshot(
            entries = entries,
            roots = roots,
            problems = problems.associateBy { it.id.value },
            expiresAt = System.currentTimeMillis() + TTL_MILLIS,
        )
    }

    companion object {
        /**
         * 快照 TTL。ponytail: 固定 TTL 而非落库点失效——新建挑战后浮动栏最多
         * 滞后 3s 出现;若用户反馈困扰,再在 ChallengeWorkspaceService 落库点
         * 加主动失效。
         */
        internal const val TTL_MILLIS = 3_000L

        /** 已知平台(与 ChallengeWorkspacePanel 一致;仓库无全量 challenge 接口,按平台聚合枚举)。 */
        val KNOWN_PLATFORMS =
            listOf("leetcode", "leetcodecn", "codeforces", "atcoder", "luogu", "hackerrank")
    }
}
