package com.codequest.platform.leetcode

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.OJException
import com.codequest.spi.CompanyProblemPage
import com.codequest.spi.CompanyProblemQuery
import com.codequest.spi.CompanyQueryCapable
import com.codequest.spi.CompanyTag
import com.codequest.spi.FavoriteList
import com.codequest.spi.FavoriteProblemPage
import com.codequest.spi.FavoriteQueryCapable
import com.codequest.spi.ProblemFilter
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.ProblemStatement
import com.codequest.spi.ProblemSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.min

/**
 * LeetCode 题目查询(WP19):ProblemQueryCapable 实现;WP42 起兼实现
 * [CompanyQueryCapable](公司列表 + 公司题单),WP56 起兼实现
 * [FavoriteQueryCapable](学习计划常量 + 我的题单 + 题单分页)。
 *
 * - 列表:内部消化 skip/limit 分页游走(单次上限 [LeetCodeApi.MAX_PAGE_SIZE]),
 *   按 [ProblemFilter.limit]/[offset] 语义输出;关键字/难度/标签映射为站点各自的
 *   过滤变量(CN filters / COM filtersV2,见 [LeetCodeApi]);
 * - 详情:`questionData` → content(HTML;**translatedContent 非空优先**——CN 站点 content 为
 *   英文原题、translatedContent 为中文,旧实现事实;COM 站点 translated 为 null 自然回退英文);
 * - 公司题单(WP42):`favoriteQuestionList` 单页查询(offset/limit 语义,total 原样透出,
 *   分页游走交由端点层「加载更多」驱动——与普通列表的内部游走不同构,故不做 Flow 包装);
 *   WP56 起底层委托泛化的 [LeetCodeApi.fetchFavoriteQuestionPage](线协议不变)。
 */
class LeetCodeProblemService(
    private val variant: LeetCodeVariant,
    private val api: LeetCodeApi,
) : ProblemQueryCapable,
    CompanyQueryCapable,
    FavoriteQueryCapable {
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
        // 1.2.4:附各语言起始代码(codeSnippets),开刷创建工作区时注入 ${challenge.code}
        return ProblemStatement(
            remoteId = remoteId,
            content = content,
            contentFormat = ContentFormat.HTML,
            // WP55:云端测试 interpret 端点按数字主档 id 寻址,详情顺带透出(空串归一为 null)
            questionId = question.questionId.takeIf { it.isNotBlank() },
            codeSnippets = question.codeSnippets,
            exampleTestcases = question.exampleTestcaseList,
        )
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

    override fun studyPlans(): List<FavoriteList> = LeetCodeStudyPlans.forVariant(variant.isCn)

    /**
     * 我的收藏题单(WP56):接口探测实现——优先 GraphQL `favoritesLists`,失败备选
     * REST `GET /api/list_favorites/`,两路都失败降级空表(**不抛错**——「我的题单」
     * 组隐藏、不阻塞学习计划组,见 spec 风险清单;收藏夹清单为附属数据,
     * AuthExpired 等领域异常同样按降级处理)。
     */
    override suspend fun fetchMyFavorites(): List<FavoriteList> =
        try {
            api.fetchFavoriteFolders().map { it.toFavoriteList() }
        } catch (error: Exception) {
            try {
                api.fetchFavoritesViaRest().map { it.toFavoriteList() }
            } catch (fallbackError: Exception) {
                emptyList()
            }
        }

    override suspend fun queryFavoriteProblems(
        favoriteSlug: String,
        filters: ProblemFilter,
        offset: Int,
        limit: Int,
    ): FavoriteProblemPage {
        // 1.6.0:内置学习计划走 studyPlanV2Detail(全量单次返回,内存过滤+切片)——
        // favoriteQuestionList 对学习计划 slug 基本返回空集(匿名双站实测,旧版
        // 「学习计划为空」的根因);该路径仍服务公司题单与我的题单
        if (favoriteSlug in LeetCodeStudyPlans.SLUGS) {
            val page = api.fetchStudyPlanDetail(favoriteSlug)
            val filtered =
                page.questions
                    .filterByDifficulty(filters.difficulty)
                    .filterByTags(filters.tags)
            return FavoriteProblemPage(
                total = filtered.size,
                questions = filtered.drop(offset).take(limit).map { it.toSummary() },
            )
        }
        // keyword 与公司模式同规则忽略(题单面板无关键字入口);difficulty/tags 透传
        val page =
            api.fetchFavoriteQuestionPage(
                favoriteSlug = favoriteSlug,
                extraCompanySlugs = emptyList(),
                filter = ProblemFilter(difficulty = filters.difficulty, tags = filters.tags),
                skip = offset,
                limit = limit,
            )
        return FavoriteProblemPage(total = page.total, questions = page.questions.map { it.toSummary() })
    }

    /** 学习计划内存难度过滤(计划接口全量返回;难度原文大小写不敏感比对)。 */
    private fun List<LeetCodeApi.LeetCodeQuestionListItem>.filterByDifficulty(
        difficulty: Difficulty?,
    ): List<LeetCodeApi.LeetCodeQuestionListItem> =
        if (difficulty == null) {
            this
        } else {
            filter { it.difficulty.equals(difficulty.value, ignoreCase = true) }
        }

    /** 学习计划内存标签过滤(与平台侧 tags=slug 协议一致;slug 缺失回退 name)。 */
    private fun List<LeetCodeApi.LeetCodeQuestionListItem>.filterByTags(tags: List<String>): List<LeetCodeApi.LeetCodeQuestionListItem> =
        if (tags.isEmpty()) {
            this
        } else {
            filter { item -> item.topicTags.any { tag -> tags.any { selected -> selected == (tag.slug ?: tag.name) } } }
        }

    private fun LeetCodeApi.LeetCodeFavorite.toFavoriteList() = FavoriteList(slug = slug, name = name, questionCount = questionCount)

    private fun LeetCodeApi.LeetCodeQuestionListItem.toSummary() =
        ProblemSummary(
            remoteId = titleSlug,
            title = title,
            titleSecondary = titleSecondary,
            difficulty = Difficulty(difficulty),
            url = "https://${variant.domain}/problems/$titleSlug/",
            tags = topicTags,
            paidOnly = paidOnly,
            // 1.2.1(实测修正):CN 登录态下列表 status **无 null/空串**——「未做」与
            // 「提交未过」不可区分(均非 "ac"),全量下发非 ac 值。故仅 "ac"/"AC"→
            // true(站内已解);其余一律 null(未解,不参与合并)。「已尝试」只来自
            // 本地提交记录,避免全库误标 TRIED。对齐原版状态列语义。
            solvedOnPlatform = status?.takeIf { it.equals("ac", ignoreCase = true) }?.let { true },
        )
}
