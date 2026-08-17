package com.codequest.ui.antibot

import com.codequest.infra.i18n.PluginBundle
import com.codequest.spi.AntiBotChallengeHandler
import com.codequest.spi.AntiBotChallengeHandlerProvider
import com.codequest.spi.OJRuntime
import com.codequest.spi.TurnstileConfig
import com.codequest.ui.jcef.createCqBrowser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefCookie
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest

/**
 * Cloudflare Turnstile 人机验证 JCEF 弹窗(WP23,ui/anti-bot 公共组件)。
 *
 * 移植旧 Scala CloudflareTurnstileDialog(行为保持:加载目标页 → 移除挑战节点 →
 * 注入 Turnstile 挂件 → 用户完成验证后 [TurnstileConfig.resultExpression] 求值
 * 结果经 JBCefJSQuery 回传;平台 cookie 预注入保持登录态)。差异:结果解析归
 * 平台适配器(弹窗只回传原始串),取消返回 null(平台侧抛 AntiBotChallengeException)。
 *
 * 弹窗组件薄、无法脱离 IDE 单测;配置构造校验(TurnstileConfigTest)与
 * 钩子注入(OJRuntime.antiBotSource)已单独覆盖。
 */
class TurnstileDialog(
    private val config: TurnstileConfig,
    private val cookies: Map<String, String>,
) {
    private val browser = createCqBrowser()

    /** 验证结果原文;JS 回调线程写、EDT(模态循环退出后)读。 */
    @Volatile
    private var outcome: String? = null

    /** 模态显示并等待验证结果;用户取消返回 null。必须在 EDT 调用。 */
    fun showAndSolve(): String? {
        val dialog =
            DialogBuilder().apply {
                setTitle(PluginBundle.message("turnstile.dialog.title"))
                setCenterPanel(browser.component)
                addCancelAction()
                dialogWrapper.setSize(DIALOG_WIDTH, DIALOG_HEIGHT)
            }

        val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        Disposer.register(browser, jsQuery)
        jsQuery.addHandler { raw ->
            outcome = raw
            ApplicationManager.getApplication().invokeLater {
                dialog.dialogWrapper.close(DIALOG_OK_EXIT_CODE, true)
            }
            JBCefJSQuery.Response(null)
        }

        browser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadStart(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    transitionType: CefRequest.TransitionType,
                ) {
                    // 隐藏 JCEF Swing 组件(加载中白屏),仅保留挑战挂件就绪后的画面
                    if (frame.isMain) this@TurnstileDialog.browser.component.isVisible = false
                }

                override fun onLoadEnd(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    httpStatusCode: Int,
                ) {
                    if (!frame.isMain) return
                    cefBrowser.executeJavaScript(injectionJs(jsQuery), cefBrowser.url, 0)
                    this@TurnstileDialog.browser.component.isVisible = true
                }
            },
            browser.cefBrowser,
        )

        cookies.forEach { (name, value) ->
            browser.jbCefCookieManager
                .setCookie(config.pageUrl, JBCefCookie(name, value, config.domain, "/", true, false))
                .cancel(true)
        }

        Disposer.register(dialog, browser)
        dialog.show()
        return outcome
    }

    /** 挑战节点移除 + Turnstile 挂件注入脚本(结构照抄旧实现,常量来自 config)。 */
    private fun injectionJs(jsQuery: JBCefJSQuery): String =
        """
        document.documentElement.style = "overflow: hidden;";
        const node = document.querySelector("${config.challengeSelector}");
        if (node != null) {
           node.parentElement.removeChild(node);
           const mask = document.createElement("div");
           mask.setAttribute("id","cf-turnstile-mask");
           mask.style = "position: fixed; top: 0; left: 0; width: 100vw; height: 100vh; " +
               "background-color: ${panelBackgroundCss()}; z-index: 9999; display: flex; " +
               "align-items: center; justify-content: center;";
           document.body.append(mask);
           const csrf = document.querySelector("input[name='csrf_token']")?.value ?? "";
           ${config.extraJs}
           turnstile.render('#cf-turnstile-mask', {
              sitekey: '${config.sitekey}',
              theme: '${if (JBColor.isBright()) "light" else "dark"}',
              callback: function(turnstile) {
                const result = ${config.resultExpression};
                ${jsQuery.inject("result")}
           }});
        }
        """.trimIndent()

    private fun panelBackgroundCss(): String {
        val panel = JBColor.PanelBackground
        return "rgb(${panel.red},${panel.green},${panel.blue})"
    }

    private companion object {
        const val DIALOG_WIDTH = 400
        const val DIALOG_HEIGHT = 400
        const val DIALOG_OK_EXIT_CODE = 0
    }
}

/**
 * [AntiBotChallengeHandler] 的 JCEF 生产实现(WP23):从 SPI transport 取平台
 * cookie(保持弹窗内登录态),在 EDT 弹 [TurnstileDialog];协议见接口 KDoc。
 */
class JcefAntiBotChallengeHandler : AntiBotChallengeHandler {
    override suspend fun solve(challenge: TurnstileConfig): String? {
        val cookies = OJRuntime.transport().cookies(challenge.platformId, challenge.pageUrl)
        var solved: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            solved = runCatching { TurnstileDialog(challenge, cookies).showAndSolve() }.getOrNull()
        }
        return solved
    }
}

/** EP `com.codequest.antiBotHandlerProvider` 注册(OJRuntime.antiBotHandler 消费)。 */
class JcefAntiBotChallengeHandlerProvider : AntiBotChallengeHandlerProvider {
    override fun create(): AntiBotChallengeHandler = JcefAntiBotChallengeHandler()
}
