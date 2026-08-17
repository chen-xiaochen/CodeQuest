package com.codequest.platform.codeforces

import com.codequest.core.model.AntiBotChallengeException
import com.codequest.core.model.OJException
import com.codequest.spi.OJTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/**
 * CodeForces API 封装(WP24):REST / HTML 端点到 Kotlin 数据的纯映射层。
 *
 * 端点与协议事实沿用旧 Scala 实现(CodeForcesApi.scala):
 * - 题库:`GET /api/problemset.problems` + `?problemsetName=acmsguru` 两次调用合并,
 *   acmsguru 题目的 null contestId 统一修正为 [ACMSGURU_CONTEST_ID](99999);
 * - 竞赛列表:`GET /api/contest.list?gym=false`(公开,无需鉴权);
 * - 竞赛题目/排名:`GET /api/contest.standings?contestId=`(匿名仅支持裸 contestId,
 *   不支持 handles 过滤,返回全量行——排名不进 SPI 的 WP22 结论依据);
 * - 题面页:`GET /problemset/problem/{cid}/{idx}`(HTML);
 * - 登录态:`GET /settings/general`(HTML `div.userbox` 存在即已登录);
 * - 提交:表单 POST `/problemset/submit`(字段见 [submitAnswer]);
 * - 提交状态:表单 POST `/data/submitSource?rv=<9位hex>`,响应 JSON 取 `verdict`
 *   (缺失 = 评测未开始,旧实现保持 Processing 的事实)。
 *
 * CF REST 信封:`{"status":"OK","result":...}` / `{"status":"FAILED","comment":...}`
 * (HTTP 200)——本层统一校验,FAILED 抛 [OJException]。提交/轮询的表单 POST 走
 * `OJTransport.postForm`(WP25 自本包 FormPostCapable 探测接口提升而来,该接口已删除)。
 */
class CodeForcesApi(
    private val transport: OJTransport,
) {
    private val json = Json

    // -------------------------------------------------------------------
    // 公开 REST(problemset / contest)
    // -------------------------------------------------------------------

    /** 题库条目(problemset.problems 单条;problemStatistics 不消费——SPI 无 solvedCount 字段)。 */
    data class CfProblem(
        /** null 仅出现在主响应的异常条目;acmsguru 已修正为 [ACMSGURU_CONTEST_ID]。 */
        val contestId: Long?,
        val problemsetName: String?,
        val index: String,
        val name: String,
        val points: Double?,
        val rating: Int?,
        val tags: List<String>,
    ) {
        /** 平台侧题目标识:`contestId + index`(如 "1927A")。 */
        fun remoteId(): String = "${contestId?.toString().orEmpty()}$index"
    }

    /** contest.list 单条(字段与旧 CodeForcesContest 一致,verified against live response)。 */
    data class CfContest(
        val id: Long,
        val name: String,
        val type: String,
        val phase: String,
        val frozen: Boolean,
        val durationSeconds: Long,
        val startTimeSeconds: Long,
        val relativeTimeSeconds: Long,
    )

    /** contest.standings 行(排名辅助查询;rank 可能为 null,如赛后未排完)。 */
    data class CfStandingsRow(
        val rank: Int?,
        val handle: String,
        val points: Double,
        val penalty: Int,
    )

    /** contest.standings 结果(题目列表 + 排名行;**排名不进 SPI**,平台包内辅助查询)。 */
    data class CfStandings(
        val contest: CfContest,
        val problems: List<CfProblem>,
        val rows: List<CfStandingsRow>,
    )

    /** 全量题库(两次调用合并;~万级条目,单次响应数 MB,由调用方决定缓存/过滤策略)。 */
    suspend fun fetchProblemset(): List<CfProblem> {
        val main = parseProblemsetResult(apiGet(CODEFORCES_PROBLEMSET_URL))
        val acmsguru =
            parseProblemsetResult(apiGet(CODEFORCES_PROBLEMSET_ACMSGURU_URL))
                .map { problem -> problem.copy(contestId = ACMSGURU_CONTEST_ID) }
        return main + acmsguru
    }

    /** 全量竞赛列表(upcoming + finished,gym=false)。 */
    suspend fun contestList(): List<CfContest> {
        val result = apiGet(CODEFORCES_CONTEST_LIST_URL)
        return result.jsonArray.map { parseContest(it.jsonObject) }
    }

    /** 单场竞赛 standings(匿名全量;未开始的比赛 API 报 FAILED → [OJException])。 */
    suspend fun contestStandings(contestId: Long): CfStandings {
        val result = apiGet(codeForcesStandingsUrl(contestId)).jsonObject
        val contest = parseContest(result["contest"]?.jsonObject ?: throw OJException("standings 响应缺少 contest 字段"))
        val problems =
            result["problems"]?.jsonArray.orEmpty().map { element ->
                parseProblem(element.jsonObject)
            }
        val rows =
            result["rows"]?.jsonArray.orEmpty().map { element ->
                val row = element.jsonObject
                CfStandingsRow(
                    rank = row.intOrNull("rank"),
                    handle =
                        row["party"]
                            ?.jsonObject
                            ?.get("members")
                            ?.jsonArray
                            ?.firstOrNull()
                            ?.jsonObject
                            ?.get("handle")
                            ?.jsonPrimitive
                            ?.content
                            ?: "",
                    points = row["points"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: DEFAULT_ROW_POINTS,
                    penalty = row.intOrNull("penalty") ?: 0,
                )
            }
        return CfStandings(contest = contest, problems = problems, rows = rows)
    }

    // -------------------------------------------------------------------
    // HTML 页面(题面 / 登录态)
    // -------------------------------------------------------------------

    /** 题面页原文(acmsguru 分支 URL 由 [codeForcesProblemPageUrl] 处理)。 */
    suspend fun fetchProblemPageHtml(
        contestId: Long,
        index: String,
    ): String = transport.get(CODEFORCES_PLATFORM_ID, codeForcesProblemPageUrl(contestId, index))

    /** settings 页原文(登录态与用户名提取的解析在调用方,见 [CodeForcesHtml])。 */
    suspend fun fetchSettingsPageHtml(): String = transport.get(CODEFORCES_PLATFORM_ID, CODEFORCES_SETTINGS_URL)

    // -------------------------------------------------------------------
    // 提交与轮询(表单 POST,旧实现逐字事实)
    // -------------------------------------------------------------------

    /** Turnstile 弹窗回传的四元组(`csrf,turnstile,ftaa,bfaa`)。 */
    data class SubmitTokens(
        val csrfToken: String,
        val turnstileToken: String,
        val ftaa: String,
        val bfaa: String,
    ) {
        companion object {
            /** 弹窗结果串的固定段数(csrf,turnstile,ftaa,bfaa)。 */
            private const val TOKEN_ARITY = 4

            private const val TOKEN_CSRF = 0

            private const val TOKEN_TURNSTILE = 1

            private const val TOKEN_FTAA = 2

            private const val TOKEN_BFAA = 3

            /** 解析弹窗结果串;格式不符返回 null(调用方按人机验证失败处理)。 */
            fun parse(raw: String): SubmitTokens? {
                val parts = raw.split(',')
                if (parts.size != TOKEN_ARITY || parts.any { it.isBlank() }) return null
                return SubmitTokens(
                    csrfToken = parts[TOKEN_CSRF],
                    turnstileToken = parts[TOKEN_TURNSTILE],
                    ftaa = parts[TOKEN_FTAA],
                    bfaa = parts[TOKEN_BFAA],
                )
            }
        }
    }

    /**
     * 提交代码,返回平台侧提交 id。
     *
     * 表单字段与旧实现 `UrlForm` 逐字一致(turnstile 双字段、action、_tta=460 等);
     * 响应为 HTML:错误 span(见 [CodeForcesHtml.extractSubmitError])抛 [OJException],
     * 成功从 `tr[data-submission-id]` 第一行取提交 id。
     */
    suspend fun submitAnswer(
        contestId: Long,
        index: String,
        programTypeId: String,
        source: String,
        tokens: SubmitTokens,
    ): String {
        val fields =
            listOf(
                "turnstileToken" to tokens.turnstileToken,
                "cf-turnstile-response" to tokens.turnstileToken,
                "csrf_token" to tokens.csrfToken,
                "ftaa" to tokens.ftaa,
                "bfaa" to tokens.bfaa,
                "action" to "submitSolutionFormSubmitted",
                "contestId" to contestId.toString(),
                "submittedProblemIndex" to index,
                "programTypeId" to programTypeId,
                "source" to source,
                "tabSize" to "4",
                "sourceFile" to "",
                "_tta" to "460",
            )
        val html = postForm(codeForcesSubmitUrl(contestId), fields)
        CodeForcesHtml.extractSubmitError(html)?.let { error ->
            throw OJException("CodeForces 拒绝提交: $error")
        }
        return CodeForcesHtml.extractFirstSubmissionId(html)
            ?: throw OJException("CodeForces 提交响应中未找到提交记录(登录态失效或页面结构变化)")
    }

    /**
     * 查询提交判定:POST `/data/submitSource`,响应 JSON 的 `verdict`
     * (如 "Running on test 3" / "Accepted" / "Wrong answer on test 5")。
     * **verdict 缺失 = 评测未开始**(旧实现保持 Processing),返回 null。
     */
    suspend fun fetchSubmissionVerdict(
        submissionId: String,
        contestId: Long,
        csrfToken: String,
    ): String? {
        val fields =
            listOf(
                "submissionId" to submissionId,
                "csrf_token" to csrfToken,
            )
        val headers =
            mapOf(
                "x-csrf-token" to csrfToken,
                "Referer" to codeForcesMySubmissionsUrl(contestId),
            )
        val raw = postForm(codeForcesSubmitSourceUrl(randomRv()), fields, headers)
        val body = json.parseToJsonElement(raw).jsonObject
        val verdict = body["verdict"] ?: return null
        if (verdict is JsonNull) return null
        return verdict.jsonPrimitive.content
    }

    // -------------------------------------------------------------------
    // 内部:信封解析与表单传输
    // -------------------------------------------------------------------

    /** CF REST 信封收口:status=OK 返回 result 原文;FAILED/异常抛 [OJException]。 */
    private suspend fun apiGet(url: String): kotlinx.serialization.json.JsonElement {
        val raw = transport.get(CODEFORCES_PLATFORM_ID, url)
        val body =
            runCatching { json.parseToJsonElement(raw).jsonObject }
                .getOrElse { throw OJException("Codeforces API 响应不是 JSON($url): ${raw.take(SNIPPET)}") }
        return body.extractResult(url, raw)
    }

    /** 信封字段校验与 result 提取(错误信息带 status/comment)。 */
    private fun JsonObject.extractResult(
        url: String,
        raw: String,
    ): kotlinx.serialization.json.JsonElement {
        val status = this["status"]?.jsonPrimitive?.content
        if (status != STATUS_OK) {
            val comment = (this["comment"] as? JsonNull)?.let { "" } ?: this["comment"]?.jsonPrimitive?.content.orEmpty()
            throw OJException("Codeforces API 调用失败(status=$status, comment=$comment, $url): ${raw.take(SNIPPET)}")
        }
        return this["result"] ?: throw OJException("Codeforces API 响应缺少 result($url)")
    }

    private fun parseProblemsetResult(result: kotlinx.serialization.json.JsonElement): List<CfProblem> =
        result.jsonObject["problems"]
            ?.jsonArray
            .orEmpty()
            .map { parseProblem(it.jsonObject) }

    private fun parseProblem(problem: JsonObject): CfProblem =
        CfProblem(
            contestId = problem.longOrNull("contestId"),
            problemsetName = problem.stringOrNull("problemsetName"),
            index = problem["index"]?.jsonPrimitive?.content.orEmpty(),
            name = problem["name"]?.jsonPrimitive?.content.orEmpty(),
            points = problem["points"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            rating = problem.intOrNull("rating"),
            tags =
                problem["tags"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
        )

    private fun parseContest(contest: JsonObject): CfContest =
        CfContest(
            id = contest.longOrNull("id") ?: throw OJException("contest 对象缺少 id: $contest"),
            name = contest["name"]?.jsonPrimitive?.content.orEmpty(),
            type = contest["type"]?.jsonPrimitive?.content.orEmpty(),
            phase = contest["phase"]?.jsonPrimitive?.content.orEmpty(),
            frozen = contest["frozen"]?.jsonPrimitive?.content == "true",
            durationSeconds = contest.longOrNull("durationSeconds") ?: 0L,
            startTimeSeconds = contest.longOrNull("startTimeSeconds") ?: 0L,
            relativeTimeSeconds = contest.longOrNull("relativeTimeSeconds") ?: 0L,
        )

    /** 表单 POST(字段唯一,`toMap` 转 [Map] 后保持插入序;WP25 起走 SPI postForm)。 */
    private suspend fun postForm(
        url: String,
        fields: List<Pair<String, String>>,
        headers: Map<String, String> = emptyMap(),
    ): String = transport.postForm(CODEFORCES_PLATFORM_ID, url, fields.toMap(), headers)

    /** 9 位 hex 缓存穿透参数(旧实现 `Random.nextLong().toHexString.substring(0,9)`)。 */
    private fun randomRv(): String =
        (Random.nextLong() and Long.MAX_VALUE)
            .toString(RV_RADIX)
            .padStart(RV_LENGTH, '0')
            .take(RV_LENGTH)

    private fun JsonObject.stringOrNull(name: String): String? {
        val value = this[name] ?: return null
        if (value is JsonNull) return null
        return value.jsonPrimitive.content
    }

    private fun JsonObject.longOrNull(name: String): Long? = this[name]?.jsonPrimitive?.content?.toLongOrNull()

    private fun JsonObject.intOrNull(name: String): Int? = this[name]?.jsonPrimitive?.content?.toIntOrNull()

    private companion object {
        const val SNIPPET = 200

        const val STATUS_OK = "OK"

        /** standings 行的 points 缺省值(字段缺失/解析失败)。 */
        const val DEFAULT_ROW_POINTS = 0.0

        /** rv 参数:hex 基数与长度(旧实现事实)。 */
        const val RV_RADIX = 16

        const val RV_LENGTH = 9
    }
}

/** Turnstile 弹窗取消/格式不符 → 领域异常(提交链路上抛点,供 SubmissionService 复用)。 */
internal fun turnstileFailed(detail: String): AntiBotChallengeException =
    AntiBotChallengeException(
        "CodeForces 人机验证未完成: $detail",
        challengeType = "cloudflare-turnstile",
    )
