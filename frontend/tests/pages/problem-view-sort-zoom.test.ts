/**
 * WP41:题解排序下拉 + webview 缩放工具栏。
 * - 排序下拉仅 LeetCode 系平台渲染(原版排序器即 LC 题解视图专属),
 *   选项值/文案与后端 SPI 事实一致;变更即重拉(请求带 orderBy);
 * - 无排序能力平台(洛谷)无下拉且请求不带 orderBy;
 * - 缩放三键(−/100%/+)经 `webview:zoom` 桥消息发出
 *   {delta:±1} 步进 / {level:0} 复位。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProblemViewApp from '../../src/pages/problem-view/App.vue'
import { TOPIC_WEBVIEW_ZOOM, resetGlobalBridgeHook } from '../../src/bridge/cq-bridge'

type FetchHandler = (url: string) => { status: number; body: unknown } | undefined

let handler: FetchHandler = () => undefined
const hitCounts = new Map<string, number>()

beforeEach(() => {
  handler = () => undefined
  hitCounts.clear()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      hitCounts.set(url, (hitCounts.get(url) ?? 0) + 1)
      const result = handler(url)
      return result
        ? new Response(JSON.stringify(result.body), {
            status: result.status,
            headers: { 'Content-Type': 'application/json' },
          })
        : new Response(JSON.stringify({ error: 'not-mocked' }), { status: 404 })
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  delete window.cqSend
  window.__cqBridge = undefined
  resetGlobalBridgeHook()
})

const LC_DETAIL = {
  platform: 'leetcode',
  remoteId: 'two-sum',
  title: 'Two Sum',
  difficulty: 'Easy',
  url: 'https://leetcode.com/problems/two-sum/',
  content: '# Two Sum',
  contentFormat: 'MARKDOWN',
  source: 'remote',
}

const LUOGU_DETAIL = {
  platform: 'luogu',
  remoteId: 'P1001',
  title: 'A+B Problem',
  difficulty: '入门',
  url: 'https://www.luogu.com.cn/problem/P1001',
  content: '# A+B Problem',
  contentFormat: 'MARKDOWN',
  source: 'remote',
}

const HOT_ITEMS = [
  { id: 'hot-1', title: 'hot solution', url: 'https://leetcode.com/articles/hot-1/' },
]
const NEWEST_ITEMS = [
  { id: 'new-1', title: 'newest solution', url: 'https://leetcode.com/articles/new-1/' },
]

function solutionsBody(items: unknown[]) {
  return { platform: 'leetcode', remoteId: 'two-sum', items }
}

describe('problem-view solutions order (WP41)', () => {
  it('renders sort dropdown on leetcode platform and refetches with orderBy on change', async () => {
    window.history.replaceState({}, '', '/problem-view/?platform=leetcode&remoteId=two-sum')
    handler = (url) => {
      if (url === '/api/problem/two-sum/solutions?platform=leetcode&orderBy=hot') {
        return { status: 200, body: solutionsBody(HOT_ITEMS) }
      }
      if (url === '/api/problem/two-sum/solutions?platform=leetcode&orderBy=newest') {
        return { status: 200, body: solutionsBody(NEWEST_ITEMS) }
      }
      if (url.startsWith('/api/problem/two-sum/solutions')) {
        throw new Error(`unexpected solutions url without orderBy: ${url}`)
      }
      if (url.startsWith('/api/problem/two-sum')) {
        return { status: 200, body: LC_DETAIL }
      }
      return undefined
    }

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    // 初始请求显式带默认排序 hot(下拉默认与后端默认一致)
    expect(hitCounts.get('/api/problem/two-sum/solutions?platform=leetcode&orderBy=hot')).toBe(1)
    expect(wrapper.findAll('.solutions__item')).toHaveLength(1)
    expect(wrapper.find('.solutions__item-title').text()).toBe('hot solution')

    // 选项值 = 后端枚举/旧 Show 事实;文案 WP50 中文(value 不变)
    const select = wrapper.find('.solutions__order-select')
    expect(select.exists()).toBe(true)
    const options = select.findAll('option')
    expect(options.map((o) => (o.element as HTMLOptionElement).value)).toEqual([
      'mostUpvote',
      'hot',
      'newest',
      'oldest',
    ])
    expect(options.map((o) => o.text())).toEqual(['最多点赞', '最热', '最新', '最早'])

    // 变更即重拉
    await select.setValue('newest')
    await flushPromises()

    expect(hitCounts.get('/api/problem/two-sum/solutions?platform=leetcode&orderBy=newest')).toBe(1)
    expect(wrapper.find('.solutions__item-title').text()).toBe('newest solution')
  })

  it('omits sort dropdown and orderBy param on platforms without ordering capability', async () => {
    window.history.replaceState({}, '', '/problem-view/?platform=luogu&remoteId=P1001')
    handler = (url) => {
      if (url === '/api/problem/P1001/solutions?platform=luogu') {
        return { status: 200, body: { platform: 'luogu', remoteId: 'P1001', items: [] } }
      }
      if (url.startsWith('/api/problem/P1001/solutions')) {
        throw new Error(`unexpected orderBy in url: ${url}`)
      }
      if (url.startsWith('/api/problem/P1001')) {
        return { status: 200, body: LUOGU_DETAIL }
      }
      return undefined
    }

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    expect(wrapper.find('.solutions__order-select').exists()).toBe(false)
    expect(hitCounts.get('/api/problem/P1001/solutions?platform=luogu')).toBe(1)
    expect(wrapper.text()).toContain('暂无题解')
  })
})

describe('problem-view zoom toolbar (WP41)', () => {
  it('sends webview:zoom bridge messages from the three zoom buttons', async () => {
    window.history.replaceState({}, '', '/problem-view/?platform=luogu&remoteId=P1001')
    handler = (url) => {
      if (url.startsWith('/api/problem/P1001/solutions')) {
        return { status: 200, body: { platform: 'luogu', remoteId: 'P1001', items: [] } }
      }
      if (url.startsWith('/api/problem/P1001')) {
        return { status: 200, body: LUOGU_DETAIL }
      }
      return undefined
    }
    const sends: Array<{ topic: string; payload: unknown }> = []
    vi.stubGlobal('cqSend', (message: string) => {
      const envelope = JSON.parse(message) as { topic: string; payload: string }
      sends.push({ topic: envelope.topic, payload: JSON.parse(envelope.payload) })
    })

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    const buttons = wrapper.findAll('.zoombar__btn')
    expect(buttons).toHaveLength(3)

    await buttons[0].trigger('click')
    await wrapper.find('.zoombar__btn--reset').trigger('click')
    await buttons[2].trigger('click')

    expect(sends).toEqual([
      { topic: TOPIC_WEBVIEW_ZOOM, payload: { delta: -1 } },
      { topic: TOPIC_WEBVIEW_ZOOM, payload: { level: 0 } },
      { topic: TOPIC_WEBVIEW_ZOOM, payload: { delta: 1 } },
    ])
  })

  it('hides zoom toolbar when no problem is selected', async () => {
    window.history.replaceState({}, '', '/problem-view/')

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    expect(wrapper.find('.zoombar').exists()).toBe(false)
  })
})
