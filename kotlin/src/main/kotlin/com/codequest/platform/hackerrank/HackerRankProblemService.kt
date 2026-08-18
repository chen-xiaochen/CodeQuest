package com.codequest.platform.hackerrank

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
 * HackerRank 题目查询(WP27):ProblemQueryCapable 实现。
 *
 * ## 维度映射(SPI ProblemFilter ↔ 旧查询维度)
 *
 * 旧实现有两条查询链路,按 keyword 分流(SPI 允许实现自行选择支持的条件):
 * - **无 keyword**(旧 QueryParametersPresenter):REST 题库分页——但旧链路要求
 *   用户先选领域(track),SPI 的 [ProblemFilter] 无领域维度,固定 [defaultDomainSlug]
 *   (构造参数,默认 "algorithms" 主域;领域全量树经 [HackerRankApi.fetchDomains]
 *   可查,留给未来 UI)。difficulty → `filters[difficulty][]`(旧 UI 只提供
 *   easy/medium/hard);tags → `filters[subdomains][]`(tracker slug,即领域
 *   子域 slug);offset/limit 原生映射 REST 同名参数(旧实现 offset/limit 直传);
 *   旧 status/skills 过滤需登录态且 SPI 无对应维度,不接入;
 * - **有 keyword**(旧 KeywordQueryPresenter):master + projecteuler 两竞赛
 *   appsearch 并查 → 每命中项再拉详情补难度/contest(旧实现逐项 getChallengeDetail,
 *   单项失败跳过)→ 内存应用 offset/limit 窗口;此链路 difficulty/tags 忽略
 *   (旧实现同样未过滤)。
 *
 * ## 详情 contentFormat
 *
 * 题面 = 详情 `body_html` 原文(HTML), [ContentFormat.HTML] 交前端渲染管线
 * (与 LeetCode/AtCoder 同通道);body_html 缺失/为空视为页面结构异常抛 [OJException]。
 */
class HackerRankProblemService(
    private val api: HackerRankApi,
    private val defaultDomainSlug: String = DEFAULT_DOMAIN_SLUG,
) : ProblemQueryCapable {
    override fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary> =
        flow {
            if (filter.keyword.isNullOrEmpty()) {
                val page =
                    api.searchChallenges(
                        offset = filter.offset,
                        limit = filter.limit,
                        contestSlug = HACKERRANK_MASTER_CONTEST,
                        domainSlug = defaultDomainSlug,
                        filters =
                            HackerRankApi.ChallengeFilters(
                                difficulties = difficultyFilter(filter.difficulty),
                                subdomains = filter.tags,
                            ),
                    )
                page.items.forEach { item -> emit(item.toSummary()) }
            } else {
                val hits =
                    api.searchChallengesWithKeyword(HACKERRANK_MASTER_CONTEST, filter.keyword) +
                        api.searchChallengesWithKeyword(HACKERRANK_PROJECT_EULER_CONTEST, filter.keyword)
                hits
                    .drop(filter.offset)
                    .take(filter.limit)
                    .forEach { hit ->
                        // 旧 KeywordQueryPresenter:逐项拉详情补难度/contest,单项失败跳过
                        val detail =
                            runCatching {
                                api.fetchChallengeDetail(hit.challengeSlug, hit.contestSlug)
                            }.getOrNull()
                        emit(
                            if (detail != null && detail.difficultyName.isNotEmpty()) {
                                detail.toSummary()
                            } else {
                                hit.toSummary()
                            },
                        )
                    }
            }
        }

    override suspend fun fetchProblemDetail(remoteId: String): ProblemStatement {
        val (contestSlug, challengeSlug) = parseChallengeRemoteId(remoteId)
        val detail = api.fetchChallengeDetail(challengeSlug, contestSlug)
        val body = detail.bodyHtml
        if (body.isNullOrEmpty()) {
            throw OJException("HackerRank 题面为空(body_html 缺失,可能页面结构变化): $remoteId")
        }
        return ProblemStatement(remoteId = remoteId, content = body, contentFormat = ContentFormat.HTML)
    }

    /** difficulty 值 → 旧 ChallengeDifficulty.value(小写词);非已知词原样小写透传。 */
    internal fun difficultyFilter(difficulty: Difficulty?): List<String> {
        if (difficulty == null) return emptyList()
        val value = difficulty.value.trim()
        if (value.isEmpty()) return emptyList()
        return listOf(value.lowercase())
    }

    private fun HackerRankApi.HackerRankChallengeDetail.toSummary() =
        ProblemSummary(
            remoteId = hackerrankChallengeRemoteId(contestSlug, slug),
            title = name,
            difficulty = Difficulty(difficultyName.ifEmpty { UNKNOWN_DIFFICULTY }),
            url = hackerrankChallengeProblemUrl(contestSlug, slug),
            tags = emptyList(),
        )

    private fun HackerRankApi.HackerRankKeywordItem.toSummary() =
        ProblemSummary(
            remoteId = hackerrankChallengeRemoteId(contestSlug, challengeSlug),
            title = challengeName,
            difficulty = Difficulty(UNKNOWN_DIFFICULTY),
            url = hackerrankChallengeProblemUrl(contestSlug, challengeSlug),
            tags = emptyList(),
        )

    companion object {
        /**
         * 无 keyword 链路的默认领域(旧 UI 初始选中排序后首个分类,数据依赖;
         * SPI 无领域维度,固定主域 algorithms—— HackerRank 公开题库主流量入口)。
         */
        const val DEFAULT_DOMAIN_SLUG: String = "algorithms"

        /** appsearch 条目/列表项无难度数据时的难度原文。 */
        const val UNKNOWN_DIFFICULTY: String = "unknown"
    }
}
