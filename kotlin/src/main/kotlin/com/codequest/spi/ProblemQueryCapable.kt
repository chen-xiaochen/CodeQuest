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
 * 平台侧标签(1.6.0):name 为标签名原文(本地 tag 表关联键),slug/中文名为可选
 * 富信息(LC 列表接口携带;平台无此概念时仅 name)。
 */
@Serializable
data class TagInfo(
    val name: String,
    val slug: String? = null,
    val nameCn: String? = null,
)

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
    /** 1.6.0:副标题(标题另一语言形态);无双语概念的平台为 null。 */
    val titleSecondary: String? = null,
    val difficulty: Difficulty,
    val url: String,
    /** 平台侧标签;与本地 tag 表的关联由用例层建立。 */
    val tags: List<TagInfo> = emptyList(),
    /**
     * 会员/付费题标记(WP42 起透传,列表状态列 🔒 图标数据源;WP37 KDoc 预留的
     * 跨平台语义——LC paidOnly / HR premium)。平台无此概念时恒 false(缺省)。
     */
    val paidOnly: Boolean = false,
    /**
     * 平台侧已解状态(1.2.1 修复反馈):登录用户站内做题状态透传——LC 列表 GraphQL
     * `status` 字段,仅 "ac"/"AC" → true(站内已解)。**实测 CN 登录态下列表对未做
     * 与提交未过不可区分(均下发非 ac 值),故其余形态一律 null**(不参与合并),
     * 「已尝试」仅由本地提交记录产生。CF/AtCoder/洛谷/HR 列表 API 无此数据,恒
     * null(缺省)。与本地提交聚合在端点层取最高(AC > TRIED > UNSOLVED)。
     */
    val solvedOnPlatform: Boolean? = null,
)

/**
 * 平台侧题面原文(WP17;1.2.4 起附各语言起始代码)。
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
    /**
     * 平台侧题目数字主档 id(WP55:云端测试 interpret 请求体需要;LC 为
     * questionData.questionId)。平台无此概念(CF/AtCoder 等以 remoteId 寻址)为
     * null,消费方按「平台不支持」处理。
     */
    val questionId: String? = null,
    /**
     * 各语言起始代码(1.2.4):LC `codeSnippets`(langSlug 与 [com.codequest.core.workspace.WorkspaceLanguage]
     * 的 id 同名约定:java/python3/cpp/kotlin/go,直接按 langSlug 匹配)。挑战创建时注入
     * 模版 `${challenge.code}`;平台不提供(CF/AtCoder stdin 风格、洛谷/HR 无对应字段)为空,
     * 调用方回退语言默认桩。
     */
    val codeSnippets: List<CodeSnippet> = emptyList(),
    /**
     * 题面示例用例输入(1.3.2):LC `exampleTestcaseList`——平台已按用例切好,每项为
     * 完整输入(多行用例内含换行)。开刷创建挑战时自动写入测试用例(source=PLATFORM,
     * 仅新建时,不覆盖既有);平台不提供为空。
     */
    val exampleTestcases: List<String> = emptyList(),
)

/** 平台起始代码片段(lang=展示名,langSlug=语言标识,code=原文)。 */
@Serializable
data class CodeSnippet(
    val lang: String,
    val langSlug: String,
    val code: String,
)
