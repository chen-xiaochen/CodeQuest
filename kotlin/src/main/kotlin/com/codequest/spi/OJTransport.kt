package com.codequest.spi

import com.codequest.core.model.PlatformId
import com.intellij.openapi.extensions.ExtensionPointName

/**
 * OJ 平台传输接口(WP19):平台适配器可见的最小 HTTP/cookie 会话契约。
 *
 * ## 为什么在 spi 定义(分层自洽说明)
 *
 * 分层规则禁止 `platform → infra`,而平台适配器必然要发 HTTP 请求与读写 cookie,
 * 故把「适配器需要的最小方法集」抽象为本接口(spi 只依赖 core + IntelliJ 扩展机制,
 * 依赖方向合法);生产实现是 infra 的 `HttpOJTransport`(薄委托 `OJHttpClient`,
 * infra → spi 方向合法),经 `com.codequest.ojTransportProvider` EP 注册。
 * 测试注入假实现即可脱离网络/IntelliJ 运行。
 *
 * 语义与 infra.http.OJHttpClient 对齐:非 2xx 自动抛 core 领域异常
 * (AuthExpired / RateLimited / AntiBot / OJException),适配器不感知状态码;
 * cookie 按平台隔离(每平台独立 CookieJar)。
 *
 * 线程/协程约定见 [OJPlatform];方法均 main-safe。
 */
interface OJTransport {
    /** GET,返回响应体文本;非 2xx 抛领域异常。 */
    suspend fun get(
        platformId: PlatformId,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String

    /** POST JSON(原始串承载),返回响应体文本。 */
    suspend fun postJson(
        platformId: PlatformId,
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
    ): String

    /**
     * GraphQL 便捷方法:把 query/variables 包装成 `{"query":...,"variables":...}`
     * JSON 信封后 POST,返回原始响应体文本(调用方自行解析)。
     */
    suspend fun postGraphQL(
        platformId: PlatformId,
        url: String,
        query: String,
        variables: String? = null,
        operationName: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String

    /** 预置 cookie(登录注入):写入该平台 CookieJar,之后的请求自动携带。 */
    suspend fun updateCookies(
        platformId: PlatformId,
        url: String,
        cookies: Map<String, String>,
    )

    /** 该平台 CookieJar 中匹配 [url] 的 cookie 快照(name → value)。 */
    suspend fun cookies(
        platformId: PlatformId,
        url: String,
    ): Map<String, String>

    /** 清空该平台 CookieJar(退出登录);其他平台不受影响。 */
    suspend fun clearCookies(platformId: PlatformId)
}

/**
 * OJ 平台凭据持久化接口(WP19):平台适配器可见的最小凭据存取契约。
 *
 * 与 infra 的 `OJSecretsStore` 同语义(save/load/remove,按平台隔离),独立成 SPI
 * 接口的理由同 [OJTransport](platform 禁止 import infra);生产实现是 infra 的
 * `SecretsStoreCredentialStore`(委托 `PasswordSafeSecretsStore`)。
 */
interface OJCredentialStore {
    /** 写入(覆盖)某平台凭据。 */
    fun save(
        platformId: PlatformId,
        secret: String,
    )

    /** 读取某平台凭据;从未保存过返回 null。 */
    fun load(platformId: PlatformId): String?

    /** 删除某平台凭据;未保存过时为 no-op。 */
    fun remove(platformId: PlatformId)
}

/** 传输层实现来源(EP `com.codequest.ojTransportProvider`),形态对齐 [OJPlatformProvider]。 */
interface OJTransportProvider {
    fun create(): OJTransport
}

/** 凭据存储实现来源(EP `com.codequest.ojCredentialStoreProvider`)。 */
interface OJCredentialStoreProvider {
    fun create(): OJCredentialStore
}

/**
 * SPI 运行时环境(WP19):平台适配器获取 transport / 凭据存储的统一入口。
 *
 * 实现经 IntelliJ Extension Point 注入(生产:infra 的 HttpOJTransport 与
 * SecretsStoreCredentialStore,plugin.xml 声明),平台 provider 在 `create()` 时
 * 经本对象取依赖注入平台实例——platform 包全程不 import infra。
 *
 * 测试可覆盖 [transportSource]/[credentialStoreSource] 脱离 IntelliJ 平台运行,
 * 用法与 [OJPlatformRegistry.providerSource] 一致(测后调 [resetToDefaults] 恢复)。
 */
object OJRuntime {
    private val TRANSPORT_EP = ExtensionPointName<OJTransportProvider>("com.codequest.ojTransportProvider")
    private val CREDENTIAL_STORE_EP =
        ExtensionPointName<OJCredentialStoreProvider>("com.codequest.ojCredentialStoreProvider")

    private const val TRANSPORT_EP_HINT =
        "未注册 com.codequest.ojTransportProvider 扩展点" +
            "(生产应在 plugin.xml 声明 infra.http.HttpOJTransportProvider)"

    private const val CREDENTIAL_STORE_EP_HINT =
        "未注册 com.codequest.ojCredentialStoreProvider 扩展点" +
            "(生产应在 plugin.xml 声明 infra.secrets.SecretsStoreCredentialStoreProvider)"

    private val defaultTransportSource: () -> OJTransport = {
        TRANSPORT_EP.extensionList.firstOrNull()?.create() ?: error(TRANSPORT_EP_HINT)
    }

    private val defaultCredentialStoreSource: () -> OJCredentialStore = {
        CREDENTIAL_STORE_EP.extensionList.firstOrNull()?.create() ?: error(CREDENTIAL_STORE_EP_HINT)
    }

    @Volatile
    internal var transportSource: () -> OJTransport = defaultTransportSource

    @Volatile
    internal var credentialStoreSource: () -> OJCredentialStore = defaultCredentialStoreSource

    /** 传输层实现(进程内共享,由实现自决定单例语义)。 */
    fun transport(): OJTransport = transportSource()

    /** 凭据存储实现。 */
    fun credentialStore(): OJCredentialStore = credentialStoreSource()

    /** 测试恢复默认来源(EP 解析)。 */
    internal fun resetToDefaults() {
        transportSource = defaultTransportSource
        credentialStoreSource = defaultCredentialStoreSource
    }
}
