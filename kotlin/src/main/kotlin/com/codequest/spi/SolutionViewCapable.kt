package com.codequest.spi

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.OJException
import kotlinx.serialization.Serializable

/**
 * 题解浏览能力(WP17):平台适配器按需实现(LeetCode/力扣CN/洛谷)。
 *
 * 列表(WP26)与正文拉取(WP31)两级:
 * - [fetchSolutions]:题解引用列表;
 * - [fetchSolutionContent]:单篇正文(默认抛异常,见方法 KDoc 的取舍说明)。
 *
 * ## WP31 签名取舍:两个定位参数而非 SolutionArticleRef
 *
 * 调用方(REST 端点 `/api/problem/{remoteId}/solutions/{sid}`)只持有
 * remoteId + sid,没有完整 [SolutionArticleRef](title/url 在列表响应里);
 * 且洛谷正文寻址需要 pid(= remoteId), [SolutionArticleRef] 不携带。
 * 故签名取 `(remoteId, solutionId)`,与端点路由 1:1 映射,避免端点侧
 * 为拼 ref 而二次拉取列表。
 *
 * 线程/协程约定见 [OJPlatform];实现只抛 core 领域异常体系。
 */
interface SolutionViewCapable {
    /**
     * 拉取指定题目的题解引用列表(remoteId 与 [ProblemSummary.remoteId] 同源)。
     *
     * [orderBy] 为可选排序键(WP41;不透明字符串,语义由平台自定义——如
     * LeetCode 的 hot/newest/oldest/mostUpvote),**无排序能力的平台如实
     * 忽略**。取舍:不在 SPI 定义平台专属排序枚举(避免把单一平台的站点
     * 协议值泄漏进通用契约),端点层也不校验取值(纯透传);平台按已知值
     * 映射,未知值回退各自默认(见 LeetCodeApi.solutionOrderByGraphQLValue)。
     *
     * 默认参数保住**调用方**零改动(单参调用继续成立);实现方须补第二参
     * 并忽略之(Kotlin override 须声明完整签名,不能省略带默认值的尾参)
     * ——这是本参数的最小破坏面,新实现仅两处(LeetCode/洛谷)。
     */
    suspend fun fetchSolutions(
        remoteId: String,
        orderBy: String? = null,
    ): List<SolutionArticleRef>

    /**
     * 拉取单篇题解正文([solutionId] 即 [SolutionArticleRef.id])。
     *
     * **默认抛 [OJException] 而非声明抽象**:题解正文是列表之后的增强能力,
     * 强求抽象会迫使所有 SolutionViewCapable 平台同步实现(违反能力接口
     * 「按需组合」的精神,也牵连既有实现与测试桩)。默认实现让未接入正文
     * 的平台保持可编译可用;调用方(REST 端点)经标准错误映射得到 502 +
     * 明确文案,而非链接期破坏。实现只抛 core 领域异常体系——故默认抛
     * OJException 而非 UnsupportedOperationException。
     */
    suspend fun fetchSolutionContent(
        remoteId: String,
        solutionId: String,
    ): SolutionArticleContent = throw OJException("该平台未实现题解正文拉取: solution '$solutionId' of '$remoteId'")
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

/** 题解文章正文(WP31):标题 + 原文 + 渲染格式,交前端统一管线处理。 */
@Serializable
data class SolutionArticleContent(
    /** 平台侧题解文章标识(回显定位用,与 [SolutionArticleRef.id] 同源)。 */
    val id: String,
    val title: String,
    /** 正文原文(Markdown 或 HTML,由 [contentFormat] 决定渲染管线)。 */
    val content: String,
    val contentFormat: ContentFormat,
)
