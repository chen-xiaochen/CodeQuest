package com.codequest.infra.db

import com.codequest.core.model.Problem

/**
 * 跨题全文检索存取端口(WP30):聚合三源命中(标题 / 题面 / 题解笔记)为题目列表,
 * 支持标签(多标签 **AND**)与平台过滤、分页。
 *
 * 放 infra.db 而非 core(照 [ProblemStore] WP20 先例):core 契约的
 * [com.codequest.core.repository.ProblemRepository.search] 是单平台 + 关键字
 * 检索,不含题解笔记源、标签 AND 组合与命中来源标记;该聚合是搜索端点
 * (WP30 `/api/search`)的组合层需求,由独立实现 [ExposedSearchStore] 承担,
 * 不改既有 core 接口。
 */
interface SearchStore {
    /**
     * 三源聚合检索。
     *
     * - [SearchRequest.keyword] 非空:命中来源 = 标题/remoteId LIKE(title)、
     *   题面 problem_content_fts(content,短词/FTS 异常降级 LIKE)、题解笔记
     *   solution_note_fts(note,经 challenge 映射回题目;降级路径同前);
     *   三源命中并集为结果集,每题带命中来源集合([SearchHit.matchedSources])。
     * - [SearchRequest.keyword] 为空:**浏览模式**——返回满足标签/平台过滤的
     *   近期题目(按 fetched_at 倒序),matchedSources 为空集合。
     * - [SearchRequest.tags] 多标签为 AND 语义:题目须**同时具备**全部选定标签
     *   (tag.name 经 problem_tag 关联匹配)。
     * - [SearchRequest.platform] 为空 = 全部平台;非空为纯列过滤,
     *   未知平台如实返回空(不校验注册表)。
     *
     * @return [SearchPage.total] 为过滤后命中总数(分页前),供 hasMore 推断。
     */
    suspend fun search(request: SearchRequest): SearchPage

    /**
     * 标签清单(名称 + 关联题数),按题数降序、名称升序;仅统计 problem_tag
     * 实际关联。[platform] 非空时只统计该平台的题目。
     */
    suspend fun tags(platform: String? = null): List<TagSummary>
}

/** 检索请求(参数校验归端点,store 信任调用方已给出合法值)。 */
data class SearchRequest(
    /** 检索关键字(已 trim);null = 不过滤关键字(浏览模式)。 */
    val keyword: String? = null,
    /** 标签名列表;空 = 不过滤;多标签 AND 语义。 */
    val tags: List<String> = emptyList(),
    /** 平台 id;null = 全部平台。 */
    val platform: String? = null,
    val limit: Int = DEFAULT_LIMIT,
    val offset: Int = 0,
) {
    private companion object {
        const val DEFAULT_LIMIT = 50
    }
}

/** 检索结果页:total 为过滤后命中总数(分页前)。 */
data class SearchPage(
    val total: Int,
    val items: List<SearchHit>,
)

/** 单条命中:题目主档(含标签)+ 关键字命中来源集合(浏览模式为空集合)。 */
data class SearchHit(
    val problem: Problem,
    val matchedSources: Set<SearchSource>,
)

/** 关键字命中来源:title = 标题/remoteId;content = 题面;note = 题解笔记。 */
enum class SearchSource {
    TITLE,
    CONTENT,
    NOTE,
}

/** 标签清单项:名称 + 关联题目数(slug/中文名 1.6.0 起,库内未 enrich 时为 null)。 */
data class TagSummary(
    val name: String,
    val problemCount: Int,
    val slug: String? = null,
    val nameCn: String? = null,
)
