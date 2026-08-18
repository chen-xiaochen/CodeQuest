package com.codequest.platform.luogu

import com.codequest.core.model.AuthExpiredException
import com.codequest.spi.AuthCapable
import com.codequest.spi.LoginSession
import com.codequest.spi.OJAuthPrompt
import com.codequest.spi.OJCredentialStore
import com.codequest.spi.OJTransport

/**
 * 洛谷 cookie 登录(WP26):AuthCapable 实现。
 *
 * ## 登录选型(如实说明)
 *
 * - **cookie 粘贴**:洛谷提交/题解等主链路要求登录态 + csrf token,且提交可能触发
 *   易盾/图片验证码(旧实现经 JCEF 弹窗处理,依赖未迁移的旧 webview 静态资源),
 *   程序化账密登录不可行——旧实现同样只依赖浏览器会话 cookie(_uid/_client_id 等)。
 *   故沿用与 LeetCode/CodeForces/AtCoder 相同的「浏览器登录后粘贴 Cookie 请求头」
 *   形态,凭证原文经 spi 的 [OJAuthPrompt] 回调取得(ui 侧复用通用 cookie 对话框,
 *   见 LuoguAuthPromptProvider),解析归本类;
 * - **登录态校验**:`GET /user/setting?_contentOnly=1`,旧 checkLogin 事实——
 *   HTTP 错误(未登录 401)按未登录;200 时 `<title>` 为「用户设置 - 洛谷」/
 *   「Welcome - Luogu Spilopelia」即已登录,否则看 `<h1>` 是否含 401。
 *
 * 流程与 LeetCode/AtCoder 同构:内存会话复用 → 启动恢复(credential store 原文 →
 * 注入 transport → 校验)→ 交互登录 → 校验通过后原文持久化。用户标识取
 * lentille `user.uid`(不可得兜底 "unknown",旧实现富信息不进 SPI 契约)。
 */
class LuoguAuth(
    private val api: LuoguApi,
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

        val stored = credentialStore.load(LUOGU_PLATFORM_ID)
        if (stored != null && injectAndVerify(stored)) {
            return requireNotNull(session)
        }

        val pasted =
            prompt.requestCredentials()
                ?: throw AuthExpiredException("洛谷($LUOGU_DOMAIN)未登录且未提供 cookie,登录流程被取消")
        if (!injectAndVerify(pasted)) {
            throw AuthExpiredException("洛谷($LUOGU_DOMAIN)cookie 无效或已过期(user/setting 页未处于登录态)")
        }
        credentialStore.save(LUOGU_PLATFORM_ID, pasted)
        return requireNotNull(session)
    }

    /** 注入 cookie 并经 user/setting 页校验;网络/解析异常按「未登录」处理走后续流程。 */
    private suspend fun injectAndVerify(rawCookies: String): Boolean {
        val cookies = parseCookieHeader(rawCookies)
        if (cookies.isEmpty()) return false
        return try {
            transport.updateCookies(LUOGU_PLATFORM_ID, LUOGU_HOME_URL, cookies)
            if (!runCatching { api.checkLogin() }.getOrDefault(false)) return false
            session =
                LoginSession(
                    platformId = LUOGU_PLATFORM_ID,
                    userId = runCatching { api.fetchUserInfo()?.uid }.getOrNull() ?: UNKNOWN_USER,
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
        credentialStore.remove(LUOGU_PLATFORM_ID)
        transport.clearCookies(LUOGU_PLATFORM_ID)
        session = null
        verified = false
    }

    companion object {
        private const val UNKNOWN_USER = "unknown"

        /** `_uid=123; _client_id=xxx` → map(cookie 值可含 `=`,按首个 `=` 切分)。 */
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
