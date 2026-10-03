package com.codequest.infra.messaging

import com.codequest.spi.DailyQuestion
import com.codequest.spi.DailyQuestionCapable
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * 每日一题数据端点(1.8.0 F1,infra 层,风格照 [RatingApi])。
 *
 * 端点契约(由 [ResourceHttpServer] 挂载):
 * - `GET /api/daily-question?platform=` → `{date, frontendQuestionId, title,
 *   titleSlug, difficulty, tags:[{name,slug}]}`;拉取失败/降级 null 时全字段
 *   null(200,照 /api/rating/user-contest 双 null 先例,前端静默隐藏卡片);
 * - 无能力平台 404(照 /api/contests 探测惯例)。
 *
 * **当日进程内缓存**:`volatile (platformId, 本地日期, payload)`,同平台同
 * 本地日命中直接回放已编码 payload(站点当日不变);跨日首次请求重拉。
 * 降级 null 不缓存(失败态每次重试)。不落库、不持久化(spec YAGNI)。
 */
class DailyQuestionApi(
    private val platforms: () -> List<OJPlatform> = OJPlatformRegistry::all,
    private val json: Json = DEFAULT_JSON,
    private val today: () -> LocalDate = LocalDate::now,
) {
    /** 当日缓存条目:平台 id + 缓存写入日本地日期 + 已编码 payload。 */
    private data class Cached(
        val platformId: String,
        val day: LocalDate,
        val payload: String,
    )

    @Volatile
    private var cache: Cached? = null

    suspend fun dailyQuestion(query: Map<String, String>): ProblemApiResponse {
        val id = query["platform"]?.trim().orEmpty()
        if (id.isEmpty()) return badRequest("missing required query param: platform")
        val capable =
            platforms().firstOrNull { it.id.value == id } as? DailyQuestionCapable
                ?: return unknownPlatform(id)
        val day = today()
        cache?.let { cached ->
            if (cached.platformId == id && cached.day == day) {
                return ProblemApiResponse(STATUS_OK, cached.payload)
            }
        }
        val question = runCatching { capable.fetchDailyQuestion() }.getOrNull()
        val payload =
            if (question == null) {
                json.encodeToString(DailyQuestionResponseDto.serializer(), DailyQuestionResponseDto())
            } else {
                json.encodeToString(DailyQuestionResponseDto.serializer(), question.toDto())
            }
        if (question != null) cache = Cached(platformId = id, day = day, payload = payload)
        return ProblemApiResponse(STATUS_OK, payload)
    }

    private fun DailyQuestion.toDto() =
        DailyQuestionResponseDto(
            date = date,
            frontendQuestionId = frontendQuestionId,
            title = title,
            titleSlug = titleSlug,
            difficulty = difficulty,
            tags = tags.map { DailyQuestionTagDto(name = it.name, slug = it.slug) },
        )

    private fun badRequest(message: String): ProblemApiResponse =
        ProblemApiResponse(STATUS_BAD_REQUEST, """{"error":"bad-request","message":"$message"}""")

    private fun unknownPlatform(id: String): ProblemApiResponse =
        ProblemApiResponse(
            STATUS_NOT_FOUND,
            """{"error":"unknown-platform","message":"no daily-question-capable platform registered for id '$id'"}""",
        )

    private companion object {
        /** explicitNulls 开:降级态全 null 显式出现在 JSON(前端判空简单)。 */
        val DEFAULT_JSON = Json { encodeDefaults = true }

        const val STATUS_OK = 200
        const val STATUS_BAD_REQUEST = 400
        const val STATUS_NOT_FOUND = 404
    }
}

// ---------------------------------------------------------------------------
// 线协议 DTO(kotlinx.serialization;字段即公开 API 契约,勿随意改名)
// ---------------------------------------------------------------------------

/** 每日一题响应:降级态全字段 null(照 /api/rating/user-contest 双 null 先例)。 */
@Serializable
private data class DailyQuestionResponseDto(
    /** yyyy-MM-dd;降级 null。 */
    val date: String? = null,
    val frontendQuestionId: String? = null,
    val title: String? = null,
    /** 与题库 remoteId 同源,题面链路直达;降级 null。 */
    val titleSlug: String? = null,
    val difficulty: String? = null,
    val tags: List<DailyQuestionTagDto> = emptyList(),
)

@Serializable
private data class DailyQuestionTagDto(
    val name: String,
    val slug: String,
)
