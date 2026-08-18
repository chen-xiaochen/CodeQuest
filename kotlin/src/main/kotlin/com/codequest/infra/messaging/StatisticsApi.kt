package com.codequest.infra.messaging

import com.codequest.core.StatisticsData
import com.codequest.core.StatisticsService
import com.codequest.core.SubmissionQuery
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Submission
import com.codequest.infra.db.ExposedSubmissionRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

/**
 * 刷题统计数据端点逻辑(WP29,infra 层,风格照抄 [SubmissionsApi]):
 * 本地库 submission 表经 [StatisticsService] 聚合,口径与 submissions 页一致。
 *
 * 端点契约(由 [ResourceHttpServer] 挂载):
 * - `GET /api/stats?platform=&days=`:summary(全量口径,仅平台过滤)+
 *   heatmap(今天往前 days 天含今天的完整日历,零提交日占位)+
 *   trend(今天往前 min(days, 30) 天);
 * - `platform` 缺省 = 全部平台;**不是**注册表校验——纯 DB 列过滤,
 *   未知平台如实返回零值(与 submissions 列表同语义,不 404);
 * - `days` 缺省 365(近一年),须为 1..731 的整数,否则 400;
 * - 日期为本地时区 ISO 字符串(yyyy-MM-dd);AC 率为 0..1 小数。
 *
 * 数据源经 [ExposedSubmissionRepository.listPage] 分页拉全量(复用既有查询
 * 能力,不改仓库),适配成 core 端口 [SubmissionQuery];本地库量级下可接受。
 */
class StatisticsApi(
    private val submissions: () -> ExposedSubmissionRepository,
    private val json: Json = DEFAULT_JSON,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** `GET /api/stats?platform=&days=`。 */
    suspend fun statistics(query: Map<String, String>): ProblemApiResponse {
        val platformId = query["platform"]?.trim()?.takeIf { it.isNotEmpty() }?.let(::PlatformId)
        val heatmapDays =
            query["days"]?.let { text ->
                text.toIntOrNull()?.takeIf { it in 1..MAX_DAYS }
                    ?: return badRequest("days must be an integer in 1..$MAX_DAYS")
            } ?: StatisticsService.DEFAULT_HEATMAP_DAYS
        val trendDays = heatmapDays.coerceAtMost(StatisticsService.DEFAULT_TREND_DAYS)
        val data = service().statistics(platformId, heatmapDays, trendDays)
        return ok(data.toDto(platformId?.value))
    }

    /** 服务无状态,按请求构造(仓库随切库指向新句柄,同 SubmissionsApi.store 语义)。 */
    private fun service() = StatisticsService(submissions = SubmissionQuery(::listAll), clock = clock, zone = zone)

    /** 分页拉全量提交(listPage 排序确定,offset 游标推进;不足一页即到底)。 */
    private suspend fun listAll(): List<Submission> {
        val repository = submissions()
        val rows = ArrayList<Submission>()
        while (true) {
            val page = repository.listPage(challengeId = null, limit = FETCH_PAGE, offset = rows.size)
            rows.addAll(page)
            if (page.size < FETCH_PAGE) return rows
        }
    }

    // ------------------------------------------------------------------
    // 序列化与错误映射(照抄 SubmissionsApi)
    // ------------------------------------------------------------------

    private fun StatisticsData.toDto(platform: String?) =
        StatsResponseDto(
            platform = platform,
            days = heatmap.size,
            summary =
                StatsSummaryDto(
                    totalSubmissions = summary.totalSubmissions,
                    acceptedSubmissions = summary.acceptedSubmissions,
                    acceptanceRate = summary.acceptanceRate,
                    currentStreakDays = summary.currentStreakDays,
                    longestStreakDays = summary.longestStreakDays,
                ),
            heatmap = heatmap.map { DailyCountDto(date = it.date.toString(), count = it.count) },
            trend = trend.map { DailyCountDto(date = it.date.toString(), count = it.count) },
        )

    private fun ok(dto: Any): ProblemApiResponse = ProblemApiResponse(STATUS_OK, json.encodeDto(dto))

    private fun badRequest(message: String): ProblemApiResponse =
        ProblemApiResponse(
            STATUS_BAD_REQUEST,
            json.encodeDto(StatsErrorResponseDto("bad-request", message)),
        )

    private fun Json.encodeDto(dto: Any): String =
        when (dto) {
            is StatsResponseDto -> encodeToString(StatsResponseDto.serializer(), dto)
            is StatsErrorResponseDto -> encodeToString(StatsErrorResponseDto.serializer(), dto)
            else -> throw IllegalArgumentException("unsupported dto: $dto")
        }

    private companion object {
        /** explicitNulls=false:null 字段(如 platform)直接省略,前端按缺省回退。 */
        val DEFAULT_JSON =
            Json {
                encodeDefaults = true
                explicitNulls = false
            }

        /** 全量拉取的分页步长(本地库量级下一两页到底)。 */
        const val FETCH_PAGE = 1000

        /** days 上限,与 core MAX_WINDOW_DAYS 对齐。 */
        const val MAX_DAYS = 731

        const val STATUS_OK = 200

        const val STATUS_BAD_REQUEST = 400
    }
}

// ---------------------------------------------------------------------------
// 线协议 DTO(kotlinx.serialization;字段即公开 API 契约,勿随意改名)
// ---------------------------------------------------------------------------

@Serializable
private data class StatsResponseDto(
    /** 过滤的平台 id;null = 全部平台。 */
    val platform: String? = null,
    /** 热力图窗口天数(响应实际采用的值,缺省 365)。 */
    val days: Int,
    val summary: StatsSummaryDto,
    val heatmap: List<DailyCountDto>,
    val trend: List<DailyCountDto>,
)

@Serializable
private data class StatsSummaryDto(
    val totalSubmissions: Int,
    val acceptedSubmissions: Int,
    /** AC 率,0..1 小数。 */
    val acceptanceRate: Double,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
)

@Serializable
private data class DailyCountDto(
    /** 本地日期 ISO 字符串(yyyy-MM-dd)。 */
    val date: String,
    val count: Int,
)

/** 与 SubmissionsApi 的 ErrorResponseDto 同构;独立声明避免跨文件私有类型耦合。 */
@Serializable
private data class StatsErrorResponseDto(
    val error: String,
    val message: String? = null,
)
