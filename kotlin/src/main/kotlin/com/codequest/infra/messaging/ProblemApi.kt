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
import com.codequest.infra.db.ProblemStore
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
 * 端点契约(由 [ResourceHttpServer] 挂载,详细文档见 WP20 报告):
 * - `GET /api/platforms`:支持题目查询的平台(id + displayName)
 * - `GET /api/problems?platform=&keyword=&difficulty=&tags=&limit=&offset=`:列表 + 落库
 * - `GET /api/problem/{remoteId}?platform=`:题面,本地缓存优先,miss 时平台拉取并落库
 * - 领域异常 → HTTP 状态 + JSON 错误体(AuthExpired 401 / RateLimited 429 / AntiBot 403 /
 *   其余 OJException 502 / 参数问题 400 / 未知平台 404)
 *
 * 纯 Kotlin 可脱离 IntelliJ 平台测试;平台与存储来源以 lambda 注入,支持按请求解析
 * (数据库切换、平台注册表变更即时生效)。
 */
class ProblemApi(
    private val store: () -> ProblemStore,
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
     */
    suspend fun listProblems(query: Map<String, String>): ProblemApiResponse {
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
        return ok(
            ProblemListResponseDto(
                platform = platform.id.value,
                offset = filter.offset,
                limit = filter.limit,
                items = items.map { it.toDto() },
                hasMore = items.size == filter.limit,
            ),
        )
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

    private fun unknownPlatform(query: Map<String, String>): ProblemApiResponse =
        if (query["platform"].isNullOrBlank()) {
            badRequest("missing required query param: platform")
        } else {
            errorResponse(
                STATUS_NOT_FOUND,
                ErrorResponseDto(error = CODE_UNKNOWN_PLATFORM, message = "no platform registered for id '${query["platform"]}'"),
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

private fun com.codequest.spi.ProblemSummary.toDto() =
    ProblemItemDto(
        remoteId = remoteId,
        title = title,
        difficulty = difficulty.value,
        url = url,
        tags = tags,
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
