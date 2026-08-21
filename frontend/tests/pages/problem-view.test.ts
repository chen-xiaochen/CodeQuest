/**
 * WP26/WP31:problem-view 页题解区块——detail + solutions 双端点加载、
 * 题解降级提示(未登录 auth-expired 不影响题面)、列表→应用内正文阅读器
 * (正文经 /solutions/{sid} 端点 + MD 管线:代码高亮/KaTeX)、浏览器风格
 * 导航工具栏(后退/前进/刷新,历史栈页内维护)、站外打开仍走
 * `nav:openUrl` 桥消息外发(无真实桥时回退 window.open)。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProblemViewApp from '../../src/pages/problem-view/App.vue'
import { TOPIC_AUTH_LOGIN, TOPIC_AUTH_LOGIN_RESULT, TOPIC_CHALLENGE_CREATE, TOPIC_CHALLENGE_CREATE_RESULT, TOPIC_NAV_OPEN_URL, resetGlobalBridgeHook } from '../../src/bridge/cq-bridge'

type FetchHandler = (url: string, init?: RequestInit) => { status: number; body: unknown } | undefined

let handler: FetchHandler = () => undefined
/** 各 URL 的命中计数(refresh/缓存行为断言用)。 */
const hitCounts = new Map<string, number>()

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
  hitCounts.clear()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      hitCounts.set(url, (hitCounts.get(url) ?? 0) + 1)
      const result = handler(url, init)
      return result ? jsonResponse(result.status, result.body) : jsonResponse(404, { error: 'not-mocked' })
    }),
  )
  window.history.replaceState({}, '', '/problem-view/?platform=luogu&remoteId=P1001')
})

afterEach(() => {
  vi.unstubAllGlobals()
  delete window.cqSend
  window.__cqBridge = undefined
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

/** WP31 正文夹具:Markdown,含代码块与行内公式(冒烟断言 hljs/KaTeX)。 */
function articleBody(id: string, title: string) {
  return {
    platform: 'luogu',
    remoteId: 'P1001',
    id,
    title,
    content: [
      '## 思路',
      '',
      '前缀和 $a_i + b_i$ 单调递增。',
      '',
      '```cpp',
      'int main() { return 0; }',
      '```',
    ].join('\n'),
    contentFormat: 'MARKDOWN',
  }
}

const CONTENT_77 = articleBody('77', '一份题解')
const CONTENT_78 = articleBody('78', 'bob')

function happyHandler(): FetchHandler {
  return (url) => {
    if (url === '/api/problem/P1001/solutions/77?platform=luogu') {
      return { status: 200, body: CONTENT_77 }
    }
    if (url === '/api/problem/P1001/solutions/78?platform=luogu') {
      return { status: 200, body: CONTENT_78 }
    }
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

    expect(wrapper.find('.solutions__state').text()).toContain('暂无题解')
  })
})

describe('problem-view solution reader (WP31)', () => {
  it('opens article in-app and renders markdown pipeline with highlight and math', async () => {
    handler = happyHandler()

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    await wrapper.findAll('.solutions__open')[0].trigger('click')
    await flushPromises()

    // 列表被阅读器取代
    expect(wrapper.find('.reader').exists()).toBe(true)
    expect(wrapper.findAll('.solutions__item')).toHaveLength(0)
    expect(wrapper.find('.reader__title').text()).toBe('一份题解')

    // 复用 MD 渲染管线:代码高亮类名 + KaTeX 公式
    const body = wrapper.find('.reader__body')
    expect(body.exists()).toBe(true)
    expect(body.find('h2').text()).toBe('思路')
    expect(body.find('pre > code').html()).toContain('hljs-keyword')
    expect(body.findAll('.katex').length).toBeGreaterThanOrEqual(1)

    // 按钮态:可后退、不可前进
    expect((wrapper.findAll('.solutions__nav-btn')[0].element as HTMLButtonElement).disabled).toBe(false)
    expect((wrapper.findAll('.solutions__nav-btn')[1].element as HTMLButtonElement).disabled).toBe(true)
  })

  it('shows content error state with retry inside reader', async () => {
    handler = (url) => {
      if (url === '/api/problem/P1001/solutions/77?platform=luogu') {
        return { status: 502, body: { error: 'oj-error', message: '洛谷题解不存在' } }
      }
      return happyHandler()(url)
    }

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    await wrapper.findAll('.solutions__open')[0].trigger('click')
    await flushPromises()

    const state = wrapper.find('.reader__state--error')
    expect(state.exists()).toBe(true)
    expect(state.text()).toContain('oj-error')

    // Retry 重新请求正文
    handler = happyHandler()
    await state.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.reader__body').exists()).toBe(true)
  })

  it('navigates back to list and forward back to cached article', async () => {
    handler = happyHandler()

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    await wrapper.findAll('.solutions__open')[0].trigger('click')
    await flushPromises()
    const contentUrl = '/api/problem/P1001/solutions/77?platform=luogu'
    expect(hitCounts.get(contentUrl)).toBe(1)

    // 后退 → 回列表根视图
    await wrapper.findAll('.solutions__nav-btn')[0].trigger('click')
    await flushPromises()
    expect(wrapper.find('.reader').exists()).toBe(false)
    expect(wrapper.findAll('.solutions__item')).toHaveLength(2)

    // 前进 → 回正文(命中缓存,不再发请求)
    await wrapper.findAll('.solutions__nav-btn')[1].trigger('click')
    await flushPromises()
    expect(wrapper.find('.reader__title').text()).toBe('一份题解')
    expect(hitCounts.get(contentUrl)).toBe(1)
  })

  it('truncates forward history when opening a new article after back', async () => {
    handler = happyHandler()

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    await wrapper.findAll('.solutions__open')[0].trigger('click')
    await flushPromises()
    await wrapper.findAll('.solutions__nav-btn')[0].trigger('click')
    await flushPromises()

    // 后退到列表后打开另一篇 → 前进分支被截断(浏览器语义)
    await wrapper.findAll('.solutions__open')[1].trigger('click')
    await flushPromises()
    expect(wrapper.find('.reader__title').text()).toBe('bob')
    expect((wrapper.findAll('.solutions__nav-btn')[1].element as HTMLButtonElement).disabled).toBe(true)
  })

  it('refresh re-fetches current article bypassing cache', async () => {
    handler = happyHandler()

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    await wrapper.findAll('.solutions__open')[0].trigger('click')
    await flushPromises()
    const contentUrl = '/api/problem/P1001/solutions/77?platform=luogu'
    expect(hitCounts.get(contentUrl)).toBe(1)

    await wrapper.findAll('.solutions__nav-btn')[2].trigger('click')
    await flushPromises()

    expect(hitCounts.get(contentUrl)).toBe(2)
    expect(wrapper.find('.reader__body').exists()).toBe(true)
  })

  it('refresh from list root reloads the solutions list', async () => {
    let items = SOLUTIONS
    handler = (url) => {
      if (url.startsWith('/api/problem/P1001/solutions?')) {
        return { status: 200, body: { platform: 'luogu', remoteId: 'P1001', items } }
      }
      if (url.startsWith('/api/problem/P1001')) {
        return { status: 200, body: DETAIL }
      }
      return undefined
    }

    const wrapper = mount(ProblemViewApp)
    await flushPromises()
    expect(wrapper.findAll('.solutions__item')).toHaveLength(2)

    items = [SOLUTIONS[0]]
    await wrapper.findAll('.solutions__nav-btn')[2].trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.solutions__item')).toHaveLength(1)
  })

  it('sends nav:openUrl bridge message from reader external open', async () => {
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

    // 列表点击进阅读器,再从工具栏站外打开(真实桥存在,不回退 window.open)
    await wrapper.findAll('.solutions__open')[1].trigger('click')
    await flushPromises()
    await wrapper.find('.reader__external').trigger('click')

    expect(sends).toEqual([
      {
        topic: TOPIC_NAV_OPEN_URL,
        payload: { url: 'https://www.luogu.com.cn/problem/solution/P1001#solution-78' },
      },
    ])
    expect(openSpy).not.toHaveBeenCalled()
  })

  it('falls back to window.open when no real bridge is injected', async () => {
    handler = happyHandler()
    const openSpy = vi.fn()
    vi.stubGlobal('open', openSpy)

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    await wrapper.findAll('.solutions__open')[0].trigger('click')
    await flushPromises()
    await wrapper.find('.reader__external').trigger('click')

    expect(openSpy).toHaveBeenCalledWith(
      'https://www.luogu.com.cn/problem/solution/P1001#solution-77',
      '_blank',
      'noopener',
    )
  })
})

describe('problem-view login entry (1.0.x auth:login)', () => {
  it('shows Login button on auth-expired statement error, sends auth:login and refetches on success', async () => {
    let detailHits = 0
    handler = (url) => {
      if (url.startsWith('/api/problem/P1001/solutions')) {
        return { status: 200, body: { platform: 'luogu', remoteId: 'P1001', items: SOLUTIONS } }
      }
      if (url.startsWith('/api/problem/P1001')) {
        detailHits += 1
        if (detailHits === 1) {
          return { status: 401, body: { error: 'auth-expired', message: 'auth expired or missing' } }
        }
        return { status: 200, body: DETAIL }
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

    const state = wrapper.find('.state--error')
    expect(state.exists()).toBe(true)
    expect(state.text()).toContain('auth-expired')
    const loginBtn = state.find('.state__login')
    expect(loginBtn.exists()).toBe(true)

    await loginBtn.trigger('click')
    expect(sends).toEqual([{ topic: TOPIC_AUTH_LOGIN, payload: { platform: 'luogu' } }])
    // 登录尚未完成前不重复发请求
    expect(detailHits).toBe(1)

    // Kotlin 回推成功结果(本页平台) → 自动重拉
    window.__cqBridge?.emit({
      topic: TOPIC_AUTH_LOGIN_RESULT,
      payload: JSON.stringify({ ok: true, platform: 'luogu' }),
    })
    await flushPromises()

    expect(detailHits).toBe(2)
    expect(wrapper.find('.statement').text()).toContain('A+B Problem')
  })

  it('ignores login result for a different platform', async () => {
    let detailHits = 0
    handler = (url) => {
      if (url.startsWith('/api/problem/P1001')) {
        // 注意:本分支同时截获题面与题解两个 URL(测试无独立 solutions 分支),
        // 挂载基线 = load + loadSolutions 各一次
        detailHits += 1
        return { status: 401, body: { error: 'auth-expired', message: 'auth expired or missing' } }
      }
      return undefined
    }

    const wrapper = mount(ProblemViewApp)
    await flushPromises()
    const baseline = detailHits
    expect(baseline).toBe(2)

    window.__cqBridge?.emit({
      topic: TOPIC_AUTH_LOGIN_RESULT,
      payload: JSON.stringify({ ok: true, platform: 'hackerrank' }),
    })
    await flushPromises()

    // 他平台结果被忽略:无重拉
    expect(detailHits).toBe(baseline)
    expect(wrapper.find('.state--error').exists()).toBe(true)
  })

  it('shows Login button in solutions block on auth-expired and reloads after login', async () => {
    let solutionsHits = 0
    handler = (url) => {
      if (url.startsWith('/api/problem/P1001/solutions')) {
        solutionsHits += 1
        if (solutionsHits === 1) {
          return { status: 401, body: { error: 'auth-expired', message: '请先登录' } }
        }
        return { status: 200, body: { platform: 'luogu', remoteId: 'P1001', items: SOLUTIONS } }
      }
      if (url.startsWith('/api/problem/P1001')) {
        return { status: 200, body: DETAIL }
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

    const state = wrapper.find('.solutions__state--error')
    expect(state.exists()).toBe(true)
    const loginBtn = state.find('.state__login')
    expect(loginBtn.exists()).toBe(true)

    await loginBtn.trigger('click')
    expect(sends).toEqual([{ topic: TOPIC_AUTH_LOGIN, payload: { platform: 'luogu' } }])

    window.__cqBridge?.emit({
      topic: TOPIC_AUTH_LOGIN_RESULT,
      payload: JSON.stringify({ ok: true, platform: 'luogu' }),
    })
    await flushPromises()

    expect(solutionsHits).toBe(2)
    expect(wrapper.findAll('.solutions__item')).toHaveLength(2)
  })
})

describe('problem-view start coding (1.0.x challenge:create)', () => {
  it('renders Start coding button on statement header and sends full detail payload', async () => {
    handler = happyHandler()
    const sends: Array<{ topic: string; payload: unknown }> = []
    vi.stubGlobal('cqSend', (message: string) => {
      const envelope = JSON.parse(message) as { topic: string; payload: string }
      sends.push({ topic: envelope.topic, payload: JSON.parse(envelope.payload) })
    })

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    const button = wrapper.find('.statement-header__start')
    expect(button.exists()).toBe(true)

    await button.trigger('click')
    expect(sends).toEqual([
      {
        topic: TOPIC_CHALLENGE_CREATE,
        payload: {
          platform: 'luogu',
          remoteId: 'P1001',
          title: 'A+B Problem',
          difficulty: '入门',
          url: 'https://www.luogu.com.cn/problem/P1001',
        },
      },
    ])
  })

  it('shows failure message when creation result is not ok and clears pending on success', async () => {
    handler = happyHandler()
    vi.stubGlobal('cqSend', () => {})

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    await wrapper.find('.statement-header__start').trigger('click')
    expect((wrapper.find('.statement-header__start').element as HTMLButtonElement).disabled).toBe(true)

    window.__cqBridge!.emit({
      topic: TOPIC_CHALLENGE_CREATE_RESULT,
      payload: JSON.stringify({ ok: false, error: 'boom' }),
    })
    await flushPromises()

    expect(wrapper.find('.statement-header__start-msg').text()).toContain('boom')
    expect((wrapper.find('.statement-header__start').element as HTMLButtonElement).disabled).toBe(false)

    await wrapper.find('.statement-header__start').trigger('click')
    window.__cqBridge!.emit({
      topic: TOPIC_CHALLENGE_CREATE_RESULT,
      payload: JSON.stringify({ ok: true }),
    })
    await flushPromises()

    expect(wrapper.find('.statement-header__start-msg').exists()).toBe(false)
    expect((wrapper.find('.statement-header__start').element as HTMLButtonElement).disabled).toBe(false)
  })
})
