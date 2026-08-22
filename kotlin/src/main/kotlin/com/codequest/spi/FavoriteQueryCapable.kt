package com.codequest.spi

import kotlinx.serialization.Serializable

/**
 * 题单查询能力(WP56):按收藏夹/学习计划 slug 浏览题库(LeetCode 系
 * `favoriteQuestionList` 家族)。
 *
 * ## SPI 取舍说明
 *
 * 与 [CompanyQueryCapable] 同款能力发现模式:公司题单(WP42)与学习计划/收藏夹
 * 在平台侧共用同一查询,故底层单页查询泛化为 [queryFavoriteProblems]
 * (公司实现委托之,线协议不变);「我的题单」为独立数据源([fetchMyFavorites],
 * 登录态收藏夹清单,接口探测实现、失败降级空表);[studyPlans] 为平台侧内置
 * 学习计划常量(无平台请求)。平台没有该能力就不实现本接口(端点按 404 如实回)。
 */
interface FavoriteQueryCapable {
    /** 内置学习计划清单(静态常量;slug 即官方 /studyplan/{slug}/ 路径段)。 */
    fun studyPlans(): List<FavoriteList>

    /**
     * 我的收藏题单(登录态):实现侧接口探测(优先 GraphQL、备选 REST),
     * 两路都失败降级为空表——不抛错,「我的题单」组隐藏、不阻塞学习计划组。
     */
    suspend fun fetchMyFavorites(): List<FavoriteList>

    /**
     * 题单分页查询:[favoriteSlug] 为收藏夹/学习计划 slug(公司题单同族输入);
     * [offset]/[limit] 为平台侧分页(skip/limit 语义,与
     * [CompanyQueryCapable.queryCompanyProblems] 一致);[filters] 仅消费
     * difficulty/tags(keyword 与公司模式同规则被忽略)。
     */
    suspend fun queryFavoriteProblems(
        favoriteSlug: String,
        filters: ProblemFilter,
        offset: Int,
        limit: Int,
    ): FavoriteProblemPage
}

/** 题单条目(学习计划组无题数概念,questionCount 缺省 0;「我的题单」组带题数)。 */
@Serializable
data class FavoriteList(
    val slug: String,
    val name: String,
    val questionCount: Int = 0,
)

/** 题单分页结果(total 为平台侧总数)。 */
@Serializable
data class FavoriteProblemPage(
    val total: Int,
    val questions: List<ProblemSummary>,
)
