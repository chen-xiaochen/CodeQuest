package com.codequest.infra.messaging

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLConnection
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

/**
 * 本地资源与数据 HTTP 服务(WP11,infra 层)。
 *
 * 设计原则(spec 2026-08-14 §4):webview 页面的**数据**全部经本服务 HTTP 拉取,
 * JCEF 桥消息只留给命令与事件,消灭旧版大数据量消息序列化卡顿。
 *
 * - 静态资源:从插件 classpath 下 [resourceRoot](默认 `webview/`,WP12 前端产物落点)读取
 * - REST 数据端点:`/api/` 前缀——`GET /api/health`;题目数据端点(WP20,经 [problemApi]
 *   注入后启用):`GET /api/platforms`、`GET /api/problems`(WP42 起支持 `company=`
 *   公司题单参数、WP56 起支持 `favorite=` 题单参数)、`GET /api/companies`、
 *   `GET /api/favorites`(WP56,题单清单)、`GET /api/problem/{remoteId}`;
 *   账号状态端点(WP38,经 [authApi] 注入后启用):`GET /api/auth/status`、
 *   静默恢复 `POST /api/auth/refresh`(WP48);
 *   提交记录端点(WP21 列表 / WP36 详情,经 [submissionsApi] 注入后启用):
 *   `GET /api/submissions`、`GET /api/submission/{id}/detail`;
 *   站内提交历史端点(1.7.0 F2,经 [submissionHistoryApi] 注入后启用):
 *   `POST /api/submissions/sync`、`GET /api/submissions/remote?platform=&limit=&offset=`;
 *   测试用例端点(1.5.0,经 [testCasesApi] 注入后启用):`GET|POST /api/testcases`、
 *   `POST /api/testcases/update`、`DELETE /api/testcases?id=`;
 *   竞赛端点(WP23,经 [contestApi] 注入后启用):`GET /api/contests[?platform=]`、
 *   `GET /api/contest/{remoteId}?platform=`、提醒订阅 `GET|POST|DELETE /api/contests/reminders`;
 *   题解端点(WP26 列表 / WP31 正文,经 [solutionsApi] 注入后启用):
 *   `GET /api/problem/{remoteId}/solutions?platform=`、
 *   `GET /api/problem/{remoteId}/solutions/{sid}?platform=`;
 *   统计端点(WP29,经 [statsApi] 注入后启用):`GET /api/stats?platform=&days=`;
 *   检索端点(WP30,经 [searchApi] 注入后启用):`GET /api/search`、`GET /api/tags`;
 *   评分端点(1.7.0 F1,经 [ratingApi] 注入后启用):`GET /api/rating/status`、
 *   `POST /api/rating/refresh`、`GET /api/rating/user-contest?platform=`;
 *   复习端点(1.7.0 F3,经 [reviewApi] 注入后启用):`GET /api/review/today`、
 *   `POST /api/review/problems/{problemId}/add`、`POST /api/review/problems/{problemId}/complete`、
 *   `GET|PUT /api/review/config`;
 *   自定义 CSS 端点(WP39,经 [customCss] provider 注入):`GET /api/custom.css`
 *   返回 text/css(未设置空体);
 *   其余骨架 404
 * - 监听 `127.0.0.1` 随机端口(端口 0 由 OS 分配)
 * - [start]/[stop] 幂等,可重复调用
 */
@Suppress("LongParameterList") // API 端点 DI 直通构造,聚合封装不值
class ResourceHttpServer(
    private val resourceRoot: String = DEFAULT_RESOURCE_ROOT,
    private val problemApi: ProblemApi? = null,
    private val submissionsApi: SubmissionsApi? = null,
    private val testCasesApi: TestCasesApi? = null,
    private val contestApi: ContestApi? = null,
    private val solutionsApi: SolutionsApi? = null,
    private val statsApi: StatisticsApi? = null,
    private val searchApi: SearchApi? = null,
    private val authApi: AuthApi? = null,
    /** 评分端点(1.7.0 F1,经 [ratingApi] 注入后启用):`GET /api/rating/status`、
     *  `POST /api/rating/refresh`、`GET /api/rating/user-contest?platform=`。 */
    private val ratingApi: RatingApi? = null,
    /** 站内提交历史端点(1.7.0 F2,经 [submissionHistoryApi] 注入后启用):
     *  `POST /api/submissions/sync`、`GET /api/submissions/remote?platform=&limit=&offset=`。 */
    private val submissionHistoryApi: SubmissionHistoryApi? = null,
    /** 间隔重复复习端点(1.7.0 F3,经 [reviewApi] 注入后启用):`GET /api/review/today`、
     *  `POST /api/review/problems/{problemId}/add`、`POST /api/review/problems/{problemId}/complete`、
     *  `GET|PUT /api/review/config`。 */
    private val reviewApi: ReviewApi? = null,
    /** WP39:题面自定义 CSS(按请求现读设置,改设置即刻对新请求生效);缺省空 = 未设置。 */
    private val customCss: () -> String = { "" },
) {
    private val lock = Any()
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    /** 服务是否正在运行。 */
    val isRunning: Boolean
        get() = synchronized(lock) { server != null }

    /** 实际监听端口;未启动时为 null。 */
    val listeningPort: Int?
        get() = synchronized(lock) { server?.address?.port }

    /**
     * 启动服务(幂等:已运行则直接返回现有端口)。
     *
     * @return 实际监听端口
     */
    fun start(): Int =
        synchronized(lock) {
            server?.let { return it.address.port }
            val newServer = HttpServer.create(InetSocketAddress(HOST, PORT_ANY), BACKLOG)
            val pool = Executors.newCachedThreadPool(WorkerThreadFactory())
            newServer.executor = pool
            newServer.createContext("/") { exchange -> handle(exchange) }
            newServer.start()
            server = newServer
            executor = pool
            checkNotNull(listeningPort) { "server started but port unknown" }
        }

    /** 停止服务并释放线程池(幂等:未启动则无操作)。 */
    fun stop() {
        synchronized(lock) {
            server?.stop(SHUTDOWN_DELAY_SECONDS)
            executor?.shutdown()
            server = null
            executor = null
        }
    }

    // ------------------------------------------------------------------
    // 请求分发
    // ------------------------------------------------------------------

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val path = it.requestURI.path ?: "/"
            runCatching {
                when {
                    path.startsWith(API_PREFIX) -> handleApi(it, path)
                    else -> handleStatic(it, path)
                }
            }.onFailure { error ->
                LOGGER.severe("Error handling $path: $error")
                runCatching {
                    respond(it, STATUS_INTERNAL_ERROR, "text/plain; charset=UTF-8", "Internal Server Error".toByteArray())
                }
            }
        }
    }

    private fun handleApi(
        exchange: HttpExchange,
        path: String,
    ) {
        val query = parseQuery(exchange)
        when {
            path == "/api/health" && exchange.isGet() -> {
                respond(
                    exchange,
                    STATUS_OK,
                    "application/json; charset=UTF-8",
                    HEALTH_RESPONSE.toByteArray(StandardCharsets.UTF_8),
                )
            }

            path == "/api/platforms" && exchange.isGet() -> {
                runProblemApi(exchange) { it.listPlatforms() }
            }

            // WP38:账号状态快照(同步;未装配 authApi 时 503,形态同 stats/search)
            path == "/api/auth/status" && exchange.isGet() -> {
                val api = authApi
                val response =
                    if (api == null) {
                        ProblemApiResponse(STATUS_SERVICE_UNAVAILABLE, """{"error":"api-not-configured"}""")
                    } else {
                        api.authStatus()
                    }
                respondJson(exchange, response.status, response.json, response.headers)
            }

            // WP48:静默登录恢复(并发 restoreSession,suspend;平台源与 status 同一
            // AuthApi 实例注入,恢复置位的 verified 即 status 读到的快照)
            path == "/api/auth/refresh" && exchange.requestMethod.uppercase() == "POST" -> {
                val api = authApi
                val response =
                    if (api == null) {
                        ProblemApiResponse(STATUS_SERVICE_UNAVAILABLE, """{"error":"api-not-configured"}""")
                    } else {
                        runBlocking { api.authRefresh() }
                    }
                respondJson(exchange, response.status, response.json, response.headers)
            }

            path == "/api/problems" && exchange.isGet() -> {
                runProblemApi(exchange) { api -> api.listProblems(query) }
            }

            // WP39:题面自定义 CSS(text/css 透传;未设置回 200 空体——样式是附属能力,
            // 非数据 API,不回 503;每次请求现读设置,settings apply 后无需重启)
            path == "/api/custom.css" && exchange.isGet() -> {
                val css = customCss().toByteArray(StandardCharsets.UTF_8)
                respond(exchange, STATUS_OK, CSS_CONTENT_TYPE, css)
            }

            else -> {
                // WP20/WP23/WP29/WP31/WP36:各路由家族单独提取(handleApi 圈复杂度门禁);
                // 依序尝试,全部未命中再回 404
                val families =
                    listOf(
                        { handleProblemRoutes(exchange, path, query) },
                        { handleContestRoutes(exchange, path, query) },
                        { handleSubmissionRoutes(exchange, path, query) },
                        { handleTestCaseRoutes(exchange, path, query) },
                        { handleLocalDataRoutes(exchange, path, query) },
                        { handleCatalogRoutes(exchange, path, query) },
                        { handleRatingRoutes(exchange, path, query) },
                        { handleReviewRoutes(exchange, path) },
                    )
                if (families.none { it() }) {
                    respond(exchange, STATUS_NOT_FOUND, "application/json; charset=UTF-8", NOT_FOUND_RESPONSE.toByteArray())
                }
            }
        }
    }

    /**
     * `/api/problem/` 家族路由(WP20 详情 / WP26 列表 / WP31 正文;三者共享
     * 前缀,匹配顺序:正文(含 `/solutions/` 中缀)→ 列表(`/solutions` 后缀)
     * → 详情(其余))。非 GET 或前缀不匹配返回 false 交回上层。
     */
    private fun handleProblemRoutes(
        exchange: HttpExchange,
        path: String,
        query: Map<String, String>,
    ): Boolean {
        if (!path.startsWith(PROBLEM_DETAIL_PREFIX) || !exchange.isGet()) return false
        when {
            // WP31:题解正文 `/api/problem/{remoteId}/solutions/{sid}`
            path.contains(SOLUTIONS_CONTENT_MARKER) -> {
                val parsed = parseSolutionContentPath(path)
                if (parsed == null) {
                    respondJson(exchange, STATUS_BAD_REQUEST, SOLUTION_CONTENT_BAD_REQUEST)
                } else {
                    val (remoteId, solutionId) = parsed
                    runSolutionsApi(exchange) { api -> api.solutionContent(remoteId, solutionId, query) }
                }
            }

            // WP26:题解引用列表 `/api/problem/{remoteId}/solutions`
            path.endsWith(SOLUTIONS_ROUTE_SUFFIX) -> {
                val remoteId =
                    path
                        .removePrefix(PROBLEM_DETAIL_PREFIX)
                        .removeSuffix(SOLUTIONS_ROUTE_SUFFIX)
                        .takeIf { it.isNotEmpty() }
                        ?.let(::urlDecode)
                if (remoteId.isNullOrEmpty()) {
                    respondJson(exchange, STATUS_BAD_REQUEST, BAD_REQUEST_RESPONSE)
                } else {
                    runSolutionsApi(exchange) { api -> api.listSolutions(remoteId, query) }
                }
            }

            // WP20:题目详情 `/api/problem/{remoteId}`
            else -> {
                val remoteId =
                    path
                        .removePrefix(PROBLEM_DETAIL_PREFIX)
                        .takeIf { it.isNotEmpty() }
                        ?.let(::urlDecode)
                if (remoteId.isNullOrEmpty()) {
                    respondJson(exchange, STATUS_BAD_REQUEST, BAD_REQUEST_RESPONSE)
                } else {
                    runProblemApi(exchange) { api -> api.problemDetail(remoteId, query) }
                }
            }
        }
        return true
    }

    /** 提交端点路由家族(WP21 列表 / WP36 详情 / 1.7.0 F2 站内历史;形态同 [handleContestRoutes])。 */
    private fun handleSubmissionRoutes(
        exchange: HttpExchange,
        path: String,
        query: Map<String, String>,
    ): Boolean {
        val method = exchange.requestMethod.uppercase()
        when {
            path == "/api/submissions" && exchange.isGet() -> {
                runSubmissionsApi(exchange) { api -> api.listSubmissions(query) }
            }

            // 1.7.0 F2:站内提交历史同步(同步在 HTTP 工作线程跑完再返回结果)
            path == "/api/submissions/sync" && method == "POST" -> {
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                runSubmissionHistoryApi(exchange) { api -> api.syncSubmissions(body) }
            }

            // 1.7.0 F2:站内提交历史分页列表(倒序 + total)
            path == "/api/submissions/remote" && exchange.isGet() -> {
                runSubmissionHistoryApi(exchange) { api -> api.listRemote(query) }
            }

            // WP36:提交详情 `/api/submission/{id}/detail`(id 为本地 submission id,
            // 不含 "/";URL 解码后交端点。与列表路由 `/api/submissions` 单复数区分;
            // 无 /detail 后缀的路径不匹配 → 交回上层 404)
            path.startsWith(SUBMISSION_DETAIL_PREFIX) && path.endsWith(SUBMISSION_DETAIL_MARKER) && exchange.isGet() -> {
                val id =
                    path
                        .removePrefix(SUBMISSION_DETAIL_PREFIX)
                        .removeSuffix(SUBMISSION_DETAIL_MARKER)
                if (id.isEmpty() || id.contains('/')) {
                    respondJson(
                        exchange,
                        STATUS_BAD_REQUEST,
                        """{"error":"bad-request","message":"invalid submission id path segment"}""",
                    )
                } else {
                    runSubmissionsApi(exchange) { api -> api.submissionDetail(urlDecode(id)) }
                }
            }

            else -> {
                return false
            }
        }
        return true
    }

    /** 测试用例端点路由家族(1.5.0 列表/新增/更新/删除;形态同 [handleSubmissionRoutes])。 */
    private fun handleTestCaseRoutes(
        exchange: HttpExchange,
        path: String,
        query: Map<String, String>,
    ): Boolean {
        val method = exchange.requestMethod.uppercase()
        when {
            path == "/api/testcases" && exchange.isGet() -> {
                runTestCasesApi(exchange) { api -> api.listTestCases(query) }
            }

            path == "/api/testcases" && method == "POST" -> {
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                runTestCasesApi(exchange) { api -> api.createTestCase(body) }
            }

            path == "/api/testcases/update" && method == "POST" -> {
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                runTestCasesApi(exchange) { api -> api.updateTestCase(body) }
            }

            path == "/api/testcases" && method == "DELETE" -> {
                runTestCasesApi(exchange) { api -> api.deleteTestCase(query) }
            }

            else -> {
                return false
            }
        }
        return true
    }

    /** 目录清单端点路由(WP42 companies / WP56 favorites;下拉数据源,均走 ProblemApi)。 */
    private fun handleCatalogRoutes(
        exchange: HttpExchange,
        path: String,
        query: Map<String, String>,
    ): Boolean {
        when {
            // WP42:公司标签清单(公司下拉数据源;company 参数的公司题单走 /api/problems)
            path == "/api/companies" && exchange.isGet() -> {
                runProblemApi(exchange) { api -> api.listCompanies(query) }
            }

            // WP56:题单清单(学习计划组 + 我的题单组;favorite 参数的题单查询走 /api/problems)
            path == "/api/favorites" && exchange.isGet() -> {
                runProblemApi(exchange) { api -> api.listFavorites(query) }
            }

            else -> {
                return false
            }
        }
        return true
    }

    /** 评分端点路由家族(1.7.0 F1 状态/刷新/用户竞赛分;形态同 [handleLocalDataRoutes])。 */
    private fun handleRatingRoutes(
        exchange: HttpExchange,
        path: String,
        query: Map<String, String>,
    ): Boolean {
        val method = exchange.requestMethod.uppercase()
        when {
            path == "/api/rating/status" && exchange.isGet() -> {
                runRatingApi(exchange) { api -> api.status() }
            }

            path == "/api/rating/refresh" && method == "POST" -> {
                runRatingApi(exchange) { api -> api.refresh() }
            }

            path == "/api/rating/user-contest" && exchange.isGet() -> {
                runRatingApi(exchange) { api -> api.userContest(query) }
            }

            else -> {
                return false
            }
        }
        return true
    }

    /** 执行 [RatingApi] 调用并回写应答(形态同 [runProblemApi])。 */
    private fun runRatingApi(
        exchange: HttpExchange,
        block: suspend (RatingApi) -> ProblemApiResponse,
    ) {
        val api = ratingApi
        val response =
            if (api == null) {
                ProblemApiResponse(STATUS_SERVICE_UNAVAILABLE, """{"error":"api-not-configured"}""")
            } else {
                runBlocking { block(api) }
            }
        respondJson(exchange, response.status, response.json, response.headers)
    }

    /** 复习端点路由家族(1.7.0 F3 today/add/complete/config;形态同 [handleRatingRoutes])。 */
    private fun handleReviewRoutes(
        exchange: HttpExchange,
        path: String,
    ): Boolean {
        val method = exchange.requestMethod.uppercase()
        when {
            path == "/api/review/today" && exchange.isGet() -> {
                runReviewApi(exchange) { api -> api.today() }
            }

            path == "/api/review/config" && exchange.isGet() -> {
                runReviewApi(exchange) { api -> api.getConfig() }
            }

            // 间隔配置写入(body {"intervals":[...]};非法 400 附默认值提示)
            path == "/api/review/config" && method == "PUT" -> {
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                runReviewApi(exchange) { api -> api.putConfig(body) }
            }

            path.startsWith(REVIEW_PROBLEM_PREFIX) && method == "POST" &&
                (path.endsWith(REVIEW_ADD_SUFFIX) || path.endsWith(REVIEW_COMPLETE_SUFFIX)) -> {
                val suffix = if (path.endsWith(REVIEW_ADD_SUFFIX)) REVIEW_ADD_SUFFIX else REVIEW_COMPLETE_SUFFIX
                val problemId =
                    path
                        .removePrefix(REVIEW_PROBLEM_PREFIX)
                        .removeSuffix(suffix)
                        .takeIf { it.isNotEmpty() }
                        ?.let(::urlDecode)
                if (problemId == null) {
                    respondJson(exchange, STATUS_BAD_REQUEST, REVIEW_PROBLEM_BAD_REQUEST)
                } else if (suffix == REVIEW_ADD_SUFFIX) {
                    runReviewApi(exchange) { api -> api.add(problemId) }
                } else {
                    runReviewApi(exchange) { api -> api.complete(problemId) }
                }
            }

            else -> {
                return false
            }
        }
        return true
    }

    /** 执行 [ReviewApi] 调用并回写应答(形态同 [runRatingApi],1.7.0 F3)。 */
    private fun runReviewApi(
        exchange: HttpExchange,
        block: suspend (ReviewApi) -> ProblemApiResponse,
    ) {
        val api = reviewApi
        val response =
            if (api == null) {
                ProblemApiResponse(STATUS_SERVICE_UNAVAILABLE, """{"error":"api-not-configured"}""")
            } else {
                runBlocking { block(api) }
            }
        respondJson(exchange, response.status, response.json, response.headers)
    }

    /** 本地库数据端点路由(WP29 stats / WP30 search;形态同 [handleContestRoutes])。 */
    private fun handleLocalDataRoutes(
        exchange: HttpExchange,
        path: String,
        query: Map<String, String>,
    ): Boolean {
        when {
            // WP29:刷题统计(summary + 热力图 + 趋势)
            path == "/api/stats" && exchange.isGet() -> {
                val api = statsApi
                val response =
                    if (api == null) {
                        ProblemApiResponse(STATUS_SERVICE_UNAVAILABLE, """{"error":"api-not-configured"}""")
                    } else {
                        runBlocking { api.statistics(query) }
                    }
                respondJson(exchange, response.status, response.json, response.headers)
            }

            // WP30:跨题全文检索(三源聚合 + 标签/平台过滤)与标签清单
            path == "/api/search" && exchange.isGet() -> {
                val api = searchApi
                val response =
                    if (api == null) {
                        ProblemApiResponse(STATUS_SERVICE_UNAVAILABLE, """{"error":"api-not-configured"}""")
                    } else {
                        runBlocking { api.search(query) }
                    }
                respondJson(exchange, response.status, response.json, response.headers)
            }

            path == "/api/tags" && exchange.isGet() -> {
                val api = searchApi
                val response =
                    if (api == null) {
                        ProblemApiResponse(STATUS_SERVICE_UNAVAILABLE, """{"error":"api-not-configured"}""")
                    } else {
                        runBlocking { api.listTags(query) }
                    }
                respondJson(exchange, response.status, response.json, response.headers)
            }

            else -> {
                return false
            }
        }
        return true
    }

    /** 竞赛端点路由(WP23):列表(无参=平台标签)、详情、提醒订阅(POST/DELETE/回显)。 */
    private fun handleContestRoutes(
        exchange: HttpExchange,
        path: String,
        query: Map<String, String>,
    ): Boolean {
        val method = exchange.requestMethod.uppercase()
        when {
            path == "/api/contests" && exchange.isGet() && query["platform"].isNullOrBlank() -> {
                runContestApi(exchange) { api -> api.listContestPlatforms() }
            }

            path == "/api/contests" && exchange.isGet() -> {
                runContestApi(exchange) { api -> api.listContests(query) }
            }

            path == "/api/contests/reminders" && exchange.isGet() -> {
                runContestApi(exchange) { api -> api.listReminders(query) }
            }

            path == "/api/contests/reminders" && method == "POST" -> {
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                runContestApi(exchange) { api -> api.subscribeReminder(body) }
            }

            path == "/api/contests/reminders" && method == "DELETE" -> {
                runContestApi(exchange) { api -> api.unsubscribeReminder(query) }
            }

            path.startsWith(CONTEST_DETAIL_PREFIX) && exchange.isGet() -> {
                val remoteId =
                    path
                        .removePrefix(CONTEST_DETAIL_PREFIX)
                        .takeIf { it.isNotEmpty() }
                        ?.let(::urlDecode)
                if (remoteId.isNullOrEmpty()) {
                    respondJson(exchange, STATUS_BAD_REQUEST, BAD_REQUEST_RESPONSE)
                } else {
                    runContestApi(exchange) { api -> api.contestDetail(remoteId, query) }
                }
            }

            else -> {
                return false
            }
        }
        return true
    }

    /** 执行 [ContestApi] 调用并回写应答(形态同 [runProblemApi])。 */
    private fun runContestApi(
        exchange: HttpExchange,
        block: suspend (ContestApi) -> ProblemApiResponse,
    ) {
        val api = contestApi
        val response =
            if (api == null) {
                ProblemApiResponse(STATUS_SERVICE_UNAVAILABLE, """{"error":"api-not-configured"}""")
            } else {
                runBlocking { block(api) }
            }
        respondJson(exchange, response.status, response.json, response.headers)
    }

    /** 执行 [SubmissionsApi] 调用并回写应答(形态同 [runProblemApi],WP36 提取路由家族时同步收口)。 */
    private fun runSubmissionsApi(
        exchange: HttpExchange,
        block: suspend (SubmissionsApi) -> ProblemApiResponse,
    ) {
        val api = submissionsApi
        val response =
            if (api == null) {
                ProblemApiResponse(STATUS_SERVICE_UNAVAILABLE, """{"error":"api-not-configured"}""")
            } else {
                runBlocking { block(api) }
            }
        respondJson(exchange, response.status, response.json, response.headers)
    }

    /** 执行 [SubmissionHistoryApi] 调用并回写应答(形态同 [runRatingApi],1.7.0 F2)。 */
    private fun runSubmissionHistoryApi(
        exchange: HttpExchange,
        block: suspend (SubmissionHistoryApi) -> ProblemApiResponse,
    ) {
        val api = submissionHistoryApi
        val response =
            if (api == null) {
                ProblemApiResponse(STATUS_SERVICE_UNAVAILABLE, """{"error":"api-not-configured"}""")
            } else {
                runBlocking { block(api) }
            }
        respondJson(exchange, response.status, response.json, response.headers)
    }

    /** 执行 [TestCasesApi] 调用并回写应答(形态同 [runProblemApi],1.5.0)。 */
    private fun runTestCasesApi(
        exchange: HttpExchange,
        block: suspend (TestCasesApi) -> ProblemApiResponse,
    ) {
        val api = testCasesApi
        val response =
            if (api == null) {
                ProblemApiResponse(STATUS_SERVICE_UNAVAILABLE, """{"error":"api-not-configured"}""")
            } else {
                runBlocking { block(api) }
            }
        respondJson(exchange, response.status, response.json, response.headers)
    }

    /** 执行 [SolutionsApi] 调用并回写应答(形态同 [runProblemApi],WP26)。 */
    private fun runSolutionsApi(
        exchange: HttpExchange,
        block: suspend (SolutionsApi) -> ProblemApiResponse,
    ) {
        val api = solutionsApi
        val response =
            if (api == null) {
                ProblemApiResponse(STATUS_SERVICE_UNAVAILABLE, """{"error":"api-not-configured"}""")
            } else {
                runBlocking { block(api) }
            }
        respondJson(exchange, response.status, response.json, response.headers)
    }

    /** 执行 [ProblemApi] 调用(suspend,阻塞 HTTP 工作线程可接受)并回写应答。 */
    private fun runProblemApi(
        exchange: HttpExchange,
        block: suspend (ProblemApi) -> ProblemApiResponse,
    ) {
        val api = problemApi
        val response =
            if (api == null) {
                ProblemApiResponse(STATUS_SERVICE_UNAVAILABLE, """{"error":"api-not-configured"}""")
            } else {
                runBlocking { block(api) }
            }
        respondJson(exchange, response.status, response.json, response.headers)
    }

    private fun respondJson(
        exchange: HttpExchange,
        status: Int,
        json: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        respond(exchange, status, "application/json; charset=UTF-8", json.toByteArray(StandardCharsets.UTF_8))
    }

    /** 解析 rawQuery 为解码后的参数表(重复键取首个)。 */
    private fun parseQuery(exchange: HttpExchange): Map<String, String> {
        val raw = exchange.requestURI.rawQuery ?: return emptyMap()
        return raw
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val index = pair.indexOf('=')
                if (index < 0) {
                    urlDecode(pair) to ""
                } else {
                    urlDecode(pair.substring(0, index)) to urlDecode(pair.substring(index + 1))
                }
            }
    }

    /**
     * 解析题解正文路径 `/api/problem/{remoteId}/solutions/{sid}`(WP31)。
     *
     * @return (remoteId, solutionId);任一段缺失/为空返回 null(交由调用方回 400)。
     *         分隔以首个 `/solutions/` 标记为准——remoteId(LC slug/洛谷 pid)
     *         不含路径段,标记前的所有内容都归 remoteId。
     */
    private fun parseSolutionContentPath(path: String): Pair<String, String>? {
        val rest = path.removePrefix(PROBLEM_DETAIL_PREFIX)
        val markerIndex = rest.indexOf(SOLUTIONS_CONTENT_MARKER)
        if (markerIndex <= 0) return null
        val remoteId = urlDecode(rest.substring(0, markerIndex))
        val solutionId = urlDecode(rest.substring(markerIndex + SOLUTIONS_CONTENT_MARKER.length))
        if (remoteId.isEmpty() || solutionId.isEmpty()) return null
        return remoteId to solutionId
    }

    private fun urlDecode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun handleStatic(
        exchange: HttpExchange,
        path: String,
    ) {
        if (!exchange.isGet()) {
            respond(exchange, STATUS_METHOD_NOT_ALLOWED, "text/plain; charset=UTF-8", "Method Not Allowed".toByteArray())
            return
        }
        // 防目录穿越:拒绝包含 ".." 段的路径
        val rawRelative = path.removePrefix("/")
        if (rawRelative.split('/').any { it == ".." }) {
            respond(exchange, STATUS_BAD_REQUEST, "text/plain; charset=UTF-8", "Bad Request".toByteArray())
            return
        }
        // 目录路径(/problems/)解析到其 index.html;jar 内目录资源不可直接 getResource
        val relative =
            when {
                rawRelative.isEmpty() -> DEFAULT_INDEX
                rawRelative.endsWith("/") -> rawRelative + DEFAULT_INDEX
                else -> rawRelative
            }
        val resourceUrl = javaClass.classLoader.getResource("$resourceRoot/$relative")
        if (resourceUrl == null) {
            val message = "Resource not found: $path".toByteArray()
            respond(exchange, STATUS_NOT_FOUND, "text/plain; charset=UTF-8", message)
            return
        }
        val bytes = resourceUrl.openStream().use { it.readBytes() }
        respond(exchange, STATUS_OK, contentTypeFor(relative), bytes)
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        contentType: String,
        body: ByteArray,
    ) {
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(status, if (body.isEmpty()) EMPTY_BODY else body.size.toLong())
        exchange.responseBody.use { output ->
            if (body.isNotEmpty()) output.write(body)
        }
    }

    private fun HttpExchange.isGet(): Boolean =
        requestMethod.equals("GET", ignoreCase = true) || requestMethod.equals("HEAD", ignoreCase = true)

    private fun contentTypeFor(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html; charset=UTF-8"
            lower.endsWith(".css") -> "text/css; charset=UTF-8"
            lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript; charset=UTF-8"
            lower.endsWith(".json") || lower.endsWith(".map") -> "application/json; charset=UTF-8"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".ico") -> "image/x-icon"
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".woff") -> "font/woff"
            lower.endsWith(".ttf") -> "font/ttf"
            else -> URLConnection.guessContentTypeFromName(lower) ?: "application/octet-stream"
        }
    }

    private class WorkerThreadFactory : ThreadFactory {
        private val counter = AtomicInteger(0)

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "cq-http-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    companion object {
        private val LOGGER = Logger.getLogger(ResourceHttpServer::class.java.name)

        /** 插件 classpath 下的静态资源根目录(WP12 前端构建产物输出位置)。 */
        const val DEFAULT_RESOURCE_ROOT = "webview"

        private const val DEFAULT_INDEX = "index.html"
        private const val HOST = "127.0.0.1"
        private const val PORT_ANY = 0
        private const val BACKLOG = 0
        private const val API_PREFIX = "/api/"
        private const val PROBLEM_DETAIL_PREFIX = "/api/problem/"

        /** WP36:提交详情路由前缀(`/api/submission/{id}/detail`)。 */
        private const val SUBMISSION_DETAIL_PREFIX = "/api/submission/"

        /** WP36:提交详情路由后缀(拼在 id 之后)。 */
        private const val SUBMISSION_DETAIL_MARKER = "/detail"

        /** WP26:题解引用列表路由后缀(拼在 remoteId 之后)。 */
        private const val SOLUTIONS_ROUTE_SUFFIX = "/solutions"

        /** WP31:题解正文路由中缀(`/solutions/` 夹在 remoteId 与 sid 之间)。 */
        private const val SOLUTIONS_CONTENT_MARKER = "/solutions/"
        private const val CONTEST_DETAIL_PREFIX = "/api/contest/"

        /** 1.7.0 F3:复习按题操作路由前缀(`/api/review/problems/{problemId}/...`)。 */
        private const val REVIEW_PROBLEM_PREFIX = "/api/review/problems/"

        /** 1.7.0 F3:手动加入复习路由后缀。 */
        private const val REVIEW_ADD_SUFFIX = "/add"

        /** 1.7.0 F3:手动完成当日复习路由后缀。 */
        private const val REVIEW_COMPLETE_SUFFIX = "/complete"

        /** 1.7.0 F3:复习按题操作缺 problemId 段错误体。 */
        private const val REVIEW_PROBLEM_BAD_REQUEST =
            """{"error":"bad-request","message":"missing problemId path segment"}"""
        private const val SHUTDOWN_DELAY_SECONDS = 0
        private const val STATUS_OK = 200
        private const val STATUS_BAD_REQUEST = 400
        private const val STATUS_NOT_FOUND = 404
        private const val STATUS_INTERNAL_ERROR = 500
        private const val STATUS_METHOD_NOT_ALLOWED = 405
        private const val STATUS_SERVICE_UNAVAILABLE = 503
        private const val EMPTY_BODY = -1L

        /** WP39:自定义 CSS 端点的 Content-Type(与静态 .css 资源一致)。 */
        private const val CSS_CONTENT_TYPE = "text/css; charset=UTF-8"
        private const val HEALTH_RESPONSE = """{"status":"ok","version":"0.1.0"}"""
        private const val NOT_FOUND_RESPONSE = """{"error":"not found"}"""
        private const val BAD_REQUEST_RESPONSE = """{"error":"bad-request","message":"missing remoteId path segment"}"""

        /** WP31:题解正文路由的 remoteId/sid 缺段错误体。 */
        private const val SOLUTION_CONTENT_BAD_REQUEST =
            """{"error":"bad-request","message":"missing remoteId or solutionId path segment"}"""
    }
}
