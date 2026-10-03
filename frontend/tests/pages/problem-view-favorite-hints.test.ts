/**
 * WP73:题面页收藏星标与题目提示(1.9.0 F1/F2)——
 * 星标中性空心初始态/本地记忆、点击乐观切换、POST /api/favorite/toggle
 * 成功保持+写 localStorage、失败回滚(502 透传/401「请先登录」)、
 * 无 questionId 中性隐藏;提示懒加载(未点击零请求)、逐条 details 折叠、
 * HTML 实体解码、空数组「无提示」、再点收起/展开不重拉、非 LC 平台隐藏。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProblemViewApp from '../../src/pages/problem-view/App.vue'
import { clearUserContestCache } from '../../src/lib/api'
import { resetGlobalBridgeHook } from '../../src/bridge/cq-bridge'

type MockResponse = { status: number; body: unknown; text?: string }
type FetchHandler = (
  url: string,
  init?: RequestInit,
) => MockResponse | undefined | Promise<MockResponse | undefined>

let handler: FetchHandler = () => undefined
let requests: Array<{ url: string; method: string; body?: string }> = []

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  handler = () => undefined
  requests = []
  localStorage.clear()
  clearUserContestCache()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      const body = typeof init?.body === 'string' ? init.body : undefined
      requests.push({ url, method, body })
      const result = await handler(url, init)
      if (!result) return jsonResponse(404, { error: 'not-mocked' })
      if (result.text !== undefined) return new Response(result.text, { status: result.status })
      return jsonResponse(result.status, result.body)
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  delete window.cqSend
  window.__cqBridge = undefined
  resetGlobalBridgeHook()
  localStorage.clear()
  clearUserContestCache()
})

/** 题面(detail 带 questionId,收藏写寻址;可覆盖各端点)。 */
function makeDetail(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    platform: 'leetcode',
    remoteId: 'two-sum',
    title: 'Two Sum',
    difficulty: 'Easy',
    url: 'https://leetcode.com/problems/two-sum/',
    content: '<p>Given an array.</p>',
    contentFormat: 'HTML',
    source: 'cache',
    questionId: '1',
    ...overrides,
  }
}

/** 题面页 handler(detail/hints/solutions/custom.css/review today + toggle 可覆盖)。 */
function problemViewHandler(options: {
  detail?: MockResponse
  hints?: MockResponse
  toggle?: MockResponse
} = {}): FetchHandler {
  return (url, init) => {
    if (url === '/api/favorite/toggle') {
      expect(init?.method).toBe('POST')
      return options.toggle ?? { status: 200, body: { ok: true } }
    }
    if (url.includes('/hints')) {
      return (
        options.hints ?? {
          status: 200,
          body: { hints: ['暴力 &lt;code&gt;O(n²)&lt;/code&gt; 即可通过', 'Try a sorted map'] },
        }
      )
    }
    if (url.includes('/solutions')) {
      return { status: 200, body: { platform: 'leetcode', remoteId: 'two-sum', items: [] } }
    }
    if (url.startsWith('/api/problem/')) {
      return options.detail ?? { status: 200, body: makeDetail() }
    }
    if (url.startsWith('/api/custom.css')) {
      return { status: 200, body: '', text: '' }
    }
    if (url.startsWith('/api/review/today')) {
      return { status: 200, body: { overdue: [], today: [], upcoming: [], totalTracked: 0 } }
    }
    return undefined
  }
}

function openProblemView(query = 'platform=leetcode&remoteId=two-sum'): ReturnType<typeof mount> {
  window.history.replaceState({}, '', `/problem-view/?${query}`)
  return mount(ProblemViewApp)
}

describe('problem-view favorite star (WP73)', () => {
  it('starts neutral (hollow star) without local memory and toggles optimistically to solid on click', async () => {
    let releaseToggle: (() => void) | null = null
    const base = problemViewHandler()
    handler = async (url, init) => {
      if (url === '/api/favorite/toggle') {
        await new Promise<void>((resolve) => {
          releaseToggle = resolve
        })
      }
      return base(url, init)
    }
    const wrapper = openProblemView()
    await flushPromises()

    // 初始:中性空心,无记忆
    const star = wrapper.find('.statement-header__fav')
    expect(star.text()).toBe('☆')
    expect(star.attributes('aria-pressed')).toBe('false')
    expect(localStorage.getItem('cq-fav-leetcode-two-sum')).toBeNull()

    // 点击:请求挂起期间即已切换(乐观),POST 体为 {platformId, questionId, add}
    await star.trigger('click')
    await flushPromises()
    expect(star.text()).toBe('★')
    expect(star.attributes('aria-pressed')).toBe('true')

    releaseToggle!()
    await flushPromises()
    // 成功:保持实心 + 写本地记忆
    expect(star.text()).toBe('★')
    expect(localStorage.getItem('cq-fav-leetcode-two-sum')).toBe('1')
    const toggle = requests.find((r) => r.url === '/api/favorite/toggle')
    expect(toggle?.method).toBe('POST')
    expect(JSON.parse(String(toggle?.body))).toEqual({ platformId: 'leetcode', questionId: '1', add: true })
  })

  it('rolls back to hollow with the backend message when the toggle fails with 502', async () => {
    handler = problemViewHandler({
      toggle: { status: 502, body: { error: 'oj-error', message: '站方拒绝' } },
    })
    const wrapper = openProblemView()
    await flushPromises()

    const star = wrapper.find('.statement-header__fav')
    await star.trigger('click')
    await flushPromises()

    expect(star.text()).toBe('☆')
    expect(wrapper.find('[role="alert"]').text()).toContain('站方拒绝')
    expect(localStorage.getItem('cq-fav-leetcode-two-sum')).toBeNull()
  })

  it('rolls back with 请先登录 on 401 auth-expired', async () => {
    handler = problemViewHandler({
      toggle: { status: 401, body: { error: 'auth-expired', message: '用户未登录' } },
    })
    const wrapper = openProblemView()
    await flushPromises()

    const star = wrapper.find('.statement-header__fav')
    await star.trigger('click')
    await flushPromises()

    expect(star.text()).toBe('☆')
    expect(wrapper.find('[role="alert"]').text()).toContain('请先登录')
  })

  it('starts solid from localStorage memory and removes the memory on toggle-off', async () => {
    localStorage.setItem('cq-fav-leetcode-two-sum', '1')
    handler = problemViewHandler()
    const wrapper = openProblemView()
    await flushPromises()

    const star = wrapper.find('.statement-header__fav')
    expect(star.text()).toBe('★')
    expect(star.attributes('aria-pressed')).toBe('true')

    await star.trigger('click')
    await flushPromises()

    // 记忆态点击 = 移除:add:false,记忆清除,回到空心
    expect(star.text()).toBe('☆')
    expect(localStorage.getItem('cq-fav-leetcode-two-sum')).toBeNull()
    const toggle = requests.find((r) => r.url === '/api/favorite/toggle')
    expect(JSON.parse(String(toggle?.body))).toEqual({ platformId: 'leetcode', questionId: '1', add: false })
  })

  it('hides the star when the detail carries no questionId', async () => {
    handler = problemViewHandler({
      detail: { status: 200, body: makeDetail({ questionId: null }) },
    })
    const wrapper = openProblemView()
    await flushPromises()

    expect(wrapper.find('.statement-header__fav').exists()).toBe(false)
  })
})

describe('problem-view hints (WP73)', () => {
  it('lazy-loads hints on first click only, rendering one collapsed details per hint with entities decoded', async () => {
    handler = problemViewHandler()
    const wrapper = openProblemView()
    await flushPromises()

    // 未点击零 hints 请求(懒加载)
    expect(requests.some((r) => r.url.includes('/hints'))).toBe(false)
    expect(wrapper.find('.hints').exists()).toBe(false)

    await wrapper.find('.statement-header__hints').trigger('click')
    await flushPromises()

    const hintsGet = requests.find((r) => r.url.includes('/hints'))
    expect(hintsGet?.url).toBe('/api/problem/two-sum/hints?platform=leetcode')
    expect(hintsGet?.method).toBe('GET')

    // 逐条折叠:details/summary「提示 N」结构,默认收起(open 属性缺省)
    const items = wrapper.findAll('.hints__item')
    expect(items.length).toBe(2)
    expect(items[0].find('.hints__summary').text()).toBe('提示 1')
    expect(items[1].find('.hints__summary').text()).toBe('提示 2')
    expect(items[0].attributes('open')).toBeUndefined()

    // 正文文本渲染:HTML 实体解码为字面文本(&lt;code&gt; → <code>)
    expect(items[0].find('.hints__text').text()).toContain('<code>O(n²)</code>')
  })

  it('shows 无提示 for an empty hints array', async () => {
    handler = problemViewHandler({ hints: { status: 200, body: { hints: [] } } })
    const wrapper = openProblemView()
    await flushPromises()

    await wrapper.find('.statement-header__hints').trigger('click')
    await flushPromises()

    expect(wrapper.find('.hints__state').text()).toBe('无提示')
    expect(wrapper.findAll('.hints__item').length).toBe(0)
  })

  it('collapses the whole block on second click and reopens without refetching', async () => {
    handler = problemViewHandler()
    const wrapper = openProblemView()
    await flushPromises()

    await wrapper.find('.statement-header__hints').trigger('click')
    await flushPromises()
    expect(wrapper.find('.hints').exists()).toBe(true)
    expect(requests.filter((r) => r.url.includes('/hints')).length).toBe(1)

    // 再点:收起整个区块
    await wrapper.find('.statement-header__hints').trigger('click')
    await flushPromises()
    expect(wrapper.find('.hints').exists()).toBe(false)

    // 再点:展开且不重拉(缓存态)
    await wrapper.find('.statement-header__hints').trigger('click')
    await flushPromises()
    expect(wrapper.find('.hints').exists()).toBe(true)
    expect(wrapper.findAll('.hints__item').length).toBe(2)
    expect(requests.filter((r) => r.url.includes('/hints')).length).toBe(1)
  })

  it('hides the hints button on non-LeetCode platforms', async () => {
    handler = problemViewHandler({
      detail: {
        status: 200,
        body: makeDetail({ platform: 'codeforces', remoteId: '1927A', questionId: null }),
      },
    })
    const wrapper = openProblemView('platform=codeforces&remoteId=1927A')
    await flushPromises()

    expect(wrapper.find('.statement-header__hints').exists()).toBe(false)
  })
})
