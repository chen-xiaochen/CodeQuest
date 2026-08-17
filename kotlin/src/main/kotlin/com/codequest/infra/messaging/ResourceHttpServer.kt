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
 *   注入后启用):`GET /api/platforms`、`GET /api/problems`、`GET /api/problem/{remoteId}`;
 *   提交记录端点(WP21,经 [submissionsApi] 注入后启用):`GET /api/submissions`;
 *   竞赛端点(WP23,经 [contestApi] 注入后启用):`GET /api/contests[?platform=]`、
 *   `GET /api/contest/{remoteId}?platform=`、提醒订阅 `GET|POST|DELETE /api/contests/reminders`;
 *   其余骨架 404
 * - 监听 `127.0.0.1` 随机端口(端口 0 由 OS 分配)
 * - [start]/[stop] 幂等,可重复调用
 */
class ResourceHttpServer(
    private val resourceRoot: String = DEFAULT_RESOURCE_ROOT,
    private val problemApi: ProblemApi? = null,
    private val submissionsApi: SubmissionsApi? = null,
    private val contestApi: ContestApi? = null,
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

            path == "/api/problems" && exchange.isGet() -> {
                runProblemApi(exchange) { api -> api.listProblems(query) }
            }

            path.startsWith(PROBLEM_DETAIL_PREFIX) && exchange.isGet() -> {
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

            path == "/api/submissions" && exchange.isGet() -> {
                val api = submissionsApi
                val response =
                    if (api == null) {
                        ProblemApiResponse(STATUS_SERVICE_UNAVAILABLE, """{"error":"api-not-configured"}""")
                    } else {
                        runBlocking { api.listSubmissions(query) }
                    }
                respondJson(exchange, response.status, response.json, response.headers)
            }

            else -> {
                // WP23:竞赛路由单独提取(handleApi 圈复杂度门禁);未命中再回 404
                if (!handleContestRoutes(exchange, path, query)) {
                    respond(exchange, STATUS_NOT_FOUND, "application/json; charset=UTF-8", NOT_FOUND_RESPONSE.toByteArray())
                }
            }
        }
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
        private const val CONTEST_DETAIL_PREFIX = "/api/contest/"
        private const val SHUTDOWN_DELAY_SECONDS = 0
        private const val STATUS_OK = 200
        private const val STATUS_BAD_REQUEST = 400
        private const val STATUS_NOT_FOUND = 404
        private const val STATUS_INTERNAL_ERROR = 500
        private const val STATUS_METHOD_NOT_ALLOWED = 405
        private const val STATUS_SERVICE_UNAVAILABLE = 503
        private const val EMPTY_BODY = -1L
        private const val HEALTH_RESPONSE = """{"status":"ok","version":"0.1.0"}"""
        private const val NOT_FOUND_RESPONSE = """{"error":"not found"}"""
        private const val BAD_REQUEST_RESPONSE = """{"error":"bad-request","message":"missing remoteId path segment"}"""
    }
}
