package com.codequest.infra.messaging

import com.codequest.core.model.AntiBotChallengeException
import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.OJException
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemDetail
import com.codequest.core.model.ProblemId
import com.codequest.core.model.RateLimitedException
import com.codequest.core.model.Tag
import com.codequest.core.model.TagId
import com.codequest.infra.db.ProblemStatusStore
import com.codequest.infra.db.ProblemStore
import com.codequest.infra.db.SolveStatus
import com.codequest.spi.CompanyQueryCapable
import com.codequest.spi.FavoriteQueryCapable
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.ProblemFilter
import com.codequest.spi.ProblemQueryCapable
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.logging.Logger

/**
 * 题目数据 REST 端点逻辑(WP20,infra 层):平台数据经 SPI(OJPlatformRegistry)拉取,
 * 结果经 [ProblemStore] 落本地库缓存;协议纯 JSON(kotlinx.serialization)。
 *
 * WP37 起列表项附加刷题状态([ProblemStatusStore] 本地聚合,零平台请求)与
 * paidOnly 标记(语义见 [ProblemItemDto])。
 *
 * 端点契约(由 [ResourceHttpServer] 挂载,详细文档见 WP20 报告):
 * - `GET /api/platforms`:支持题目查询的平台(id + displayName)
 * - `GET /api/problems?platform=&keyword=&difficulty=&tags=&limit=&offset=`:列表 + 落库
 * - `GET /api/companies?platform=`:公司标签清单(WP42,CompanyQueryCapable 平台)
 * - `GET /api/favorites?platform=`:题单清单(WP56,FavoriteQueryCapable 平台)——
 *   内置学习计划组 + 我的题单组(接口探测失败降级空表,不阻塞学习计划组)
 * - `GET /api/problems?platform=&company=&period=&difficulty=&tags=&limit=&offset=`:
 *   公司题单(WP42)——company 非空时走公司查询路径;**keyword 在公司模式下被忽略**
 *   (旧实现事实:公司查询恒发空 searchKeyword,公司面板无关键字入口)
 * - `GET /api/problems?platform=&favorite=&difficulty=&tags=&limit=&offset=`:
 *   题单查询(WP56)——favorite 非空时走题单查询路径;**与 company 同给时 favorite
 *   优先、company 忽略**(前端已互斥,后端宽松兜底);keyword 同公司模式被忽略
 * - `GET /api/problem/{remoteId}?platform=`:题面,本地缓存优先,miss 时平台拉取并落库
 * - 领域异常 → HTTP 状态 + JSON 错误体(AuthExpired 401 / RateLimited 429 / AntiBot 403 /
 *   其余 OJException 502 / 参数问题 400 / 未知平台 404)
 *
 * 纯 Kotlin 可脱离 IntelliJ 平台测试;平台与存储来源以 lambda 注入,支持按请求解析
 * (数据库切换、平台注册表变更即时生效)。
 */
class ProblemApi(
    private val store: () -> ProblemStore,
    /** WP37:题目刷题状态聚合源(按请求解析,切库即时生效)。 */
    private val statuses: () -> ProblemStatusStore,
    private val platforms: () -> List<OJPlatform> = OJPlatformRegistry::all,
    private val json: Json = DEFAULT_JSON,
    private val now: () -> Instant = Instant::now,
) {
    /** `/api/platforms`:返回实现了 [ProblemQueryCapable] 的平台(题目列表页数据源)。 */
    fun listPlatforms(): ProblemApiResponse =
        ok(
            PlatformsResponseDto(
                platforms =
                    queryPlatforms().map { (platform, _) ->
                        PlatformDto(id = platform.id.value, displayName = platform.info.displayName)
                    },
            ),
        )

    /**
     * `/api/problems`:SPI queryProblems 流式收集 → 逐条落库(主档 + 标签)→ 返回本页数据。
     * [hasMore] = 返回条数等于请求 limit(平台侧总数不经 SPI 暴露,由「满页」语义推断)。
     * `favorite` 参数非空时改走题单路径([listFavoriteProblems],WP56,优先于 company);
     * `company` 参数非空时改走公司题单路径([listCompanyProblems],WP42)。
     */
    suspend fun listProblems(query: Map<String, String>): ProblemApiResponse {
        if (!query["favorite"].isNullOrBlank()) return listFavoriteProblems(query)
        if (!query["company"].isNullOrBlank()) return listCompanyProblems(query)
        val (platform, capable) = resolvePlatform(query) ?: return unknownPlatform(query)
        val filter =
            parseFilter(query)
                ?: return badRequest("limit/offset must be integers; limit in 1..$MAX_LIMIT; offset >= 0")
        val items =
            try {
                capable.queryProblems(filter).toList()
            } catch (error: Throwable) {
                return error.toErrorResponse()
            }
        return persistAndRespond(platform, filter.offset, filter.limit, items)
    }

    /**
     * `/api/problems?company=`:公司题单(WP42)——SPI queryCompanyProblems 单页查询,
     * 落库与状态聚合与普通列表同路径(状态列/状态筛选语义一致)。
     * company 为逗号分隔 slug 列表(协议支持多公司,UI 单选);`period` 透传期间 slug
     * (thirty-days 等,缺省不拼后缀);**keyword 被忽略**(见类 KDoc)。
     */
    private suspend fun listCompanyProblems(query: Map<String, String>): ProblemApiResponse {
        val (platform, capable) = resolveCompanyPlatform(query) ?: return unknownCompanyPlatform(query)
        val filter =
            parseFilter(query)
                ?: return badRequest("limit/offset must be integers; limit in 1..$MAX_LIMIT; offset >= 0")
        val companies =
            query["company"]
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val period = query["period"]?.trim()?.takeIf { it.isNotEmpty() }
        val page =
            try {
                capable.queryCompanyProblems(
                    com.codequest.spi.CompanyProblemQuery(
                        companySlugs = companies,
                        interviewPeriodSlug = period,
                        difficulty = filter.difficulty,
                        tags = filter.tags,
                        offset = filter.offset,
                        limit = filter.limit,
                    ),
                )
            } catch (error: Throwable) {
                return error.toErrorResponse()
            }
        return persistAndRespond(platform, filter.offset, filter.limit, page.questions)
    }

    /**
     * `/api/problems?favorite=`:题单查询(WP56)——SPI queryFavoriteProblems 单页查询,
     * 落库与状态聚合与普通/公司列表同路径(状态列/状态筛选语义一致)。
     * favorite 为学习计划/收藏夹 slug(透传平台侧 favoriteSlug);**keyword 被忽略**
     * (与公司模式同规则,题单面板无关键字入口)。
     */
    private suspend fun listFavoriteProblems(query: Map<String, String>): ProblemApiResponse {
        val (platform, capable) = resolveFavoritePlatform(query) ?: return unknownFavoritePlatform(query)
        val filter =
            parseFilter(query)
                ?: return badRequest("limit/offset must be integers; limit in 1..$MAX_LIMIT; offset >= 0")
        val favoriteSlug = query["favorite"].orEmpty().trim()
        val page =
            try {
                capable.queryFavoriteProblems(
                    favoriteSlug = favoriteSlug,
                    filters = ProblemFilter(difficulty = filter.difficulty, tags = filter.tags),
                    offset = filter.offset,
                    limit = filter.limit,
                )
            } catch (error: Throwable) {
                return error.toErrorResponse()
            }
        return persistAndRespond(platform, filter.offset, filter.limit, page.questions)
    }

    /** `/api/companies`:公司标签清单(公司下拉数据源;无该能力的平台按 404 如实回)。 */
    suspend fun listCompanies(query: Map<String, String>): ProblemApiResponse {
        val id = query["platform"]?.trim().orEmpty()
        if (id.isEmpty()) return badRequest("missing required query param: platform")
        val platform = companyCapablePlatform(id) ?: return unknownCompanyPlatform(query)
        val capable = platform as CompanyQueryCapable
        val companies =
            try {
                capable.fetchCompanies()
            } catch (error: Throwable) {
                return error.toErrorResponse()
            }
        return ok(
            CompaniesResponseDto(
                platform = platform.id.value,
                items = companies.map { CompanyItemDto(slug = it.slug, name = it.name, questionCount = it.questionCount) },
            ),
        )
    }

    /**
     * `/api/favorites`:题单清单(WP56)——内置学习计划组(平台常量,零平台请求)
     * + 我的题单组(登录态收藏夹;实现侧双路探测失败降级空表,本层兜异常按错误映射回)。
     */
    suspend fun listFavorites(query: Map<String, String>): ProblemApiResponse {
        val id = query["platform"]?.trim().orEmpty()
        if (id.isEmpty()) return badRequest("missing required query param: platform")
        val platform = favoriteCapablePlatform(id) ?: return unknownFavoritePlatform(query)
        val capable = platform as FavoriteQueryCapable
        val myFavorites =
            try {
                capable.fetchMyFavorites()
            } catch (error: Throwable) {
                return error.toErrorResponse()
            }
        return ok(
            FavoritesResponseDto(
                platform = platform.id.value,
                studyPlans = capable.studyPlans().map { StudyPlanItemDto(slug = it.slug, name = it.name) },
                myFavorites = myFavorites.map { MyFavoriteItemDto(slug = it.slug, name = it.name, questionCount = it.questionCount) },
            ),
        )
    }

    /** 列表项落库(主档 + 标签)→ 状态聚合 → 本页应答(普通/公司题单共用收尾,WP42 提取)。 */
    private suspend fun persistAndRespond(
        platform: OJPlatform,
        offset: Int,
        limit: Int,
        items: List<com.codequest.spi.ProblemSummary>,
    ): ProblemApiResponse {
        items.forEach { summary ->
            store().save(
                Problem(
                    id = problemId(platform.id, summary.remoteId),
                    platformId = platform.id,
                    remoteId = summary.remoteId,
                    title = summary.title,
                    difficulty = summary.difficulty,
                    url = summary.url,
                    tags = summary.tags.map { tagId(it) },
                    fetchedAt = now(),
                ),
            )
        }
        // WP37:主档落库后按确定性 problemId 批量聚合刷题状态(未做 → UNSOLVED 兜底);
        // 1.2.1 修复反馈:与平台侧已解状态取最高合并(力扣站内已解的题,即便从未在
        // 插件内提交,也如实显示 AC)
        val statusById = statuses().statuses(items.map { problemId(platform.id, it.remoteId) })
        return ok(
            ProblemListResponseDto(
                platform = platform.id.value,
                offset = offset,
                limit = limit,
                items =
                    items.map {
                        it.toDto(
                            mergeSolveStatus(statusById[problemId(platform.id, it.remoteId)], it.solvedOnPlatform),
                        )
                    },
                hasMore = items.size == limit,
            ),
        )
    }

    /** 平台侧已解 ∪ 本地提交聚合,取最高(AC > TRIED > UNSOLVED;平台 null 不参与)。 */
    private fun mergeSolveStatus(
        local: SolveStatus?,
        solvedOnPlatform: Boolean?,
    ): SolveStatus =
        when {
            local == SolveStatus.AC || solvedOnPlatform == true -> SolveStatus.AC
            local == SolveStatus.TRIED || solvedOnPlatform == false -> SolveStatus.TRIED
            else -> SolveStatus.UNSOLVED
        }

    /**
     * `/api/problem/{remoteId}`:本地 problem_content 缓存优先;miss 时
     * fetchProblemDetail → 主档(无则建桩)与题面一并落库 → 返回。
     */
    suspend fun problemDetail(
        remoteId: String,
        query: Map<String, String>,
    ): ProblemApiResponse {
        val (platform, capable) = resolvePlatform(query) ?: return unknownPlatform(query)
        val cachedProblem = store().findByRemoteId(platform.id, remoteId)
        if (cachedProblem != null) {
            val cachedDetail = store().findDetail(cachedProblem.id)
            if (cachedDetail != null) {
                return ok(cachedProblem.toDetailDto(cachedDetail, source = SOURCE_CACHE))
            }
        }
        val statement =
            try {
                capable.fetchProblemDetail(remoteId)
            } catch (error: Throwable) {
                return error.toErrorResponse()
            }
        // 主档缺失(未经列表页直达)时先落桩,后续列表查询会用同 id upsert 补全真实标题/难度
        val problem =
            cachedProblem
                ?: Problem(
                    id = problemId(platform.id, remoteId),
                    platformId = platform.id,
                    remoteId = remoteId,
                    title = remoteId,
                    difficulty = Difficulty(STUB_DIFFICULTY),
                    url = "",
                    tags = emptyList(),
                    fetchedAt = now(),
                ).also { store().save(it) }
        store().saveDetail(ProblemDetail(problemId = problem.id, contentMd = statement.content, contentFormat = statement.contentFormat))
        return ok(problem.toDetailDto(ProblemDetail(problem.id, statement.content, statement.contentFormat), source = SOURCE_REMOTE))
    }

    // ------------------------------------------------------------------
    // 参数与平台解析
    // ------------------------------------------------------------------

    /** 能力接口是独立 trait(不继承 OJPlatform),配对持有平台身份与查询能力。 */
    private fun queryPlatforms(): List<Pair<OJPlatform, ProblemQueryCapable>> =
        platforms().mapNotNull { platform -> (platform as? ProblemQueryCapable)?.let { platform to it } }

    private fun resolvePlatform(query: Map<String, String>): Pair<OJPlatform, ProblemQueryCapable>? {
        val id = query["platform"]?.trim().orEmpty()
        if (id.isEmpty()) return null
        return queryPlatforms().firstOrNull { it.first.id.value == id }
    }

    /** 公司题单平台解析(WP42):平台需实现 CompanyQueryCapable(如 LC 双站点)。 */
    private fun resolveCompanyPlatform(query: Map<String, String>): Pair<OJPlatform, CompanyQueryCapable>? {
        val id = query["platform"]?.trim().orEmpty()
        if (id.isEmpty()) return null
        val platform = companyCapablePlatform(id) ?: return null
        return platform to (platform as CompanyQueryCapable)
    }

    /** 按 id 找已注册且具备公司题单能力的平台(列表/公司题单端点共用)。 */
    private fun companyCapablePlatform(id: String): OJPlatform? =
        platforms().firstOrNull { candidate -> candidate.id.value == id && candidate is CompanyQueryCapable }

    /** 题单平台解析(WP56):平台需实现 FavoriteQueryCapable(如 LC 双站点)。 */
    private fun resolveFavoritePlatform(query: Map<String, String>): Pair<OJPlatform, FavoriteQueryCapable>? {
        val id = query["platform"]?.trim().orEmpty()
        if (id.isEmpty()) return null
        val platform = favoriteCapablePlatform(id) ?: return null
        return platform to (platform as FavoriteQueryCapable)
    }

    /** 按 id 找已注册且具备题单能力的平台(题单清单/题单查询端点共用)。 */
    private fun favoriteCapablePlatform(id: String): OJPlatform? =
        platforms().firstOrNull { candidate -> candidate.id.value == id && candidate is FavoriteQueryCapable }

    private fun unknownPlatform(query: Map<String, String>): ProblemApiResponse =
        if (query["platform"].isNullOrBlank()) {
            badRequest("missing required query param: platform")
        } else {
            errorResponse(
                STATUS_NOT_FOUND,
                ErrorResponseDto(error = CODE_UNKNOWN_PLATFORM, message = "no platform registered for id '${query["platform"]}'"),
            )
        }

    /** 公司端点专用 404(WP42):未注册或平台无 CompanyQueryCapable 均如实回,形态同 SolutionsApi。 */
    private fun unknownCompanyPlatform(query: Map<String, String>): ProblemApiResponse =
        if (query["platform"].isNullOrBlank()) {
            badRequest("missing required query param: platform")
        } else {
            errorResponse(
                STATUS_NOT_FOUND,
                ErrorResponseDto(
                    error = CODE_UNKNOWN_PLATFORM,
                    message = "no company-capable platform registered for id '${query["platform"]}'",
                ),
            )
        }

    /** 题单端点专用 404(WP56):未注册或平台无 FavoriteQueryCapable 均如实回,形态同公司端点。 */
    private fun unknownFavoritePlatform(query: Map<String, String>): ProblemApiResponse =
        if (query["platform"].isNullOrBlank()) {
            badRequest("missing required query param: platform")
        } else {
            errorResponse(
                STATUS_NOT_FOUND,
                ErrorResponseDto(
                    error = CODE_UNKNOWN_PLATFORM,
                    message = "no favorite-capable platform registered for id '${query["platform"]}'",
                ),
            )
        }

    /** 全部参数合法返回过滤器;limit/offset 非法返回 null(调用方回 400)。 */
    private fun parseFilter(query: Map<String, String>): ProblemFilter? {
        val limit = query["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
        val offset = query["offset"]?.toIntOrNull() ?: 0
        if (limit !in 1..MAX_LIMIT || offset < 0) return null
        return ProblemFilter(
            keyword = query["keyword"]?.trim()?.takeIf { it.isNotEmpty() },
            difficulty = query["difficulty"]?.trim()?.takeIf { it.isNotEmpty() }?.let(::Difficulty),
            tags =
                query["tags"]
                    .orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
            limit = limit,
            offset = offset,
        )
    }

    // ------------------------------------------------------------------
    // 序列化与错误映射
    // ------------------------------------------------------------------

    private fun ok(dto: Any): ProblemApiResponse = ProblemApiResponse(STATUS_OK, json.encodeDto(dto))

    private fun badRequest(message: String): ProblemApiResponse =
        errorResponse(STATUS_BAD_REQUEST, ErrorResponseDto(CODE_BAD_REQUEST, message))

    private fun errorResponse(
        status: Int,
        dto: ErrorResponseDto,
        headers: Map<String, String> = emptyMap(),
    ): ProblemApiResponse {
        LOGGER.fine { "problem api error ${dto.error}: ${dto.message}" }
        return ProblemApiResponse(status, json.encodeDto(dto), headers)
    }

    private fun Throwable.toErrorResponse(): ProblemApiResponse =
        when (this) {
            is AuthExpiredException -> {
                errorResponse(STATUS_UNAUTHORIZED, ErrorResponseDto(CODE_AUTH_EXPIRED, message))
            }

            is RateLimitedException -> {
                errorResponse(
                    STATUS_RATE_LIMITED,
                    ErrorResponseDto(CODE_RATE_LIMITED, message),
                    headers = mapOf("Retry-After" to retryAfterSeconds(retryAfterMs).toString()),
                )
            }

            is AntiBotChallengeException -> {
                errorResponse(STATUS_FORBIDDEN, ErrorResponseDto(CODE_ANTI_BOT, message))
            }

            is OJException -> {
                errorResponse(STATUS_BAD_GATEWAY, ErrorResponseDto(CODE_OJ_ERROR, message))
            }

            else -> {
                LOGGER.warning("problem api unexpected error: $this")
                errorResponse(STATUS_INTERNAL_ERROR, ErrorResponseDto(CODE_INTERNAL, "unexpected server error"))
            }
        }

    private fun Json.encodeDto(dto: Any): String =
        when (dto) {
            is PlatformsResponseDto -> encodeToString(PlatformsResponseDto.serializer(), dto)
            is ProblemListResponseDto -> encodeToString(ProblemListResponseDto.serializer(), dto)
            is ProblemDetailResponseDto -> encodeToString(ProblemDetailResponseDto.serializer(), dto)
            is CompaniesResponseDto -> encodeToString(CompaniesResponseDto.serializer(), dto)
            is FavoritesResponseDto -> encodeToString(FavoritesResponseDto.serializer(), dto)
            is ErrorResponseDto -> encodeToString(ErrorResponseDto.serializer(), dto)
            else -> throw IllegalArgumentException("unsupported dto: $dto")
        }

    /** 毫秒 → 秒向上取整(Retry-After 头,至少 1 秒;平台未给重试建议时用默认值)。 */
    private fun retryAfterSeconds(retryAfterMs: Long?): Long {
        val millis = retryAfterMs ?: DEFAULT_RETRY_AFTER_SECONDS.times(MILLIS_PER_SECOND)
        return ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(DEFAULT_RETRY_AFTER_SECONDS)
    }

    private companion object {
        private val LOGGER = Logger.getLogger(ProblemApi::class.java.name)

        private val DEFAULT_JSON = Json { encodeDefaults = true }

        private const val MILLIS_PER_SECOND = 1000L

        private const val DEFAULT_RETRY_AFTER_SECONDS = 1L

        private const val DEFAULT_LIMIT = 50

        /** 单次列表上限:与 LeetCode 单页协议上限一致(平台内部自行分页游走)。 */
        private const val MAX_LIMIT = 100

        private const val STUB_DIFFICULTY = "unknown"

        private const val SOURCE_CACHE = "cache"
        private const val SOURCE_REMOTE = "remote"

        private const val STATUS_OK = 200
        private const val STATUS_BAD_REQUEST = 400
        private const val STATUS_UNAUTHORIZED = 401
        private const val STATUS_FORBIDDEN = 403
        private const val STATUS_NOT_FOUND = 404
        private const val STATUS_INTERNAL_ERROR = 500
        private const val STATUS_BAD_GATEWAY = 502
        private const val STATUS_RATE_LIMITED = 429

        private const val CODE_BAD_REQUEST = "bad-request"
        private const val CODE_UNKNOWN_PLATFORM = "unknown-platform"
        private const val CODE_AUTH_EXPIRED = "auth-expired"
        private const val CODE_RATE_LIMITED = "rate-limited"
        private const val CODE_ANTI_BOT = "anti-bot"
        private const val CODE_OJ_ERROR = "oj-error"
        private const val CODE_INTERNAL = "internal-error"
    }
}

/** 端点应答:HTTP 状态 + JSON 体 + 可选响应头(如 Retry-After)。 */
class ProblemApiResponse(
    val status: Int,
    val json: String,
    val headers: Map<String, String> = emptyMap(),
)

/** 确定性主档 id:`<platform>:<remoteId>`(列表 upsert 与详情缓存命中依赖同一映射)。 */
internal fun problemId(
    platformId: PlatformId,
    remoteId: String,
): ProblemId = ProblemId("${platformId.value}:$remoteId")

/** 确定性标签 id:同名标签跨平台共享一行(主档 save 为全量替换,幂等)。 */
private fun tagId(name: String) = Tag(TagId("tag:$name"), name)

private fun com.codequest.spi.ProblemSummary.toDto(status: SolveStatus) =
    ProblemItemDto(
        remoteId = remoteId,
        title = title,
        difficulty = difficulty.value,
        url = url,
        tags = tags,
        status = status,
        paidOnly = paidOnly,
    )

private fun Problem.toDetailDto(
    detail: ProblemDetail,
    source: String,
) = ProblemDetailResponseDto(
    platform = platformId.value,
    remoteId = remoteId,
    title = title,
    difficulty = difficulty.value,
    url = url,
    content = detail.contentMd,
    contentFormat = detail.contentFormat.name,
    source = source,
)

// ---------------------------------------------------------------------------
// 线协议 DTO(kotlinx.serialization;字段即公开 API 契约,勿随意改名)
// ---------------------------------------------------------------------------

@Serializable
private data class PlatformsResponseDto(
    val platforms: List<PlatformDto>,
)

@Serializable
private data class PlatformDto(
    val id: String,
    val displayName: String,
)

@Serializable
private data class ProblemListResponseDto(
    val platform: String,
    val offset: Int,
    val limit: Int,
    val items: List<ProblemItemDto>,
    val hasMore: Boolean,
)

@Serializable
private data class ProblemItemDto(
    val remoteId: String,
    val title: String,
    val difficulty: String,
    val url: String,
    val tags: List<String> = emptyList(),
    /**
     * WP37 刷题状态(本地聚合,零平台请求):该题全部工作区提交里任一 isSuccessful → AC;
     * 有提交无 AC → TRIED;无提交 → UNSOLVED。多 challenge 取最高(AC > TRIED)。
     */
    val status: SolveStatus,
    /**
     * WP37 会员题标记(对齐原版状态列 🔒 语义;不与会员登录态联动——YAGNI):
     * WP42 起 SPI [com.codequest.spi.ProblemSummary] 透传该标记(LC paidOnly;
     * 无此概念的平台缺省 false),端点原样转发。
     */
    val paidOnly: Boolean,
)

/** 公司标签清单响应(WP42,`/api/companies`)。 */
@Serializable
private data class CompaniesResponseDto(
    val platform: String,
    val items: List<CompanyItemDto>,
)

@Serializable
private data class CompanyItemDto(
    val slug: String,
    val name: String,
    /** 公司题量(下拉展示;period 维度的精确数以题单 total 为准)。 */
    val questionCount: Int,
)

/** 题单清单响应(WP56,`/api/favorites`):两组条目形态不同故拆两个 item DTO。 */
@Serializable
private data class FavoritesResponseDto(
    val platform: String,
    val studyPlans: List<StudyPlanItemDto>,
    val myFavorites: List<MyFavoriteItemDto>,
)

/** 学习计划条目(内置常量,无题数概念)。 */
@Serializable
private data class StudyPlanItemDto(
    val slug: String,
    val name: String,
)

/** 我的题单条目(登录态收藏夹;题数为下拉展示,精确数以题单 total 为准)。 */
@Serializable
private data class MyFavoriteItemDto(
    val slug: String,
    val name: String,
    val questionCount: Int,
)

@Serializable
private data class ProblemDetailResponseDto(
    val platform: String,
    val remoteId: String,
    val title: String,
    val difficulty: String,
    val url: String,
    val content: String,
    /** ContentFormat.name:HTML / MARKDOWN。 */
    val contentFormat: String,
    /** cache = 本地库命中;remote = 平台拉取(已落库)。 */
    val source: String,
)

@Serializable
private data class ErrorResponseDto(
    val error: String,
    val message: String? = null,
)
