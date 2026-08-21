package com.codequest.infra.messaging

import com.codequest.spi.AuthCapable
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 账号状态端点(WP38,infra 层,风格照抄 [ContestApi]):原版顶栏账号菜单
 * (AccountActionGroup)对齐——前端账号条需要每平台登录快照。
 *
 * ## 端点契约(由 [ResourceHttpServer] 挂载)
 *
 * `GET /api/auth/status` → `{"items":[{"platform":"leetcode","loggedIn":true,"userId":"u"}]}`
 * ——全部已注册平台的同步登录快照([AuthCapable.isLoggedIn],不触发登录流程、不上网络);
 * 非 AuthCapable 平台不排除,`loggedIn` 如实为 false。
 *
 * `POST /api/auth/refresh`(WP48)→ 与 status 同构响应:对全部 AuthCapable 平台
 * **并发**静默恢复([AuthCapable.restoreSession]:只尝试已存凭据注入+校验,
 * 绝不弹窗),items 反映恢复后的 loggedIn/userId;单平台恢复抛异常按未登录
 * 处理(不拖垮整批)。实例源与 status 同一 [platforms] 通道——restore 置位的
 * verified 即后续 status 读到的快照。
 *
 * ## 数据源与分层(ui → infra 反转注入)
 *
 * 登录态是平台实例上的内存标志(verified),实例由 ui 层 SubmissionPlatforms
 * 按 id 缓存(登录桥/提交流程共享同一实例);registry 每次 `create()` 产全新实例、
 * 快照恒为未登录,不可作数据源。infra 不得反向依赖 ui,故照
 * `ojTransportProvider`「SPI→infra 注入」先例,经 [AuthPlatformSourceProvider] EP
 * 由 ui 层注册提交链路同源实例的解析入口;未注册时回退 [OJPlatformRegistry]
 * (测试/降级用)。
 *
 * ## userId 契约(WP48 起)
 *
 * status 透传 [AuthCapable.currentSession] 只读快照(WP44 契约):已登录平台
 * 如实带回 userId,未登录(currentSession 为 null)为 null——快照不触发网络,
 * 登录实时性归 ensureLoggedIn/refresh 端点。
 */
class AuthApi(
    private val platforms: () -> List<OJPlatform> = OJPlatformRegistry::all,
    private val json: Json = DEFAULT_JSON,
) {
    /** `GET /api/auth/status`:全部平台登录快照。 */
    fun authStatus(): ProblemApiResponse =
        ProblemApiResponse(
            STATUS_OK,
            json.encodeToString(
                AuthStatusResponseDto.serializer(),
                AuthStatusResponseDto(items = platforms().map { it.toAuthStatusItem() }),
            ),
        )

    /** `POST /api/auth/refresh`(WP48):并发静默恢复全部 AuthCapable 平台。 */
    suspend fun authRefresh(): ProblemApiResponse =
        coroutineScope {
            val items =
                platforms()
                    .map { platform -> async { restoreSnapshot(platform) } }
                    .awaitAll()
            ProblemApiResponse(
                STATUS_OK,
                json.encodeToString(AuthStatusResponseDto.serializer(), AuthStatusResponseDto(items = items)),
            )
        }

    /** 同步快照项:loggedIn 读内存标志,userId 透传只读会话(WP48)。 */
    private fun OJPlatform.toAuthStatusItem(): AuthStatusItemDto {
        val auth = this as? AuthCapable
        return AuthStatusItemDto(
            platform = id.value,
            loggedIn = auth?.isLoggedIn() ?: false,
            userId = auth?.currentSession()?.userId,
        )
    }

    /**
     * 单平台恢复快照:restoreSession 成功 → loggedIn true + 会话 userId;
     * 返回 null(无已存凭据/校验失败)/抛异常/非 AuthCapable → 如实未登录。
     */
    private suspend fun restoreSnapshot(platform: OJPlatform): AuthStatusItemDto {
        val auth = platform as? AuthCapable ?: return AuthStatusItemDto(platform = platform.id.value, loggedIn = false)
        val session = runCatching { auth.restoreSession() }.getOrNull()
        return AuthStatusItemDto(
            platform = platform.id.value,
            loggedIn = session != null,
            userId = session?.userId,
        )
    }

    private companion object {
        private val DEFAULT_JSON = Json { encodeDefaults = true }

        private const val STATUS_OK = 200
    }
}

/**
 * 登录态平台实例源(ui → infra 注入点,WP38):ui 实现方委托 SubmissionPlatforms
 * 缓存实例,使本端点快照与登录桥/提交流程读写同一份内存登录态。
 */
fun interface AuthPlatformSourceProvider {
    /** 提交链路同源的(带登录态)平台实例;按调用取最新快照。 */
    fun platforms(project: Project): List<OJPlatform>
}

/** EP 消费入口;无实现时回退注册表(全新实例,快照恒未登录——测试/降级用)。 */
object AuthPlatformSources {
    private val EP_NAME = ExtensionPointName<AuthPlatformSourceProvider>("com.codequest.authPlatformSourceProvider")

    fun forProject(project: Project): List<OJPlatform> = EP_NAME.extensionList.firstOrNull()?.platforms(project) ?: OJPlatformRegistry.all()
}

@Serializable
private data class AuthStatusResponseDto(
    val items: List<AuthStatusItemDto>,
)

@Serializable
private data class AuthStatusItemDto(
    val platform: String,
    val loggedIn: Boolean,
    /** WP48 起透传 currentSession 只读快照;未登录/非 AuthCapable 为 null。 */
    val userId: String? = null,
)
