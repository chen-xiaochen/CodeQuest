package com.codequest.ui.toolwindow

import com.codequest.core.model.PlatformId
import com.codequest.infra.messaging.AuthPlatformSourceProvider
import com.codequest.spi.AuthCapable
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import com.codequest.ui.jcef.CQBridge
import com.codequest.ui.submission.SubmissionPlatforms
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.logging.Logger

/**
 * 浏览链路登录入口(1.0.x 缺陷修复):`auth:login` 桥命令。
 *
 * ## 缺陷背景
 *
 * 重登流程(cookie 粘贴对话框)此前只挂在提交流程(SubmissionFlow catch
 * AuthExpiredException);浏览链路(题面/题解/列表)遇 401 只弹原始错误——
 * HackerRank 题面等需登录的浏览场景被 401 卡死,用户无从登录。
 *
 * ## 修复形态(对齐既有模式)
 *
 * 页面错误态展示 Login 按钮 → `auth:login` {platform} → 本 handler 经
 * [SubmissionPlatforms] 解析**带登录对话框**的平台实例(authPrompt EP 装配)→
 * `ensureLoggedIn()`(EDT 弹窗校验)→ `auth:login-result` {ok, platform, error?}
 * 回推页面,成功后页面自行重拉数据。逻辑核([performLogin])为纯函数式注入,
 * 单测不依赖 IntelliJ 运行时;[register] 只做桥接线。
 *
 * ## WP38:登出桥(账号菜单出口)
 *
 * 原版顶栏 AccountActionGroup 有 LogoutAction,重写版登录此前无登出入口;
 * `auth:logout` {platform} → 同源实例 `logout()`(清凭据 + cookie + 内存态,
 * 未登录为无害 no-op)→ `auth:logout-result` {ok, platform, error?} 回推。
 * 另附 [SubmissionAuthPlatformSourceProvider]:为 infra 的 `/api/auth/status`
 * 端点反转注入本层缓存实例(ui → infra EP,不破坏分层)。
 */
object AuthLoginBridge {
    /** 页面 → Kotlin:请求登录;payload `{"platform":"hackerrank"}`。 */
    const val TOPIC_AUTH_LOGIN = "auth:login"

    /** Kotlin → 页面:登录结果;payload `{"ok":bool,"platform":"...","error":"..."}`。 */
    const val TOPIC_AUTH_LOGIN_RESULT = "auth:login-result"

    /** 页面 → Kotlin:请求登出(WP38);payload `{"platform":"codeforces"}`。 */
    const val TOPIC_AUTH_LOGOUT = "auth:logout"

    /** Kotlin → 页面:登出结果(WP38);payload `{"ok":bool,"platform":"...","error":"..."}`。 */
    const val TOPIC_AUTH_LOGOUT_RESULT = "auth:logout-result"

    private val LOGGER = Logger.getLogger(AuthLoginBridge::class.java.name)

    /** 在工具窗口的桥上注册登录/登出命令 handler(工厂 configure 回调调用)。 */
    fun register(
        project: Project,
        bridge: CQBridge,
    ) {
        bridge.on(TOPIC_AUTH_LOGIN) { payload ->
            val request = parseAuthLoginPayload(payload)
            if (request == null) {
                LOGGER.warning("dropping malformed $TOPIC_AUTH_LOGIN payload: $payload")
                return@on
            }
            // ensureLoggedIn 弹模态对话框并阻塞至用户确认,绝不能占用桥分发线程
            ApplicationManager.getApplication().executeOnPooledThread {
                val result =
                    runBlocking {
                        performLogin(PlatformId(request.platform)) { SubmissionPlatforms.resolve(project, it) }
                    }
                bridge.postMessage(TOPIC_AUTH_LOGIN_RESULT, result.toJson())
            }
        }
        // WP38:登出无 UI,但 logout 涉及凭据存储/transport 清理 IO,同样走池线程
        bridge.on(TOPIC_AUTH_LOGOUT) { payload ->
            val request = parseAuthLogoutPayload(payload)
            if (request == null) {
                LOGGER.warning("dropping malformed $TOPIC_AUTH_LOGOUT payload: $payload")
                return@on
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                val result =
                    runBlocking {
                        performLogout(PlatformId(request.platform)) { SubmissionPlatforms.resolve(project, it) }
                    }
                bridge.postMessage(TOPIC_AUTH_LOGOUT_RESULT, result.toJson())
            }
        }
    }
}

/**
 * [AuthPlatformSourceProvider] 的提交链路实现(WP38):`/api/auth/status` 的实例源
 * 与登录桥/提交流程同源([SubmissionPlatforms] 按 id 缓存的实例)——登录/登出改的
 * verified 内存标志即该端点读到的快照。经 plugin.xml `authPlatformSourceProvider`
 * EP 注册,infra 消费方不反向依赖 ui。
 */
class SubmissionAuthPlatformSourceProvider : AuthPlatformSourceProvider {
    override fun platforms(project: Project): List<OJPlatform> =
        OJPlatformRegistry.all().mapNotNull { SubmissionPlatforms.resolve(project, it.id) }
}

/** auth:login 的载荷。 */
data class AuthLoginRequest(
    val platform: String,
)

/** 解析 `auth:login` payload(纯函数,单测锁定协议);缺字段/空白/非法 JSON 返回 null。 */
fun parseAuthLoginPayload(payload: String): AuthLoginRequest? =
    runCatching {
        val platform =
            Json
                .parseToJsonElement(payload)
                .jsonObject["platform"]
                ?.jsonPrimitive
                ?.content
        if (platform.isNullOrBlank()) null else AuthLoginRequest(platform)
    }.getOrNull()

/** auth:login-result 的载荷;[platform] 回显供多窗口页面过滤。 */
data class AuthLoginResult(
    val ok: Boolean,
    val platform: String,
    val error: String? = null,
) {
    fun toJson(): String =
        buildJsonObject {
            put("ok", ok)
            put("platform", platform)
            if (error != null) put("error", error)
        }.toString()
}

/**
 * 登录逻辑核(纯函数式注入平台解析,单测友好):解析平台 → AuthCapable 校验 →
 * ensureLoggedIn(失败/取消/无登录能力的错误信息原样带回页面)。
 */
suspend fun performLogin(
    platformId: PlatformId,
    resolvePlatform: (PlatformId) -> OJPlatform?,
): AuthLoginResult {
    val platform = resolvePlatform(platformId)
    if (platform == null) {
        return AuthLoginResult(ok = false, platform = platformId.value, error = "unknown platform: ${platformId.value}")
    }
    val auth = platform as? AuthCapable
    if (auth == null) {
        return AuthLoginResult(ok = false, platform = platformId.value, error = "platform ${platformId.value} has no interactive login")
    }
    return try {
        auth.ensureLoggedIn()
        AuthLoginResult(ok = true, platform = platformId.value)
    } catch (e: Exception) {
        AuthLoginResult(ok = false, platform = platformId.value, error = e.message ?: e.javaClass.simpleName)
    }
}

/** auth:logout 的载荷(WP38)。 */
data class AuthLogoutRequest(
    val platform: String,
)

/** 解析 `auth:logout` payload(纯函数,单测锁定协议);缺字段/空白/非法 JSON 返回 null。 */
fun parseAuthLogoutPayload(payload: String): AuthLogoutRequest? =
    runCatching {
        val platform =
            Json
                .parseToJsonElement(payload)
                .jsonObject["platform"]
                ?.jsonPrimitive
                ?.content
        if (platform.isNullOrBlank()) null else AuthLogoutRequest(platform)
    }.getOrNull()

/** auth:logout-result 的载荷(WP38);[platform] 回显供多窗口页面过滤。 */
data class AuthLogoutResult(
    val ok: Boolean,
    val platform: String,
    val error: String? = null,
) {
    fun toJson(): String =
        buildJsonObject {
            put("ok", ok)
            put("platform", platform)
            if (error != null) put("error", error)
        }.toString()
}

/**
 * 登出逻辑核(WP38,形态照 [performLogin]):解析平台 → `logout()` 清凭据 +
 * cookie + 内存态(SPI 契约:未登录为无害 no-op,仍回 ok)→ 结果回推页面。
 */
suspend fun performLogout(
    platformId: PlatformId,
    resolvePlatform: (PlatformId) -> OJPlatform?,
): AuthLogoutResult {
    val platform = resolvePlatform(platformId)
    if (platform == null) {
        return AuthLogoutResult(ok = false, platform = platformId.value, error = "unknown platform: ${platformId.value}")
    }
    val auth =
        platform as? AuthCapable
            ?: return AuthLogoutResult(ok = false, platform = platformId.value, error = "platform ${platformId.value} has no logout")
    return try {
        auth.logout()
        AuthLogoutResult(ok = true, platform = platformId.value)
    } catch (e: Exception) {
        AuthLogoutResult(ok = false, platform = platformId.value, error = e.message ?: e.javaClass.simpleName)
    }
}
