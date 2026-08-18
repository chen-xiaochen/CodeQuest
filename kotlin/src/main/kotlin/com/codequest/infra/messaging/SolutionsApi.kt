package com.codequest.infra.messaging

import com.codequest.core.model.AntiBotChallengeException
import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.OJException
import com.codequest.core.model.RateLimitedException
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.SolutionViewCapable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.logging.Logger

/**
 * 题解数据 REST 端点逻辑(WP26,infra 层):SolutionViewCapable 平台的题解
 * 引用列表经 SPI(OJPlatformRegistry)拉取,纯透传不落库(正文拉取与缓存是 M5)。
 * 照 [ProblemApi] 模式(平台来源以 lambda 注入,按请求解析,纯 Kotlin 可测)。
 *
 * 端点契约(由 [ResourceHttpServer] 挂载):
 * - `GET /api/problem/{remoteId}/solutions?platform=`:题解引用列表
 *   `{platform, remoteId, items:[{id,title,url}]}`;
 * - 领域异常 → HTTP 状态 + JSON 错误体(AuthExpired 401 / RateLimited 429 /
 *   AntiBot 403 / 其余 OJException 502 / 参数问题 400 / 未知平台 404),
 *   状态码与错误码语义与 ProblemApi 一致。
 */
class SolutionsApi(
    private val platforms: () -> List<OJPlatform> = OJPlatformRegistry::all,
    private val json: Json = DEFAULT_JSON,
) {
    /** `/api/problem/{remoteId}/solutions`:经 SPI fetchSolutions 拉取引用列表。 */
    suspend fun listSolutions(
        remoteId: String,
        query: Map<String, String>,
    ): ProblemApiResponse {
        val id = query["platform"]?.trim().orEmpty()
        if (id.isEmpty()) return badRequest("missing required query param: platform")
        val platform =
            platforms()
                .firstOrNull { candidate -> candidate.id.value == id && candidate is SolutionViewCapable }
                ?: return errorResponse(
                    STATUS_NOT_FOUND,
                    SolutionsErrorDto(error = CODE_UNKNOWN_PLATFORM, message = "no solution-capable platform registered for id '$id'"),
                )
        val capable = platform as SolutionViewCapable
        val refs =
            try {
                capable.fetchSolutions(remoteId)
            } catch (error: Throwable) {
                return error.toErrorResponse()
            }
        return ok(
            SolutionsResponseDto(
                platform = platform.id.value,
                remoteId = remoteId,
                items = refs.map { ref -> SolutionItemDto(id = ref.id, title = ref.title, url = ref.url) },
            ),
        )
    }

    // ------------------------------------------------------------------
    // 序列化与错误映射(语义与 ProblemApi 对齐;SolutionsApi 无本地存储,
    // 不复用其私有实现,各自独立演化)
    // ------------------------------------------------------------------

    private fun ok(dto: Any): ProblemApiResponse = ProblemApiResponse(STATUS_OK, json.encodeDto(dto))

    private fun badRequest(message: String): ProblemApiResponse =
        errorResponse(STATUS_BAD_REQUEST, SolutionsErrorDto(CODE_BAD_REQUEST, message))

    private fun errorResponse(
        status: Int,
        dto: SolutionsErrorDto,
        headers: Map<String, String> = emptyMap(),
    ): ProblemApiResponse {
        LOGGER.fine { "solutions api error ${dto.error}: ${dto.message}" }
        return ProblemApiResponse(status, json.encodeDto(dto), headers)
    }

    private fun Throwable.toErrorResponse(): ProblemApiResponse =
        when (this) {
            is AuthExpiredException -> {
                errorResponse(STATUS_UNAUTHORIZED, SolutionsErrorDto(CODE_AUTH_EXPIRED, message))
            }

            is RateLimitedException -> {
                errorResponse(
                    STATUS_RATE_LIMITED,
                    SolutionsErrorDto(CODE_RATE_LIMITED, message),
                    headers = mapOf("Retry-After" to retryAfterSeconds(retryAfterMs).toString()),
                )
            }

            is AntiBotChallengeException -> {
                errorResponse(STATUS_FORBIDDEN, SolutionsErrorDto(CODE_ANTI_BOT, message))
            }

            is OJException -> {
                errorResponse(STATUS_BAD_GATEWAY, SolutionsErrorDto(CODE_OJ_ERROR, message))
            }

            else -> {
                LOGGER.warning("solutions api unexpected error: $this")
                errorResponse(STATUS_INTERNAL_ERROR, SolutionsErrorDto(CODE_INTERNAL, "unexpected server error"))
            }
        }

    private fun Json.encodeDto(dto: Any): String =
        when (dto) {
            is SolutionsResponseDto -> encodeToString(SolutionsResponseDto.serializer(), dto)
            is SolutionsErrorDto -> encodeToString(SolutionsErrorDto.serializer(), dto)
            else -> throw IllegalArgumentException("unsupported dto: $dto")
        }

    /** 毫秒 → 秒向上取整(Retry-After 头,至少 1 秒;与 ProblemApi 同语义)。 */
    private fun retryAfterSeconds(retryAfterMs: Long?): Long {
        val millis = retryAfterMs ?: DEFAULT_RETRY_AFTER_SECONDS * MILLIS_PER_SECOND
        return ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(DEFAULT_RETRY_AFTER_SECONDS)
    }

    private companion object {
        private val LOGGER = Logger.getLogger(SolutionsApi::class.java.name)

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
private data class SolutionsResponseDto(
    val platform: String,
    val remoteId: String,
    val items: List<SolutionItemDto>,
)

@Serializable
private data class SolutionItemDto(
    val id: String,
    val title: String,
    val url: String,
)

@Serializable
private data class SolutionsErrorDto(
    val error: String,
    val message: String? = null,
)
