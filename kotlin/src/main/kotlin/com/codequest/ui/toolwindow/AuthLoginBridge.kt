package com.codequest.ui.toolwindow

import com.codequest.core.model.PlatformId
import com.codequest.spi.AuthCapable
import com.codequest.spi.OJPlatform
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
 */
object AuthLoginBridge {
    /** 页面 → Kotlin:请求登录;payload `{"platform":"hackerrank"}`。 */
    const val TOPIC_AUTH_LOGIN = "auth:login"

    /** Kotlin → 页面:登录结果;payload `{"ok":bool,"platform":"...","error":"..."}`。 */
    const val TOPIC_AUTH_LOGIN_RESULT = "auth:login-result"

    private val LOGGER = Logger.getLogger(AuthLoginBridge::class.java.name)

    /** 在工具窗口的桥上注册登录命令 handler(工厂 configure 回调调用)。 */
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
    }
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
