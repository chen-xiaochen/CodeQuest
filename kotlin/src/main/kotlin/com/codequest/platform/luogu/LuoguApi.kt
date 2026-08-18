package com.codequest.platform.luogu

import com.codequest.core.model.AntiBotChallengeException
import com.codequest.core.model.OJException
import com.codequest.core.model.SubmissionResult
import com.codequest.spi.OJTransport
import com.codequest.spi.SubmissionStatusSnapshot
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 洛谷 API 封装(WP26):lentille HTML 数据、表单式 JSON 提交与 record 轮询到
 * Kotlin 数据的纯映射层。
 *
 * 端点与协议事实沿用旧 Scala 实现(LuoGuApi.scala):
 * - 反爬:每次带公共头的请求前先 GET 首页,响应体含 `C3VK=` 时注入同名 cookie 重试
 *   (旧实现无限递归,本实现限 [MAX_ANTI_CRAWL_ROUNDS] 轮防死循环),通过后取
 *   `meta[name=csrf-token]`;
 * - 公共头:`x-csrf-token` + `x-requested-with: XMLHttpRequest`;
 * - 列表:`/problem/list?page=&_contentOnly=1[&difficulty=&keyword=]` → lentille
 *   `data.problems.{perPage,count,result}`(列表项标题字段为 `name`,旧 case class
 *   写作 `title`,解析两者兼容);
 * - 详情:`/problem/{pid}` → lentille `data.problem.*`,题面各字段为 Markdown 原文,
 *   按 [buildStatementMarkdown] 拼装(旧 makeDescriptionMarkdown 逐字);
 * - 题解:`/problem/solution/{pid}?_contentOnly=1` → `data.solutions.result`
 *   (需登录,未登录 HTTP 401 由 transport 翻译为 AuthExpiredException);
 *   列表项内嵌 `content`(该楼层 Markdown 全文,WP31 正文复用同一端点);
 * - 提交:`POST /fe/api/problem/submit/{pid}`,JSON `{lang:int, enableO2:1, code}`,
 *   头另带 Referer,响应 `{rid}`。403(易盾/图片验证码)旧实现有 JCEF 弹窗链路,
 *   依赖未迁移的旧 webview 静态资源,本 WP 按 transport 错误映射兜底(AuthExpired);
 * - 轮询:`GET /record/{rid}`(无公共头),JSON 藏在 `decodeURIComponent("...")`
 *   内(URL 编码),判定在 `currentData.record.status`;503 为评测高峰暂态,旧实现
 *   sleep 2s 重试,本实现保留(经 [retryDelay] 注入以便测试)。
 */
class LuoguApi(
    private val transport: OJTransport,
    private val retryDelay: suspend (Long) -> Unit = { ms -> delay(ms) },
) {
    private val json = Json

    // -------------------------------------------------------------------
    // 反爬通道与公共头
    // -------------------------------------------------------------------

    /**
     * 取 CSRF token:GET 首页 → 命中 C3VK 反爬标记则注入 cookie 重试(旧实现事实,
     * 限 [MAX_ANTI_CRAWL_ROUNDS] 轮),通过后提取 `meta[name=csrf-token]`。
     */
    internal suspend fun ensureCsrfToken(): String {
        repeat(MAX_ANTI_CRAWL_ROUNDS) {
            val html = transport.get(LUOGU_PLATFORM_ID, LUOGU_HOME_URL)
            val c3vk = LuoguHtml.extractC3vk(html)
            if (c3vk == null) {
                return LuoguHtml.extractCsrfToken(html)
                    ?: throw OJException("洛谷首页未提供 csrf-token(反爬页或页面结构变化)")
            }
            transport.updateCookies(LUOGU_PLATFORM_ID, LUOGU_HOME_URL, mapOf(C3VK_COOKIE to c3vk))
        }
        throw OJException("洛谷反爬验证(C3VK)$MAX_ANTI_CRAWL_ROUNDS 轮后仍未通过")
    }

    /** 旧实现 commonHeaders:x-csrf-token + x-requested-with。 */
    private suspend fun commonHeaders(): Map<String, String> =
        mapOf(
            "x-csrf-token" to ensureCsrfToken(),
            "x-requested-with" to "XMLHttpRequest",
        )

    /** GET + lentille-context 提取:200 响应为内嵌 JSON 的 HTML(2026-08 实测佐证)。 */
    private suspend fun getLentilleJson(
        url: String,
        headers: Map<String, String>,
    ): JsonObject {
        val raw = transport.get(LUOGU_PLATFORM_ID, url, headers)
        val context =
            LuoguHtml.extractLentilleContext(raw)
                ?: throw OJException("洛谷响应缺少 lentille-context 数据(页面结构变化或被反爬拦截): ${raw.take(SNIPPET)}")
        return runCatching { json.parseToJsonElement(context).jsonObject }
            .getOrElse { throw OJException("洛谷 lentille-context 不是合法 JSON: ${context.take(SNIPPET)}") }
    }

    // -------------------------------------------------------------------
    // 登录态(旧 checkLogin / getUserInfo 事实)
    // -------------------------------------------------------------------

    /**
     * 登录态判定:HTTP 错误(未登录 401 等,transport 已翻译领域异常)按 false;
     * 200 时 `<title>` 为已知登录态文案即 true,否则看首个 `<h1>` 是否含 401。
     */
    suspend fun checkLogin(): Boolean =
        try {
            val html = transport.get(LUOGU_PLATFORM_ID, LUOGU_USER_SETTING_URL, commonHeaders())
            val title = LuoguHtml.extractTitle(html).orEmpty()
            if (title == LOGGED_IN_TITLE_CN || title == LOGGED_IN_TITLE_EN) {
                true
            } else {
                !LuoguHtml.contains401Heading(html)
            }
        } catch (
            @Suppress("SwallowedException") error: OJException,
        ) {
            false
        }

    /** 用户信息(lentille `user`;uid/name,头像等富信息不进 SPI 契约);不可得返回 null。 */
    data class LuoguUserInfo(
        val uid: String,
        val name: String,
    )

    suspend fun fetchUserInfo(): LuoguUserInfo? {
        val root = getLentilleJson(LUOGU_USER_SETTING_URL, commonHeaders())
        // 旧实现读根级 `user`;信封演进时回退 `data.user`(与列表/详情页 data 前缀对齐)
        val user = root.obj("user") ?: root.obj("data")?.obj("user") ?: return null
        val uid = user.intOrZero("uid").toString()
        val name = user.stringOrEmpty("name")
        if (uid == "0" && name.isEmpty()) return null
        return LuoguUserInfo(uid = uid, name = name)
    }

    // -------------------------------------------------------------------
    // 题目列表
    // -------------------------------------------------------------------

    /** 列表项(pid 即 remoteId;标题字段为 `name`,兼容旧 `title`)。 */
    data class LuoguProblemItem(
        val pid: String,
        val name: String,
        /** 洛谷难度档位 0-7(暂无评定 → NOI/NOI+/CTSC)。 */
        val difficulty: Int,
        val totalSubmit: Int,
        val totalAccepted: Int,
    )

    data class LuoguProblemPage(
        val count: Int,
        val perPage: Int,
        val items: List<LuoguProblemItem>,
    )

    /**
     * 题目列表分页查询。[difficulty] 为洛谷档位数字(0-7);[keyword] 为题面搜索词
     * (URL 编码,%20 形态空格)。旧实现的 `type`(题库)/`tag`(标签数字 id)过滤
     * SPI 的 [com.codequest.spi.ProblemFilter] 无对应维度/需标签 id 表,不接入。
     */
    suspend fun fetchProblemPage(
        page: Int,
        difficulty: Int? = null,
        keyword: String? = null,
    ): LuoguProblemPage {
        var url = luoguProblemListUrl(page)
        difficulty?.let { url += "&difficulty=$it" }
        keyword?.takeIf { it.isNotEmpty() }?.let { url += "&keyword=" + encodeQuery(it) }
        val problems =
            getLentilleJson(url, commonHeaders()).obj("data")?.obj("problems")
                ?: throw OJException("洛谷题目列表缺少 data.problems 结构")
        return LuoguProblemPage(
            count = problems.intOrZero("count"),
            perPage = problems.intOrZero("perPage").takeIf { it > 0 } ?: DEFAULT_PER_PAGE,
            items =
                problems.arrayOrEmpty("result").map { element ->
                    val item = element.jsonObject
                    LuoguProblemItem(
                        pid = item.stringOrEmpty("pid"),
                        name = item.stringOrEmpty("name").ifEmpty { item.stringOrEmpty("title") },
                        difficulty = item.intOrZero("difficulty"),
                        totalSubmit = item.intOrZero("totalSubmit"),
                        totalAccepted = item.intOrZero("totalAccepted"),
                    )
                },
        )
    }

    // -------------------------------------------------------------------
    // 题目详情(题面为 Markdown)
    // -------------------------------------------------------------------

    /** 详情数据:md 为拼装后的题面 Markdown;samples 为 (输入, 输出) 对。 */
    data class LuoguProblemData(
        val pid: String,
        val title: String,
        val markdown: String,
        /** 平台接受语言 id 数字串(供提交语言过滤)。 */
        val acceptLanguages: List<String>,
        val samples: List<Pair<String, String>>,
    )

    suspend fun fetchProblemData(pid: String): LuoguProblemData {
        val problem =
            getLentilleJson(luoguProblemUrl(pid), commonHeaders()).obj("data")?.obj("problem")
                ?: throw OJException("洛谷题目页缺少 data.problem 结构(题目不存在或不可见): $pid")
        if (problem.stringOrEmpty("pid").isEmpty() && problem.stringOrEmpty("title").isEmpty()) {
            throw OJException("洛谷题目数据缺失 pid/title: $pid")
        }
        return LuoguProblemData(
            pid = problem.stringOrEmpty("pid").ifEmpty { pid },
            title = problem.stringOrEmpty("title"),
            markdown = buildStatementMarkdown(problem),
            acceptLanguages = problem.arrayOrEmpty("acceptLanguages").mapNotNull { it.jsonPrimitive.intOrNull?.toString() },
            samples =
                problem.arrayOrEmpty("samples").mapNotNull { sample ->
                    val pair = sample.jsonArray
                    val input = pair.getOrNull(0)?.jsonPrimitive?.content
                    val output = pair.getOrNull(1)?.jsonPrimitive?.content
                    if (input != null && output != null) input to output else null
                },
        )
    }

    // -------------------------------------------------------------------
    // 题解列表(需登录)
    // -------------------------------------------------------------------

    /** 题解条目引用(正文拉取 M5;title 取 `title` 字段,缺失回退作者名)。 */
    data class LuoguSolutionItem(
        val id: String,
        val title: String,
    )

    suspend fun fetchSolutionList(pid: String): List<LuoguSolutionItem> {
        val solutions =
            getLentilleJson(luoguSolutionListUrl(pid), commonHeaders()).obj("data")?.obj("solutions")
                ?: throw OJException("洛谷题解页缺少 data.solutions 结构(未登录或页面结构变化): $pid")
        return solutions.arrayOrEmpty("result").map { element ->
            val item = element.jsonObject
            LuoguSolutionItem(
                id = item.intOrNullField("id")?.toString().orEmpty(),
                title =
                    item.stringOrEmpty("title").ifEmpty {
                        item.obj("author")?.stringOrEmpty("name").orEmpty()
                    },
            )
        }
    }

    // -------------------------------------------------------------------
    // 题解正文(WP31)
    // -------------------------------------------------------------------

    /** 题解正文数据:content 为 Markdown 原文(含 LaTeX 公式与代码块)。 */
    data class LuoguSolutionDetail(
        val id: String,
        val title: String,
        val content: String,
    )

    /**
     * 单篇题解正文(WP31)。**洛谷无单篇题解端点**——正文内嵌于题解列表页
     * payload(luogu-api-docs:SolutionsData.solutions 为 List<ArticleDetails>,
     * Article.content 即该楼层 Markdown 全文),故复用列表端点按 id 筛出目标
     * 楼层;与 [fetchSolutionList] 同一首页 payload,列表翻页寻址超出本期
     * 范围(YAGNI)。未登录时平台可能下发截断正文(contentFull=false),
     * 本实现按当前登录态会话原样透传。
     */
    suspend fun fetchSolutionDetail(
        pid: String,
        solutionId: String,
    ): LuoguSolutionDetail {
        val solutions =
            getLentilleJson(luoguSolutionListUrl(pid), commonHeaders()).obj("data")?.obj("solutions")
                ?: throw OJException("洛谷题解页缺少 data.solutions 结构(未登录或页面结构变化): $pid")
        val item =
            solutions
                .arrayOrEmpty("result")
                .map { element -> element.jsonObject }
                .firstOrNull { candidate -> candidate.intOrNullField("id")?.toString() == solutionId }
                ?: throw OJException("洛谷题解不存在或不在首页楼层列表中: $pid/$solutionId")
        return LuoguSolutionDetail(
            id = solutionId,
            title =
                item.stringOrEmpty("title").ifEmpty {
                    item.obj("author")?.stringOrEmpty("name").orEmpty()
                },
            content = item.stringOrEmpty("content"),
        )
    }

    // -------------------------------------------------------------------
    // 提交与轮询(旧 postAnswer / getSubmitAnswerResult 事实)
    // -------------------------------------------------------------------

    /** 提交代码(JSON body `{lang, enableO2, code}`),返回 record id(rid)。 */
    suspend fun submitCode(
        pid: String,
        langId: String,
        code: String,
    ): String {
        val lang =
            langId.toIntOrNull()
                ?: throw OJException("洛谷提交语言 id 必须是数字: $langId")
        val body =
            buildJsonObject {
                put("lang", lang)
                put("enableO2", ENABLE_O2)
                put("code", code)
            }.toString()
        val raw =
            transport.postJson(
                platformId = LUOGU_PLATFORM_ID,
                url = luoguSubmitUrl(pid),
                jsonBody = body,
                headers = commonHeaders() + ("Referer" to luoguProblemUrl(pid)),
            )
        val rid =
            json
                .parseToJsonElement(raw)
                .jsonObject["rid"]
                ?.jsonPrimitive
                ?.intOrNull
                ?: throw OJException("洛谷提交响应缺少 rid: ${raw.take(SNIPPET)}")
        return rid.toString()
    }

    /**
     * 拉取提交判定快照(record 页)。503(评测高峰暂态)按旧实现 sleep 2s 重试
     * [RECORD_503_MAX_RETRIES] 次,其余错误如实上抛。耗时/内存列旧实现未提取,
     * 指标如实为 null。
     */
    suspend fun fetchRecordStatus(recordId: String): SubmissionStatusSnapshot {
        var attempt = 0
        while (true) {
            try {
                return fetchRecordStatusOnce(recordId)
            } catch (error: AntiBotChallengeException) {
                throw error
            } catch (error: OJException) {
                val transient503 =
                    error.message.orEmpty().contains(SERVICE_UNAVAILABLE_MARK) &&
                        attempt < RECORD_503_MAX_RETRIES
                if (!transient503) throw error
                attempt++
                retryDelay(RECORD_503_RETRY_INTERVAL_MS)
            }
        }
    }

    private suspend fun fetchRecordStatusOnce(recordId: String): SubmissionStatusSnapshot {
        val html = transport.get(LUOGU_PLATFORM_ID, luoguRecordUrl(recordId))
        val root = parseRecordJson(html)
        val record =
            root.obj("currentData")?.obj("record")
                ?: throw OJException("洛谷 record 数据缺少 currentData.record 结构")
        val status =
            record.intOrNullField("status")
                ?: throw OJException("洛谷 record 数据缺少 status 字段")
        var result = recordStatusToResult(status)
        if (result is SubmissionResult.Unknown) {
            // 旧实现:整卷失败(旧 Failure 兜底)时,取 testCases 中首个具体失败判定
            result = firstConcreteTestCaseFailure(root) ?: result
        }
        return SubmissionStatusSnapshot(result = result, timeMs = null, memoryKb = null)
    }

    /** record 页 HTML → `decodeURIComponent` URL 解码 JSON(两级失败均抛 OJException)。 */
    private fun parseRecordJson(html: String): JsonObject {
        val encoded =
            LuoguHtml.extractEncodedRecordJson(html)
                ?: throw OJException("洛谷 record 页缺少 decodeURIComponent 数据(页面结构变化): ${html.take(SNIPPET)}")
        val decoded =
            runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8) }
                .getOrElse { throw OJException("洛谷 record 数据 URL 解码失败: ${encoded.take(SNIPPET)}") }
        return parseJsonObjectOrThrow(decoded, "洛谷 record 数据不是合法 JSON")
    }

    private fun parseJsonObjectOrThrow(
        text: String,
        message: String,
    ): JsonObject =
        runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw OJException("$message: ${text.take(SNIPPET)}") }

    /** 遍历 subtasks[].testCases[].status,取首个「具体失败」(WA/TLE/RE 等)判定。 */
    private fun firstConcreteTestCaseFailure(root: JsonObject): com.codequest.core.model.SubmissionResult? {
        val judgeResult =
            root
                .obj("currentData")
                ?.obj("record")
                ?.obj("detail")
                ?.obj("judgeResult") ?: return null
        val cases =
            judgeResult.arrayOrEmpty("subtasks").flatMap { subtask ->
                subtask.jsonObject.arrayOrEmpty("testCases")
            }
        return cases
            .asSequence()
            .mapNotNull { case -> case.jsonObject.intOrNullField("status") }
            .map { status -> recordStatusToResult(status) }
            .firstOrNull { result ->
                result !is SubmissionResult.Pending &&
                    result !is SubmissionResult.Judging &&
                    result !is SubmissionResult.Accepted &&
                    result !is SubmissionResult.Unknown
            }
    }

    // -------------------------------------------------------------------
    // 其余 helpers 见文件底部文件级私有扩展
    // -------------------------------------------------------------------

    private fun encodeQuery(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        private const val SNIPPET = 200

        /** 旧实现 enableO2 固定 1。 */
        private const val ENABLE_O2 = 1

        private const val C3VK_COOKIE = "C3VK"

        /** 列表页响应缺 perPage 时的兜底(实测 50)。 */
        private const val DEFAULT_PER_PAGE = 50

        /** record 页 503 重试参数(旧 sleep 2s + retryLimitsWithBackoff(5, 2s) 事实)。 */
        const val RECORD_503_MAX_RETRIES: Int = 5

        const val RECORD_503_RETRY_INTERVAL_MS: Long = 2_000L

        private const val SERVICE_UNAVAILABLE_MARK = "HTTP 503"

        /** 反爬重试轮数上限(旧实现无限递归,此处防御性封顶)。 */
        const val MAX_ANTI_CRAWL_ROUNDS: Int = 3

        /** 旧 checkLogin 的登录态 title 文案。 */
        private const val LOGGED_IN_TITLE_CN = "用户设置 - 洛谷"

        private const val LOGGED_IN_TITLE_EN = "Welcome - Luogu Spilopelia"

        // 洛谷 record.status 数字语义(旧 luoguSubmissionStatus 逐字)
        private const val STATUS_WAITING = 0

        private const val STATUS_JUDGING = 1

        private const val STATUS_COMPILE_ERROR = 2

        private const val STATUS_OUTPUT_LIMIT = 3

        private const val STATUS_MEMORY_LIMIT = 4

        private const val STATUS_TIME_LIMIT = 5

        private const val STATUS_RUNTIME_ERROR = 7

        private const val STATUS_ACCEPTED = 12

        /**
         * record.status → 平台无关判定(旧 luoguSubmissionStatus 事实)。
         * 与旧实现的两处差异(与 WP24/WP25 同理由):
         * - 旧 0/1 统称 Processing,新体系细分 Pending(排队)/Judging(评测中);
         * - 旧 `Failure` 兜底在新体系无通用 Failure,归 Unknown 终态保留 code,
         *   避免已终局结果被轮询到超时(具体失败原因由 testCases 回捞)。
         */
        fun recordStatusToResult(status: Int): com.codequest.core.model.SubmissionResult =
            when (status) {
                STATUS_WAITING -> SubmissionResult.Pending
                STATUS_JUDGING -> SubmissionResult.Judging
                STATUS_COMPILE_ERROR -> SubmissionResult.CompileError
                STATUS_OUTPUT_LIMIT -> SubmissionResult.OutputLimitExceeded
                STATUS_MEMORY_LIMIT -> SubmissionResult.MemoryLimitExceeded
                STATUS_TIME_LIMIT -> SubmissionResult.TimeLimitExceeded
                STATUS_RUNTIME_ERROR -> SubmissionResult.RuntimeError
                STATUS_ACCEPTED -> SubmissionResult.Accepted
                else -> SubmissionResult.Unknown(status.toString())
            }
    }
}

/**
 * 题面 Markdown 拼装(旧 makeDescriptionMarkdown 逐字):题面各字段本身即 Markdown
 * 原文(含 LaTeX 公式),直接拼接交由前端管线(marked + KaTeX)渲染。
 */
internal fun buildStatementMarkdown(problem: JsonObject): String {
    val titleSection = "# ${problem.stringOrEmpty("pid")} ${problem.stringOrEmpty("title")}"
    val content = problem.obj("content")
    val description = "## 题目描述\n\n${content?.stringOrEmpty("description").orEmpty()}"
    val formatI = "## 输入格式\n\n${content?.stringOrEmpty("formatI").orEmpty()}"
    val formatO = "## 输出格式\n\n${content?.stringOrEmpty("formatO").orEmpty()}"
    val hint = "## 说明/提示\n\n${content?.stringOrEmpty("hint").orEmpty()}"
    val translation =
        problem.stringOrEmpty("translation").let { raw ->
            if (raw.isEmpty()) "" else "## 题意翻译\n\n$raw"
        }
    val limits = problem.obj("limits")
    val limitsSection =
        if (limits != null) {
            val timeMs =
                limits
                    .arrayOrEmpty("time")
                    .firstOrNull()
                    ?.jsonPrimitive
                    ?.intOrNull
            val memoryKb =
                limits
                    .arrayOrEmpty("memory")
                    .firstOrNull()
                    ?.jsonPrimitive
                    ?.intOrNull
            if (timeMs != null && memoryKb != null) {
                val seconds = timeMs.toDouble() / MILLIS_PER_SECOND
                val megabytes = memoryKb.toDouble() / BYTES_PER_KB
                "## 时间限制\n\n${seconds}s\n\n## 内存限制\n\n${megabytes}MB"
            } else {
                ""
            }
        } else {
            ""
        }
    val samples =
        problem
            .arrayOrEmpty("samples")
            .mapIndexed { index, sample ->
                val pair = sample.jsonArray
                val input =
                    pair
                        .getOrNull(0)
                        ?.jsonPrimitive
                        ?.content
                        .orEmpty()
                val output =
                    pair
                        .getOrNull(1)
                        ?.jsonPrimitive
                        ?.content
                        .orEmpty()
                "## 样例${index + SAMPLE_INDEX_BASE}\n\n#### 输入\n\n```\n$input\n```\n\n#### 输出\n\n```\n$output\n```"
            }.joinToString("\n")
    return "$titleSection\n\n$limitsSection\n\n$description\n\n$formatI\n\n$formatO\n\n$translation\n\n$samples\n\n$hint"
}

private const val MILLIS_PER_SECOND = 1_000.0

private const val BYTES_PER_KB = 1_024.0

private const val SAMPLE_INDEX_BASE = 1

// 文件级 JSON 取值扩展(LuoguApi 类内与 buildStatementMarkdown 共用)
private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.stringOrEmpty(name: String): String = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()

private fun JsonObject.intOrZero(name: String): Int = intOrNullField(name) ?: 0

private fun JsonObject.intOrNullField(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.arrayOrEmpty(name: String): List<JsonElement> = (this[name] as? JsonArray) ?: emptyList()
