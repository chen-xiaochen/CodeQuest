/**
 * CQBridge JS 侧客户端(WP12)。
 *
 * ## 线上协议(与 Kotlin 侧 CQBridge.kt 严格对齐,勿改动格式)
 *
 * - JS → Kotlin:`window.cqSend(JSON.stringify({topic, payload}))`
 *   - payload 必须是 **JSON 字符串**:Kotlin `handleInbound` 以
 *     `element["payload"]?.jsonPrimitive?.content` 取值,若 payload 是对象/
 *     数字等非字符串 primitive,`runCatching` 会得到 null,消息被静默丢弃。
 * - Kotlin → JS:executeJavaScript 执行
 *   `window.__cqBridge.emit({"topic":"...","payload":"..."})`
 *   - emit 实参是**对象字面量**(postMessage 内联 JsonObject 文本);
 *     本客户端同时接受字符串信封,便于 DevTools 手工调试。
 * - 信封双向统一:`{"topic":"<ns:action>","payload":"<json 文本>"}`,
 *   topic 命名空间化(`problems:list` 风格)。
 *
 * ## dev 浏览器 mock 回退
 *
 * 无 `window.cqSend` 注入的环境(纯浏览器 dev / 单测)自动回退为本地回环:
 * 消息直接分发到本地 handler,其中 `bridge:echo` 会像 Kotlin 侧
 * JcefToolWindowSupport 注册的 echo handler 一样回发 `bridge:echo:reply`。
 */

/** Kotlin→JS 信封:emit 实参(Kotlin 内联对象字面量时为对象;也接受字符串)。 */
export interface BridgeEnvelopeLike {
  topic: string
  payload: string
}

/** 消息 handler:payload 已尽力反序列化(JSON 可解析则给对象,否则给原始字符串)。 */
export type BridgeHandler = (payload: unknown) => void

/** 沙箱回声测试点(WP11 Kotlin 侧已注册,见 JcefToolWindowSupport)。 */
export const TOPIC_ECHO = 'bridge:echo'
export const TOPIC_ECHO_REPLY = 'bridge:echo:reply'

/** WP20:题目列表页点击行 → Kotlin 侧打开题面工具窗口;payload {platformId, remoteId}。 */
export const TOPIC_NAV_OPEN_PROBLEM = 'nav:openProblem'

declare global {
  interface Window {
    /** JBCefJSQuery 注入的 JS→Kotlin 发送函数。 */
    cqSend?: (message: string) => void
    /** Kotlin executeJavaScript 调用的 Kotlin→JS 分发钩子。 */
    __cqBridge?: { emit: (envelope: string | BridgeEnvelopeLike) => void }
  }
}

function safeParseJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

/** 尽力把 payload 文本还原为对象;非 JSON 文本原样返回。 */
function decodePayload(raw: unknown): unknown {
  if (typeof raw !== 'string') return raw ?? null
  const parsed = safeParseJson(raw)
  return parsed === undefined ? raw : parsed
}

export interface CqBridge {
  /** JS→Kotlin 发送一条命令/事件消息。 */
  send(topic: string, payload?: unknown): void
  /** 注册接收 handler,返回反注册函数。 */
  on(topic: string, handler: BridgeHandler): () => void
  /** 注销 handler(幂等)。 */
  off(topic: string, handler: BridgeHandler): void
  /** 是否处于真实桥通道(有 Kotlin 注入的 cqSend)。 */
  isRealBridge(): boolean
}

export function createCqBridge(): CqBridge {
  const handlers = new Map<string, Set<BridgeHandler>>()

  function dispatch(topic: string, payload: unknown): void {
    const topicHandlers = handlers.get(topic)
    if (!topicHandlers || topicHandlers.size === 0) return
    for (const handler of [...topicHandlers]) {
      try {
        handler(payload)
      } catch (error) {
        // 单个 handler 崩溃不阻断其余 handler(对齐 Kotlin TopicDispatcher 语义)
        console.error(`[cq-bridge] handler error on topic "${topic}"`, error)
      }
    }
  }

  function handleEmit(envelope: string | BridgeEnvelopeLike): void {
    let record: BridgeEnvelopeLike | undefined
    if (typeof envelope === 'string') {
      const parsed = safeParseJson(envelope)
      if (parsed && typeof parsed === 'object' && 'topic' in parsed) {
        record = parsed as BridgeEnvelopeLike
      }
    } else if (envelope && typeof envelope === 'object' && 'topic' in envelope) {
      record = envelope as BridgeEnvelopeLike
    }
    if (!record || typeof record.topic !== 'string' || record.topic.length === 0) return
    dispatch(record.topic, decodePayload(record.payload))
  }

  function installHook(): void {
    if (!window.__cqBridge) {
      window.__cqBridge = { emit: handleEmit }
    }
  }

  /** dev 回环:模拟 Kotlin JcefToolWindowSupport 注册的 echo handler。 */
  function mockLoopback(topic: string, payload: unknown): void {
    if (topic === TOPIC_ECHO) {
      setTimeout(() => {
        handleEmit({ topic: TOPIC_ECHO_REPLY, payload: JSON.stringify(payload ?? null) })
      }, 0)
      return
    }
    dispatch(topic, payload)
  }

  installHook()

  return {
    send(topic, payload) {
      const payloadText = JSON.stringify(payload ?? null)
      const envelope = JSON.stringify({ topic, payload: payloadText })
      if (typeof window.cqSend === 'function') {
        window.cqSend(envelope)
        return
      }
      console.debug(`[cq-bridge] mock loopback (no window.cqSend): ${envelope}`)
      mockLoopback(topic, decodePayload(payloadText))
    },

    on(topic, handler) {
      installHook()
      let set = handlers.get(topic)
      if (!set) {
        set = new Set()
        handlers.set(topic, set)
      }
      set.add(handler)
      return () => {
        set!.delete(handler)
      }
    },

    off(topic, handler) {
      handlers.get(topic)?.delete(handler)
    },

    isRealBridge() {
      return typeof window.cqSend === 'function'
    },
  }
}

/** 模块级单例(各页面 main.ts 使用,测试请用 createCqBridge 自建实例)。 */
let singleton: CqBridge | undefined

export function cqBridge(): CqBridge {
  singleton ??= createCqBridge()
  return singleton
}

/** 仅测试用:清掉单例挂在 window 上的钩子引用状态。 */
export function resetGlobalBridgeHook(): void {
  singleton = undefined
}
