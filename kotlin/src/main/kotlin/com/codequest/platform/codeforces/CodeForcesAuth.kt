package com.codequest.platform.codeforces

import com.codequest.core.model.AuthExpiredException
import com.codequest.spi.AuthCapable
import com.codequest.spi.LoginSession
import com.codequest.spi.OJAuthPrompt
import com.codequest.spi.OJCredentialStore
import com.codequest.spi.OJTransport

/**
 * CodeForces cookie 登录(WP24):AuthCapable 实现。
 *
 * ## 登录选型(如实说明)
 *
 * - **cookie 粘贴**:CF 登录表单本身由 Cloudflare Turnstile 保护,程序化
 *   账密登录不可行——旧实现同样从未做过程序化登录,只依赖浏览器会话 cookie
 *   (JSESSIONID 等,提交时经 JCEF 弹窗注入保持登录态)。故沿用与 LeetCode
 *   相同的「浏览器登录后粘贴 Cookie 请求头」形态,凭证原文经 spi 的
 *   [OJAuthPrompt] 回调取得(ui 侧复用通用 cookie 对话框,见
 *   CodeForcesAuthPromptProvider),解析归本类;
 * - **登录态校验**:`GET /settings/general`,HTML 出现 `div.userbox` 即已登录
 *   (旧 checkLogin 事实);用户名取侧栏 avatar 第 2 个链接,缺失兜底 "unknown"。
 *
 * 流程与 [com.codequest.platform.leetcode.LeetCodeAuth] 同构:内存会话复用 +
 * 启动恢复(WP44 起归 [restoreSession]:credential store 原文 → 注入 transport →
 * settings 校验,静默不弹 prompt)→ 交互登录 → 校验通过后原文持久化。
 */
class CodeForcesAuth(
    private val api: CodeForcesApi,
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
        // 内存会话复用 + 启动恢复(静默)归 restoreSession
        restoreSession()?.let { return it }

        val pasted =
            prompt.requestCredentials()
                ?: throw AuthExpiredException("CodeForces($CODEFORCES_DOMAIN)未登录且未提供 cookie,登录流程被取消")
        if (!injectAndVerify(pasted)) {
            throw AuthExpiredException("CodeForces($CODEFORCES_DOMAIN)cookie 无效或已过期(settings 页无 userbox)")
        }
        credentialStore.save(CODEFORCES_PLATFORM_ID, pasted)
        return requireNotNull(session)
    }

    override fun currentSession(): LoginSession? = session?.takeIf { verified }

    /** 静默恢复(WP44):只读已存 cookie 注入校验,绝不弹 [prompt];失败返回 null。 */
    override suspend fun restoreSession(): LoginSession? {
        session?.takeIf { verified }?.let { return it }
        val stored = credentialStore.load(CODEFORCES_PLATFORM_ID) ?: return null
        return if (injectAndVerify(stored)) session else null
    }

    /** 注入 cookie 并经 settings 页校验;网络/解析异常按「未登录」处理走后续流程。 */
    private suspend fun injectAndVerify(rawCookies: String): Boolean {
        val cookies = parseCookieHeader(rawCookies)
        if (cookies.isEmpty()) return false
        return try {
            transport.updateCookies(CODEFORCES_PLATFORM_ID, CODEFORCES_HOME_URL, cookies)
            val html = runCatching { api.fetchSettingsPageHtml() }.getOrNull() ?: return false
            if (!CodeForcesHtml.hasDivElementWithClass(html, "userbox")) return false
            session =
                LoginSession(
                    platformId = CODEFORCES_PLATFORM_ID,
                    userId = CodeForcesHtml.extractSidebarUsername(html) ?: UNKNOWN_USER,
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
        credentialStore.remove(CODEFORCES_PLATFORM_ID)
        transport.clearCookies(CODEFORCES_PLATFORM_ID)
        session = null
        verified = false
    }

    companion object {
        private const val UNKNOWN_USER = "unknown"

        /** `JSESSIONID=xxx; 39ce7=yyy` → map(cookie 值可含 `=`,按首个 `=` 切分)。 */
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
