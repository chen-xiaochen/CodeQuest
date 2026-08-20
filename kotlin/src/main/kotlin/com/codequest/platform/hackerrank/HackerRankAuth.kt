package com.codequest.platform.hackerrank

import com.codequest.core.model.AuthExpiredException
import com.codequest.spi.AuthCapable
import com.codequest.spi.LoginSession
import com.codequest.spi.OJAuthPrompt
import com.codequest.spi.OJCredentialStore
import com.codequest.spi.OJTransport

/**
 * HackerRank cookie 登录(WP27):AuthCapable 实现。
 *
 * ## 登录选型(如实说明)
 *
 * - **cookie 粘贴**:旧实现从未做过程序化登录,登录链路 = 浏览器登录后粘贴
 *   Cookie 请求头(旧 AuthService.validateUserCookieAndTestLogin 事实,凭据原文
 *   存 SensitiveDataStore)。故沿用与 LeetCode/CodeForces/AtCoder/洛谷相同的
 *   「粘贴 Cookie」形态,凭证原文经 spi 的 [OJAuthPrompt] 回调取得(ui 侧复用
 *   通用 cookie 对话框,见 HackerRankAuthPromptProvider),解析归本类;
 * - **登录态校验**:`GET /community/v1/promotion_slots/banner-dashboard`,
 *   2xx 即已登录(旧 HackerRankApi.checkLogin 唯一判定,非 2xx 按未登录);
 * - **用户标识**:dashboard `__NEXT_DATA__` 的 prefetchedData.profile.username
 *   (旧 getInitialData 事实;昵称/头像等富信息不进 SPI 契约),不可得兜底
 *   "unknown"。
 *
 * 流程与既有五平台同构:内存会话复用 + 启动恢复(WP44 起归 [restoreSession]:
 * credential store 原文 → 注入 transport → 校验,静默不弹 prompt)→ 交互登录
 * → 校验通过后原文持久化。
 */
class HackerRankAuth(
    private val api: HackerRankApi,
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
                ?: throw AuthExpiredException("HackerRank($HACKERRANK_DOMAIN)未登录且未提供 cookie,登录流程被取消")
        if (!injectAndVerify(pasted)) {
            throw AuthExpiredException("HackerRank($HACKERRANK_DOMAIN)cookie 无效或已过期(banner-dashboard 校验未通过)")
        }
        credentialStore.save(HACKERRANK_PLATFORM_ID, pasted)
        return requireNotNull(session)
    }

    override fun currentSession(): LoginSession? = session?.takeIf { verified }

    /** 静默恢复(WP44):只读已存 cookie 注入校验,绝不弹 [prompt];失败返回 null。 */
    override suspend fun restoreSession(): LoginSession? {
        session?.takeIf { verified }?.let { return it }
        val stored = credentialStore.load(HACKERRANK_PLATFORM_ID) ?: return null
        return if (injectAndVerify(stored)) session else null
    }

    /** 注入 cookie 并经 banner-dashboard 校验;网络/解析异常按「未登录」处理走后续流程。 */
    private suspend fun injectAndVerify(rawCookies: String): Boolean {
        val cookies = parseCookieHeader(rawCookies)
        if (cookies.isEmpty()) return false
        return try {
            transport.updateCookies(HACKERRANK_PLATFORM_ID, HACKERRANK_HOME_URL, cookies)
            if (!runCatching { api.checkLogin() }.getOrDefault(false)) return false
            session =
                LoginSession(
                    platformId = HACKERRANK_PLATFORM_ID,
                    userId = runCatching { api.fetchUserInfo()?.username }.getOrNull() ?: UNKNOWN_USER,
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
        credentialStore.remove(HACKERRANK_PLATFORM_ID)
        transport.clearCookies(HACKERRANK_PLATFORM_ID)
        session = null
        verified = false
    }

    companion object {
        private const val UNKNOWN_USER = "unknown"

        /** `_hr=1; remember_me=xxx` → map(cookie 值可含 `=`,按首个 `=` 切分)。 */
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
