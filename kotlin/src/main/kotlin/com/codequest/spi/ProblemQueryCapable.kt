package com.codequest.spi

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * 题目查询能力(WP17):平台适配器按需实现。
 *
 * 与 spec §2 签名的两处适配(基于 core 实际类型语义):
 * - 题目标识用平台侧 remoteId(String)而非 core 的 ProblemId——ProblemId 是本地题目主档
 *   (数据库)id,平台适配器只认平台侧标识,二者由用例层在落库时建立映射;
 * - 题面返回 spi 的 [ProblemStatement] 而非 core 的 ProblemDetail——后者是本地
 *   problem_content 缓存记录(以本地 ProblemId 为主键),适配器无法也不应构造本地 id。
 *
 * 线程/协程约定见 [OJPlatform];实现只抛 core 领域异常体系。
 */
interface ProblemQueryCapable {
    /**
     * 按过滤条件流式拉取题目列表。冷流:每次收集独立请求;平台不支持的条件(如 LC 无标签
     * 过滤)由实现自行忽略或在 KDoc 声明;分页游走由实现内部消化,按 [ProblemFilter.limit]/[offset] 语义输出。
     */
    fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary>

    /** 拉取单题题面原文。题目不存在时抛 OJException 子类(由实现约定具体类型)。 */
    suspend fun fetchProblemDetail(remoteId: String): ProblemStatement
}

/** 题目列表过滤条件(WP17 最小集:关键字/难度/标签/分页)。 */
@Serializable
data class ProblemFilter(
    /** 标题/标识模糊匹配关键字;null 表示不过滤。 */
    val keyword: String? = null,
    /** 难度过滤(保留平台原始字符串语义,见 core Difficulty KDoc);null 表示不过滤。 */
    val difficulty: Difficulty? = null,
    /** 标签名过滤(平台侧标签原文);空列表表示不过滤。 */
    val tags: List<String> = emptyList(),
    val limit: Int = DEFAULT_LIMIT,
    val offset: Int = 0,
) {
    companion object {
        const val DEFAULT_LIMIT: Int = 50
    }
}

/**
 * 平台侧题目摘要(列表项,WP17)。
 *
 * 仅含列表展示与落库建立主档所需字段。
 */
@Serializable
data class ProblemSummary(
    /** 平台侧题目标识,如 CF "1927A"、LC 标题 slug。 */
    val remoteId: String,
    val title: String,
    val difficulty: Difficulty,
    val url: String,
    /** 平台侧标签名原文;与本地 tag 表的关联由用例层建立。 */
    val tags: List<String> = emptyList(),
    /**
     * 会员/付费题标记(WP42 起透传,列表状态列 🔒 图标数据源;WP37 KDoc 预留的
     * 跨平台语义——LC paidOnly / HR premium)。平台无此概念时恒 false(缺省)。
     */
    val paidOnly: Boolean = false,
)

/**
 * 平台侧题面原文(WP17)。
 *
 * 与 core 的 ProblemDetail(本地缓存记录)区分:本类型以 remoteId 为键、只有内容;
 * 用例层负责补充本地 ProblemId 后转 ProblemDetail 落库。
 */
@Serializable
data class ProblemStatement(
    val remoteId: String,
    /** 题面原文;内容形态由 [contentFormat] 决定(Markdown 或 HTML)。 */
    val content: String,
    val contentFormat: ContentFormat,
)
