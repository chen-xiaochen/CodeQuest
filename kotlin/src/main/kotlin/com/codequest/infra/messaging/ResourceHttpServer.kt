package com.codequest.infra.messaging

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLConnection
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
 * - REST 数据端点:`/api/` 前缀,当前提供 `GET /api/health`,其余骨架 404
 * - 监听 `127.0.0.1` 随机端口(端口 0 由 OS 分配)
 * - [start]/[stop] 幂等,可重复调用
 */
class ResourceHttpServer(
    private val resourceRoot: String = DEFAULT_RESOURCE_ROOT,
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
        when {
            path == "/api/health" && exchange.isGet() -> {
                respond(
                    exchange,
                    STATUS_OK,
                    "application/json; charset=UTF-8",
                    HEALTH_RESPONSE.toByteArray(StandardCharsets.UTF_8),
                )
            }

            else -> {
                respond(exchange, STATUS_NOT_FOUND, "application/json; charset=UTF-8", NOT_FOUND_RESPONSE.toByteArray())
            }
        }
    }

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
        private const val SHUTDOWN_DELAY_SECONDS = 0
        private const val STATUS_OK = 200
        private const val STATUS_BAD_REQUEST = 400
        private const val STATUS_NOT_FOUND = 404
        private const val STATUS_INTERNAL_ERROR = 500
        private const val STATUS_METHOD_NOT_ALLOWED = 405
        private const val EMPTY_BODY = -1L
        private const val HEALTH_RESPONSE = """{"status":"ok","version":"0.1.0"}"""
        private const val NOT_FOUND_RESPONSE = """{"error":"not found"}"""
    }
}
