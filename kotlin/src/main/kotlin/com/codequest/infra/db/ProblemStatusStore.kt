package com.codequest.infra.db

import com.codequest.core.model.ProblemId

/**
 * 题目刷题状态聚合端口(WP37):题目 → 本地提交历史的最高成就,供列表端点
 * (WP37 起 `/api/problems`、`/api/search`)给每项附加 status 列。
 * 纯本地聚合,零平台请求。
 *
 * 放 infra.db 而非 core(照 [SearchStore] WP30 先例):core 既有仓库契约(WP13)
 * 只有按 challenge 的 submission 查询与按 problem 的 challenge 查询,「按 problem
 * 批量聚合 submission 判定」是列表端点的组合层需求;以独立端口最小化补充,
 * 不改既有 core 接口及其实现/测试假件(亦同 WP29 [com.codequest.core.SubmissionQuery]
 * 的「新端口而非改既有接口」取舍)。
 */
interface ProblemStatusStore {
    /**
     * 批量查询题目刷题状态。聚合口径:该题全部 challenge(经 problem_id 关联)的
     * submission 里——任一 isSuccessful(仅 Accepted,PartialScore 不算完全通过)→
     * [SolveStatus.AC];有提交但无 AC(含 Pending/Judging 等非终态)→ [SolveStatus.TRIED];
     * 无任何提交 → **不在返回表**,调用方按 [SolveStatus.UNSOLVED] 回退(避免大结果集
     * 里海量「未做」项的空值传输)。多 challenge 取最高(AC > TRIED)。
     *
     * problem.id 为 `<platform>:<remoteId>` 确定性主键,跨平台天然隔离。
     */
    suspend fun statuses(problemIds: Collection<ProblemId>): Map<ProblemId, SolveStatus>
}

/**
 * 刷题状态(WP37,线协议取枚举名:AC / TRIED / UNSOLVED)。
 *
 * [SolveStatus.UNSOLVED] 不会被 [ProblemStatusStore.statuses] 返回(未做 = 缺席),
 * 专供端点层兜底映射与前端三值契约;排序语义 AC > TRIED > UNSOLVED。
 */
enum class SolveStatus {
    /** 未做:该题无任何本地提交。 */
    UNSOLVED,

    /** 已尝试:有提交但无完全通过。 */
    TRIED,

    /** 已通过:任一关联提交 isSuccessful(仅 Accepted)。 */
    AC,
}
