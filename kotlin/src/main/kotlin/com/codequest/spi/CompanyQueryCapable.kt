package com.codequest.spi

import com.codequest.core.model.Difficulty
import kotlinx.serialization.Serializable

/**
 * 公司题单查询能力(WP42):按公司浏览题库(如 LeetCode 公司标签题单)。
 *
 * ## SPI 取舍说明
 *
 * 公司题单是 LeetCode 系平台专属能力,且查询形态(公司 slug 集合 + 面试期间
 * + 平台内分页 total)与 [ProblemQueryCapable.queryProblems] 的通用过滤流
 * (Flow 游走、无 total 契约)不同构,故**独立能力接口**而非:
 * - 往 [ProblemFilter] 加 company 字段——单平台语义污染通用契约(spec 总原则);
 * - 端点直接调平台服务——绕过 SPI 分层,其他平台无法以能力声明参与。
 *
 * 平台没有该能力就不实现本接口(端点按 404 如实回),与
 * [SolutionViewCapable] / [SubmissionDetailCapable] 同款能力发现模式。
 */
interface CompanyQueryCapable {
    /** 公司标签清单(公司下拉数据源;按平台侧原始顺序)。 */
    suspend fun fetchCompanies(): List<CompanyTag>

    /**
     * 公司题单分页查询。[CompanyProblemQuery.offset]/[limit] 为平台侧分页
     * (skip/limit 语义);返回页含 total 供分页游标判断。
     * 公司集合为空时实现应直接返回空页(不发平台请求)。
     */
    suspend fun queryCompanyProblems(query: CompanyProblemQuery): CompanyProblemPage
}

/** 公司标签(旧实现 LeetCodeQuestionCompanyTag:name/slug/id/questionCount;id 无消费方,不透出)。 */
@Serializable
data class CompanyTag(
    val slug: String,
    val name: String,
    val questionCount: Int,
)

/**
 * 公司题单查询条件(WP42)。
 *
 * [companySlugs] 语义为旧实现事实:首项进 `favoriteSlug`(可拼期间后缀),
 * 其余项进 filtersV2.companyFilter(多公司交集);单选时即纯 favoriteSlug 查询。
 * [interviewPeriodSlug] 为期间 slug(thirty-days/three-months/six-months/
 * more-than-six-months/all);null = 不拼后缀(裸 favorite,旧实现收藏夹链路形态)。
 */
@Serializable
data class CompanyProblemQuery(
    val companySlugs: List<String>,
    val interviewPeriodSlug: String? = null,
    val difficulty: Difficulty? = null,
    val tags: List<String> = emptyList(),
    val offset: Int = 0,
    val limit: Int = 50,
)

/** 公司题单分页结果(total 为平台侧总数)。 */
@Serializable
data class CompanyProblemPage(
    val total: Int,
    val questions: List<ProblemSummary>,
)
