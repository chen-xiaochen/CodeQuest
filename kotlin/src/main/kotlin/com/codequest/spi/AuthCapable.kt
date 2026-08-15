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
 * - [logout]:清除本地会话凭据;未登录时为无害 no-op。
 *
 * 线程/协程约定见 [OJPlatform];实现只抛 core 领域异常体系。
 */
interface AuthCapable {
    suspend fun ensureLoggedIn(): LoginSession

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
