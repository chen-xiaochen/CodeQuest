package com.codequest.platform.leetcode

import com.codequest.core.model.AuthExpiredException
import com.codequest.spi.AuthCapable
import com.codequest.spi.LoginSession
import com.codequest.spi.OJCredentialStore
import com.codequest.spi.OJTransport

/**
 * LeetCode cookie 登录(WP19):AuthCapable 实现。
 *
 * 流程(参考旧实现「浏览器登录后粘贴 cookie」形态,UI 对话框在 WP21 接线):
 * 1. **内存会话复用 + 启动恢复**(WP44 起归 [restoreSession],静默不弹 prompt):
 *    已校验直接返回;否则从 [OJCredentialStore] 读上次保存的 cookie 串 → 注入
 *    transport CookieJar → `globalData` 校验登录态;
 * 2. **交互登录**:[cookiePrompt] 弹出粘贴输入(WP20/21 注入真实对话框;默认实现
 *    返回 null 即触发 [AuthExpiredException],保持无 UI 依赖),校验通过后
 *    **原文持久化**到 credential store。
 *
 * 关键 cookie 为 `LEETCODE_SESSION`(两站点一致,旧实现事实);粘贴串中通常还带
 * `csrftoken`,一并注入。cookie 无效(未登录/isSignedIn=false)抛 [AuthExpiredException]。
 */
class LeetCodeAuth(
    private val variant: LeetCodeVariant,
    private val api: LeetCodeApi,
    private val transport: OJTransport,
    private val credentialStore: OJCredentialStore,
    private val cookiePrompt: CookiePrompt = CookiePrompt { null },
) : AuthCapable {
    /** cookie 粘贴输入回调:返回 `name=value; name=value` 串;用户取消返回 null。 */
    fun interface CookiePrompt {
        suspend fun requestCookies(): String?
    }

    @Volatile
    private var session: LoginSession? = null

    /** 同步登录快照(SPI 契约:仅 UI 状态展示,不可作为提交前置判断)。 */
    @Volatile
    private var verified: Boolean = false

    override fun isLoggedIn(): Boolean = verified

    override suspend fun ensureLoggedIn(): LoginSession {
        // 内存会话复用 + 启动恢复(静默)归 restoreSession
        restoreSession()?.let { return it }

        // 交互登录:粘贴 cookie → 校验 → 持久化
        val pasted =
            cookiePrompt.requestCookies()
                ?: throw AuthExpiredException("LeetCode(${variant.domain})未登录且未提供 cookie,登录流程被取消")
        if (!injectAndVerify(pasted)) {
            throw AuthExpiredException("LeetCode(${variant.domain})cookie 无效或已过期(isSignedIn=false)")
        }
        credentialStore.save(variant.id, pasted)
        return requireNotNull(session)
    }

    override fun currentSession(): LoginSession? = session?.takeIf { verified }

    /** 静默恢复(WP44):只读已存 cookie 注入校验,绝不弹 [cookiePrompt];失败返回 null。 */
    override suspend fun restoreSession(): LoginSession? {
        session?.takeIf { verified }?.let { return it }
        val stored = credentialStore.load(variant.id) ?: return null
        return if (injectAndVerify(stored)) session else null
    }

    /** 注入 cookie 并经 globalData 校验;任何网络/鉴权异常按「未登录」处理走后续流程。 */
    private suspend fun injectAndVerify(rawCookies: String): Boolean {
        val cookies = parseCookieHeader(rawCookies)
        if (cookies.isEmpty()) return false
        return try {
            transport.updateCookies(variant.id, variant.homeUrl, cookies)
            val status = runCatching { api.fetchUserStatus() }.getOrNull() ?: return false
            if (!status.isSignedIn) return false
            session =
                LoginSession(
                    platformId = variant.id,
                    userId = status.username ?: status.userSlug ?: UNKNOWN_USER,
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
        credentialStore.remove(variant.id)
        transport.clearCookies(variant.id)
        session = null
        verified = false
    }

    companion object {
        private const val UNKNOWN_USER = "unknown"

        /** `LEETCODE_SESSION=xxx; csrftoken=yyy` → map(cookie 值可含 `=`,按首个 `=` 切分)。 */
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
