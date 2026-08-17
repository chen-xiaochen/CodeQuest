package com.codequest.infra.http

import com.codequest.core.model.PlatformId
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.util.date.GMTDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OJ 平台 HTTP 会话层(WP18):ktor-client 封装 + 按平台隔离的 CookieJar +
 * 统一错误 → 领域异常映射。
 *
 * ## 设计决策
 *
 * - **每平台独立 [HttpClient] + 独立 [CookiesStorage]**:ktor 的 Cookie 存储挂在
 *   client 实例上,天然做到「一个平台一个 Jar」,leetcode 与 codeforces(乃至
 *   访问同一 host 时)互不可见对方 cookie(单测固化)。各平台 client 共享
 *   **同一个引擎实例**(OkHttp 连接池复用);传入引擎实例的 client 关闭时不会
 *   关引擎,由本类 [close] 统一释放。
 * - **引擎可注入**:生产用 OkHttp(成熟连接池/超时语义,旧版 CodeEpiphany 同款),
 *   测试注入 [io.ktor.client.engine.mock.MockEngine],Windows 单测不真连网络。
 * - **单例([shared])**而非 project 服务:HTTP 会话与打开的 IntelliJ 工程无关,
 *   且 `@Service` 需要改 plugin.xml 注册(WP18 约束只加 infra 增量);适配器
 *   经 [shared] 取进程级单例。
 * - **不做**:代理配置、HTTP 缓存、自动重定向(WP19 需要时再加,YAGNI)。
 *
 * ## 错误映射
 *
 * 所有请求方法经 [requestText] 收口:非 2xx 抛 core 领域异常
 * (见 [OJErrorMapping]),适配器只处理 OJException 体系。
 *
 * ## 给 WP19 的用法
 *
 * ```kotlin
 * val http = OJHttpClient.shared()
 * // 登录:粘贴 cookie 后注入 + 持久化
 * http.updateCookies(PlatformId("leetcode"), "https://leetcode.com", pastedCookies)
 * val body = http.postGraphQL(
 *     PlatformId("leetcode"), "https://leetcode.com/graphql",
 *     query = "query globalData { userStatus { isSignedIn } }",
 * )
 * ```
 */
class OJHttpClient(
    engine: HttpClientEngine = defaultEngine(),
) : Closeable {
    private val engine: HttpClientEngine = engine
    private val clients = ConcurrentHashMap<PlatformId, HttpClient>()
    private val cookieStorages = ConcurrentHashMap<PlatformId, CookiesStorage>()

    /** 某平台专属 client(懒创建,线程安全);CookieJar 为该平台私有 [AcceptAllCookiesStorage]。 */
    internal fun clientFor(platform: PlatformId): HttpClient =
        clients.computeIfAbsent(platform) {
            HttpClient(engine) {
                expectSuccess = false // 非 2xx 由 OJErrorMapping 统一翻译,不用 ktor 内建异常
                followRedirects = false
                install(HttpCookies) {
                    storage = storageFor(platform)
                }
                install(HttpTimeout) {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = SOCKET_TIMEOUT_MS
                }
            }
        }

    private fun storageFor(platform: PlatformId): CookiesStorage = cookieStorages.computeIfAbsent(platform) { AcceptAllCookiesStorage() }

    /** GET,返回响应体文本;非 2xx 抛领域异常。 */
    suspend fun get(
        platform: PlatformId,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String =
        requestText(platform) {
            get(urlString = url) { applyBaseHeaders(headers) }
        }

    /** POST JSON(原始串承载,GraphQL 同走此通道),返回响应体文本。 */
    suspend fun postJson(
        platform: PlatformId,
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
    ): String =
        requestText(platform) {
            post(urlString = url) {
                applyBaseHeaders(headers)
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
        }

    /**
     * GraphQL 便捷方法:把 query/variables 包装成 `{"query":...,"variables":...}`
     * JSON 信封后 POST,返回原始响应体文本(调用方用 kotlinx-serialization 解析)。
     */
    suspend fun postGraphQL(
        platform: PlatformId,
        url: String,
        query: String,
        variables: String? = null,
        operationName: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val envelope =
            buildJsonObject {
                put("query", JsonPrimitive(query))
                variables?.let { put("variables", Json.parseToJsonElement(it)) }
                operationName?.let { put("operationName", JsonPrimitive(it)) }
            }.toString()
        return postJson(platform, url, envelope, headers)
    }

    /**
     * POST 表单(WP25):body 为 `name=value&...` 的
     * `application/x-www-form-urlencoded`(键值经 [encodeURLParameter] 转义,
     * 空格转 `+`),错误映射照旧走 [requestText]。CF/AtCoder 提交端点共用。
     */
    suspend fun postForm(
        platform: PlatformId,
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val encodedBody =
            form.entries.joinToString(separator = "&") { (name, value) ->
                name.encodeURLParameter(spaceToPlus = true) + "=" + value.encodeURLParameter(spaceToPlus = true)
            }
        return requestText(platform) {
            post(urlString = url) {
                applyBaseHeaders(headers)
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(encodedBody)
            }
        }
    }

    /**
     * 预置 cookie(登录注入):把 `name=value` 们写入该平台 CookieJar,
     * 之后的请求自动携带。域/路径取自 [url](各平台主站地址)。
     */
    suspend fun updateCookies(
        platform: PlatformId,
        url: String,
        cookies: Map<String, String>,
    ) {
        val storage = storageFor(platform)
        val baseUrl = URLBuilder(url).build()
        cookies.forEach { (name, value) ->
            storage.addCookie(
                baseUrl,
                Cookie(
                    name = name,
                    value = value,
                    path = "/",
                    expires = GMTDate(FAR_FUTURE_EPOCH_MS),
                ),
            )
        }
    }

    /** 查询该平台 CookieJar 中匹配 [url] 的 cookie(快照)。 */
    suspend fun cookiesFor(
        platform: PlatformId,
        url: String,
    ): List<Cookie> = cookieStorages[platform]?.get(URLBuilder(url).build()) ?: emptyList()

    /** 导出 `a=1; b=2` 形式的 Cookie 请求头(供 JCEF 登录页等外部消费)。 */
    suspend fun exportCookieHeader(
        platform: PlatformId,
        url: String,
    ): String = cookiesFor(platform, url).joinToString("; ") { "${it.name}=${it.value}" }

    /**
     * 清空某平台的 CookieJar(退出登录);其他平台不受影响。
     * ktor 3.5 的 CookiesStorage 已无 cleanup(),改为丢弃该平台的 storage 与 client,
     * 下次请求懒重建全新 Jar。
     */
    suspend fun clearCookies(platform: PlatformId) {
        cookieStorages.remove(platform)?.close()
        clients.remove(platform)?.close()
    }

    override fun close() {
        clients.values.forEach { it.close() } // 传入引擎实例的 client 不负责关引擎
        clients.clear()
        engine.close()
    }

    /** 请求收口:统一基础头 + 响应体读取 + 非 2xx → 领域异常。 */
    private suspend fun requestText(
        platform: PlatformId,
        block: suspend HttpClient.() -> HttpResponse,
    ): String {
        val response = clientFor(platform).block()
        val body = response.bodyAsText()
        OJErrorMapping
            .toDomainException(
                status = response.status,
                body = body,
                retryAfterHeaderValue = response.headers[HttpHeaders.RetryAfter],
                cfMitigatedHeaderValue = response.headers[CF_MITIGATED_HEADER],
            )?.let { throw it }
        return body
    }

    companion object {
        /** 常见桌面 Chrome UA:OJ 站点对非常规 UA 更容易触发反爬。 */
        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36 CodeQuest/0.1"

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SOCKET_TIMEOUT_MS = 60_000L

        /** OkHttp 引擎侧超时(毫秒):连接 15s、读写 60s、整调用 300s(题面抓取可能较慢)。 */
        private const val OKHTTP_CONNECT_TIMEOUT_MS = 15_000L
        private const val OKHTTP_SOCKET_TIMEOUT_MS = 60_000L
        private const val OKHTTP_CALL_TIMEOUT_MS = 300_000L

        private const val CF_MITIGATED_HEADER = "cf-mitigated"

        /** 持久 cookie 的远期过期时间(GMTDate 毫秒时间戳,约公元 9600 年内)。 */
        private const val FAR_FUTURE_EPOCH_MS = 253_402_300_799_000L

        /** 进程级单例(理由见类 KDoc)。 */
        @Volatile
        private var sharedInstance: OJHttpClient? = null

        fun shared(): OJHttpClient = sharedInstance ?: OJHttpClient().also { sharedInstance = it }

        /** 生产引擎:OkHttp(连接池由引擎统一持有,平台 client 复用)。 */
        fun defaultEngine(): HttpClientEngine =
            OkHttp.create {
                config {
                    connectTimeout(OKHTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    readTimeout(OKHTTP_SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    writeTimeout(OKHTTP_SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    callTimeout(OKHTTP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    followRedirects(false)
                }
            }
    }
}

/** 平台 UA + 基础头;[extra] 中同名头可覆盖默认值。 */
private fun HttpRequestBuilder.applyBaseHeaders(extra: Map<String, String>) {
    headers {
        append(HttpHeaders.UserAgent, OJHttpClient.DEFAULT_USER_AGENT)
        append(HttpHeaders.Accept, "application/json, text/plain, */*")
        append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9,zh-CN;q=0.8")
        extra.forEach { (name, value) -> append(name, value) }
    }
}
