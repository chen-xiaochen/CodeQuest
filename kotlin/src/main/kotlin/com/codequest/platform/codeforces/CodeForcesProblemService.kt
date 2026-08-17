package com.codequest.platform.codeforces

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.OJException
import com.codequest.spi.ProblemFilter
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.ProblemStatement
import com.codequest.spi.ProblemSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * CodeForces 题目查询(WP24):ProblemQueryCapable 实现。
 *
 * - **列表**:`problemset.problems` 无服务端过滤/分页参数(旧实现事实,一次全量
 *   ~万条),故冷流内一次拉取(含 acmsguru 合并)后**内存过滤**:
 *   - keyword → 题名忽略大小写包含;
 *   - difficulty → 精确 rating 数字串;或旧五档词(easy/medium/hard/advanced/
 *     expert)按旧 `codeForcesDifficultyToRatingRange` 区间映射;
 *   - tags → 任一标签命中(旧 UI「combine tags by or」语义);
 *   - offset/limit 窗口在过滤后应用。
 * - **详情**:remoteId("1234A")拆 contestId+index → 题面页 HTML,提取整个
 *   `div.problem-statement` 作题面(含题干/IO 说明/样例/Note,交前端 HTML 渲染
 *   管线;旧实现拆分题干与样例建 challenge,新管线整块给 [ProblemStatement])。
 */
class CodeForcesProblemService(
    private val api: CodeForcesApi,
) : ProblemQueryCapable {
    override fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary> =
        flow {
            api
                .fetchProblemset()
                .asSequence()
                .filter { problem -> problem.matchesKeyword(filter.keyword) }
                .filter { problem -> problem.matchesDifficulty(filter.difficulty) }
                .filter { problem -> problem.matchesTags(filter.tags) }
                .drop(filter.offset)
                .take(filter.limit)
                .forEach { problem -> emit(problem.toSummary()) }
        }

    override suspend fun fetchProblemDetail(remoteId: String): ProblemStatement {
        val (contestId, index) = parseProblemRemoteId(remoteId)
        val html = api.fetchProblemPageHtml(contestId, index)
        val statement =
            CodeForcesHtml.extractDivElementByClass(html, STATEMENT_CLASS)
                ?: throw OJException(
                    "CodeForces 题面不可获取(页面无 problem-statement,可能已下线或被反爬拦截): $remoteId",
                )
        return ProblemStatement(remoteId = remoteId, content = statement, contentFormat = ContentFormat.HTML)
    }

    private fun CodeForcesApi.CfProblem.matchesKeyword(keyword: String?): Boolean {
        if (keyword.isNullOrEmpty()) return true
        return name.contains(keyword, ignoreCase = true) || remoteId().startsWith(keyword, ignoreCase = true)
    }

    private fun CodeForcesApi.CfProblem.matchesDifficulty(difficulty: Difficulty?): Boolean {
        if (difficulty == null) return true
        val wanted = difficulty.value.trim()
        wanted.toIntOrNull()?.let { return rating == it }
        // 旧五档词 → rating 区间(旧 codeForcesDifficultyToRatingRange 事实;无 rating 不命中)
        val band = ratingBandOf(wanted) ?: return true
        val problemRating = rating ?: return false
        return problemRating >= band.first && problemRating <= band.second
    }

    private fun CodeForcesApi.CfProblem.matchesTags(tags: List<String>): Boolean {
        if (tags.isEmpty()) return true
        return this.tags.any { it in tags }
    }

    private fun CodeForcesApi.CfProblem.toSummary() =
        ProblemSummary(
            remoteId = remoteId(),
            title = name,
            difficulty = rating?.let { Difficulty(it.toString()) } ?: Difficulty(UNRATED),
            url = codeForcesProblemPageUrl(contestId ?: 0L, index),
            tags = tags,
        )

    companion object {
        private const val STATEMENT_CLASS = "problem-statement"

        /** 无 rating 题目的难度原文(展示侧按需映射文案)。 */
        const val UNRATED: String = "unrated"

        // 旧五档词的 rating 区间边界(旧 codeForcesDifficultyToRatingRange 事实,区间右闭)
        private const val RATING_MEDIUM_MIN = 1_200
        private const val RATING_HARD_MIN = 1_600
        private const val RATING_ADVANCED_MIN = 1_900
        private const val RATING_EXPERT_MIN = 2_200

        /** 旧五档词 → rating 区间;非五档词返回 null(不过滤)。 */
        fun ratingBandOf(word: String): Pair<Int, Int>? =
            when (word.lowercase()) {
                "easy" -> 0 to RATING_MEDIUM_MIN - 1
                "medium" -> RATING_MEDIUM_MIN to RATING_HARD_MIN - 1
                "hard" -> RATING_HARD_MIN to RATING_ADVANCED_MIN - 1
                "advanced" -> RATING_ADVANCED_MIN to RATING_EXPERT_MIN - 1
                "expert" -> RATING_EXPERT_MIN to Int.MAX_VALUE
                else -> null
            }
    }
}

/**
 * 解析 CF 题目标识:`"1234A"` / `"1926B2"` → (contestId, index);
 * 非法形态抛 [OJException](index 约定:字母 + 至多两位字母数字,CF 实际取值)。
 */
internal fun parseProblemRemoteId(remoteId: String): Pair<Long, String> {
    val match = Regex("""^(\d+)([A-Za-z][A-Za-z0-9]{0,2})$""").find(remoteId)
    if (match == null) {
        throw OJException("非法 CodeForces 题目标识: '$remoteId'(约定形如 1234A)")
    }
    return match.groupValues[1].toLong() to match.groupValues[2]
}
