/**
 * WP23:contest 页真实渲染(端点 mock)——平台标签/列表行(时间、阶段徽标、
 * 参加外链)/提醒开关回显与订阅-取消交互/空态与错误态。
 * WP50:平台标签登录门控(已登录过滤/全未登录引导/auth 失败不门控)+文案中文断言。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ContestApp from '../../src/pages/contest/App.vue'

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
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const PLATFORMS = [{ id: 'codeforces', displayName: 'CodeForces' }]

const CONTESTS = [
  {
    remoteId: '1900',
    title: 'Codeforces Round 950 (Div. 1)',
    startTime: '2026-08-20T17:35:00Z',
    durationMinutes: 120,
    phase: 'BEFORE',
    url: 'https://codeforces.com/contests/1900',
  },
  {
    remoteId: '1899',
    title: 'Codeforces Round 949 (Div. 2)',
    startTime: '2026-08-18T17:35:00Z',
    durationMinutes: 150,
    phase: 'CODING',
    url: 'https://codeforces.com/contests/1899',
  },
]

/** happy-path 端点回放;subscribed 为已订阅 remoteId 列表(开关回显)。 */
function happyHandler(subscribed: string[] = ['1900']): FetchHandler {
  return (url, init) => {
    if (url === '/api/contests') {
      return { status: 200, body: { platforms: PLATFORMS } }
    }
    if (url === '/api/contests?platform=codeforces') {
      return { status: 200, body: { platform: 'codeforces', items: CONTESTS } }
    }
    if (url === '/api/contests/reminders?platform=codeforces') {
      if (init?.method === 'DELETE') {
        return { status: 200, body: { deleted: true } }
      }
      return {
        status: 200,
        body: {
          platform: 'codeforces',
          items: CONTESTS.filter((contest) => subscribed.includes(contest.remoteId)).map((contest) => ({
            platform: 'codeforces',
            remoteId: contest.remoteId,
            remindAt: '2026-08-19T17:35:00Z',
            notified: false,
          })),
        },
      }
    }
    if (url === '/api/contests/reminders' && init?.method === 'POST') {
      return {
        status: 200,
        body: { platform: 'codeforces', remoteId: '1899', remindAt: '2026-08-17T17:35:00Z', notified: false },
      }
    }
    return undefined
  }
}

describe('contest page', () => {
  it('shows an empty state when no platform implements contests', async () => {
    handler = (url) => {
      if (url === '/api/contests') {
        return { status: 200, body: { platforms: [] } }
      }
      return undefined
    }
    const wrapper = mount(ContestApp)
    await flushPromises()

    expect(wrapper.text()).toContain('尚未接入支持竞赛的平台')
  })

  it('renders contest rows with time, phase badge, join link and reminder toggles', async () => {
    handler = happyHandler()
    const wrapper = mount(ContestApp)
    await flushPromises()

    const rows = wrapper.findAll('.contests__row')
    expect(rows.length).toBe(2)
    expect(rows[0].text()).toContain('1900')
    expect(rows[0].text()).toContain('Codeforces Round 950')
    expect(rows[0].text()).toContain('2 小时')
    expect(rows[0].find('.phase-badge--BEFORE').exists()).toBe(true)
    expect(rows[0].find('.phase-badge--BEFORE').text()).toBe('未开始')
    expect(rows[1].find('.phase-badge--CODING').text()).toBe('进行中')

    const link = rows[0].find('a.contests__join')
    expect(link.attributes('href')).toBe('https://codeforces.com/contests/1900')
    expect(link.text()).toBe('打开')
    expect(rows[0].text()).toContain('提前 24 小时提醒')

    // 1900 已订阅(回显 checked),1899 未订阅
    const toggles = wrapper.findAll('input.contests__remind')
    expect((toggles[0].element as HTMLInputElement).checked).toBe(true)
    expect((toggles[1].element as HTMLInputElement).checked).toBe(false)
  })

  it('subscribes a reminder on toggle-on and unsubscribes on toggle-off', async () => {
    const calls: Array<{ url: string; method: string; body?: string }> = []
    const base = happyHandler()
    handler = (url, init) => {
      calls.push({ url, method: init?.method ?? 'GET', body: typeof init?.body === 'string' ? init.body : undefined })
      return base(url, init)
    }
    const wrapper = mount(ContestApp)
    await flushPromises()

    const toggles = wrapper.findAll('input.contests__remind')
    await toggles[1].setValue(true)
    const post = calls.find((call) => call.method === 'POST')
    expect(post?.url).toBe('/api/contests/reminders')
    expect(post?.body).toContain('"remoteId":"1899"')
    expect(post?.body).toContain('"leadTimeMinutes":1440')

    await toggles[0].setValue(false)
    const remove = calls.filter((call) => call.method === 'DELETE').pop()
    expect(remove?.url).toContain('platform=codeforces')
    expect(remove?.url).toContain('remoteId=1900')
  })

  it('surfaces endpoint errors', async () => {
    handler = (url) => {
      if (url === '/api/contests') {
        return { status: 200, body: { platforms: PLATFORMS } }
      }
      return { status: 429, body: { error: 'rate-limited', message: 'slow down' } }
    }
    const wrapper = mount(ContestApp)
    await flushPromises()

    expect(wrapper.text()).toContain('rate-limited')
  })
})

// ---------------------------------------------------------------------------
// WP50:平台标签登录门控(join /api/auth/status,照题库页模式)
// ---------------------------------------------------------------------------

const TWO_PLATFORMS = [
  { id: 'codeforces', displayName: 'CodeForces' },
  { id: 'atcoder', displayName: 'AtCoder' },
]

function authHandler(items: unknown[]): FetchHandler {
  const base = happyHandler()
  return (url, init) => {
    if (url === '/api/auth/status') {
      return { status: 200, body: { items } }
    }
    if (url === '/api/contests') {
      return { status: 200, body: { platforms: TWO_PLATFORMS } }
    }
    return base(url, init)
  }
}

describe('contest page platform gate (WP50)', () => {
  it('keeps only logged-in platforms in tabs and defaults to the first one', async () => {
    handler = authHandler([
      { platform: 'codeforces', loggedIn: true, userId: 'cf_user' },
      { platform: 'atcoder', loggedIn: false, userId: null },
    ])
    const wrapper = mount(ContestApp)
    await flushPromises()

    const tabs = wrapper.findAll('.tabs__item')
    expect(tabs.length).toBe(1)
    expect(tabs[0].text()).toBe('CodeForces')
    // 默认选中已登录平台并加载其竞赛
    expect(wrapper.findAll('.contests__row').length).toBe(2)
  })

  it('shows guidance instead of tabs when no platform is logged in (no leetcode fallback)', async () => {
    handler = authHandler([
      { platform: 'codeforces', loggedIn: false, userId: null },
      { platform: 'atcoder', loggedIn: false, userId: null },
    ])
    const wrapper = mount(ContestApp)
    await flushPromises()

    expect(wrapper.find('.tabs').exists()).toBe(false)
    expect(wrapper.find('.contests__row').exists()).toBe(false)
    expect(wrapper.text()).toContain('当前无已登录平台')
    expect(wrapper.text()).toContain('题库页账号区登录')
  })

  it('does not gate when the auth status endpoint fails', async () => {
    // /api/auth/status 未 mock → 404 not-mocked → authKnown=false,展示全部平台
    handler = (url, init) => {
      if (url === '/api/auth/status') {
        return { status: 500, body: { error: 'server-error' } }
      }
      if (url === '/api/contests') {
        return { status: 200, body: { platforms: TWO_PLATFORMS } }
      }
      return happyHandler()(url, init)
    }
    const wrapper = mount(ContestApp)
    await flushPromises()

    expect(wrapper.findAll('.tabs__item').length).toBe(2)
  })
})
