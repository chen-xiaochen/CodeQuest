package com.codequest.infra.messaging

import com.codequest.core.model.PlatformId
import com.codequest.core.model.RemoteSubmissionRecord
import com.codequest.infra.db.ExposedRemoteSubmissionRepository
import com.codequest.infra.submission.SubmissionHistorySyncService
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import com.codequest.spi.SubmissionHistoryCapable
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 站内提交历史数据端点(1.7.0 F2,infra 层,风格照抄 [RatingApi])。
 *
 * 端点契约(由 [ResourceHttpServer] 挂载):
 * - `POST /api/submissions/sync`(body `{"platformId": "..."}`):经
 *   [SubmissionHistorySyncService] 倒序分页拉取站内提交落库,同步在 HTTP 工作线程
 *   (pooled)跑完再返回 `{synced, total, stoppedBy}`;
 *   body 非法/缺 platformId → 400;平台未注册 → 404;注册平台无
 *   [SubmissionHistoryCapable] 能力 → 400;拉取异常 → 502(已入库部分保留);
 * - `GET /api/submissions/remote?platform=&limit=&offset=`:`remote_submission`
 *   表分页列表(提交时间倒序)+ total;platform 必填,limit 缺省 50(1..100),
 *   offset 缺省 0,非法 → 400。
 */
class SubmissionHistoryApi(
    private val syncService: SubmissionHistorySyncService,
    private val remoteSubmissions: () -> ExposedRemoteSubmissionRepository,
    private val platforms: () -> List<OJPlatform> = OJPlatformRegistry::all,
    private val json: Json = DEFAULT_JSON,
) {
    /** `POST /api/submissions/sync`。 */
    suspend fun syncSubmissions(body: String): ProblemApiResponse {
        val platformId =
            try {
                PlatformId(json.decodeFromString(SyncRequestDto.serializer(), body).platformId.trim())
            } catch (_: SerializationException) {
                return badRequest("request body must be JSON: {platformId}")
            }
        if (platformId.value.isEmpty()) return badRequest("platformId must be non-blank")
        val platform = platforms().firstOrNull { it.id == platformId } ?: return unknownPlatform(platformId.value)
        if (platform !is SubmissionHistoryCapable) return unsupportedPlatform(platformId.value)
        val result =
            try {
                syncService.sync(platformId)
            } catch (error: Exception) {
                // 拉取失败(未登录/限流/网络)502 透传消息;已入库部分保留(增量语义可续)
                return errorResponse(
                    STATUS_BAD_GATEWAY,
                    SyncErrorResponseDto(CODE_SYNC_FAILED, error.message ?: "sync failed"),
                )
            }
        return ok(
            SyncResponseDto(
                synced = result.synced,
                total = result.total,
                stoppedBy = result.stoppedBy.name,
            ),
        )
    }

    /** `GET /api/submissions/remote?platform=&limit=&offset=`。 */
    suspend fun listRemote(query: Map<String, String>): ProblemApiResponse {
        val platformId =
            query["platform"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: return badRequest("missing required query param: platform")
        val limit =
            query["limit"]?.let { text ->
                text.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT } ?: return badRequest("limit must be an integer in 1..$MAX_LIMIT")
            } ?: DEFAULT_LIMIT
        val offset =
            query["offset"]?.let { text ->
                text.toIntOrNull()?.takeIf { it >= 0 } ?: return badRequest("offset must be an integer >= 0")
            } ?: 0
        val repository = remoteSubmissions()
        val items = repository.listPage(PlatformId(platformId), limit = limit, offset = offset)
        val total = repository.countByPlatform(PlatformId(platformId))
        return ok(
            RemoteSubmissionListResponseDto(
                platform = platformId,
                total = total,
                offset = offset,
                limit = limit,
                items = items,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 序列化与错误映射(照抄 RatingApi)
    // ------------------------------------------------------------------

    private fun ok(dto: Any): ProblemApiResponse = ProblemApiResponse(STATUS_OK, json.encodeDto(dto))

    private fun badRequest(message: String): ProblemApiResponse =
        errorResponse(STATUS_BAD_REQUEST, SyncErrorResponseDto(CODE_BAD_REQUEST, message))

    private fun unknownPlatform(id: String): ProblemApiResponse =
        errorResponse(
            STATUS_NOT_FOUND,
            SyncErrorResponseDto(CODE_UNKNOWN_PLATFORM, "no platform registered for id '$id'"),
        )

    private fun unsupportedPlatform(id: String): ProblemApiResponse =
        errorResponse(
            STATUS_BAD_REQUEST,
            SyncErrorResponseDto(CODE_UNSUPPORTED_PLATFORM, "platform '$id' does not support submission history sync"),
        )

    private fun errorResponse(
        status: Int,
        dto: SyncErrorResponseDto,
    ): ProblemApiResponse = ProblemApiResponse(status, json.encodeDto(dto))

    private fun Json.encodeDto(dto: Any): String =
        when (dto) {
            is SyncResponseDto -> encodeToString(SyncResponseDto.serializer(), dto)
            is RemoteSubmissionListResponseDto -> encodeToString(RemoteSubmissionListResponseDto.serializer(), dto)
            is SyncErrorResponseDto -> encodeToString(SyncErrorResponseDto.serializer(), dto)
            else -> throw IllegalArgumentException("unsupported dto: $dto")
        }

    private companion object {
        /** explicitNulls=false + records 直出:platform 传值恒非 null,形态与 SubmissionsApi 一致。 */
        val DEFAULT_JSON =
            Json {
                encodeDefaults = true
                explicitNulls = false
            }

        const val STATUS_OK = 200
        const val STATUS_BAD_REQUEST = 400
        const val STATUS_NOT_FOUND = 404
        const val STATUS_BAD_GATEWAY = 502

        const val CODE_BAD_REQUEST = "bad-request"
        const val CODE_UNKNOWN_PLATFORM = "unknown-platform"
        const val CODE_UNSUPPORTED_PLATFORM = "unsupported-platform"
        const val CODE_SYNC_FAILED = "sync-failed"

        const val DEFAULT_LIMIT = 50

        const val MAX_LIMIT = 100
    }
}

// ---------------------------------------------------------------------------
// 线协议 DTO(kotlinx.serialization;字段即公开 API 契约,勿随意改名)
// ---------------------------------------------------------------------------

@Serializable
private data class SyncRequestDto(
    val platformId: String,
)

@Serializable
private data class SyncResponseDto(
    /** 本次新入库条数。 */
    val synced: Int,
    /** 该平台站内历史总条数(含存量)。 */
    val total: Int,
    /** 停止原因:EXISTING(增量到头)/ LIMIT(页数保护)/ END(自然到底)。 */
    val stoppedBy: String,
)

@Serializable
private data class RemoteSubmissionListResponseDto(
    val platform: String,
    val total: Long,
    val offset: Int,
    val limit: Int,
    /** 站内历史条目(模型字段即表结构口径,直出免映射)。 */
    val items: List<RemoteSubmissionRecord>,
)

/** 与 RatingApi 的 ErrorResponseDto 同构;独立声明避免跨文件私有类型耦合。 */
@Serializable
private data class SyncErrorResponseDto(
    val error: String,
    val message: String? = null,
)
