package com.codequest.infra.messaging

import com.codequest.infra.db.ProblemStatusStore
import com.codequest.infra.db.SearchHit
import com.codequest.infra.db.SearchRequest
import com.codequest.infra.db.SearchSource
import com.codequest.infra.db.SearchStore
import com.codequest.infra.db.SolveStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 跨题全文检索端点逻辑(WP30,infra 层,风格照抄 [StatisticsApi]):本地库三源
 * 聚合检索(标题/remoteId、题面 FTS5、题解笔记 FTS5 经 challenge 映射),数据经
 * [SearchStore] 出仓;协议纯 JSON(kotlinx.serialization)。
 *
 * WP37 起每项附加刷题状态([ProblemStatusStore] 本地聚合,零平台请求)与
 * paidOnly 标记(语义见 [SearchItemDto])。
 *
 * 端点契约(由 [ResourceHttpServer] 挂载):
 * - `GET /api/search?keyword=&tags=a,b&platform=&limit=&offset=`:
 *   - keyword 经 FTS5 命中三源聚合去重为题目列表(排序 fetched_at 倒序),每项带
 *     命中来源标记 matchedSources("title"/"content"/"note" 的数组;keyword 为空
 *     时为空数组——浏览模式无来源概念);
 *   - tags 逗号分隔,多标签 **AND** 语义(题目须同时具备全部选定标签);
 *   - platform 缺省 = 全部平台;纯列过滤,未知平台如实返回空(不 404);
 *   - **keyword 与 tags 均为空 = 浏览模式**:返回满足过滤条件的近期题目
 *     (按 fetched_at 倒序),而非空列表——搜索页兼作本地题库浏览;
 *   - limit 缺省 50(1..100)、offset 缺省 0(>= 0),非法 400 bad-request;
 *   - 响应带 total(过滤后命中总数,分页前)与 hasMore(offset+items < total)。
 * - `GET /api/tags?platform=`:标签清单(名称 + 关联题数,题数降序);platform
 *   过滤同上。FTS 异常降级复用 [com.codequest.infra.db.FtsSupport] 行为
 *   (短路关键字/表缺失 → LIKE 子串),端点层无需额外处理。
 *
 * 数据源按请求解析(仓库随切库指向新句柄,数据库切换即时生效,同 WP29 语义)。
 */
class SearchApi(
    private val store: () -> SearchStore,
    /** WP37:题目刷题状态聚合源(按请求解析,切库即时生效)。 */
    private val statuses: () -> ProblemStatusStore,
    private val json: Json = DEFAULT_JSON,
) {
    /** `GET /api/search?keyword=&tags=&platform=&limit=&offset=`。 */
    suspend fun search(query: Map<String, String>): ProblemApiResponse {
        val limit =
            query["limit"]?.let { text ->
                text.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
                    ?: return badRequest("limit must be an integer in 1..$MAX_LIMIT")
            } ?: DEFAULT_LIMIT
        val offset =
            query["offset"]?.let { text ->
                text.toIntOrNull()?.takeIf { it >= 0 }
                    ?: return badRequest("offset must be an integer >= 0")
            } ?: 0
        val keyword = query["keyword"]?.trim()?.takeIf { it.isNotEmpty() }
        val tags =
            query["tags"]
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val platform = query["platform"]?.trim()?.takeIf { it.isNotEmpty() }

        val page = store().search(SearchRequest(keyword = keyword, tags = tags, platform = platform, limit = limit, offset = offset))
        // WP37:本页题目批量聚合刷题状态(未做 → UNSOLVED 兜底)
        val statusById = statuses().statuses(page.items.map { it.problem.id })
        return ok(
            SearchResponseDto(
                keyword = keyword,
                platform = platform,
                tags = tags,
                offset = offset,
                limit = limit,
                total = page.total,
                items = page.items.map { it.toDto(statusById[it.problem.id] ?: SolveStatus.UNSOLVED) },
                hasMore = offset + page.items.size < page.total,
            ),
        )
    }

    /** `GET /api/tags?platform=`:标签名称 + 关联题数(1.6.0 起附 slug/中文名,未 enrich 时省略)。 */
    suspend fun listTags(query: Map<String, String>): ProblemApiResponse {
        val platform = query["platform"]?.trim()?.takeIf { it.isNotEmpty() }
        val tags = store().tags(platform)
        return ok(TagsResponseDto(tags.map { TagDto(name = it.name, count = it.problemCount, slug = it.slug, nameCn = it.nameCn) }))
    }

    // ------------------------------------------------------------------
    // 序列化与错误映射(照抄 StatisticsApi)
    // ------------------------------------------------------------------

    private fun SearchHit.toDto(status: SolveStatus) =
        SearchItemDto(
            platform = problem.platformId.value,
            remoteId = problem.remoteId,
            title = problem.title,
            titleSecondary = problem.titleSecondary,
            difficulty = problem.difficulty.value,
            url = problem.url,
            tags = problem.tags.map { SearchTagDto(name = it.name, nameCn = it.nameCn) },
            matchedSources = matchedSources.map { it.wireName },
            status = status,
            paidOnly = PAID_ONLY_UNAVAILABLE,
        )

    private val SearchSource.wireName: String
        get() = name.lowercase()

    private fun ok(dto: Any): ProblemApiResponse = ProblemApiResponse(STATUS_OK, json.encodeDto(dto))

    private fun badRequest(message: String): ProblemApiResponse =
        ProblemApiResponse(
            STATUS_BAD_REQUEST,
            json.encodeDto(SearchErrorResponseDto("bad-request", message)),
        )

    private fun Json.encodeDto(dto: Any): String =
        when (dto) {
            is SearchResponseDto -> encodeToString(SearchResponseDto.serializer(), dto)
            is TagsResponseDto -> encodeToString(TagsResponseDto.serializer(), dto)
            is SearchErrorResponseDto -> encodeToString(SearchErrorResponseDto.serializer(), dto)
            else -> throw IllegalArgumentException("unsupported dto: $dto")
        }

    private companion object {
        /** explicitNulls=false:null 字段(如 keyword)直接省略,前端按缺省回退。 */
        val DEFAULT_JSON =
            Json {
                encodeDefaults = true
                explicitNulls = false
            }

        private const val DEFAULT_LIMIT = 50

        private const val MAX_LIMIT = 100

        /** WP37:本地库/SPI 均无会员标记前的 paidOnly 占位值(见 SearchItemDto.paidOnly KDoc)。 */
        private const val PAID_ONLY_UNAVAILABLE = false

        private const val STATUS_OK = 200

        private const val STATUS_BAD_REQUEST = 400
    }
}

// ---------------------------------------------------------------------------
// 线协议 DTO(kotlinx.serialization;字段即公开 API 契约,勿随意改名)
// ---------------------------------------------------------------------------

@Serializable
private data class SearchResponseDto(
    /** 本次检索关键字;缺省 = 浏览模式。 */
    val keyword: String? = null,
    /** 过滤的平台 id;缺省 = 全部平台。 */
    val platform: String? = null,
    /** 本次过滤的标签(AND 语义);空数组 = 未过滤。 */
    val tags: List<String> = emptyList(),
    val offset: Int,
    val limit: Int,
    /** 过滤后命中总数(分页前)。 */
    val total: Int,
    val items: List<SearchItemDto>,
    val hasMore: Boolean,
)

@Serializable
private data class SearchItemDto(
    val platform: String,
    val remoteId: String,
    val title: String,
    /** 1.6.0:副标题(标题另一语言形态);单语数据源省略(explicitNulls=false)。 */
    val titleSecondary: String? = null,
    val difficulty: String,
    val url: String,
    val tags: List<SearchTagDto> = emptyList(),
    /** 命中来源标记:"title"(标题/remoteId)/ "content"(题面)/ "note"(题解笔记);浏览模式为空数组。 */
    val matchedSources: List<String> = emptyList(),
    /**
     * WP37 刷题状态(本地聚合,零平台请求):该题全部工作区提交里任一 isSuccessful → AC;
     * 有提交无 AC → TRIED;无提交 → UNSOLVED。多 challenge 取最高(AC > TRIED)。
     */
    val status: SolveStatus,
    /**
     * WP37 会员题标记(对齐原版状态列 🔒 语义;不与会员登录态联动——YAGNI):
     * 本地题目主档无会员列、SPI 亦未透传(见 ProblemApi 的 ProblemItemDto 同名字段),
     * **当前恒 false**——字段先行,前端按值渲染。
     */
    val paidOnly: Boolean,
)

@Serializable
private data class TagsResponseDto(
    val tags: List<TagDto>,
)

@Serializable
private data class TagDto(
    val name: String,
    /** 关联题目数。 */
    val count: Int,
    /** 1.6.0:标签 slug(平台过滤用);未 enrich 时省略。 */
    val slug: String? = null,
    /** 1.6.0:中文标签名(展示用);未 enrich 时省略。 */
    val nameCn: String? = null,
)

/** 1.6.0:命中项标签(name 原文 + 可选中文名,展示侧 nameCn ?? name)。 */
@Serializable
private data class SearchTagDto(
    val name: String,
    val nameCn: String? = null,
)

/** 与 StatisticsApi 的 StatsErrorResponseDto 同构;独立声明避免跨文件私有类型耦合。 */
@Serializable
private data class SearchErrorResponseDto(
    val error: String,
    val message: String? = null,
)
