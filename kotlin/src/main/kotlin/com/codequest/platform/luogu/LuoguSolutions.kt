package com.codequest.platform.luogu

import com.codequest.core.model.ContentFormat
import com.codequest.spi.SolutionArticleContent
import com.codequest.spi.SolutionArticleRef
import com.codequest.spi.SolutionViewCapable

/**
 * 洛谷题解浏览(WP26 列表 / WP31 正文):SolutionViewCapable 实现。
 *
 * 数据来自 `/problem/solution/{pid}?_contentOnly=1` 的 lentille
 * `data.solutions.result`(需登录;未登录经 transport 抛 AuthExpiredException,
 * 由 UI 引导登录)。洛谷无单篇题解端点,正文内嵌于列表项 `content`
 * (Markdown 原文,见 [LuoguApi.fetchSolutionDetail]);每条引用携带
 * solution 页锚点 URL 作浏览器兜底出口。
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

    override suspend fun fetchSolutionContent(
        remoteId: String,
        solutionId: String,
    ): SolutionArticleContent {
        val detail = api.fetchSolutionDetail(remoteId, solutionId)
        return SolutionArticleContent(
            id = detail.id,
            title = detail.title,
            content = detail.content,
            contentFormat = ContentFormat.MARKDOWN,
        )
    }
}
