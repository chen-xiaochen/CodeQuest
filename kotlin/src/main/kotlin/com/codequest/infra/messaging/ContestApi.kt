package com.codequest.infra.messaging

import com.codequest.core.ContestReminderService
import com.codequest.core.model.AntiBotChallengeException
import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.ContestPhase
import com.codequest.core.model.InstantSerializer
import com.codequest.core.model.OJException
import com.codequest.core.model.PlatformId
import com.codequest.core.model.RateLimitedException
import com.codequest.core.repository.ContestReminderRepository
import com.codequest.spi.ContestCapable
import com.codequest.spi.ContestDetail
import com.codequest.spi.ContestSummary
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.logging.Logger

/**
 * 竞赛数据 REST 端点逻辑(WP23,infra 层,风格照抄 [ProblemApi]):
 * 平台数据经 SPI(ContestCapable)拉取;提醒经 [ContestReminderService] 落
 * contest_reminder 表(ui 层 ContestReminderScheduler 周期派发通知)。
 *
 * 端点契约(由 [ResourceHttpServer] 挂载):
 * - `GET /api/contests`:返回实现了 [ContestCapable] 的平台(contest 页平台标签数据源)
 * - `GET /api/contests?platform=`:竞赛列表(时间/阶段/链接)
 * - `GET /api/contest/{remoteId}?platform=`:竞赛详情(题目引用 + 可参加标记)
 * - `GET /api/contests/reminders?platform=`:该平台已订阅提醒(开关回显)
 * - `POST /api/contests/reminders` body `{platform,remoteId,startTime,leadTimeMinutes?}`:订阅/更新
 * - `DELETE /api/contests/reminders?platform=&remoteId=`:取消订阅
 * - 领域异常 → HTTP 状态 + JSON 错误体(AuthExpired 401 / RateLimited 429 / AntiBot 403 /
 *   其余 OJException 502 / 参数问题 400 / 未知平台 404,与 ProblemApi 一致)
 *
 * 「参加」动作如实选型:CF 参加需登录 POST,平台内交互留 WP24;本期前端以
 * url 外链兜底。纯 Kotlin 可脱离 IntelliJ 平台测试;来源以 lambda 注入。
 */
class ContestApi(
    private val platforms: () -> List<OJPlatform> = OJPlatformRegistry::all,
    private val reminders: () -> ContestReminderRepository,
    private val json: Json = DEFAULT_JSON,
    private val clock: () -> Instant = Instant::now,
) {
    /** `/api/contests`(无 platform):支持竞赛查询的平台列表。 */
    fun listContestPlatforms(): ProblemApiResponse =
        ok(
            ContestPlatformsResponseDto(
                platforms =
                    contestPlatforms().map { (platform, _) ->
                        ContestPlatformDto(id = platform.id.value, displayName = platform.info.displayName)
                    },
            ),
        )

    /** `/api/contests?platform=`:竞赛列表(全量流式收集;近期竞赛数量小,不分页)。 */
    suspend fun listContests(query: Map<String, String>): ProblemApiResponse {
        val (platform, capable) = resolvePlatform(query) ?: return unknownPlatform(query)
        val summaries =
            try {
                capable.contests().toList()
            } catch (error: Throwable) {
                return error.toErrorResponse()
            }
        return ok(ContestListResponseDto(platform = platform.id.value, items = summaries.map { it.toDto() }))
    }

    /** `/api/contest/{remoteId}?platform=`:竞赛详情。 */
    suspend fun contestDetail(
        remoteId: String,
        query: Map<String, String>,
    ): ProblemApiResponse {
        val (platform, capable) = resolvePlatform(query) ?: return unknownPlatform(query)
        val detail =
            try {
                capable.contestDetail(remoteId)
            } catch (error: Throwable) {
                return error.toErrorResponse()
            }
        return ok(detail.toDto(platform.id.value))
    }

    /** `/api/contests/reminders?platform=`:已订阅提醒(开关状态回显)。 */
    suspend fun listReminders(query: Map<String, String>): ProblemApiResponse {
        val platformId = requirePlatformId(query) ?: return unknownPlatform(query)
        if (resolvePlatform(query) == null) return unknownPlatform(query)
        val items = reminderService().listReminders(platformId)
        return ok(
            ReminderListResponseDto(
                platform = platformId.value,
                items =
                    items.map { reminder ->
                        ReminderResponseDto(
                            platform = platformId.value,
                            remoteId = reminder.contestId.value.removePrefix("${platformId.value}:"),
                            remindAt = reminder.remindAt,
                            notified = reminder.notified,
                        )
                    },
            ),
        )
    }

    /** `POST /api/contests/reminders`:订阅(或更新)提醒;body 为 JSON。 */
    suspend fun subscribeReminder(body: String): ProblemApiResponse {
        val request =
            try {
                json.decodeFromString(SubscribeRequestDto.serializer(), body)
            } catch (_: SerializationException) {
                return badRequest("request body must be JSON: {platform, remoteId, startTime, leadTimeMinutes?}")
            }
        if (request.platform.isBlank() || request.remoteId.isBlank()) {
            return badRequest("platform and remoteId must be non-blank")
        }
        val platformId = PlatformId(request.platform.trim())
        if (resolvePlatform(mapOf("platform" to platformId.value)) == null) return unknownPlatform(mapOf("platform" to platformId.value))
        val reminder =
            try {
                reminderService().subscribe(
                    platformId = platformId,
                    remoteId = request.remoteId.trim(),
                    startTime = request.startTime,
                    leadTimeMinutes = request.leadTimeMinutes ?: ContestReminderService.DEFAULT_LEAD_TIME_MINUTES,
                )
            } catch (error: IllegalArgumentException) {
                return badRequest(error.message.orEmpty())
            }
        return ok(reminder.toDto(platformId.value, request.remoteId.trim()))
    }

    /** `DELETE /api/contests/reminders?platform=&remoteId=`:取消订阅。 */
    suspend fun unsubscribeReminder(query: Map<String, String>): ProblemApiResponse {
        val platformId = query["platform"]?.trim()?.takeIf { it.isNotEmpty() } ?: return badRequest(MISSING_PLATFORM_PARAM)
        val remoteId = query["remoteId"]?.trim()?.takeIf { it.isNotEmpty() } ?: return badRequest("missing required query param: remoteId")
        val platformQuery = mapOf("platform" to platformId)
        if (resolvePlatform(platformQuery) == null) return unknownPlatform(platformQuery)
        val deleted = reminderService().unsubscribe(PlatformId(platformId), remoteId)
        return ok(DeleteResponseDto(deleted = deleted))
    }

    // ------------------------------------------------------------------
    // 平台解析与支撑
    // ------------------------------------------------------------------

    private fun contestPlatforms(): List<Pair<OJPlatform, ContestCapable>> =
        platforms().mapNotNull { platform -> (platform as? ContestCapable)?.let { platform to it } }

    private fun resolvePlatform(query: Map<String, String>): Pair<OJPlatform, ContestCapable>? {
        val id = query["platform"]?.trim().orEmpty()
        if (id.isEmpty()) return null
        return contestPlatforms().firstOrNull { it.first.id.value == id }
    }

    private fun requirePlatformId(query: Map<String, String>): PlatformId? {
        val id = query["platform"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return PlatformId(id)
    }

    /** 服务无状态,按请求构造(reminders 仓库随切库指向新句柄,同 ProblemApi.store 语义)。 */
    private fun reminderService() = ContestReminderService(reminders(), clock)

    private fun unknownPlatform(query: Map<String, String>): ProblemApiResponse =
        if (query["platform"].isNullOrBlank()) {
            badRequest(MISSING_PLATFORM_PARAM)
        } else {
            val message = "no ContestCapable platform registered for id '${query["platform"]}'"
            errorResponse(STATUS_NOT_FOUND, ContestErrorResponseDto(error = CODE_UNKNOWN_PLATFORM, message = message))
        }

    // ------------------------------------------------------------------
    // 序列化与错误映射(照抄 ProblemApi)
    // ------------------------------------------------------------------

    private fun ok(dto: Any): ProblemApiResponse = ProblemApiResponse(STATUS_OK, json.encodeDto(dto))

    private fun badRequest(message: String): ProblemApiResponse =
        errorResponse(STATUS_BAD_REQUEST, ContestErrorResponseDto(CODE_BAD_REQUEST, message))

    private fun errorResponse(
        status: Int,
        dto: ContestErrorResponseDto,
        headers: Map<String, String> = emptyMap(),
    ): ProblemApiResponse {
        LOGGER.fine { "contest api error ${dto.error}: ${dto.message}" }
        return ProblemApiResponse(status, json.encodeDto(dto), headers)
    }

    private fun Throwable.toErrorResponse(): ProblemApiResponse =
        when (this) {
            is AuthExpiredException -> {
                errorResponse(STATUS_UNAUTHORIZED, ContestErrorResponseDto(CODE_AUTH_EXPIRED, message))
            }

            is RateLimitedException -> {
                errorResponse(
                    STATUS_RATE_LIMITED,
                    ContestErrorResponseDto(CODE_RATE_LIMITED, message),
                    headers = mapOf("Retry-After" to retryAfterSeconds(retryAfterMs).toString()),
                )
            }

            is AntiBotChallengeException -> {
                errorResponse(STATUS_FORBIDDEN, ContestErrorResponseDto(CODE_ANTI_BOT, message))
            }

            is OJException -> {
                errorResponse(STATUS_BAD_GATEWAY, ContestErrorResponseDto(CODE_OJ_ERROR, message))
            }

            else -> {
                LOGGER.warning("contest api unexpected error: $this")
                errorResponse(STATUS_INTERNAL_ERROR, ContestErrorResponseDto(CODE_INTERNAL, "unexpected server error"))
            }
        }

    private fun Json.encodeDto(dto: Any): String =
        when (dto) {
            is ContestPlatformsResponseDto -> encodeToString(ContestPlatformsResponseDto.serializer(), dto)
            is ContestListResponseDto -> encodeToString(ContestListResponseDto.serializer(), dto)
            is ContestDetailResponseDto -> encodeToString(ContestDetailResponseDto.serializer(), dto)
            is ReminderListResponseDto -> encodeToString(ReminderListResponseDto.serializer(), dto)
            is ReminderResponseDto -> encodeToString(ReminderResponseDto.serializer(), dto)
            is DeleteResponseDto -> encodeToString(DeleteResponseDto.serializer(), dto)
            is ContestErrorResponseDto -> encodeToString(ContestErrorResponseDto.serializer(), dto)
            else -> throw IllegalArgumentException("unsupported dto: $dto")
        }

    private fun retryAfterSeconds(retryAfterMs: Long?): Long {
        val millis = retryAfterMs ?: DEFAULT_RETRY_AFTER_SECONDS.times(MILLIS_PER_SECOND)
        return ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(DEFAULT_RETRY_AFTER_SECONDS)
    }

    private companion object {
        private val LOGGER = Logger.getLogger(ContestApi::class.java.name)

        private val DEFAULT_JSON = Json { encodeDefaults = true }

        private const val MILLIS_PER_SECOND = 1000L

        private const val DEFAULT_RETRY_AFTER_SECONDS = 1L

        private const val STATUS_OK = 200
        private const val STATUS_BAD_REQUEST = 400
        private const val STATUS_UNAUTHORIZED = 401
        private const val STATUS_FORBIDDEN = 403
        private const val STATUS_NOT_FOUND = 404
        private const val STATUS_INTERNAL_ERROR = 500
        private const val STATUS_BAD_GATEWAY = 502
        private const val STATUS_RATE_LIMITED = 429

        private const val MISSING_PLATFORM_PARAM = "missing required query param: platform"

        private const val CODE_BAD_REQUEST = "bad-request"
        private const val CODE_UNKNOWN_PLATFORM = "unknown-platform"
        private const val CODE_AUTH_EXPIRED = "auth-expired"
        private const val CODE_RATE_LIMITED = "rate-limited"
        private const val CODE_ANTI_BOT = "anti-bot"
        private const val CODE_OJ_ERROR = "oj-error"
        private const val CODE_INTERNAL = "internal-error"
    }
}

// ---------------------------------------------------------------------------
// 线协议 DTO(kotlinx.serialization;字段即公开 API 契约,勿随意改名)
// ---------------------------------------------------------------------------

@Serializable
private data class ContestPlatformsResponseDto(
    val platforms: List<ContestPlatformDto>,
)

@Serializable
private data class ContestPlatformDto(
    val id: String,
    val displayName: String,
)

@Serializable
private data class ContestListResponseDto(
    val platform: String,
    val items: List<ContestItemDto>,
)

@Serializable
private data class ContestItemDto(
    val remoteId: String,
    val title: String,
    @Serializable(with = InstantSerializer::class)
    val startTime: Instant,
    val durationMinutes: Int,
    val phase: ContestPhase,
    val url: String,
)

@Serializable
private data class ContestDetailResponseDto(
    val platform: String,
    val remoteId: String,
    val title: String,
    @Serializable(with = InstantSerializer::class)
    val startTime: Instant,
    val durationMinutes: Int,
    val phase: ContestPhase,
    val url: String,
    val problems: List<ContestProblemDto>,
    val joinable: Boolean,
)

@Serializable
private data class ContestProblemDto(
    val remoteId: String,
    val index: String? = null,
    val title: String,
)

@Serializable
private data class ReminderListResponseDto(
    val platform: String,
    val items: List<ReminderResponseDto>,
)

@Serializable
private data class ReminderResponseDto(
    val platform: String,
    val remoteId: String,
    @Serializable(with = InstantSerializer::class)
    val remindAt: Instant,
    val notified: Boolean,
)

@Serializable
private data class SubscribeRequestDto(
    val platform: String,
    val remoteId: String,
    @Serializable(with = InstantSerializer::class)
    val startTime: Instant,
    val leadTimeMinutes: Long? = null,
)

@Serializable
private data class DeleteResponseDto(
    val deleted: Boolean,
)

@Serializable
private data class ContestErrorResponseDto(
    val error: String,
    val message: String? = null,
)

private fun ContestSummary.toDto() =
    ContestItemDto(
        remoteId = remoteId,
        title = title,
        startTime = startTime,
        durationMinutes = durationMinutes,
        phase = phase,
        url = url,
    )

private fun com.codequest.core.model.ContestReminder.toDto(
    platform: String,
    remoteId: String,
) = ReminderResponseDto(platform = platform, remoteId = remoteId, remindAt = remindAt, notified = notified)

private fun ContestDetail.toDto(platform: String) =
    ContestDetailResponseDto(
        platform = platform,
        remoteId = summary.remoteId,
        title = summary.title,
        startTime = summary.startTime,
        durationMinutes = summary.durationMinutes,
        phase = summary.phase,
        url = summary.url,
        problems = problems.map { ContestProblemDto(remoteId = it.remoteId, index = it.index, title = it.title) },
        joinable = joinable,
    )
