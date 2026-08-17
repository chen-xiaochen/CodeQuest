package com.codequest.platform.codeforces

import com.codequest.core.model.ContestPhase
import com.codequest.core.model.OJException
import com.codequest.spi.ContestCapable
import com.codequest.spi.ContestDetail
import com.codequest.spi.ContestProblemRef
import com.codequest.spi.ContestSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant

/**
 * CodeForces 竞赛能力(WP24):ContestCapable 实现。
 *
 * 数据全部来自公开 `contest.list` / `contest.standings`(无需鉴权,旧实现事实):
 * - [contests]:非 FINISHED 全量 + FINISHED 近 [RECENT_FINISHED_SECONDS] 内的
 *   「近期竞赛」(SPI 契约语义;contest.list 含全部历史赛,不过滤会发射千级条目);
 * - [contestDetail]:题目引用取自 `contest.standings` 的 problems 数组;
 *   **未开始的比赛题目不公开**(standings 报 FAILED),此时 problems 为空;
 *   joinable:BEFORE(可注册)恒 true,CODING 看登录态,其余 false;
 * - [contestStandings]:排名**辅助查询**(不进 SPI——WP22 结论:匿名端点只支持裸
 *   contestId、无 handles 过滤、全量行数大;UI 展示留待真需求出现再评估)。
 *
 * 「参加」= 外链(WP23 已定):BEFORE 阶段 summary.url 指向注册页。
 */
class CodeForcesContestService(
    private val api: CodeForcesApi,
    private val isLoggedIn: () -> Boolean,
    private val clock: () -> Instant = Instant::now,
) : ContestCapable {
    override fun contests(): Flow<ContestSummary> =
        flow {
            val now = clock().epochSecond
            api.contestList().forEach { contest ->
                val finishedRecently =
                    contest.phase == FINISHED_PHASE && now - contest.startTimeSeconds <= RECENT_FINISHED_SECONDS
                if (contest.phase != FINISHED_PHASE || finishedRecently) {
                    emit(contest.toSummary())
                }
            }
        }

    override suspend fun contestDetail(remoteId: String): ContestDetail {
        val contestId =
            remoteId.toLongOrNull()
                ?: throw OJException("非法 CodeForces 竞赛标识: '$remoteId'(约定为数字 contestId)")
        val contest =
            api.contestList().firstOrNull { it.id == contestId }
                ?: throw OJException("CodeForces 竞赛不存在: $remoteId")
        val summary = contest.toSummary()
        val problems =
            if (summary.phase == ContestPhase.BEFORE) {
                emptyList() // 题目未公开(旧实现事实:standings 对未开始比赛报错)
            } else {
                // standings 不可用(gym/权限/结构变化)时题目列表为空,不致败详情
                runCatching { api.contestStandings(contestId).problems }
                    .getOrDefault(emptyList())
                    .map { problem ->
                        ContestProblemRef(
                            remoteId = "${problem.contestId ?: contestId}${problem.index}",
                            index = problem.index.takeIf { it.isNotEmpty() },
                            title = problem.name,
                        )
                    }
            }
        return ContestDetail(summary = summary, problems = problems, joinable = isJoinable(summary.phase))
    }

    /**
     * 排名辅助查询(平台包内,**不在 SPI**):匿名全量,不支持 handles 过滤,
     * 返回行数可达数千(限制说明见类 KDoc;消费方自行分页/截断)。
     */
    suspend fun contestStandings(remoteId: String): CodeForcesApi.CfStandings {
        val contestId =
            remoteId.toLongOrNull()
                ?: throw OJException("非法 CodeForces 竞赛标识: '$remoteId'(约定为数字 contestId)")
        return api.contestStandings(contestId)
    }

    private fun isJoinable(phase: ContestPhase): Boolean =
        when (phase) {
            ContestPhase.BEFORE -> true

            // 注册期(外链注册页)
            ContestPhase.CODING -> isLoggedIn()

            // 进行中:登录即可(重定向进赛场)
            else -> false
        }

    private fun CodeForcesApi.CfContest.toSummary() =
        ContestSummary(
            remoteId = id.toString(),
            title = name,
            startTime = Instant.ofEpochSecond(startTimeSeconds),
            durationMinutes = (durationSeconds / SECONDS_PER_MINUTE).toInt(),
            phase = contestPhaseOf(phase),
            // BEFORE → 注册页,其余 → 竞赛页(旧 contestUrl 事实)
            url =
                if (phase == BEFORE_PHASE) {
                    codeForcesContestRegistrationUrl(id)
                } else {
                    codeForcesContestUrl(id)
                },
        )

    companion object {
        private const val BEFORE_PHASE = "BEFORE"
        private const val FINISHED_PHASE = "FINISHED"
        private const val SECONDS_PER_MINUTE = 60L

        /** 「刚结束」窗口:近 7 天(近期竞赛语义,含回看)。 */
        const val RECENT_FINISHED_SECONDS: Long = 7L * 24 * 60 * 60
    }
}

/** contest.list 的 phase 原文 → [ContestPhase](五值同名;未知值 = 协议变化,显式失败)。 */
internal fun contestPhaseOf(raw: String): ContestPhase =
    when (raw) {
        "BEFORE" -> ContestPhase.BEFORE
        "CODING" -> ContestPhase.CODING
        "PENDING_SYSTEM_TEST" -> ContestPhase.PENDING_SYSTEM_TEST
        "SYSTEM_TEST" -> ContestPhase.SYSTEM_TEST
        "FINISHED" -> ContestPhase.FINISHED
        else -> throw OJException("未知 CodeForces 竞赛阶段(协议变化?): '$raw'")
    }
