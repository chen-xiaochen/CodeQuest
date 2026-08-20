/**
 * WP20:problems 页真实列表渲染(端点 mock)——行渲染/难度徽标/分页追加/
 * 点击行发 nav:openProblem 桥消息/错误态。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProblemsApp from '../../src/pages/problems/App.vue'
import {
  TOPIC_AUTH_LOGIN,
  TOPIC_AUTH_LOGIN_RESULT,
  TOPIC_AUTH_LOGOUT,
  TOPIC_AUTH_LOGOUT_RESULT,
  resetGlobalBridgeHook,
} from '../../src/bridge/cq-bridge'

type FetchHandler = (url: string) => { status: number; body: unknown } | undefined

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
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      const result = handler(url)
      return result ? jsonResponse(result.status, result.body) : jsonResponse(404, { error: 'not-mocked' })
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  delete window.cqSend
  window.__cqBridge = undefined
  resetGlobalBridgeHook()
})

function mockListPage(items: Array<Record<string, unknown>>, hasMore = true): void {
  handler = (url) => {
    if (url.startsWith('/api/platforms')) {
      return {
        status: 200,
        body: { platforms: [{ id: 'leetcode', displayName: 'LeetCode' }, { id: 'leetcodecn', displayName: '力扣' }] },
      }
    }
    if (url.startsWith('/api/problems')) {
      const offset = Number(new URLSearchParams(url.split('?')[1]).get('offset') ?? '0')
      return { status: 200, body: { platform: 'leetcode', offset, limit: 50, items, hasMore } }
    }
    return undefined
  }
}

const ROWS = [
  {
    remoteId: 'two-sum',
    title: 'Two Sum',
    difficulty: 'Easy',
    url: 'https://leetcode.com/problems/two-sum/',
    tags: ['Array', 'Hash Table'],
  },
  {
    remoteId: 'trapping-rain-water',
    title: 'Trapping Rain Water',
    difficulty: 'Hard',
    url: 'https://leetcode.com/problems/trapping-rain-water/',
    tags: [],
  },
]

describe('problems page', () => {
  it('renders list rows with title, difficulty badge tone and tags', async () => {
    mockListPage(ROWS)
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const rows = wrapper.findAll('.problems__row')
    expect(rows.length).toBe(2)
    expect(rows[0].text()).toContain('Two Sum')
    expect(rows[0].text()).toContain('two-sum')
    expect(rows[0].find('.diff-badge--easy').exists()).toBe(true)
    expect(rows[0].text()).toContain('Array · Hash Table')
    expect(rows[1].find('.diff-badge--hard').exists()).toBe(true)
  })

  it('appends next page when clicking load more', async () => {
    const pages = [ROWS, ROWS.map((row) => ({ ...row, remoteId: `${row.remoteId}-p2` }))]
    handler = (url) => {
      if (url.startsWith('/api/platforms')) {
        return { status: 200, body: { platforms: [{ id: 'leetcode', displayName: 'LeetCode' }] } }
      }
      if (url.startsWith('/api/problems')) {
        const offset = Number(new URLSearchParams(url.split('?')[1]).get('offset') ?? '0')
        const page = offset === 0 ? pages[0] : pages[1]
        return { status: 200, body: { platform: 'leetcode', offset, limit: 50, items: page, hasMore: offset === 0 } }
      }
      return undefined
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    expect(wrapper.findAll('.problems__row').length).toBe(2)
    await wrapper.find('.pager__more').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.problems__row').length).toBe(4)
    expect(wrapper.text()).toContain('two-sum-p2')
  })

  it('sends nav:openProblem bridge message with platformId and remoteId on row click', async () => {
    mockListPage(ROWS)
    const sent: string[] = []
    window.cqSend = (message: string) => {
      sent.push(message)
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.findAll('.problems__row')[0].trigger('click')

    expect(sent.length).toBe(1)
    const envelope = JSON.parse(sent[0]) as { topic: string; payload: string }
    expect(envelope.topic).toBe('nav:openProblem')
    expect(JSON.parse(envelope.payload)).toEqual({ platformId: 'leetcode', remoteId: 'two-sum' })
  })

  it('shows error state with backend error code', async () => {
    handler = (url) => {
      if (url.startsWith('/api/platforms')) {
        return { status: 200, body: { platforms: [{ id: 'leetcode', displayName: 'LeetCode' }] } }
      }
      if (url.startsWith('/api/problems')) {
        return { status: 401, body: { error: 'auth-expired', message: 'login required' } }
      }
      return undefined
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    expect(wrapper.find('.state--error').text()).toContain('auth-expired')
  })

  it('shows Login entry on auth-expired and reloads after auth:login succeeds (1.0.x)', async () => {
    let listHits = 0
    handler = (url) => {
      if (url.startsWith('/api/platforms')) {
        return { status: 200, body: { platforms: [{ id: 'leetcode', displayName: 'LeetCode' }] } }
      }
      if (url.startsWith('/api/problems')) {
        listHits += 1
        if (listHits === 1) {
          return { status: 401, body: { error: 'auth-expired', message: 'login required' } }
        }
        return { status: 200, body: { platform: 'leetcode', offset: 0, limit: 50, items: ROWS, hasMore: false } }
      }
      return undefined
    }
    const sends: Array<{ topic: string; payload: unknown }> = []
    window.cqSend = (message: string) => {
      const envelope = JSON.parse(message) as { topic: string; payload: string }
      sends.push({ topic: envelope.topic, payload: JSON.parse(envelope.payload) })
    }

    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const state = wrapper.find('.state--error')
    const loginBtn = state.find('.state__login')
    expect(loginBtn.exists()).toBe(true)

    await loginBtn.trigger('click')
    expect(sends).toEqual([{ topic: TOPIC_AUTH_LOGIN, payload: { platform: 'leetcode' } }])
    expect(listHits).toBe(1)

    window.__cqBridge!.emit({
      topic: TOPIC_AUTH_LOGIN_RESULT,
      payload: JSON.stringify({ ok: true, platform: 'leetcode' }),
    })
    await flushPromises()

    expect(listHits).toBe(2)
    expect(wrapper.findAll('.problems__row').length).toBe(2)
    expect(wrapper.find('.state--error').exists()).toBe(false)
  })

  it('forwards keyword and difficulty filters in the query string', async () => {
    const calls: string[] = []
    handler = (url) => {
      if (url.startsWith('/api/platforms')) {
        return { status: 200, body: { platforms: [{ id: 'leetcode', displayName: 'LeetCode' }] } }
      }
      if (url.startsWith('/api/problems')) {
        calls.push(url)
        return { status: 200, body: { platform: 'leetcode', offset: 0, limit: 50, items: [], hasMore: false } }
      }
      return undefined
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.find('input[type="search"]').setValue('two')
    await wrapper.findAll('select')[1].setValue('easy')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(calls.length).toBeGreaterThanOrEqual(2)
    const last = calls[calls.length - 1]
    expect(last).toContain('keyword=two')
    expect(last).toContain('difficulty=easy')
  })

  // -----------------------------------------------------------------
  // WP37:状态列与本地筛选
  // -----------------------------------------------------------------

  const STATUS_ROWS = [
    { remoteId: 'ac-one', title: 'AC One', difficulty: 'Easy', url: 'u1', tags: [], status: 'AC' },
    { remoteId: 'tried-one', title: 'Tried One', difficulty: 'Medium', url: 'u2', tags: [], status: 'TRIED' },
    { remoteId: 'fresh-one', title: 'Fresh One', difficulty: 'Easy', url: 'u3', tags: [] },
    { remoteId: 'locked-one', title: 'Locked One', difficulty: 'Hard', url: 'u4', tags: [], status: 'AC', paidOnly: true },
  ]

  it('renders the status icon column per row (lock > ac > tried > unsolved)', async () => {
    mockListPage(STATUS_ROWS, false)
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const rows = wrapper.findAll('.problems__row')
    expect(rows.length).toBe(4)
    expect(rows[0].find('.status-icon--ac').exists()).toBe(true)
    expect(rows[0].find('.status-icon').attributes('title')).toBe('Solved')
    expect(rows[1].find('.status-icon--tried').exists()).toBe(true)
    expect(rows[1].find('.status-icon').attributes('title')).toBe('Tried')
    // 未做:占位图标存在但无提示文案(原版 Unsolved 渲染空)
    expect(rows[2].find('.status-icon--unsolved').exists()).toBe(true)
    expect(rows[2].find('.status-icon').attributes('title')).toBe('')
    // 会员题:锁优先于刷题状态(即使 status=AC)
    expect(rows[3].find('.status-icon--locked').exists()).toBe(true)
    expect(rows[3].find('.status-icon--locked').attributes('title')).toBe('Premium problem (locked)')
    expect(rows[3].find('.status-icon--ac').exists()).toBe(false)
  })

  it('filters loaded rows by status locally without refetching', async () => {
    let listCalls = 0
    handler = (url) => {
      if (url.startsWith('/api/platforms')) {
        return { status: 200, body: { platforms: [{ id: 'leetcode', displayName: 'LeetCode' }] } }
      }
      if (url.startsWith('/api/problems')) {
        listCalls += 1
        return { status: 200, body: { platform: 'leetcode', offset: 0, limit: 50, items: STATUS_ROWS, hasMore: false } }
      }
      return undefined
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    expect(wrapper.findAll('.problems__row').length).toBe(4)
    const callsAfterLoad = listCalls

    const statusSelect = wrapper.findAll('select')[2]
    await statusSelect.setValue('AC')
    expect(wrapper.findAll('.problems__row').length).toBe(2)
    expect(wrapper.text()).toContain('ac-one')
    expect(wrapper.text()).toContain('locked-one')
    expect(wrapper.text()).not.toContain('tried-one')

    await statusSelect.setValue('TRIED')
    expect(wrapper.findAll('.problems__row').length).toBe(1)

    await statusSelect.setValue('UNSOLVED')
    expect(wrapper.findAll('.problems__row').length).toBe(1)
    expect(wrapper.text()).toContain('fresh-one')

    await statusSelect.setValue('')
    expect(wrapper.findAll('.problems__row').length).toBe(4)

    // 本地过滤:筛选全程零新请求
    expect(listCalls).toBe(callsAfterLoad)
  })

  it('notes that the status filter only applies to loaded items', async () => {
    mockListPage(STATUS_ROWS, false)
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    expect(wrapper.find('.filter-note').exists()).toBe(false)
    await wrapper.findAll('select')[2].setValue('AC')
    expect(wrapper.find('.filter-note').exists()).toBe(true)
    expect(wrapper.find('.filter-note').text()).toContain('loaded items')

    await wrapper.findAll('select')[2].setValue('')
    expect(wrapper.find('.filter-note').exists()).toBe(false)
  })

  it('shows a dedicated empty hint when no loaded item matches the filter', async () => {
    const onlyAc = [STATUS_ROWS[0]]
    mockListPage(onlyAc, false)
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.findAll('select')[2].setValue('TRIED')

    expect(wrapper.findAll('.problems__row').length).toBe(0)
    expect(wrapper.find('.state').text()).toContain('No loaded items match the status filter')
  })

  it('reset clears the status filter along with the other filters', async () => {
    mockListPage(STATUS_ROWS, false)
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.findAll('select')[2].setValue('TRIED')
    expect(wrapper.findAll('.problems__row').length).toBe(1)

    await wrapper.findAll('button').find((btn) => btn.text() === 'Reset')!.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.problems__row').length).toBe(4)
  })

  // -----------------------------------------------------------------
  // WP38:账号条(每平台徽标;点击登录/登出)
  // -----------------------------------------------------------------

  it('renders account badges with logged-in highlight and muted logged-out', async () => {
    mockListPage(ROWS, false)
    handler = withAuthStatus(handler, [
      { platform: 'leetcode', loggedIn: true, userId: null },
      { platform: 'leetcodecn', loggedIn: false, userId: null },
    ])
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const badges = wrapper.findAll('.accounts__badge')
    expect(badges.length).toBe(2)
    expect(badges[0].classes()).toContain('accounts__badge--on')
    expect(badges[0].attributes('title')).toContain('log out')
    expect(badges[0].text()).toContain('LeetCode')
    expect(badges[1].classes()).not.toContain('accounts__badge--on')
    expect(badges[1].attributes('title')).toContain('log in')
  })

  it('shows the platform userId on a logged-in badge when available', async () => {
    mockListPage(ROWS, false)
    handler = withAuthStatus(handler, [
      { platform: 'leetcode', loggedIn: true, userId: 'alice' },
    ])
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const badge = wrapper.find('.accounts__badge')
    expect(badge.text()).toContain('alice')
  })

  it('sends auth:login when clicking a logged-out badge', async () => {
    mockListPage(ROWS, false)
    handler = withAuthStatus(handler, [
      { platform: 'leetcode', loggedIn: true, userId: null },
      { platform: 'leetcodecn', loggedIn: false, userId: null },
    ])
    const sends: Array<{ topic: string; payload: unknown }> = []
    window.cqSend = (message: string) => {
      const envelope = JSON.parse(message) as { topic: string; payload: string }
      sends.push({ topic: envelope.topic, payload: JSON.parse(envelope.payload) })
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.findAll('.accounts__badge')[1].trigger('click')

    expect(sends).toEqual([{ topic: TOPIC_AUTH_LOGIN, payload: { platform: 'leetcodecn' } }])
  })

  it('sends auth:logout for a logged-in badge and refreshes status after the result', async () => {
    mockListPage(ROWS, false)
    const statusHits = { value: 0 }
    handler = (url) => {
      if (url.startsWith('/api/auth/status')) {
        statusHits.value += 1
        return {
          status: 200,
          body: { items: [{ platform: 'leetcode', loggedIn: true, userId: null }] },
        }
      }
      return mockListPageHandler(url)
    }
    const sends: Array<{ topic: string; payload: unknown }> = []
    window.cqSend = (message: string) => {
      const envelope = JSON.parse(message) as { topic: string; payload: string }
      sends.push({ topic: envelope.topic, payload: JSON.parse(envelope.payload) })
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()
    expect(statusHits.value).toBe(1)

    await wrapper.find('.accounts__badge').trigger('click')
    expect(sends).toEqual([{ topic: TOPIC_AUTH_LOGOUT, payload: { platform: 'leetcode' } }])

    window.__cqBridge!.emit({
      topic: TOPIC_AUTH_LOGOUT_RESULT,
      payload: JSON.stringify({ ok: true, platform: 'leetcode' }),
    })
    await flushPromises()

    expect(statusHits.value).toBe(2)
  })

  it('refreshes auth status after auth:login-result from a badge login', async () => {
    mockListPage(ROWS, false)
    const statusHits = { value: 0 }
    handler = (url) => {
      if (url.startsWith('/api/auth/status')) {
        statusHits.value += 1
        return {
          status: 200,
          body: { items: [{ platform: 'leetcode', loggedIn: false, userId: null }] },
        }
      }
      return mockListPageHandler(url)
    }
    window.cqSend = () => {}
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.find('.accounts__badge').trigger('click')
    window.__cqBridge!.emit({
      topic: TOPIC_AUTH_LOGIN_RESULT,
      payload: JSON.stringify({ ok: true, platform: 'leetcode' }),
    })
    await flushPromises()

    expect(statusHits.value).toBe(2)
  })

  // -----------------------------------------------------------------
  // WP42:公司下拉与公司题单(能力探测式渲染)
  // -----------------------------------------------------------------

  const COMPANIES = [
    { slug: 'amazon', name: 'Amazon', questionCount: 40 },
    { slug: 'google', name: 'Google', questionCount: 30 },
  ]

  /** 平台为 leetcode 时回公司清单,其余平台 404(能力探测)。 */
  function companyAwareHandler(listCalls?: string[]): FetchHandler {
    return (url) => {
      if (url.startsWith('/api/platforms')) {
        return {
          status: 200,
          body: {
            platforms: [
              { id: 'leetcode', displayName: 'LeetCode' },
              { id: 'codeforces', displayName: 'Codeforces' },
            ],
          },
        }
      }
      if (url.startsWith('/api/companies')) {
        const platform = new URLSearchParams(url.split('?')[1] ?? '').get('platform')
        if (platform === 'leetcode') {
          return { status: 200, body: { platform, items: COMPANIES } }
        }
        return { status: 404, body: { error: 'unknown-platform' } }
      }
      if (url.startsWith('/api/problems')) {
        listCalls?.push(url)
        return { status: 200, body: { platform: 'leetcode', offset: 0, limit: 50, items: ROWS, hasMore: false } }
      }
      return undefined
    }
  }

  it('renders the company dropdown only for company-capable platforms', async () => {
    handler = companyAwareHandler()
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    // 平台(0)/难度(1)/状态(2)/公司(3):leetcode 有公司能力 → 4 个下拉
    const selects = wrapper.findAll('select')
    expect(selects.length).toBe(4)
    expect(selects[3].text()).toContain('All companies')
    expect(selects[3].text()).toContain('Amazon (40)')
    expect(selects[3].text()).toContain('Google (30)')
    // 未选公司:期间下拉不出现
    expect(selects[3].text()).not.toContain('Last 30 days')

    // 切到无公司能力的平台:公司下拉隐藏,剩余 3 个下拉
    await selects[0].setValue('codeforces')
    await flushPromises()
    expect(wrapper.findAll('select').length).toBe(3)
  })

  it('refetches with company and default period when a company is selected', async () => {
    const listCalls: string[] = []
    handler = companyAwareHandler(listCalls)
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.findAll('select')[3].setValue('amazon')
    await flushPromises()

    const last = listCalls[listCalls.length - 1]
    expect(last).toContain('company=amazon')
    expect(last).toContain('period=thirty-days')
  })

  it('exposes the period select in company mode and updates the period param', async () => {
    const listCalls: string[] = []
    handler = companyAwareHandler(listCalls)
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.findAll('select')[3].setValue('amazon')
    await flushPromises()
    // 公司模式:平台(0)/难度(1)/状态(2)/公司(3)/期间(4)
    const periodSelect = wrapper.findAll('select')[4]
    expect(periodSelect.text()).toContain('Last 30 days')
    expect(periodSelect.text()).toContain('All time')

    await periodSelect.setValue('all')
    await flushPromises()

    const last = listCalls[listCalls.length - 1]
    expect(last).toContain('company=amazon')
    expect(last).toContain('period=all')
  })

  it('clears the company selection when switching to a non-company platform', async () => {
    const listCalls: string[] = []
    handler = companyAwareHandler(listCalls)
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.findAll('select')[3].setValue('amazon')
    await flushPromises()
    expect(listCalls[listCalls.length - 1]).toContain('company=amazon')

    await wrapper.findAll('select')[0].setValue('codeforces')
    await flushPromises()

    const last = listCalls[listCalls.length - 1]
    expect(last).not.toContain('company=')
    expect(last).not.toContain('period=')
  })

  it('notes that keyword is ignored in company list mode', async () => {
    handler = companyAwareHandler()
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    expect(wrapper.findAll('.filter-note').length).toBe(0)
    await wrapper.findAll('select')[3].setValue('amazon')
    await wrapper.find('input[type="search"]').setValue('two')
    await flushPromises()

    const notes = wrapper.findAll('.filter-note').map((note) => note.text())
    expect(notes.some((text) => text.includes('Keyword is ignored'))).toBe(true)
  })

  it('clears the company filter on reset', async () => {
    const listCalls: string[] = []
    handler = companyAwareHandler(listCalls)
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.findAll('select')[3].setValue('amazon')
    await flushPromises()
    expect(listCalls[listCalls.length - 1]).toContain('company=amazon')

    await wrapper.findAll('button').find((btn) => btn.text() === 'Reset')!.trigger('click')
    await flushPromises()

    expect(listCalls[listCalls.length - 1]).not.toContain('company=')
  })
})

/** 在既有 handler 外层叠加 /api/auth/status mock(WP38)。 */
function withAuthStatus(
  inner: FetchHandler,
  items: Array<{ platform: string; loggedIn: boolean; userId: string | null }>,
): FetchHandler {
  return (url) => {
    if (url.startsWith('/api/auth/status')) {
      return { status: 200, body: { items } }
    }
    return inner(url)
  }
}

/** mockListPage 的 handler 主体(供用例复用/叠加)。 */
function mockListPageHandler(url: string): { status: number; body: unknown } | undefined {
  if (url.startsWith('/api/platforms')) {
    return {
      status: 200,
      body: { platforms: [{ id: 'leetcode', displayName: 'LeetCode' }, { id: 'leetcodecn', displayName: '力扣' }] },
    }
  }
  if (url.startsWith('/api/problems')) {
    return { status: 200, body: { platform: 'leetcode', offset: 0, limit: 50, items: ROWS, hasMore: false } }
  }
  return undefined
}
