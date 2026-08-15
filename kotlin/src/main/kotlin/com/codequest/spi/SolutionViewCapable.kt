package com.codequest.spi

import kotlinx.serialization.Serializable

/**
 * 题解浏览能力(WP17):平台适配器按需实现(LeetCode/力扣CN/洛谷)。
 *
 * 本期只返回题解引用列表;正文拉取(fetchSolutionArticle)与统一前端文章页
 * 留到 M5 扩展——接口位已预留在本包(SolutionArticleRef 携带定位所需的 id/url)。
 *
 * 线程/协程约定见 [OJPlatform];实现只抛 core 领域异常体系。
 */
interface SolutionViewCapable {
    /** 拉取指定题目的题解引用列表(remoteId 与 [ProblemSummary.remoteId] 同源)。 */
    suspend fun fetchSolutions(remoteId: String): List<SolutionArticleRef>
}

/** 题解文章引用(WP17 最小集:id 定位 + 标题 + 外链)。 */
@Serializable
data class SolutionArticleRef(
    /** 平台侧题解文章标识,后续正文拉取 API 以此寻址。 */
    val id: String,
    val title: String,
    /** 平台侧文章 URL(可直接在浏览器打开的兜底出口)。 */
    val url: String,
)
