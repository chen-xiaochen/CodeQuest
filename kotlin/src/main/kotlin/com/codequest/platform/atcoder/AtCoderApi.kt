package com.codequest.platform.atcoder

import com.codequest.core.model.OJException
import com.codequest.spi.OJTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.exp

/**
 * AtCoder API 封装(WP25):kenkoooo JSON 资源与 atcoder.jp HTML 端点到
 * Kotlin 数据的纯映射层。
 *
 * 端点与协议事实沿用旧 Scala 实现(AtCoderApi.scala):
 * - 题库:`GET https://kenkoooo.com/atcoder/resources/merged-problems.json`
 *   (全量 JSON 数组,snake_case 字段;旧实现从未爬 atcoder.jp 列表页);
 * - 难度:`GET https://kenkoooo.com/atcoder/resources/problem-models.json`
 *   (`{problemId: {"difficulty": IRT 值, ...}}`;difficulty 缺失按 0,
 *   再经 [calculateDisplayDifficulty] 换算——kenkoooo 源码同款公式);
 * - 题面页 / 登录态页:HTML 原文拉取,解析在调用方([AtCoderHtml]);
 * - 提交:表单 POST `/contests/<id>/submit`(字段见 [submitAnswer],
 *   含 Turnstile 双 token);
 * - 提交状态:`GET /contests/<id>/submissions/<sid>`(HTML 判定解析在 [AtCoderHtml])。
 */
class AtCoderApi(
    private val transport: OJTransport,
) {
    private val json = Json

    /** merged-problems 单条(消费字段子集:定位/展示所需的 id 与标题)。 */
    data class AtProblem(
        /** 问题 id(TaskScreenName),即平台 remoteId,如 "abc390_g"。 */
        val id: String,
        /** 所属竞赛 id,如 "abc390"(与 id 的 `_` 前缀一致)。 */
        val contestId: String,
        /** 题目字母序号,如 "G"。 */
        val problemIndex: String,
        /** 题名,如 "Permutation Concatenation"。 */
        val name: String,
        /** 完整展示标题,如 "AtCoder Beginner Contest 390 G - Permutation Concatenation"。 */
        val title: String,
    )

    /** Turnstile 弹窗回传的二元组(`csrf,turnstile`)。 */
    data class SubmitTokens(
        val csrfToken: String,
        val turnstileToken: String,
    ) {
        companion object {
            /** 弹窗结果串的固定段数(csrf,turnstile)。 */
            private const val TOKEN_ARITY = 2

            /** 解析弹窗结果串;格式不符返回 null(调用方按人机验证失败处理)。 */
            fun parse(raw: String): SubmitTokens? {
                val parts = raw.split(',')
                if (parts.size != TOKEN_ARITY || parts.any { it.isBlank() }) return null
                return SubmitTokens(csrfToken = parts[0], turnstileToken = parts[1])
            }
        }
    }

    /** 全量题库(kenkoooo 镜像,数千条;由调用方决定缓存/过滤策略)。 */
    suspend fun fetchProblems(): List<AtProblem> {
        val raw = transport.get(ATCODER_PLATFORM_ID, ATCODER_PROBLEMS_URL)
        val array =
            runCatching { json.parseToJsonElement(raw).jsonArray }
                .getOrElse { throw OJException("AtCoder 题库响应不是 JSON 数组: ${raw.take(SNIPPET)}") }
        return array.map { element ->
            val problem = element.jsonObject
            AtProblem(
                id = problem.stringOrEmpty("id"),
                contestId = problem.stringOrEmpty("contest_id"),
                problemIndex = problem.stringOrEmpty("problem_index"),
                name = problem.stringOrEmpty("name"),
                title = problem.stringOrEmpty("title"),
            )
        }
    }

    /**
     * 全量难度表(problem id → 展示难度):difficulty 字段缺失/非数按 0(旧实现
     * `getOrElse(0)` 事实),再经 [calculateDisplayDifficulty] 换算。
     */
    suspend fun fetchProblemDifficulties(): Map<String, Int> {
        val raw = transport.get(ATCODER_PLATFORM_ID, ATCODER_PROBLEM_MODELS_URL)
        val root =
            runCatching { json.parseToJsonElement(raw).jsonObject }
                .getOrElse { throw OJException("AtCoder 难度表响应不是 JSON 对象: ${raw.take(SNIPPET)}") }
        return root.entries
            .mapNotNull { (problemId, model) ->
                val irt =
                    (model as? JsonObject)
                        ?.get("difficulty")
                        ?.let { if (it is JsonNull) null else it.jsonPrimitive.content.toDoubleOrNull() }
                        ?: DEFAULT_IRT_DIFFICULTY
                problemId to calculateDisplayDifficulty(irt.toInt())
            }.toMap()
    }

    /** 题面页原文(解析在调用方,见 [AtCoderHtml.extractStatement])。 */
    suspend fun fetchTaskPageHtml(
        contestId: String,
        problemId: String,
    ): String = transport.get(ATCODER_PLATFORM_ID, atCoderTaskUrl(contestId, problemId))

    /** settings 页原文(登录态与用户名提取的解析在调用方)。 */
    suspend fun fetchSettingsPageHtml(): String = transport.get(ATCODER_PLATFORM_ID, ATCODER_SETTINGS_URL)

    /**
     * 提交代码,返回平台侧提交 id。
     *
     * 表单字段与旧实现 `UrlForm` 逐字一致(data.TaskScreenName / data.LanguageId /
     * sourceCode / csrf_token / cf-turnstile-response);响应为 HTML:错误
     * `div[role=alert]`(见 [AtCoderHtml.extractSubmitError])抛 [OJException],
     * 成功从 `td.submission-score[data-id]` 首格取提交 id。
     */
    suspend fun submitAnswer(
        contestId: String,
        problemId: String,
        languageId: String,
        source: String,
        tokens: SubmitTokens,
    ): String {
        val form =
            linkedMapOf(
                "data.TaskScreenName" to problemId,
                "data.LanguageId" to languageId,
                "sourceCode" to source,
                "csrf_token" to tokens.csrfToken,
                "cf-turnstile-response" to tokens.turnstileToken,
            )
        val html = transport.postForm(ATCODER_PLATFORM_ID, atCoderSubmitUrl(contestId), form)
        AtCoderHtml.extractSubmitError(html)?.let { error ->
            throw OJException("AtCoder 拒绝提交: $error")
        }
        return AtCoderHtml.extractSubmissionId(html)
            ?: throw OJException("AtCoder 提交响应中未找到提交记录(登录态失效或页面结构变化)")
    }

    /** 提交详情页原文(判定解析在调用方,见 [AtCoderHtml.extractJudgeStatus])。 */
    suspend fun fetchSubmissionStatusPage(
        contestId: String,
        submissionId: String,
    ): String = transport.get(ATCODER_PLATFORM_ID, atCoderSubmissionUrl(contestId, submissionId))

    private fun JsonObject.stringOrEmpty(name: String): String = this[name]?.jsonPrimitive?.content.orEmpty()

    companion object {
        private const val SNIPPET = 200

        /** difficulty 字段缺失/非数时的 IRT 兜底值(旧 getOrElse(0) 事实)。 */
        private const val DEFAULT_IRT_DIFFICULTY = 0.0

        /** IRT 换算公式的 400 基准(kenkoooo 源码事实)。 */
        private const val IRT_BASE = 400.0

        /**
         * IRT 难度 → 展示难度(kenkoooo 源码公式,旧实现逐字移植):
         * >=400 原样;否则按 `400 / exp(1 - irt/400)` 压到 (0, 400) 区间。
         */
        fun calculateDisplayDifficulty(irtDifficulty: Int): Int =
            if (irtDifficulty >= IRT_BASE) {
                irtDifficulty
            } else {
                (IRT_BASE / exp(1.0 - irtDifficulty / IRT_BASE)).toInt()
            }
    }
}
