package com.codequest.ui.jcef

import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser

/**
 * JCEF 浏览器创建辅助(WP11)。
 *
 * 防御:JCEF 在当前 IDE 不可用(精简 JDK / 未启用 Web Browser 插件)时给出清晰错误,
 * 由调用方降级为纯 Swing 占位 UI,而非让 JVM 抛原生 NoClassDefFoundError。
 */
fun createCqBrowser(): JBCefBrowser {
    check(JBCefApp.isSupported()) {
        "JCEF is not available in this IDE. " +
            "CodeQuest web views require the 'Web Browser (JCEF)' plugin (com.intellij.modules.jcef)."
    }
    return JBCefBrowser.createBuilder().build()
}
