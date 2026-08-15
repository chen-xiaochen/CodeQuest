package com.codequest.platform.leetcode

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.OJException
import com.codequest.spi.ProblemFilter
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.ProblemStatement
import com.codequest.spi.ProblemSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.min

/**
 * LeetCode 题目查询(WP19):ProblemQueryCapable 实现。
 *
 * - 列表:内部消化 skip/limit 分页游走(单次上限 [LeetCodeApi.MAX_PAGE_SIZE]),
 *   按 [ProblemFilter.limit]/[offset] 语义输出;关键字/难度/标签映射为站点各自的
 *   过滤变量(CN filters / COM filtersV2,见 [LeetCodeApi]);
 * - 详情:`questionData` → content(HTML;CN 站点 content 缺失时回退 translatedContent)。
 */
class LeetCodeProblemService(
    private val variant: LeetCodeVariant,
    private val api: LeetCodeApi,
) : ProblemQueryCapable {
    override fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary> =
        flow {
            var remaining = filter.limit
            var skip = filter.offset
            while (remaining > 0) {
                val pageSize = min(remaining, LeetCodeApi.MAX_PAGE_SIZE)
                val page = api.fetchQuestionPage(filter, skip, pageSize)
                page.questions.forEach { emit(it.toSummary()) }
                if (page.questions.isEmpty()) return@flow
                remaining -= page.questions.size
                skip += page.questions.size
                if (skip >= page.total) return@flow
            }
        }

    override suspend fun fetchProblemDetail(remoteId: String): ProblemStatement {
        val question = api.fetchQuestionDetail(remoteId)
        val content =
            question.content ?: question.translatedContent
                ?: throw OJException(
                    "LeetCode 题面不可获取(疑似会员题或站点返回空): ${variant.domain}/problems/$remoteId/",
                )
        // LeetCode 题面为 HTML(content / translatedContent 均是),与旧实现渲染路径一致
        return ProblemStatement(remoteId = remoteId, content = content, contentFormat = ContentFormat.HTML)
    }

    private fun LeetCodeApi.LeetCodeQuestionListItem.toSummary() =
        ProblemSummary(
            remoteId = titleSlug,
            title = title,
            difficulty = Difficulty(difficulty),
            url = "https://${variant.domain}/problems/$titleSlug/",
            tags = topicTags,
        )
}
