package com.codequest.platform.atcoder

import com.codequest.core.model.AuthExpiredException
import com.codequest.spi.AuthCapable
import com.codequest.spi.LoginSession
import com.codequest.spi.OJAuthPrompt
import com.codequest.spi.OJCredentialStore
import com.codequest.spi.OJTransport

/**
 * AtCoder cookie 登录(WP25):AuthCapable 实现。
 *
 * ## 登录选型(如实说明)
 *
 * - **cookie 粘贴**:AtCoder 登录后的提交链路由 Cloudflare Turnstile 保护
 *   (提交时经 JCEF 弹窗完成人机验证),程序化账密登录不可行——旧实现同样从未
 *   做过程序化登录,全程依赖浏览器会话 cookie(REVEL_SESSION 等)。故沿用与
 *   LeetCode/CodeForces 相同的「浏览器登录后粘贴 Cookie 请求头」形态,凭证原文
 *   经 spi 的 [OJAuthPrompt] 回调取得(ui 侧复用通用 cookie 对话框,见
 *   AtCoderAuthPromptProvider),解析归本类;
 * - **登录态校验**:`GET /settings`,HTML 含 `#user-nav-tabs` 即已登录(旧
 *   checkLogin 事实);用户名取账号表单 `input#ui.UserName` 的 value(旧
 *   getUserInfo 事实),缺失兜底 "unknown"(旧实现跳过用户主页/头像,富信息不进
 *   SPI 契约)。
 *
 * 流程与 LeetCode/CodeForces 同构:内存会话复用 → 启动恢复(credential store
 * 原文 → 注入 transport → settings 校验)→ 交互登录 → 校验通过后原文持久化。
 */
class AtCoderAuth(
    private val api: AtCoderApi,
    private val transport: OJTransport,
    private val credentialStore: OJCredentialStore,
    private val prompt: OJAuthPrompt = OJAuthPrompt { null },
) : AuthCapable {
    @Volatile
    private var session: LoginSession? = null

    /** 同步登录快照(SPI 契约:仅 UI 状态展示,不可作为提交前置判断)。 */
    @Volatile
    private var verified: Boolean = false

    override fun isLoggedIn(): Boolean = verified

    override suspend fun ensureLoggedIn(): LoginSession {
        session?.takeIf { verified }?.let { return it }

        val stored = credentialStore.load(ATCODER_PLATFORM_ID)
        if (stored != null && injectAndVerify(stored)) {
            return requireNotNull(session)
        }

        val pasted =
            prompt.requestCredentials()
                ?: throw AuthExpiredException("AtCoder($ATCODER_DOMAIN)未登录且未提供 cookie,登录流程被取消")
        if (!injectAndVerify(pasted)) {
            throw AuthExpiredException("AtCoder($ATCODER_DOMAIN)cookie 无效或已过期(settings 页无 user-nav-tabs)")
        }
        credentialStore.save(ATCODER_PLATFORM_ID, pasted)
        return requireNotNull(session)
    }

    /** 注入 cookie 并经 settings 页校验;网络/解析异常按「未登录」处理走后续流程。 */
    private suspend fun injectAndVerify(rawCookies: String): Boolean {
        val cookies = parseCookieHeader(rawCookies)
        if (cookies.isEmpty()) return false
        return try {
            transport.updateCookies(ATCODER_PLATFORM_ID, ATCODER_HOME_URL, cookies)
            val html = runCatching { api.fetchSettingsPageHtml() }.getOrNull() ?: return false
            if (!AtCoderHtml.hasUserNavTabs(html)) return false
            session =
                LoginSession(
                    platformId = ATCODER_PLATFORM_ID,
                    userId = AtCoderHtml.extractUserName(html) ?: UNKNOWN_USER,
                )
            verified = true
            true
        } catch (
            @Suppress("SwallowedException") e: Exception,
        ) {
            false
        }
    }

    override suspend fun logout() {
        credentialStore.remove(ATCODER_PLATFORM_ID)
        transport.clearCookies(ATCODER_PLATFORM_ID)
        session = null
        verified = false
    }

    companion object {
        private const val UNKNOWN_USER = "unknown"

        /** `REVEL_SESSION=xxx; key=yyy` → map(cookie 值可含 `=`,按首个 `=` 切分)。 */
        fun parseCookieHeader(header: String): Map<String, String> =
            header
                .split(';')
                .mapNotNull { pair ->
                    val trimmed = pair.trim()
                    if (trimmed.isEmpty()) return@mapNotNull null
                    val idx = trimmed.indexOf('=')
                    if (idx <= 0) return@mapNotNull null
                    trimmed.take(idx) to trimmed.substring(idx + 1)
                }.toMap()
    }
}
