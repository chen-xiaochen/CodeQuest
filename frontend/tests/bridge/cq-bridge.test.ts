/**
 * CQBridge JS 客户端协议测试(TDD 先行)。
 *
 * 断言对齐 Kotlin 侧 CQBridge.kt / TopicDispatcher.kt 的线上格式(WP11 实测落定):
 * - JS→Kotlin:window.cqSend(JSON.stringify({topic, payload})),payload 必须是
 *   **JSON 字符串**(Kotlin handleInbound 用 jsonPrimitive.content 取值,
 *   payload 若为对象/数字等非字符串 primitive,消息会被静默丢弃)。
 * - Kotlin→JS:executeJavaScript 调用 window.__cqBridge.emit({topic, payload})
 *   ——emit 收到的是**对象字面量**(不是字符串);也兼容字符串形式以便手工调试。
 * - 信封双向统一:{"topic":"<ns:action>","payload":"<json 文本>"}。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  TOPIC_ECHO,
  TOPIC_ECHO_REPLY,
  createCqBridge,
  resetGlobalBridgeHook,
} from '../../src/bridge/cq-bridge'

describe('cq-bridge 信封格式(与 Kotlin CQBridge.kt 对齐)', () => {
  let sent: string[]

  beforeEach(() => {
    sent = []
    // 模拟 Kotlin JBCefJSQuery 注入的真实发送函数
    window.cqSend = (message: string) => {
      sent.push(message)
    }
  })

  afterEach(() => {
    delete window.cqSend
    delete window.__cqBridge
    resetGlobalBridgeHook()
    vi.restoreAllMocks()
  })

  it('send 走 window.cqSend,payload 序列化为 JSON 字符串', () => {
    const bridge = createCqBridge()
    bridge.send(TOPIC_ECHO, { hello: 'cq' })

    expect(sent).toHaveLength(1)
    const envelope = JSON.parse(sent[0]) as { topic: string; payload: string }
    expect(envelope.topic).toBe('bridge:echo')
    // Kotlin 侧要求 payload 是字符串 primitive,内容是业务 JSON 文本
    expect(typeof envelope.payload).toBe('string')
    expect(envelope.payload).toBe(JSON.stringify({ hello: 'cq' }))
  })

  it('send payload 为 null/undefined 时仍输出合法信封', () => {
    const bridge = createCqBridge()
    bridge.send('problems:list', undefined)

    const envelope = JSON.parse(sent[0]) as { topic: string; payload: string }
    expect(envelope.topic).toBe('problems:list')
    expect(typeof envelope.payload).toBe('string')
  })

  it('emit 对象字面量形式(Kotlin postMessage 实际传参)正确分发', () => {
    const bridge = createCqBridge()
    const handler = vi.fn()
    bridge.on('problems:list', handler)

    // Kotlin: emit({"topic":"...","payload":"<json string>"})
    window.__cqBridge!.emit({
      topic: 'problems:list',
      payload: JSON.stringify({ items: [1, 2] }),
    })

    expect(handler).toHaveBeenCalledOnce()
    expect(handler).toHaveBeenCalledWith({ items: [1, 2] })
  })

  it('emit 字符串信封形式也能分发,payload 非 JSON 时透传原始字符串', () => {
    const bridge = createCqBridge()
    const handler = vi.fn()
    bridge.on('nav:openProblem', handler)

    window.__cqBridge!.emit(
      JSON.stringify({ topic: 'nav:openProblem', payload: 'plain-text' }),
    )

    expect(handler).toHaveBeenCalledWith('plain-text')
  })

  it('off 注销后不再收到消息', () => {
    const bridge = createCqBridge()
    const handler = vi.fn()
    bridge.on('problems:list', handler)
    bridge.off('problems:list', handler)

    window.__cqBridge!.emit({ topic: 'problems:list', payload: '{}' })
    expect(handler).not.toHaveBeenCalled()
  })

  it('on 返回的反注册函数等价于 off', () => {
    const bridge = createCqBridge()
    const handler = vi.fn()
    const dispose = bridge.on('problems:list', handler)
    dispose()

    window.__cqBridge!.emit({ topic: 'problems:list', payload: '{}' })
    expect(handler).not.toHaveBeenCalled()
  })

  it('未知 topic / 畸形消息不抛异常', () => {
    createCqBridge()
    expect(() => {
      window.__cqBridge!.emit({ topic: 'unknown:topic', payload: '{}' })
      window.__cqBridge!.emit('not-a-json')
      window.__cqBridge!.emit('{"payload":"{}"}')
    }).not.toThrow()
  })

  it('单个 handler 抛错不阻断同 topic 其余 handler(对齐 TopicDispatcher 语义)', () => {
    const bridge = createCqBridge()
    const boom = vi.fn(() => {
      throw new Error('boom')
    })
    const ok = vi.fn()
    bridge.on('problems:list', boom)
    bridge.on('problems:list', ok)

    expect(() => {
      window.__cqBridge!.emit({ topic: 'problems:list', payload: '{}' })
    }).not.toThrow()
    expect(ok).toHaveBeenCalledOnce()
  })
})

describe('cq-bridge dev 浏览器 mock 回退(无 cqSend 注入环境)', () => {
  beforeEach(() => {
    delete window.cqSend
  })

  afterEach(() => {
    delete window.__cqBridge
    resetGlobalBridgeHook()
    vi.restoreAllMocks()
  })

  it('bridge:echo 直接回环:send 后收到 bridge:echo:reply 回声', async () => {
    const bridge = createCqBridge()
    const reply = vi.fn()
    bridge.on(TOPIC_ECHO_REPLY, reply)

    bridge.send(TOPIC_ECHO, { hello: 'cq' })
    await vi.waitFor(() => expect(reply).toHaveBeenCalledOnce())
    expect(reply).toHaveBeenCalledWith({ hello: 'cq' })
  })

  it('mock 模式下非 echo topic 仅本地分发(自发自收)', () => {
    const bridge = createCqBridge()
    const handler = vi.fn()
    bridge.on('ui:ping', handler)

    bridge.send('ui:ping', { n: 1 })
    expect(handler).toHaveBeenCalledWith({ n: 1 })
  })

  it('isRealBridge 反映传输通道状态', () => {
    const bridge = createCqBridge()
    expect(bridge.isRealBridge()).toBe(false)

    window.cqSend = () => {}
    expect(bridge.isRealBridge()).toBe(true)
    delete window.cqSend
  })
})
