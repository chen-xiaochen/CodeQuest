package com.codequest.platform.hackerrank

import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.spi.OJTransport
import com.codequest.spi.SubmissionStatusSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * HackerRank REST API 封装(WP27):HTML 内嵌 JSON 提取、REST 列表/详情/提交/
 * 轮询到 Kotlin 数据的纯映射层。端点/字段/参数沿用旧 Scala 实现
 * (hackerrank/services/HackerRankApi.scala)事实——**旧实现无 GraphQL,全 REST**,
 * 本实现原样;协议细节与 remoteId 约定见 [HackerRankEndpoints] KDoc。
 *
 * 与旧实现的有意差异(协议事实不变):
 * - 旧 runAnswer(compile_tests 试运行链路)不迁移——新 challenge 工作区有本地
 *   运行,提交前试运行无消费方(YAGNI);
 * - 详情页的代码模板(`{lang}{ver}_template*` 键)与样例(`.challenge_sample_*`
 *   提取)不迁移——新管线的题面整块进 [com.codequest.spi.ProblemStatement],
 *   工作区模板/样例导入由 core 通用机制承担(见 spec 能力矩阵);
 * - `__NEXT_DATA__` 不再做 URL 解码(旧实现误调 Uri.decode,`+` 原文会损坏)。
 */
class HackerRankApi(
    private val transport: OJTransport,
) {
    private val json = Json

    // -------------------------------------------------------------------
    // 登录态与用户(旧 checkLogin / getInitialData 事实)
    // -------------------------------------------------------------------

    /** 登录态判定:banner-dashboard 端点 2xx 即已登录;非 2xx(transport 领域异常)按 false。 */
    suspend fun checkLogin(): Boolean =
        try {
            transport.get(HACKERRANK_PLATFORM_ID, HACKERRANK_LOGIN_CHECK_URL)
            true
        } catch (
            @Suppress("SwallowedException") error: OJException,
        ) {
            false
        }

    /** 用户信息(dashboard `__NEXT_DATA__` → prefetchedData.profile.username);未登录/缺失返回 null。 */
    data class HackerRankUserInfo(
        val username: String,
    )

    suspend fun fetchUserInfo(): HackerRankUserInfo? {
        val profile = dashboardPrefetchedData()?.obj("profile") ?: return null
        val username = profile.stringOrEmpty("username")
        if (username.isEmpty()) return null
        return HackerRankUserInfo(username = username)
    }

    /** dashboard `__NEXT_DATA__` → props.pageProps.prefetchedData(结构缺失返回 null)。 */
    private suspend fun dashboardPrefetchedData(): JsonObject? {
        val html = transport.get(HACKERRANK_PLATFORM_ID, HACKERRANK_DASHBOARD_URL)
        val nextData =
            HackerRankHtml.extractNextDataJson(html)
                ?: throw OJException("HackerRank dashboard HTML 不含 __NEXT_DATA__ 结构")
        val root =
            runCatching { json.parseToJsonElement(nextData).jsonObject }
                .getOrElse { throw OJException("HackerRank __NEXT_DATA__ 不是合法 JSON") }
        return root
            .obj("props")
            ?.obj("pageProps")
            ?.obj("prefetchedData")
    }

    // -------------------------------------------------------------------
    // 题库领域结构(旧 getInitialData 事实)
    // -------------------------------------------------------------------

    data class HackerRankSubdomain(
        val name: String,
        val slug: String,
    )

    /** 题库领域(track):master 竞赛的分类树节点 + 末位 Project Euler 特例域。 */
    data class HackerRankDomain(
        val id: Int,
        val name: String,
        val slug: String,
        val contestSlug: String,
        val subdomains: List<HackerRankSubdomain>,
    )

    /**
     * 题库领域列表:dashboard `contest.categories`(id 升序,旧 initialize() 事实)
     * + 追加 Project Euler 特例域(旧 PROJECT_EULER_DOMAIN 常量,id 99)。
     */
    suspend fun fetchDomains(): List<HackerRankDomain> {
        val categories =
            dashboardPrefetchedData()
                ?.obj("contest")
                ?.arrayOrEmpty("categories")
                ?: throw OJException("HackerRank dashboard 数据缺少 contest.categories 结构")
        val domains =
            categories
                .mapNotNull { element ->
                    val category = element.jsonObject
                    val id = category.intOrNullField("id") ?: return@mapNotNull null
                    val name = category.stringOrEmpty("name")
                    val slug = category.stringOrEmpty("slug")
                    if (name.isEmpty() || slug.isEmpty()) return@mapNotNull null
                    HackerRankDomain(
                        id = id,
                        name = name,
                        slug = slug,
                        contestSlug = HACKERRANK_MASTER_CONTEST,
                        subdomains =
                            category.arrayOrEmpty("children").mapNotNull { child ->
                                val sub = child.jsonObject
                                val subName = sub.stringOrEmpty("name")
                                val subSlug = sub.stringOrEmpty("slug")
                                if (subName.isEmpty() || subSlug.isEmpty()) null else HackerRankSubdomain(subName, subSlug)
                            },
                    )
                }.sortedBy { it.id }
        return domains +
            HackerRankDomain(
                id = PROJECT_EULER_DOMAIN_ID,
                name = "Project Euler",
                slug = HACKERRANK_PROJECT_EULER_CONTEST,
                contestSlug = HACKERRANK_PROJECT_EULER_CONTEST,
                subdomains = emptyList(),
            )
    }

    // -------------------------------------------------------------------
    // 题目列表(旧 searchChallenges 事实)
    // -------------------------------------------------------------------

    /** 列表/详情共用条目(旧 HackerRankChallengeDetail 的消费子集,其余字段无消费方)。 */
    data class HackerRankChallengeDetail(
        val id: Long,
        val slug: String,
        val name: String,
        val contestSlug: String,
        val difficultyName: String,
        val maxScore: Int,
        val preview: String?,
        /** 题面 HTML 原文(详情页 initialData 才携带;列表项为 null)。 */
        val bodyHtml: String?,
    )

    data class HackerRankChallengePage(
        val total: Int,
        val items: List<HackerRankChallengeDetail>,
    )

    /**
     * 题库查询过滤条件(旧 searchChallenges 的多值 query 参数,均为空即不过滤):
     * - [status]:solved/unsolved/tried(需登录,匿名时服务端忽略);
     * - [skills]:"Problem Solving (Basic)/(Intermediate)/(Advanced)" 原文;
     * - [difficulties]:easy/medium/hard;
     * - [subdomains]:领域内 tracker slug(即 [HackerRankDomain.subdomains] 的 slug)。
     */
    data class ChallengeFilters(
        val status: List<String> = emptyList(),
        val skills: List<String> = emptyList(),
        val difficulties: List<String> = emptyList(),
        val subdomains: List<String> = emptyList(),
    )

    /** 题库分页查询(REST offset/limit 原生分页)。 */
    suspend fun searchChallenges(
        offset: Int,
        limit: Int,
        contestSlug: String,
        domainSlug: String,
        filters: ChallengeFilters = ChallengeFilters(),
    ): HackerRankChallengePage {
        val url = buildListUrl(contestSlug, domainSlug, offset, limit, filters)
        val root = parseJsonObject(transport.get(HACKERRANK_PLATFORM_ID, url), "HackerRank 题目列表响应不是合法 JSON")
        return HackerRankChallengePage(
            total = root.intOrNullField("total") ?: throw OJException("HackerRank 题目列表响应缺少 total 字段"),
            items = (root["models"] as? JsonArray ?: emptyList()).map { it.jsonObject.toDetail() },
        )
    }

    /** 旧 searchChallengesWithKeyword 的 appsearch 端点(条目字段为 camelCase)。 */
    data class HackerRankKeywordItem(
        val contestSlug: String,
        val challengeSlug: String,
        val challengeName: String,
    )

    suspend fun searchChallengesWithKeyword(
        contestSlug: String,
        keyword: String,
    ): List<HackerRankKeywordItem> {
        val url = hackerrankAppSearchUrl(contestSlug, encodeQuery(keyword))
        val root = parseJsonObject(transport.get(HACKERRANK_PLATFORM_ID, url), "HackerRank appsearch 响应不是合法 JSON")
        val challenges =
            root["challenges"] as? JsonArray
                ?: throw OJException("HackerRank appsearch 响应缺少 challenges 结构")
        return challenges.map { element ->
            val item = element.jsonObject
            HackerRankKeywordItem(
                contestSlug = item.stringOrEmpty("contest_slug").ifEmpty { contestSlug },
                challengeSlug = item.stringOrEmpty("challenge_slug"),
                challengeName = item.stringOrEmpty("challenge_name").ifEmpty { item.stringOrEmpty("name") },
            )
        }
    }

    // -------------------------------------------------------------------
    // 题目详情与 CSRF(旧 getChallengeDetail / getChallengeCSRFToken 事实)
    // -------------------------------------------------------------------

    /** 题目详情(题目页 initialData → `community.challenges.challenge["{contest}/{slug}"].detail`)。 */
    suspend fun fetchChallengeDetail(
        challengeSlug: String,
        contestSlug: String,
    ): HackerRankChallengeDetail {
        val html = transport.get(HACKERRANK_PLATFORM_ID, hackerrankChallengeProblemUrl(contestSlug, challengeSlug))
        val initialData =
            HackerRankHtml.extractInitialDataJson(html)
                ?: throw OJException("HackerRank 题目页缺少 script#initialData 结构(页面变化或被拦截): $challengeSlug")
        val root = parseJsonObject(initialData, "HackerRank initialData 不是合法 JSON")
        val detail =
            root
                .obj("community")
                ?.obj("challenges")
                ?.obj("challenge")
                ?.obj("$contestSlug/$challengeSlug")
                ?.obj("detail")
                ?: throw OJException("HackerRank initialData 缺少 challenge[$contestSlug/$challengeSlug].detail 结构")
        return detail.toDetail()
    }

    /** CSRF token(题目页 `meta#csrf-token` content;提交前获取)。 */
    suspend fun fetchCsrfToken(
        challengeSlug: String,
        contestSlug: String,
    ): String {
        val html = transport.get(HACKERRANK_PLATFORM_ID, hackerrankChallengeProblemUrl(contestSlug, challengeSlug))
        return HackerRankHtml.extractCsrfToken(html)
            ?: throw OJException("HackerRank 题目页缺少 meta#csrf-token(登录态失效或页面结构变化)")
    }

    // -------------------------------------------------------------------
    // 提交与轮询(旧 submitAnswer / getSubmitCodeResult 事实)
    // -------------------------------------------------------------------

    /** 提交代码,返回平台 submission id。 */
    suspend fun submitAnswer(
        challengeSlug: String,
        contestSlug: String,
        language: String,
        code: String,
    ): String {
        val csrfToken = fetchCsrfToken(challengeSlug, contestSlug)
        val body =
            buildJsonObject {
                put("language", language)
                put("contest_slug", contestSlug)
                put("challenge_slug", challengeSlug)
                put("code", code)
            }.toString()
        val raw =
            transport.postJson(
                platformId = HACKERRANK_PLATFORM_ID,
                url = hackerrankSubmitUrl(contestSlug, challengeSlug),
                jsonBody = body,
                headers = mapOf("x-csrf-token" to csrfToken),
            )
        val id =
            parseJsonObject(raw, "HackerRank 提交响应不是合法 JSON")
                .obj("model")
                ?.longOrNullField("id")
                ?: throw OJException("HackerRank 提交响应缺少 model.id: ${raw.take(SNIPPET)}")
        return id.toString()
    }

    /** 提交结果(轮询单元;旧 HackerRankSubmissionResponse 的消费子集)。 */
    data class HackerRankSubmissionResult(
        val id: Long,
        /** 平台判定原文,如 "Processing" / "Accepted" / "Wrong Answer" / "Compilation error"。 */
        val status: String,
        /** 3 = 处理完毕(旧轮询终止条件)。 */
        val scoreProcessed: Int,
        val compileMessage: String?,
        /** 用例状态列表(1=通过;旧 HACKERRANK_SUBMISSION_CASE.testcasestatus 事实;WP36 详情消费)。 */
        val testcaseStatus: List<Int> = emptyList(),
    ) {
        fun toSnapshot(): SubmissionStatusSnapshot =
            // 旧实现未提取耗时/内存作快照指标(codechecker_time 仅落库明细),如实为 null
            SubmissionStatusSnapshot(
                result = submissionStatusToResult(status, scoreProcessed),
                timeMs = null,
                memoryKb = null,
            )
    }

    suspend fun fetchSubmissionResult(
        contestSlug: String,
        challengeSlug: String,
        submissionId: String,
    ): HackerRankSubmissionResult {
        val raw =
            transport.get(
                HACKERRANK_PLATFORM_ID,
                hackerrankSubmissionUrl(contestSlug, challengeSlug, submissionId),
            )
        val model =
            parseJsonObject(raw, "HackerRank 提交结果响应不是合法 JSON").obj("model")
                ?: throw OJException("HackerRank 提交结果响应缺少 model 结构: ${raw.take(SNIPPET)}")
        return HackerRankSubmissionResult(
            id = model.longOrNullField("id") ?: 0L,
            status = model.stringOrEmpty("status"),
            scoreProcessed = model.intOrNullField("score_processed") ?: 0,
            compileMessage = model.stringOrNull("compile_message"),
            testcaseStatus = model.intArrayField("testcase_status"),
        )
    }

    // -------------------------------------------------------------------
    // 其余 helpers
    // -------------------------------------------------------------------

    /**
     * 列表查询 URL(旧 withQueryParam/withMultiValueQueryParams 顺序:offset/limit
     * 后接 filters 多值参数,键含字面 `[]`;值 URL 编码,空格 %20 形态)。
     */
    private fun buildListUrl(
        contestSlug: String,
        domainSlug: String,
        offset: Int,
        limit: Int,
        filters: ChallengeFilters,
    ): String {
        val params = mutableListOf("offset=$offset", "limit=$limit")
        filters.subdomains.forEach { params += "filters[subdomains][]=${encodeQuery(it)}" }
        filters.status.forEach { params += "filters[status][]=${encodeQuery(it)}" }
        filters.difficulties.forEach { params += "filters[difficulty][]=${encodeQuery(it)}" }
        filters.skills.forEach { params += "filters[skills][]=${encodeQuery(it)}" }
        return hackerrankChallengeListUrl(contestSlug, domainSlug) + "?" + params.joinToString("&")
    }

    private fun parseJsonObject(
        text: String,
        message: String,
    ): JsonObject =
        runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw OJException("$message: ${text.take(SNIPPET)}") }

    private fun JsonObject.toDetail(): HackerRankChallengeDetail =
        HackerRankChallengeDetail(
            id = longOrNullField("id") ?: 0L,
            slug = stringOrEmpty("slug"),
            name = stringOrEmpty("name"),
            contestSlug = stringOrEmpty("contest_slug").ifEmpty { HACKERRANK_MASTER_CONTEST },
            difficultyName = stringOrEmpty("difficulty_name"),
            maxScore = intOrNullField("max_score") ?: 0,
            preview = stringOrNull("preview"),
            bodyHtml = stringOrNull("body_html"),
        )

    private fun encodeQuery(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        private const val SNIPPET = 200

        /** 旧 PROJECT_EULER_DOMAIN 常量的 id(99)。 */
        const val PROJECT_EULER_DOMAIN_ID: Int = 99

        /** 旧轮询终止条件:score_processed == 3 表示处理完毕。 */
        const val SCORE_PROCESSED_DONE: Int = 3

        /**
         * 平台判定原文 → 平台无关判定(旧 toSubmissionResult 的 contains 匹配事实,
         * 匹配顺序逐字:Accepted → Wrong Answer → Compilation error →
         * Terminated due to timeout → Processing → Runtime Error)。
         *
         * 终止性由 scoreProcessed 决定(旧实现轮询至 3):非 3 一律 [SubmissionResult.Judging]
         * 非终态;到 3 后按 status 映射,若 status 仍为 Processing 等非终态文案则归
         * [SubmissionResult.Unknown] 终态保留原文(新体系无通用 Failure,与 WP24/25/26
         * 同理由:已终局的结果不能轮询到超时)。
         */
        fun submissionStatusToResult(
            status: String,
            scoreProcessed: Int,
        ): SubmissionResult {
            if (scoreProcessed != SCORE_PROCESSED_DONE) return SubmissionResult.Judging
            val value = status.lowercase()
            return when {
                "accepted" in value -> SubmissionResult.Accepted
                "wrong answer" in value -> SubmissionResult.WrongAnswer
                "compilation error" in value -> SubmissionResult.CompileError
                "terminated due to timeout" in value -> SubmissionResult.TimeLimitExceeded
                "runtime error" in value -> SubmissionResult.RuntimeError
                "processing" in value -> SubmissionResult.Unknown(status)
                else -> SubmissionResult.Unknown(status)
            }
        }
    }
}

// 文件级 JSON 取值扩展(与 LuoguApi 同法)
private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.stringOrEmpty(name: String): String = stringOrNull(name).orEmpty()

private fun JsonObject.stringOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun JsonObject.intOrNullField(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.longOrNullField(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.arrayOrEmpty(name: String): List<JsonElement> = (this[name] as? JsonArray) ?: emptyList()

/** int 数组字段(WP36:testcase_status 列表;缺失/形态不符回空)。 */
private fun JsonObject.intArrayField(name: String): List<Int> = arrayOrEmpty(name).mapNotNull { (it as? JsonPrimitive)?.intOrNull }
