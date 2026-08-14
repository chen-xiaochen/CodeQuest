package com.codequest.ui.jcef

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CQBridge 桥协议的核心分发逻辑(WP11)。
 *
 * 设计原则(见 spec 2026-08-14 §4):桥消息**只承载命令与事件**(提交、打开文件、跳转等),
 * 页面数据一律走本地 ResourceHttpServer 的 REST 端点拉取,避免旧版大数据量消息序列化卡顿。
 *
 * topic 采用命名空间风格:`problems:list` / `submission:submit` / `nav:openProblem` 等,
 * 冒号前为域,冒号后为动作。
 *
 * 本类是纯 Kotlin 逻辑,不依赖 IntelliJ Platform / JCEF,可脱离真实浏览器进行单元测试;
 * [CQBridge] 负责把 JCEF 传输层接入本分发器。
 */
class TopicDispatcher(
    private val onHandlerError: ((topic: String, error: Throwable) -> Unit)? = null,
) {
    private val handlers = ConcurrentHashMap<String, CopyOnWriteArrayList<(String) -> Unit>>()

    /** 当前已注册 handler 的 topic 集合(快照)。 */
    val topics: Set<String>
        get() = handlers.keys.toSet()

    /**
     * 注册接收 handler。
     *
     * @param topic 命名空间化 topic,如 `problems:list`
     * @param handler 收到消息时回调,参数为原始 JSON payload 字符串
     */
    fun on(
        topic: String,
        handler: (String) -> Unit,
    ) {
        require(topic.isNotBlank()) { "topic must not be blank" }
        handlers.computeIfAbsent(topic) { CopyOnWriteArrayList() }.add(handler)
    }

    /** 注销 handler;topic 未注册过也安全(幂等)。 */
    fun off(
        topic: String,
        handler: (String) -> Unit,
    ) {
        handlers[topic]?.remove(handler)
    }

    /**
     * 分发一条消息到该 topic 的所有 handler。
     *
     * @return topic 是否有已注册 handler;未知 topic 直接返回 false,不抛异常
     */
    fun dispatch(
        topic: String,
        payload: String,
    ): Boolean {
        require(topic.isNotBlank()) { "topic must not be blank" }
        val topicHandlers = handlers[topic]?.takeIf { it.isNotEmpty() } ?: return false
        for (handler in topicHandlers) {
            try {
                handler(payload)
            } catch (error: Throwable) {
                // 单个 handler 崩溃不阻断同 topic 其余 handler,也不向调用方传播
                onHandlerError?.invoke(topic, error)
            }
        }
        return true
    }

    /** 清空全部注册(测试与销毁路径用)。 */
    fun clear() {
        handlers.clear()
    }
}
