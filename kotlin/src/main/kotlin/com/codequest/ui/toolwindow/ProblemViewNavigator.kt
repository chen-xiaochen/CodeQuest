package com.codequest.ui.toolwindow

import com.codequest.infra.messaging.CodeQuestHttpServerService
import com.codequest.infra.settings.CodeQuestSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.jcef.JBCefBrowser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.logging.Logger

/**
 * 题面工具窗口导航器(WP20):处理 `nav:openProblem` 桥消息,在题面工具窗口中
 * 打开指定平台题目。
 *
 * 链路:problems 页点击行 → bridge `nav:openProblem {platformId, remoteId}` →
 * 本服务 [openProblem] → 显示 "CodeQuest Problem View" 工具窗口 → 浏览器加载
 * `/problem-view/?platform=&remoteId=`(页面经 REST 端点拉题面,不走红数据桥)。
 *
 * 首次显示前收到导航请求时记为 pending URL,由 [ProblemViewToolWindowFactory]
 * 创建内容时消费(避免「先加载空页再二次跳转」);浏览器已就绪则直接 loadURL。
 */
@Service(Level.PROJECT)
class ProblemViewNavigator(
    private val project: Project,
) {
    @Volatile
    private var pendingUrl: String? = null

    @Volatile
    private var browser: JBCefBrowser? = null

    /** 工厂创建浏览器后注册引用(随工具窗口内容销毁置 null)。 */
    fun attach(browser: JBCefBrowser?) {
        this.browser = browser
    }

    /** 工厂创建内容时取走待打开 URL(无则 null,由调用方回退默认页面)。 */
    fun consumePendingUrl(): String? =
        pendingUrl.also {
            pendingUrl = null
        }

    /** 在题面工具窗口打开指定题目(可在任意线程调用)。 */
    fun openProblem(
        platformId: String,
        remoteId: String,
    ) {
        // 1.6.0:记录最近打开的题目供启动恢复(恢复链路重写同值,幂等无害)
        CodeQuestSettings.getInstance(project).updateLastOpenedProblem(encodeLastOpenedProblem(platformId, remoteId))
        ApplicationManager.getApplication().invokeLater {
            val base = project.getService(CodeQuestHttpServerService::class.java).baseUrl ?: return@invokeLater
            val url =
                "$base$PROBLEM_VIEW_ROUTE?platform=${encode(platformId)}&remoteId=${encode(remoteId)}"
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return@invokeLater
            toolWindow.show(null)
            val attached = browser
            if (attached != null) {
                pendingUrl = null
                attached.loadURL(url)
            } else {
                // 工具窗口内容尚未创建:show() 将触发工厂消费 pendingUrl 作为初始 URL
                pendingUrl = url
            }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        const val TOPIC_NAV_OPEN_PROBLEM = "nav:openProblem"

        private const val TOOL_WINDOW_ID = "CodeQuest Problem View"

        private const val PROBLEM_VIEW_ROUTE = "/problem-view/"
    }
}

/** nav:openProblem 的载荷。 */
data class OpenProblemRequest(
    val platformId: String,
    val remoteId: String,
)

/**
 * 解析 `nav:openProblem` 桥消息 payload(纯函数,单测锁定协议):
 * `{"platformId":"leetcode","remoteId":"two-sum"}`;缺字段/非法 JSON 返回 null。
 */
fun parseOpenProblemPayload(payload: String): OpenProblemRequest? =
    runCatching {
        val element = Json.parseToJsonElement(payload).jsonObject
        val platformId = element["platformId"]?.jsonPrimitive?.content
        val remoteId = element["remoteId"]?.jsonPrimitive?.content
        if (platformId.isNullOrEmpty() || remoteId.isNullOrEmpty()) {
            null
        } else {
            OpenProblemRequest(platformId, remoteId)
        }
    }.getOrNull()
        .also {
            if (it == null) {
                Logger.getLogger("parseOpenProblemPayload").warning("unparseable nav:openProblem payload: $payload")
            }
        }

/** 1.6.0:最近打开题目的持久化串编码(`platformId\tremoteId`,存 CodeQuestSettings)。 */
fun encodeLastOpenedProblem(
    platformId: String,
    remoteId: String,
): String = "$platformId\t$remoteId"

/** 解析持久化串;格式不符(缺分隔符/空段)返回 null(纯函数,单测锁定)。 */
fun parseLastOpenedProblem(encoded: String): OpenProblemRequest? =
    encoded
        .split('\t')
        .takeIf { parts -> parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty() }
        ?.let { OpenProblemRequest(it[0], it[1]) }
