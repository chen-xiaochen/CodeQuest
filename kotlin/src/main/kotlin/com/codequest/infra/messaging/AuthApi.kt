package com.codequest.infra.messaging

import com.codequest.spi.AuthCapable
import com.codequest.spi.OJPlatform
import com.codequest.spi.OJPlatformRegistry
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 账号状态端点(WP38,infra 层,风格照抄 [ContestApi]):原版顶栏账号菜单
 * (AccountActionGroup)对齐——前端账号条需要每平台登录快照。
 *
 * ## 端点契约(由 [ResourceHttpServer] 挂载)
 *
 * `GET /api/auth/status` → `{"items":[{"platform":"leetcode","loggedIn":true,"userId":null}]}`
 * ——全部已注册平台的同步登录快照([AuthCapable.isLoggedIn],不触发登录流程、不上网络);
 * 非 AuthCapable 平台不排除,`loggedIn` 如实为 false。
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
 * ## userId 契约
 *
 * [AuthCapable] 现契约只有 `ensureLoggedIn(): LoginSession`(交互式,可能弹窗),
 * 无「只读当前 session」方法;本端点绝不触发登录,故 `userId` **恒为 null**
 * (如实上报而非隐瞒字段,前端徽标降级为只显示平台名)。后续 SPI 若补只读
 * session 契约,本端点透传即可。
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
                AuthStatusResponseDto(
                    items =
                        platforms().map { platform ->
                            AuthStatusItemDto(
                                platform = platform.id.value,
                                loggedIn = (platform as? AuthCapable)?.isLoggedIn() ?: false,
                                userId = null,
                            )
                        },
                ),
            ),
        )

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
    /** 恒 null:SPI 无只读 session 契约(见 [AuthApi] KDoc);保留字段以稳定前端类型。 */
    val userId: String? = null,
)
