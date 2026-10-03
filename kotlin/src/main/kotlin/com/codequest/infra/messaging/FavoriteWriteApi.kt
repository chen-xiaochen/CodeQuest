package com.codequest.infra.messaging

import com.codequest.core.model.AntiBotChallengeException
import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.OJException
import com.codequest.core.model.RateLimitedException
import com.codequest.spi.FavoriteWriteCapable
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.ProblemHintsCapable
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.logging.Logger

/**
 * 收藏写与题目提示端点(1.9.0 F1/F2,infra 层,风格照 [SolutionsApi]):
 * 经 SPI(OJPlatformRegistry)按能力探测解析平台,纯透传不落库。
 *
 * 端点契约(由 [ResourceHttpServer] 挂载):
 * - `POST /api/favorite/toggle`(body `{platformId, questionId, add}`):
 *   成功 `{ok:true}`;未登录(AuthExpired)401 auth-expired(前端提示重登),
 *   站方拒绝(ok=false/协议失败)502(前端乐观切换回滚),无能力平台 404;
 * - `GET /api/problem/{remoteId}/hints?platform=`:`{hints:[...]}`;
 *   提示为附属能力,SPI 层失败降级空表不抛(200 `{hints:[]}`,前端「无提示」
 *   兜底);无能力平台 404(照 /api/contests 探测惯例)。
 *
 * 错误映射语义与 [SolutionsApi] 一致(AuthExpired 401 / RateLimited 429 /
 * AntiBot 403 / 其余 OJException 502 / 参数问题 400 / 未知平台 404)。
 */
class FavoriteWriteApi(
    private val platforms: () -> List<OJPlatform> = OJPlatformRegistry::all,
    private val json: Json = DEFAULT_JSON,
) {
    /** `POST /api/favorite/toggle`:加入(add=true)/移除默认收藏夹。 */
    suspend fun toggle(body: String): ProblemApiResponse {
        val request =
            try {
                json.decodeFromString(FavoriteToggleRequestDto.serializer(), body)
            } catch (_: SerializationException) {
                return badRequest("request body must be JSON: {platformId, questionId, add}")
            }
        if (request.platformId.isBlank() || request.questionId.isBlank()) {
            return badRequest("platformId and questionId must be non-blank")
        }
        val id = request.platformId.trim()
        val capable =
            platforms().firstOrNull { it.id.value == id } as? FavoriteWriteCapable
                ?: return unknownPlatform(id)
        try {
            if (request.add) {
                capable.addToDefaultFavorite(request.questionId.trim())
            } else {
                capable.removeFromDefaultFavorite(request.questionId.trim())
            }
        } catch (error: Throwable) {
            return error.toErrorResponse()
        }
        return ok(FavoriteToggleResponseDto(ok = true))
    }

    /** `GET /api/problem/{remoteId}/hints`:提示列表(SPI 失败降级空表不抛)。 */
    suspend fun hints(
        remoteId: String,
        query: Map<String, String>,
    ): ProblemApiResponse {
        val id = query["platform"]?.trim().orEmpty()
        if (id.isEmpty()) return badRequest("missing required query param: platform")
        if (remoteId.isEmpty()) return badRequest("missing remoteId path segment")
        val capable =
            platforms().firstOrNull { it.id.value == id } as? ProblemHintsCapable
                ?: return unknownPlatform(id)
        val hints = runCatching { capable.fetchHints(remoteId) }.getOrDefault(emptyList())
        return ok(HintsResponseDto(hints = hints))
    }

    private fun ok(dto: Any): ProblemApiResponse = ProblemApiResponse(STATUS_OK, json.encodeDto(dto))

    private fun badRequest(message: String): ProblemApiResponse =
        errorResponse(STATUS_BAD_REQUEST, FavoriteWriteErrorDto(CODE_BAD_REQUEST, message))

    private fun unknownPlatform(id: String): ProblemApiResponse =
        errorResponse(
            STATUS_NOT_FOUND,
            FavoriteWriteErrorDto(CODE_UNKNOWN_PLATFORM, "no favorite-write-capable platform registered for id '$id'"),
        )

    private fun errorResponse(
        status: Int,
        dto: FavoriteWriteErrorDto,
        headers: Map<String, String> = emptyMap(),
    ): ProblemApiResponse {
        LOGGER.fine { "favorite-write api error ${dto.error}: ${dto.message}" }
        return ProblemApiResponse(status, json.encodeDto(dto), headers)
    }

    private fun Throwable.toErrorResponse(): ProblemApiResponse =
        when (this) {
            is AuthExpiredException -> {
                errorResponse(STATUS_UNAUTHORIZED, FavoriteWriteErrorDto(CODE_AUTH_EXPIRED, message))
            }

            is RateLimitedException -> {
                errorResponse(
                    STATUS_RATE_LIMITED,
                    FavoriteWriteErrorDto(CODE_RATE_LIMITED, message),
                    headers = mapOf("Retry-After" to retryAfterSeconds(retryAfterMs).toString()),
                )
            }

            is AntiBotChallengeException -> {
                errorResponse(STATUS_FORBIDDEN, FavoriteWriteErrorDto(CODE_ANTI_BOT, message))
            }

            is OJException -> {
                errorResponse(STATUS_BAD_GATEWAY, FavoriteWriteErrorDto(CODE_OJ_ERROR, message))
            }

            else -> {
                LOGGER.warning("favorite-write api unexpected error: $this")
                errorResponse(STATUS_INTERNAL_ERROR, FavoriteWriteErrorDto(CODE_INTERNAL, "unexpected server error"))
            }
        }

    private fun Json.encodeDto(dto: Any): String =
        when (dto) {
            is FavoriteToggleRequestDto -> encodeToString(FavoriteToggleRequestDto.serializer(), dto)
            is FavoriteToggleResponseDto -> encodeToString(FavoriteToggleResponseDto.serializer(), dto)
            is HintsResponseDto -> encodeToString(HintsResponseDto.serializer(), dto)
            is FavoriteWriteErrorDto -> encodeToString(FavoriteWriteErrorDto.serializer(), dto)
            else -> throw IllegalArgumentException("unsupported dto: $dto")
        }

    /** 毫秒 → 秒向上取整(Retry-After 头,至少 1 秒;与 SolutionsApi 同语义)。 */
    private fun retryAfterSeconds(retryAfterMs: Long?): Long {
        val millis = retryAfterMs ?: DEFAULT_RETRY_AFTER_SECONDS * MILLIS_PER_SECOND
        return ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(DEFAULT_RETRY_AFTER_SECONDS)
    }

    private companion object {
        private val LOGGER = Logger.getLogger(FavoriteWriteApi::class.java.name)

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

/** toggle 请求体:platformId 平台 id、questionId 数字主档 id、add true=加入/false=移除。 */
@Serializable
private data class FavoriteToggleRequestDto(
    val platformId: String,
    val questionId: String,
    val add: Boolean,
)

/** toggle 成功响应。 */
@Serializable
private data class FavoriteToggleResponseDto(
    val ok: Boolean,
)

/** 提示列表响应(降级态空数组,200 不报错)。 */
@Serializable
private data class HintsResponseDto(
    val hints: List<String>,
)

@Serializable
private data class FavoriteWriteErrorDto(
    val error: String,
    val message: String? = null,
)
