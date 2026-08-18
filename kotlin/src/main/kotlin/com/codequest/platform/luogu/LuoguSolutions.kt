package com.codequest.platform.luogu

import com.codequest.spi.SolutionArticleRef
import com.codequest.spi.SolutionViewCapable

/**
 * 洛谷题解浏览(WP26):SolutionViewCapable 最小实现——题解引用列表。
 *
 * 数据来自 `/problem/solution/{pid}?_contentOnly=1` 的 lentille
 * `data.solutions.result`(需登录;未登录经 transport 抛 AuthExpiredException,
 * 由 UI 引导登录)。正文拉取(fetchSolutionArticle)与文章页渲染留到 M5
 * (SPI 接口位已预留);每条引用携带 solution 页锚点 URL 作浏览器兜底出口。
 */
class LuoguSolutions(
    private val api: LuoguApi,
) : SolutionViewCapable {
    override suspend fun fetchSolutions(remoteId: String): List<SolutionArticleRef> =
        api.fetchSolutionList(remoteId).map { item ->
            SolutionArticleRef(
                id = item.id,
                title = item.title,
                url = luoguSolutionArticleUrl(remoteId, item.id),
            )
        }
}
