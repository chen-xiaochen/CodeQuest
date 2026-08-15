package com.codequest.spi

import com.codequest.core.model.PlatformId
import com.intellij.openapi.extensions.ExtensionPointName
import kotlin.reflect.KClass

/**
 * OJ 平台注册入口(WP17):新增 OJ = 新建 `platform/<oj>/` 包 + plugin.xml 一行
 * `<extensions defaultExtensionNs="com.codequest"><ojPlatformProvider implementation="..."/></extensions>`,
 * 不存在任何枚举 match 分发,core/ui 不需要任何改动。
 *
 * 形态判断——纯静态 object 而非 project 服务:
 * 平台身份/能力矩阵是无状态的全插件事实,与项目无关、无生命周期/释放问题;
 * EP 声明为 dynamic,允许插件动态装卸。若某平台实例未来需要 Project 上下文
 * (如读取项目级设置),由其 provider 自行获取后注入 `create()` 返回值,
 * Registry 契约保持不变。
 */
interface OJPlatformProvider {
    /** 创建(或取出)本 provider 对应的 [OJPlatform] 实例;应可重入。 */
    fun create(): OJPlatform
}

/** OJ 平台查询入口;生产环境数据来自 IntelliJ Extension Point `com.codequest.ojPlatformProvider`。 */
object OJPlatformRegistry {
    private val EP_NAME = ExtensionPointName<OJPlatformProvider>("com.codequest.ojPlatformProvider")

    /** 生产 provider 来源(IntelliJ EP);独立成函数便于测试恢复默认。 */
    internal fun extensionPointProviders(): List<OJPlatformProvider> = EP_NAME.extensionList

    /** provider 来源,测试可注入假列表以脱离 IntelliJ 平台运行。 */
    @Volatile
    internal var providerSource: () -> List<OJPlatformProvider> = { extensionPointProviders() }

    /** 全部已注册平台,按 EP 声明顺序。 */
    fun all(): List<OJPlatform> = providerSource().map { it.create() }

    /** 按 id 查平台;未知 id 返回 null(调用方决定报错或忽略)。 */
    fun find(id: PlatformId): OJPlatform? = all().firstOrNull { it.id == id }

    /** 过滤出实现了指定能力接口的平台(如 `allWith(AuthCapable::class)`)。 */
    fun <T : Any> allWith(capability: KClass<T>): List<T> = all().filterIsInstance(capability.java)

    /** [allWith] 的 reified 便捷形式(如 `allWith<SubmissionCapable>()`)。 */
    inline fun <reified T : Any> allWith(): List<T> = allWith(T::class)
}
