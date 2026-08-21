/**
 * WP20:problems 页真实列表渲染(端点 mock)——行渲染/难度徽标/分页追加/
 * 点击行发 nav:openProblem 桥消息/错误态。
 * WP49:账号区显式登录/退出按钮、平台下拉门控与力扣中国回落、静默恢复时序、
 * 「平台题库｜本地搜索」子视图切换;文案中文化。
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
    expect(rows[0].find('.status-icon').attributes('title')).toBe('已解决')
    expect(rows[1].find('.status-icon--tried').exists()).toBe(true)
    expect(rows[1].find('.status-icon').attributes('title')).toBe('已尝试')
    // 未做:灰色细空心圆 + 「未解决」提示(1.2.1 起有可见标识)
    expect(rows[2].find('.status-icon--unsolved').exists()).toBe(true)
    expect(rows[2].find('.status-icon').attributes('title')).toBe('未解决')
    // 会员题:锁优先于刷题状态(即使 status=AC)
    expect(rows[3].find('.status-icon--locked').exists()).toBe(true)
    expect(rows[3].find('.status-icon--locked').attributes('title')).toBe('会员题(已锁定)')
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
    expect(wrapper.find('.filter-note').text()).toContain('已加载条目')

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
    expect(wrapper.find('.state').text()).toContain('没有已加载条目匹配状态筛选')
  })

  it('reset clears the status filter along with the other filters', async () => {
    mockListPage(STATUS_ROWS, false)
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.findAll('select')[2].setValue('TRIED')
    expect(wrapper.findAll('.problems__row').length).toBe(1)

    await wrapper.findAll('button').find((btn) => btn.text() === '重置')!.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.problems__row').length).toBe(4)
  })

  // -----------------------------------------------------------------
  // WP38/WP49:账号区(全部平台常驻;显式「登录/退出」按钮)
  // -----------------------------------------------------------------

  it('renders account rows for every platform with explicit login/logout buttons', async () => {
    mockListPage(ROWS, false)
    handler = withAuthStatus(handler, [
      { platform: 'leetcode', loggedIn: true, userId: null },
      { platform: 'leetcodecn', loggedIn: false, userId: null },
    ])
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const rows = wrapper.findAll('.accounts__item')
    expect(rows.length).toBe(2)
    expect(rows[0].classes()).toContain('accounts__item--on')
    expect(rows[0].text()).toContain('LeetCode')
    expect(rows[0].find('.accounts__action').text()).toBe('退出')
    expect(rows[1].classes()).not.toContain('accounts__item--on')
    expect(rows[1].text()).toContain('力扣')
    expect(rows[1].find('.accounts__action').text()).toBe('登录')
  })

  it('shows the platform userId on a logged-in row when available', async () => {
    mockListPage(ROWS, false)
    handler = withAuthStatus(handler, [
      { platform: 'leetcode', loggedIn: true, userId: 'alice' },
    ])
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const row = wrapper.find('.accounts__item')
    expect(row.text()).toContain('alice')
  })

  it('sends auth:login when clicking the login button of a logged-out platform', async () => {
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

    await wrapper.findAll('.accounts__action')[1].trigger('click')

    expect(sends).toEqual([{ topic: TOPIC_AUTH_LOGIN, payload: { platform: 'leetcodecn' } }])
  })

  it('sends auth:logout when clicking the logout button and refreshes status after the result', async () => {
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

    await wrapper.find('.accounts__action').trigger('click')
    expect(sends).toEqual([{ topic: TOPIC_AUTH_LOGOUT, payload: { platform: 'leetcode' } }])

    window.__cqBridge!.emit({
      topic: TOPIC_AUTH_LOGOUT_RESULT,
      payload: JSON.stringify({ ok: true, platform: 'leetcode' }),
    })
    await flushPromises()

    expect(statusHits.value).toBe(2)
  })

  it('refreshes auth status after auth:login-result from an account-area login', async () => {
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

    await wrapper.find('.accounts__action').trigger('click')
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
    expect(selects[3].text()).toContain('全部公司')
    expect(selects[3].text()).toContain('Amazon (40)')
    expect(selects[3].text()).toContain('Google (30)')
    // 未选公司:期间下拉不出现
    expect(selects[3].text()).not.toContain('近 30 天')

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
    expect(periodSelect.text()).toContain('近 30 天')
    expect(periodSelect.text()).toContain('全部时间')

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
    expect(notes.some((text) => text.includes('公司题单模式下关键字被忽略'))).toBe(true)
  })

  it('clears the company filter on reset', async () => {
    const listCalls: string[] = []
    handler = companyAwareHandler(listCalls)
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.findAll('select')[3].setValue('amazon')
    await flushPromises()
    expect(listCalls[listCalls.length - 1]).toContain('company=amazon')

    await wrapper.findAll('button').find((btn) => btn.text() === '重置')!.trigger('click')
    await flushPromises()

    expect(listCalls[listCalls.length - 1]).not.toContain('company=')
  })

  // -----------------------------------------------------------------
  // WP49:平台下拉门控 + 力扣中国回落 + 静默恢复 + 子视图切换
  // -----------------------------------------------------------------

  const GATE_PLATFORMS = [
    { id: 'leetcode', displayName: 'LeetCode' },
    { id: 'leetcodecn', displayName: '力扣' },
    { id: 'codeforces', displayName: 'Codeforces' },
  ]

  /** 门控用 handler:三平台 + 列表按 platform 参数回显;listCalls 记录列表请求。 */
  function gateHandler(listCalls?: string[]): FetchHandler {
    return (url) => {
      if (url.startsWith('/api/platforms')) {
        return { status: 200, body: { platforms: GATE_PLATFORMS } }
      }
      if (url.startsWith('/api/problems')) {
        listCalls?.push(url)
        const platform = new URLSearchParams(url.split('?')[1] ?? '').get('platform') ?? 'leetcode'
        return { status: 200, body: { platform, offset: 0, limit: 50, items: ROWS, hasMore: false } }
      }
      return undefined
    }
  }

  it('filters the platform dropdown to logged-in platforms only', async () => {
    const listCalls: string[] = []
    handler = withAuthStatus(gateHandler(listCalls), [
      { platform: 'leetcode', loggedIn: true, userId: 'u1' },
      { platform: 'leetcodecn', loggedIn: false, userId: null },
      { platform: 'codeforces', loggedIn: true, userId: null },
    ])
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const select = wrapper.findAll('select')[0]
    expect(select.text()).toContain('LeetCode')
    expect(select.text()).toContain('Codeforces')
    expect(select.text()).not.toContain('力扣')
    expect(wrapper.find('.fallback-hint').exists()).toBe(false)
    // 默认选中第一个已登录平台
    expect(listCalls[0]).toContain('platform=leetcode')
  })

  it('falls back to 力扣中国 with a hint when no platform is logged in', async () => {
    const listCalls: string[] = []
    handler = withAuthStatus(gateHandler(listCalls), [
      { platform: 'leetcode', loggedIn: false, userId: null },
      { platform: 'leetcodecn', loggedIn: false, userId: null },
      { platform: 'codeforces', loggedIn: false, userId: null },
    ])
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const select = wrapper.findAll('select')[0]
    expect(select.text()).toContain('力扣')
    expect(select.text()).not.toContain('LeetCode')
    expect(select.text()).not.toContain('Codeforces')
    expect(wrapper.find('.fallback-hint').text()).toContain('当前无已登录平台,默认展示力扣中国')
    expect(listCalls[0]).toContain('platform=leetcodecn')
  })

  it('keeps all platforms when the auth status endpoint is unavailable', async () => {
    handler = gateHandler()
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const select = wrapper.findAll('select')[0]
    expect(select.text()).toContain('LeetCode')
    expect(select.text()).toContain('力扣')
    expect(select.text()).toContain('Codeforces')
    expect(wrapper.find('.fallback-hint').exists()).toBe(false)
  })

  it('re-gates platforms after the silent auth refresh restores a login', async () => {
    const listCalls: string[] = []
    const refreshed = { value: false }
    handler = (url) => {
      if (url.startsWith('/api/auth/status')) {
        return {
          status: 200,
          body: {
            items: GATE_PLATFORMS.map((entry) => ({
              platform: entry.id,
              loggedIn: false,
              userId: null,
            })),
          },
        }
      }
      if (url.startsWith('/api/auth/refresh')) {
        refreshed.value = true
        return {
          status: 200,
          body: { items: [{ platform: 'leetcode', loggedIn: true, userId: 'restored' }] },
        }
      }
      return gateHandler(listCalls)(url)
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    // 静默恢复后:门控生效,下拉仅剩 LeetCode,默认选中并刷新列表
    expect(refreshed.value).toBe(true)
    const select = wrapper.findAll('select')[0]
    expect(select.text()).toContain('LeetCode')
    expect(select.text()).not.toContain('力扣')
    expect(wrapper.find('.fallback-hint').exists()).toBe(false)
    expect(listCalls[listCalls.length - 1]).toContain('platform=leetcode')
    expect(wrapper.find('.accounts__item').text()).toContain('restored')
  })

  it('switches between 平台题库 and 本地搜索 subviews keeping both alive', async () => {
    const searchItems = [
      {
        platform: 'leetcode',
        remoteId: 'two-sum',
        title: 'Two Sum',
        difficulty: 'easy',
        url: 'u',
        tags: [],
        matchedSources: ['title'],
      },
    ]
    handler = (url) => {
      if (url.startsWith('/api/search')) {
        return { status: 200, body: { offset: 0, limit: 50, total: 1, items: searchItems, hasMore: false, tags: [] } }
      }
      if (url.startsWith('/api/tags')) {
        return { status: 200, body: { tags: [] } }
      }
      return mockListPageHandler(url)
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    // 初始:平台题库子视图;本地搜索惰性未挂载
    // (jsdom getComputedStyle 不反映 inline display,isVisible 不可用,断 inline style)
    expect(wrapper.find('.platform-view').attributes('style')).toBeUndefined()
    expect(wrapper.find('.search-panel').exists()).toBe(false)

    const tabs = wrapper.findAll('.subviews__tab')
    expect(tabs.map((tab) => tab.text())).toEqual(['平台题库', '本地搜索'])
    await tabs[1].trigger('click')
    await flushPromises()

    // 互斥显示:平台视图隐藏,搜索面板出现并渲染结果
    expect(wrapper.find('.platform-view').attributes('style')).toContain('display: none')
    expect(wrapper.find('.search-panel').attributes('style')).toBeUndefined()
    expect(wrapper.findAll('.results__row').length).toBe(1)

    // 切回:平台视图保留原列表(状态本地保持)
    await wrapper.findAll('.subviews__tab')[0].trigger('click')
    expect(wrapper.find('.platform-view').attributes('style')).not.toContain('display: none')
    expect(wrapper.find('.search-panel').attributes('style')).toContain('display: none')
    expect(wrapper.findAll('.problems__row').length).toBe(2)
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
