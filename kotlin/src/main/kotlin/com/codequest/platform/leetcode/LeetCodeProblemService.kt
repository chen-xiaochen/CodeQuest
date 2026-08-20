package com.codequest.platform.leetcode

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.OJException
import com.codequest.spi.CompanyProblemPage
import com.codequest.spi.CompanyProblemQuery
import com.codequest.spi.CompanyQueryCapable
import com.codequest.spi.CompanyTag
import com.codequest.spi.ProblemFilter
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.ProblemStatement
import com.codequest.spi.ProblemSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.min

/**
 * LeetCode 题目查询(WP19):ProblemQueryCapable 实现;WP42 起兼实现
 * [CompanyQueryCapable](公司列表 + 公司题单)。
 *
 * - 列表:内部消化 skip/limit 分页游走(单次上限 [LeetCodeApi.MAX_PAGE_SIZE]),
 *   按 [ProblemFilter.limit]/[offset] 语义输出;关键字/难度/标签映射为站点各自的
 *   过滤变量(CN filters / COM filtersV2,见 [LeetCodeApi]);
 * - 详情:`questionData` → content(HTML;**translatedContent 非空优先**——CN 站点 content 为
 *   英文原题、translatedContent 为中文,旧实现事实;COM 站点 translated 为 null 自然回退英文);
 * - 公司题单(WP42):`favoriteQuestionList` 单页查询(offset/limit 语义,total 原样透出,
 *   分页游走交由端点层「加载更多」驱动——与普通列表的内部游走不同构,故不做 Flow 包装)。
 */
class LeetCodeProblemService(
    private val variant: LeetCodeVariant,
    private val api: LeetCodeApi,
) : ProblemQueryCapable,
    CompanyQueryCapable {
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
        // 旧实现事实(LeetCodeOpenChallengeService):translatedContent 非空优先(CN 中文),
        // content 兜底(COM 英文);两者皆空(会员题等)报领域异常
        val content =
            question.translatedContent?.takeIf { it.isNotEmpty() } ?: question.content
                ?: throw OJException(
                    "LeetCode 题面不可获取(疑似会员题或站点返回空): ${variant.domain}/problems/$remoteId/",
                )
        // LeetCode 题面为 HTML(content / translatedContent 均是),与旧实现渲染路径一致
        return ProblemStatement(remoteId = remoteId, content = content, contentFormat = ContentFormat.HTML)
    }

    override suspend fun fetchCompanies(): List<CompanyTag> =
        api.fetchCompanyTags().map { tag ->
            CompanyTag(slug = tag.slug, name = tag.name, questionCount = tag.questionCount)
        }

    override suspend fun queryCompanyProblems(query: CompanyProblemQuery): CompanyProblemPage {
        // keyword 无对应输入(旧实现恒发空串),不透传——不兼容点见 WP42 报告
        val page =
            api.fetchCompanyQuestionPage(
                companySlugs = query.companySlugs,
                interviewPeriodSlug = query.interviewPeriodSlug,
                filter = ProblemFilter(difficulty = query.difficulty, tags = query.tags),
                skip = query.offset,
                limit = query.limit,
            )
        return CompanyProblemPage(total = page.total, questions = page.questions.map { it.toSummary() })
    }

    private fun LeetCodeApi.LeetCodeQuestionListItem.toSummary() =
        ProblemSummary(
            remoteId = titleSlug,
            title = title,
            difficulty = Difficulty(difficulty),
            url = "https://${variant.domain}/problems/$titleSlug/",
            tags = topicTags,
            paidOnly = paidOnly,
        )
}
