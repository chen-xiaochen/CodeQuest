package com.codequest.infra.messaging

import com.codequest.infra.rating.RatingService
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.UserContestCapable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 难度评分数据端点(1.7.0 F1,infra 层):评分快照状态、在线刷新与用户竞赛分。
 *
 * 端点契约(由 [ResourceHttpServer] 挂载):
 * - `GET /api/rating/status` → `{source: "db"|"bundled"|"empty", count, updatedAt}`;
 * - `POST /api/rating/refresh` → 拉 zerotrac → upsert DB → 重载内存,
 *   `{count, updatedAt}`;失败透传错误消息(502,不破坏 DB);
 * - `GET /api/rating/user-contest?platform=` → `{rating, attendedContestsCount}`
 *   (UserContestCapable 平台;未登录/无数据/查询失败一律双 null,不抛错——
 *   着色退化绝对分档)。
 *
 * 与 [ProblemApi] 同风格:纯 Kotlin、平台经 SPI 注册表按请求解析。
 */
class RatingApi(
    private val ratings: RatingService,
    private val platforms: () -> List<OJPlatform> = OJPlatformRegistry::all,
    private val json: Json = DEFAULT_JSON,
) {
    suspend fun status(): ProblemApiResponse = ok(ratings.status().toStatusDto())

    suspend fun refresh(): ProblemApiResponse =
        try {
            val snapshot = ratings.refresh()
            ok(RatingRefreshResponseDto(count = snapshot.map.size, updatedAt = snapshot.updatedAt?.toString()))
        } catch (error: Exception) {
            errorResponse(
                STATUS_BAD_GATEWAY,
                RatingErrorResponseDto(CODE_REFRESH_FAILED, error.message ?: "refresh failed"),
            )
        }

    /** 用户竞赛分(评分着色基准):任何失败形态降级双 null,不抛错。 */
    suspend fun userContest(query: Map<String, String>): ProblemApiResponse {
        val id = query["platform"]?.trim().orEmpty()
        if (id.isEmpty()) return badRequest("missing required query param: platform")
        val capable =
            platforms().firstOrNull { it.id.value == id } as? UserContestCapable
                ?: return unknownPlatform(id)
        val ranking = runCatching { capable.fetchUserContestRanking() }.getOrNull()
        return ok(
            UserContestResponseDto(
                rating = ranking?.rating,
                attendedContestsCount = ranking?.attendedContestsCount,
            ),
        )
    }

    private fun RatingService.Snapshot.toStatusDto() =
        RatingStatusResponseDto(
            source = source,
            count = map.size,
            updatedAt = updatedAt?.toString(),
        )

    private fun ok(dto: Any): ProblemApiResponse = ProblemApiResponse(STATUS_OK, json.encodeDto(dto))

    private fun badRequest(message: String): ProblemApiResponse =
        errorResponse(STATUS_BAD_REQUEST, RatingErrorResponseDto(CODE_BAD_REQUEST, message))

    private fun unknownPlatform(id: String): ProblemApiResponse =
        errorResponse(
            STATUS_NOT_FOUND,
            RatingErrorResponseDto(CODE_UNKNOWN_PLATFORM, "no user-contest-capable platform registered for id '$id'"),
        )

    private fun errorResponse(
        status: Int,
        dto: RatingErrorResponseDto,
    ): ProblemApiResponse = ProblemApiResponse(status, json.encodeDto(dto))

    private fun Json.encodeDto(dto: Any): String =
        when (dto) {
            is RatingStatusResponseDto -> encodeToString(RatingStatusResponseDto.serializer(), dto)
            is RatingRefreshResponseDto -> encodeToString(RatingRefreshResponseDto.serializer(), dto)
            is UserContestResponseDto -> encodeToString(UserContestResponseDto.serializer(), dto)
            is RatingErrorResponseDto -> encodeToString(RatingErrorResponseDto.serializer(), dto)
            else -> throw IllegalArgumentException("unsupported dto: $dto")
        }

    private companion object {
        /** explicitNulls 默认开:user-contest 双 null 显式出现在 JSON(前端判空简单)。 */
        val DEFAULT_JSON = Json { encodeDefaults = true }

        const val STATUS_OK = 200
        const val STATUS_BAD_REQUEST = 400
        const val STATUS_NOT_FOUND = 404
        const val STATUS_BAD_GATEWAY = 502

        const val CODE_BAD_REQUEST = "bad-request"
        const val CODE_UNKNOWN_PLATFORM = "unknown-platform"
        const val CODE_REFRESH_FAILED = "refresh-failed"
    }
}

// ---------------------------------------------------------------------------
// 线协议 DTO(kotlinx.serialization;字段即公开 API 契约,勿随意改名)
// ---------------------------------------------------------------------------

@Serializable
private data class RatingStatusResponseDto(
    /** db = DB 快照 / bundled = 内置资源 / empty = 双空。 */
    val source: String,
    val count: Int,
    /** ISO-8601;仅 refresh 后有意义,DB 重启加载为 null。 */
    val updatedAt: String? = null,
)

@Serializable
private data class RatingRefreshResponseDto(
    val count: Int,
    /** ISO-8601;理论非空(解析失败已走错误分支)。 */
    val updatedAt: String? = null,
)

/** 用户竞赛分:未登录/未参赛/查询失败一律双 null(着色退化绝对分档)。 */
@Serializable
private data class UserContestResponseDto(
    val rating: Double? = null,
    val attendedContestsCount: Int? = null,
)

/** 与 ProblemApi 的 ErrorResponseDto 同构;独立声明避免跨文件私有类型耦合。 */
@Serializable
private data class RatingErrorResponseDto(
    val error: String,
    val message: String? = null,
)
