/**
 * WP26:problem-view 页题解区块——detail + solutions 双端点加载、
 * 题解降级提示(未登录 auth-expired 不影响题面)、点击 open 经
 * `nav:openUrl` 桥消息外发(无真实桥时回退 window.open)。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProblemViewApp from '../../src/pages/problem-view/App.vue'
import { TOPIC_NAV_OPEN_URL, resetGlobalBridgeHook } from '../../src/bridge/cq-bridge'

type FetchHandler = (url: string, init?: RequestInit) => { status: number; body: unknown } | undefined

let handler: FetchHandler = () => undefined

function jsonResponse(
  status: number,
  body: unknown,
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  handler = () => undefined
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const result = handler(url, init)
      return result ? jsonResponse(result.status, result.body) : jsonResponse(404, { error: 'not-mocked' })
    }),
  )
  window.history.replaceState({}, '', '/problem-view/?platform=luogu&remoteId=P1001')
})

afterEach(() => {
  vi.unstubAllGlobals()
  resetGlobalBridgeHook()
})

const DETAIL = {
  platform: 'luogu',
  remoteId: 'P1001',
  title: 'A+B Problem',
  difficulty: '入门',
  url: 'https://www.luogu.com.cn/problem/P1001',
  content: '# P1001 A+B Problem\n\n## 题目描述\n\n计算 $a+b$',
  contentFormat: 'MARKDOWN',
  source: 'remote',
}

const SOLUTIONS = [
  { id: '77', title: '一份题解', url: 'https://www.luogu.com.cn/problem/solution/P1001#solution-77' },
  { id: '78', title: 'bob', url: 'https://www.luogu.com.cn/problem/solution/P1001#solution-78' },
]

function happyHandler(): FetchHandler {
  return (url) => {
    if (url.startsWith('/api/problem/P1001/solutions')) {
      return { status: 200, body: { platform: 'luogu', remoteId: 'P1001', items: SOLUTIONS } }
    }
    if (url.startsWith('/api/problem/P1001')) {
      return { status: 200, body: DETAIL }
    }
    return undefined
  }
}

describe('problem-view solutions block', () => {
  it('renders statement and solution refs from both endpoints', async () => {
    handler = happyHandler()

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    expect(wrapper.find('.statement').text()).toContain('A+B Problem')
    const items = wrapper.findAll('.solutions__item')
    expect(items).toHaveLength(2)
    expect(items[0].find('.solutions__item-title').text()).toBe('一份题解')
  })

  it('degrades solutions block on auth error without affecting statement', async () => {
    handler = (url) => {
      if (url.startsWith('/api/problem/P1001/solutions')) {
        return { status: 401, body: { error: 'auth-expired', message: '请先登录' } }
      }
      if (url.startsWith('/api/problem/P1001')) {
        return { status: 200, body: DETAIL }
      }
      return undefined
    }

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    expect(wrapper.find('.statement').exists()).toBe(true)
    const state = wrapper.find('.solutions__state--error')
    expect(state.exists()).toBe(true)
    expect(state.text()).toContain('auth-expired')
    expect(wrapper.findAll('.solutions__item')).toHaveLength(0)
  })

  it('shows empty state when platform has no solutions', async () => {
    handler = (url) => {
      if (url.startsWith('/api/problem/P1001/solutions')) {
        return { status: 200, body: { platform: 'luogu', remoteId: 'P1001', items: [] } }
      }
      if (url.startsWith('/api/problem/P1001')) {
        return { status: 200, body: DETAIL }
      }
      return undefined
    }

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    expect(wrapper.find('.solutions__state').text()).toContain('No solutions')
  })

  it('sends nav:openUrl bridge message on open click', async () => {
    handler = happyHandler()
    const sends: Array<{ topic: string; payload: unknown }> = []
    vi.stubGlobal('cqSend', (message: string) => {
      const envelope = JSON.parse(message) as { topic: string; payload: string }
      sends.push({ topic: envelope.topic, payload: JSON.parse(envelope.payload) })
    })
    const openSpy = vi.fn()
    vi.stubGlobal('open', openSpy)

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    await wrapper.findAll('.solutions__open')[1].trigger('click')

    expect(sends).toEqual([
      {
        topic: TOPIC_NAV_OPEN_URL,
        payload: { url: 'https://www.luogu.com.cn/problem/solution/P1001#solution-78' },
      },
    ])
    // 真实桥存在时不回退 window.open
    expect(openSpy).not.toHaveBeenCalled()
  })

  it('falls back to window.open when no real bridge is injected', async () => {
    handler = happyHandler()
    const openSpy = vi.fn()
    vi.stubGlobal('open', openSpy)

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    await wrapper.findAll('.solutions__open')[0].trigger('click')

    expect(openSpy).toHaveBeenCalledWith(
      'https://www.luogu.com.cn/problem/solution/P1001#solution-77',
      '_blank',
      'noopener',
    )
  })
})
