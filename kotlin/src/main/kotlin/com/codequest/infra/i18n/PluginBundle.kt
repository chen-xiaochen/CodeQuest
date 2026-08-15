package com.codequest.infra.i18n

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

/**
 * 类型化 message 包装(WP15):中英双语资源见 `messages/PluginBundle{,_zh}.properties`。
 *
 * 放 `infra`(ui 可依赖 infra,infra 禁止依赖 ui),各层共用。
 * 经 [DynamicBundle] 读取,跟随 IDE 语言切换;`{0}` 形式参数经 [message] 传入。
 */
object PluginBundle {
    const val BUNDLE: String = "messages.PluginBundle"

    private val INSTANCE: DynamicBundle = DynamicBundle(PluginBundle::class.java, BUNDLE)

    @JvmStatic
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = INSTANCE.getMessage(key, *params)
}
