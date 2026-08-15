package com.codequest.platform.leetcode

import com.codequest.spi.SolutionArticleRef
import com.codequest.spi.SolutionViewCapable

/**
 * LeetCode 题解浏览(WP19):SolutionViewCapable 最小实现——官方题解列表引用。
 *
 * CN/COM 站点使用不同查询(见 [LeetCodeApi.fetchSolutionArticles]),正文拉取
 * (fetchSolutionArticle)与文章页渲染留到 M5(SPI 接口位已预留)。
 */
class LeetCodeSolutions(
    private val variant: LeetCodeVariant,
    private val api: LeetCodeApi,
) : SolutionViewCapable {
    override suspend fun fetchSolutions(remoteId: String): List<SolutionArticleRef> =
        api.fetchSolutionArticles(remoteId).map { article ->
            SolutionArticleRef(
                id = article.slug,
                title = article.title,
                // 浏览器兜底出口:LeetCode 文章页 /articles/{slug}/(站点自动重定向到最新路径)
                url = "https://${variant.domain}/articles/${article.slug}/",
            )
        }
}
