package com.codequest.infra.messaging

import com.codequest.core.model.ProblemId
import com.codequest.core.repository.ProblemRepository
import com.codequest.core.review.ReviewIntervalsSource
import com.codequest.core.review.ReviewItem
import com.codequest.core.review.ReviewService
import com.codequest.infra.db.ExposedReviewRepository
import com.codequest.infra.db.ExposedSettingsKvRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

/**
 * 间隔重复复习端点(1.7.0 F3,infra 层,风格照抄 [SubmissionHistoryApi])。
 *
 * 端点契约(由 [ResourceHttpServer] 挂载):
 * - `GET /api/review/today` → `{overdue:[...], today:[...], upcoming:[...], totalTracked}`,
 *   行 = `{problemId, platformId?, remoteId?, title, difficulty?, nextDate}`(nextDate 为
 *   零点 epoch 秒);元数据联查 problem 表,查不到的行仍返回(title 回退 problemId,
 *   平台/难度缺省);WP64 行补 remoteId(前端行点击经 `nav:openProblem` 开题面需要
 *   platformId+remoteId,而 problemId 是本地主档 id,前端不可拆);
 * - `POST /api/review/problems/{problemId}/add` → 手动加入(冷启动),返回
 *   `{problemId, planDates}`;已有排期幂等返回现有 plan;
 * - `POST /api/review/problems/{problemId}/complete` → 手动完成当日复习,返回
 *   `{problemId, planDates, historyDates}`;未排期 → 404;
 * - `GET /api/review/config` → `{intervals:[...]}`(非法存储回退默认);
 * - `PUT /api/review/config`(body `{"intervals":[1,4,7]}`)→ 校验正整数严格升序,
 *   非法 → 400(附默认值提示);合法落 settings_kv 后回显。
 */
class ReviewApi(
    private val reviews: () -> ExposedReviewRepository,
    private val settings: () -> ExposedSettingsKvRepository,
    private val problems: () -> ProblemRepository,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val json: Json = DEFAULT_JSON,
) {
    /** `GET /api/review/today`。 */
    suspend fun today(): ProblemApiResponse {
        val view = service().todayView()
        val problemStore = problems()

        // ponytail: 逐行 findById(本地库复习量级百级内,N+1 可忽略;题量上万再改批量 IN)
        suspend fun rows(items: List<ReviewItem>) =
            items.map { item ->
                val problem = problemStore.findById(item.problemId)
                ReviewRowDto(
                    problemId = item.problemId.value,
                    platformId = problem?.platformId?.value,
                    remoteId = problem?.remoteId,
                    title = problem?.title ?: item.problemId.value,
                    difficulty = problem?.difficulty?.value,
                    nextDate = item.nextDate,
                )
            }
        return ok(TodayResponseDto(rows(view.overdue), rows(view.today), rows(view.upcoming), view.totalTracked))
    }

    /** `POST /api/review/problems/{problemId}/add`(problemId 已由路由层解码)。 */
    suspend fun add(problemId: String): ProblemApiResponse {
        val id = normalized(problemId) ?: return badRequest("problemId must be non-blank")
        val schedule = service().addManual(ProblemId(id))
        return ok(AddResponseDto(id, schedule.planDates))
    }

    /** `POST /api/review/problems/{problemId}/complete`。 */
    suspend fun complete(problemId: String): ProblemApiResponse {
        val id = normalized(problemId) ?: return badRequest("problemId must be non-blank")
        val schedule =
            service().completeToday(ProblemId(id))
                ?: return errorResponse(
                    STATUS_NOT_FOUND,
                    ReviewErrorResponseDto(CODE_NOT_TRACKED, "problem '$id' has no review schedule"),
                )
        return ok(CompleteResponseDto(id, schedule.planDates, schedule.historyDates))
    }

    /** `GET /api/review/config`。 */
    suspend fun getConfig(): ProblemApiResponse = ok(ConfigResponseDto(service().reviewIntervals()))

    /** `PUT /api/review/config`。 */
    suspend fun putConfig(body: String): ProblemApiResponse {
        val intervals =
            try {
                json.decodeFromString(ConfigRequestDto.serializer(), body).intervals
            } catch (_: SerializationException) {
                return badRequest("request body must be JSON: {intervals:[...]}")
            }
        if (intervals.isEmpty()) return badRequest("intervals must not be empty")
        // 校验单一来源:序列化回原始文本走 core 解析,回退(≠原值)即非法
        val raw = intervals.joinToString(",")
        if (ReviewService.parseIntervals(raw) != intervals) {
            return badRequest("intervals must be positive strictly-ascending integers, e.g. ${ReviewService.DEFAULT_INTERVALS_RAW}")
        }
        settings().put(ReviewService.SETTINGS_KEY, raw)
        return ok(ConfigResponseDto(intervals))
    }

    // ------------------------------------------------------------------
    // 序列化与错误映射(照抄 SubmissionHistoryApi)
    // ------------------------------------------------------------------

    /** 服务无状态,按请求构造(仓库随切库指向新句柄,同 StatisticsApi.service 语义)。 */
    private fun service() =
        ReviewService(
            store = reviews(),
            intervalsSource = ReviewIntervalsSource { settings().get(ReviewService.SETTINGS_KEY) },
            clock = clock,
            zone = zone,
        )

    private fun normalized(problemId: String): String? = problemId.trim().takeIf { it.isNotEmpty() }

    private fun ok(dto: Any): ProblemApiResponse = ProblemApiResponse(STATUS_OK, json.encodeDto(dto))

    private fun badRequest(message: String): ProblemApiResponse =
        errorResponse(STATUS_BAD_REQUEST, ReviewErrorResponseDto(CODE_BAD_REQUEST, message))

    private fun errorResponse(
        status: Int,
        dto: ReviewErrorResponseDto,
    ): ProblemApiResponse = ProblemApiResponse(status, json.encodeDto(dto))

    private fun Json.encodeDto(dto: Any): String =
        when (dto) {
            is TodayResponseDto -> encodeToString(TodayResponseDto.serializer(), dto)
            is AddResponseDto -> encodeToString(AddResponseDto.serializer(), dto)
            is CompleteResponseDto -> encodeToString(CompleteResponseDto.serializer(), dto)
            is ConfigResponseDto -> encodeToString(ConfigResponseDto.serializer(), dto)
            is ReviewErrorResponseDto -> encodeToString(ReviewErrorResponseDto.serializer(), dto)
            else -> throw IllegalArgumentException("unsupported dto: $dto")
        }

    private companion object {
        /** explicitNulls=false:problem 行缺失时 platformId/difficulty 直接不出现在 JSON。 */
        val DEFAULT_JSON =
            Json {
                encodeDefaults = true
                explicitNulls = false
            }

        const val STATUS_OK = 200
        const val STATUS_BAD_REQUEST = 400
        const val STATUS_NOT_FOUND = 404

        const val CODE_BAD_REQUEST = "bad-request"
        const val CODE_NOT_TRACKED = "not-tracked"
    }
}

// ---------------------------------------------------------------------------
// 线协议 DTO(kotlinx.serialization;字段即公开 API 契约,勿随意改名)
// ---------------------------------------------------------------------------

@Serializable
private data class ReviewRowDto(
    val problemId: String,
    val platformId: String? = null,
    /** 平台题号(联查缺失时缺省;WP64 前端开题面用)。 */
    val remoteId: String? = null,
    val title: String,
    val difficulty: String? = null,
    /** 下次到期日(epoch 秒,零点对齐)。 */
    val nextDate: Long,
)

@Serializable
private data class TodayResponseDto(
    val overdue: List<ReviewRowDto>,
    val today: List<ReviewRowDto>,
    val upcoming: List<ReviewRowDto>,
    /** 追踪中的题目总数(含已完成排期的历史行)。 */
    val totalTracked: Int,
)

@Serializable
private data class AddResponseDto(
    val problemId: String,
    /** 排期日(epoch 秒,零点对齐,升序);已排期时为现有 plan(幂等)。 */
    val planDates: List<Long>,
)

@Serializable
private data class CompleteResponseDto(
    val problemId: String,
    /** 剩余计划日(空 = 排期自然结束)。 */
    val planDates: List<Long>,
    /** 已完成复习日。 */
    val historyDates: List<Long>,
)

@Serializable
private data class ConfigResponseDto(
    val intervals: List<Long>,
)

@Serializable
private data class ConfigRequestDto(
    val intervals: List<Long>,
)

/** 与 SubmissionHistoryApi 的 SyncErrorResponseDto 同构;独立声明避免跨文件私有类型耦合。 */
@Serializable
private data class ReviewErrorResponseDto(
    val error: String,
    val message: String? = null,
)
