package com.codequest.spi

import com.codequest.core.model.PlatformId
import kotlinx.serialization.Serializable

/**
 * 登录能力(WP17):平台适配器按需实现。
 *
 * - [ensureLoggedIn]:已登录则直接返回会话;未登录时触发登录流程(由实现决定交互形态,
 *   如 cookie 粘贴对话框 / JCEF 账号登录),完成后返回会话;
 * - [isLoggedIn]:同步快照判断,只用于 UI 状态展示,不可作为提交前置判断
 *   (远端校验归 [ensureLoggedIn],失效时抛 AuthExpiredException);
 * - [currentSession] / [restoreSession]:WP44 会话快照与静默启动恢复,
 *   默认实现返回 null(零破坏既有 fake),平台适配器应覆盖;
 * - [logout]:清除本地会话凭据;未登录时为无害 no-op。
 *
 * 线程/协程约定见 [OJPlatform];实现只抛 core 领域异常体系。
 */
interface AuthCapable {
    suspend fun ensureLoggedIn(): LoginSession

    /**
     * 同步只读会话快照(WP44):返回当前已校验会话;未登录/未实现返回 null。
     * 仅供 UI 展示用户名,不触发网络;登录实时性归 [ensureLoggedIn]。
     */
    fun currentSession(): LoginSession? = null

    /**
     * 静默启动恢复(WP44):只尝试「凭据存储读已存凭据 → 注入 → 校验」,
     * **绝不弹交互 prompt**;失败返回 null,交由 [ensureLoggedIn] 走交互流程。
     */
    suspend fun restoreSession(): LoginSession? = null

    fun isLoggedIn(): Boolean

    suspend fun logout()
}

/**
 * 登录会话(WP17 最小集:平台 id + 用户标识)。
 *
 * UI 需要显示昵称等富信息时,由平台适配器后续扩展字段,不属于 SPI 契约。
 */
@Serializable
data class LoginSession(
    val platformId: PlatformId,
    /** 平台侧用户标识(用户名/uid/cookie 派生指纹)。 */
    val userId: String,
)
