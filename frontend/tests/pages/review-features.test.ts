/**
 * WP64:复习子视图与加入复习——题库页「复习」三分组渲染+tab 徽标计数、
 * 标记完成(POST complete + 刷新 + 徽标同步)、间隔配置 PUT 与 400 提示、
 * 统计页「同步历史」三态(成功/失败/防重入)、题面页「加入复习」两态。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProblemsApp from '../../src/pages/problems/App.vue'
import StatsApp from '../../src/pages/stats/App.vue'
import ProblemViewApp from '../../src/pages/problem-view/App.vue'
import { clearUserContestCache } from '../../src/lib/api'
import { resetGlobalBridgeHook } from '../../src/bridge/cq-bridge'

type MockResponse = { status: number; body: unknown; text?: string }
/** handler 可返回 Promise(挂起可控,用于防重入用例)。 */
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

/** 与页面同公式的本地日期格式化(测试侧独立复算,不导入被测实现)。 */
function epochDay(epochSeconds: number): string {
  const date = new Date(epochSeconds * 1000)
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

const DUE_OVERDUE = 1_790_000_000
const DUE_TODAY = 1_795_000_000
const DUE_UPCOMING = 1_800_000_000

/** today 三组夹具:过期 1(带完整联查)/ 今日 2 / 未来 1(联查缺失行)。 */
const TODAY_BODY = {
  overdue: [
    {
      problemId: 'leetcode:two-sum',
      platformId: 'leetcode',
      remoteId: 'two-sum',
      title: 'Two Sum',
      difficulty: 'easy',
      nextDate: DUE_OVERDUE,
    },
  ],
  today: [
    {
      problemId: 'leetcode:add-two',
      platformId: 'leetcode',
      remoteId: 'add-two',
      title: 'Add Two Numbers',
      difficulty: 'medium',
      nextDate: DUE_TODAY,
    },
    {
      problemId: 'leetcode:lrucache',
      platformId: 'leetcode',
      remoteId: 'lrucache',
      title: 'LRU Cache',
      difficulty: 'hard',
      nextDate: DUE_TODAY,
    },
  ],
  upcoming: [
    {
      problemId: 'leetcode:missing-row',
      title: '未知主档行',
      nextDate: DUE_UPCOMING,
    },
  ],
  totalTracked: 4,
}

/** 题库页通用 handler(平台/登录/列表/评分 + 复习 today/config 可覆盖)。 */
function problemsHandler(options: {
  today?: unknown
  config?: MockResponse
} = {}): FetchHandler {
  const platforms = [{ id: 'leetcode', displayName: 'LeetCode' }]
  return (url) => {
    if (url.startsWith('/api/platforms')) {
      return { status: 200, body: { platforms } }
    }
    if (url.startsWith('/api/auth/status')) {
      return { status: 200, body: { items: [{ platform: 'leetcode', loggedIn: true, userId: 'u1' }] } }
    }
    if (url.startsWith('/api/companies') || url.startsWith('/api/favorites')) {
      return { status: 404, body: { error: 'unknown-platform' } }
    }
    if (url.startsWith('/api/problems')) {
      return { status: 200, body: { platform: 'leetcode', offset: 0, limit: 50, items: [], hasMore: false } }
    }
    if (url.startsWith('/api/rating/user-contest')) {
      return { status: 200, body: { rating: null, attendedContestsCount: null } }
    }
    if (url.startsWith('/api/review/today')) {
      return { status: 200, body: options.today ?? TODAY_BODY }
    }
    if (url.startsWith('/api/review/config')) {
      return options.config ?? { status: 200, body: { intervals: [1, 4, 7, 14, 28, 60] } }
    }
    return undefined
  }
}

/** 打开「复习」子视图(点第三个 tab)并等面板数据落定。 */
async function openReviewTab(wrapper: ReturnType<typeof mount>): Promise<void> {
  await wrapper.findAll('.subviews__tab')[2].trigger('click')
  await flushPromises()
}

describe('problems page review subview (WP64)', () => {
  it('shows the today-count badge on the tab, then renders three groups with overdue flag and due dates', async () => {
    handler = problemsHandler()
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    // 徽标 = 今日到期数(today 组 2 条),默认 tab 不动也可见
    const reviewTab = wrapper.findAll('.subviews__tab')[2]
    expect(reviewTab.text()).toContain('复习')
    expect(reviewTab.find('.subviews__badge').text()).toBe('2')

    await openReviewTab(wrapper)

    // 三分组:已过期 → 今日 → 未来;首组红色系
    const groups = wrapper.findAll('.review-group')
    expect(groups.length).toBe(3)
    expect(groups[0].classes()).toContain('review-group--overdue')
    expect(groups[0].find('.review-group__title').text()).toContain('已过期')
    expect(groups[1].find('.review-group__title').text()).toContain('今日')
    expect(groups[2].find('.review-group__title').text()).toContain('未来')

    // 过期行:标题红色 + 「已过期」标记 + 联查字段(难度/平台/到期日)
    const overdueRow = groups[0].findAll('.review-row')[0]
    expect(overdueRow.classes()).toContain('review-row--overdue')
    expect(overdueRow.text()).toContain('Two Sum')
    expect(overdueRow.find('.review-row__flag').text()).toBe('已过期')
    expect(overdueRow.find('.diff-badge--easy').exists()).toBe(true)
    expect(overdueRow.text()).toContain('leetcode')
    expect(overdueRow.text()).toContain(epochDay(DUE_OVERDUE))

    // 联查缺失行(无 platformId/remoteId)不可开题面;未来组无「标记完成」按钮
    const upcomingRow = groups[2].findAll('.review-row')[0]
    expect(upcomingRow.find('.review-row__main').attributes('disabled')).toBeDefined()
    const doneButtons = wrapper.findAll('.review-row__done')
    expect(doneButtons.length).toBe(3) // 过期 1 + 今日 2,未来组无
  })

  it('completes a today-row review via POST complete, then refetches and syncs the badge', async () => {
    let todayBody = {
      ...TODAY_BODY,
      today: TODAY_BODY.today.map((row) => ({ ...row })),
    }
    handler = (url, init) => {
      if (url.startsWith('/api/review/problems/') && url.endsWith('/complete')) {
        expect(init?.method).toBe('POST')
        todayBody = { ...todayBody, today: todayBody.today.filter((row) => row.problemId !== 'leetcode:add-two') }
        return { status: 200, body: { problemId: 'leetcode:add-two', planDates: [], historyDates: [DUE_TODAY] } }
      }
      return problemsHandler({ today: todayBody })(url, init)
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()
    await openReviewTab(wrapper)
    const todayGetsBefore = requests.filter((r) => r.url.startsWith('/api/review/today')).length

    // done 按钮序:过期(two-sum)→ 今日(add-two/lrucache);点今日第一行
    await wrapper.findAll('.review-row__done')[1].trigger('click')
    await flushPromises()

    const complete = requests.find((r) => r.url.includes('/complete'))
    expect(complete?.url).toBe('/api/review/problems/leetcode%3Aadd-two/complete')
    expect(complete?.method).toBe('POST')
    expect(requests.filter((r) => r.url.startsWith('/api/review/today')).length).toBe(todayGetsBefore + 1)
    // 徽标随 count-change 同步:today 剩 1 条
    expect(wrapper.findAll('.subviews__tab')[2].find('.subviews__badge').text()).toBe('1')
    expect(wrapper.findAll('.review-group')[1].findAll('.review-row').length).toBe(1)
  })

  it('saves intervals via PUT and surfaces the backend 400 message on invalid input', async () => {
    const config = { status: 200, body: { intervals: [1, 4, 7, 14, 28, 60] } }
    handler = (url, init) => {
      if (url === '/api/review/config' && init?.method === 'PUT') {
        const parsed = JSON.parse(String(init.body)) as { intervals: number[] }
        const valid = parsed.intervals.length > 0 && parsed.intervals.every((n, i) => n > 0 && (i === 0 || n > parsed.intervals[i - 1]))
        return valid
          ? { status: 200, body: { intervals: parsed.intervals } }
          : {
              status: 400,
              body: {
                error: 'bad-request',
                message: 'intervals must be positive strictly-ascending integers, e.g. 1,4,7,14,28,60',
              },
            }
      }
      return problemsHandler({ config })(url, init)
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()
    await openReviewTab(wrapper)

    // 初始回显 GET 到的间隔
    const input = wrapper.find('#review-intervals')
    expect((input.element as HTMLInputElement).value).toBe('1,4,7,14,28,60')

    // 非法(非升序)→ 400 消息原样提示
    await input.setValue('9,3')
    await wrapper.find('.review-config__row button').trigger('click')
    await flushPromises()
    const put = requests.find((r) => r.url === '/api/review/config' && r.method === 'PUT')
    expect(put?.body).toBe('{"intervals":[9,3]}')
    const errorMsg = wrapper.find('.review-config__msg--error')
    expect(errorMsg.text()).toContain('intervals must be positive strictly-ascending integers')

    // 合法 → 保存成功提示 + 回显归一
    await input.setValue('1, 3, 9')
    await wrapper.find('.review-config__row button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.review-config__msg--ok').text()).toContain('1, 3, 9')
    expect((input.element as HTMLInputElement).value).toBe('1,3,9')
  })
})

// ---------------------------------------------------------------------------
// 统计页「同步历史」三态
// ---------------------------------------------------------------------------

function statsHandler(options: {
  sync?: MockResponse
  statsOverrides?: Record<string, unknown>
} = {}): FetchHandler {
  return (url) => {
    if (url.startsWith('/api/stats')) {
      return {
        status: 200,
        body: {
          days: 14,
          summary: {
            totalSubmissions: 42,
            acceptedSubmissions: 21,
            acceptanceRate: 0.5,
            currentStreakDays: 3,
            longestStreakDays: 9,
          },
          heatmap: [],
          trend: [],
          remoteSyncedCount: 7,
          ...options.statsOverrides,
        },
      }
    }
    if (url.startsWith('/api/platforms')) {
      return {
        status: 200,
        body: {
          platforms: [
            { id: 'leetcode', displayName: 'LeetCode' },
            { id: 'luogu', displayName: '洛谷' },
          ],
        },
      }
    }
    if (url.startsWith('/api/submissions/sync')) {
      return options.sync ?? { status: 200, body: { synced: 12, total: 120, stoppedBy: 'EXISTING' } }
    }
    return undefined
  }
}

describe('stats page submission sync (WP64)', () => {
  it('renders the synced count and syncs the selected platform, then toasts and reloads stats', async () => {
    handler = statsHandler()
    const wrapper = mount(StatsApp)
    await flushPromises()

    expect(wrapper.find('.remote-sync__count').text()).toContain('站内历史:已同步 7 条')
    const statsGetsBefore = requests.filter((r) => r.url.startsWith('/api/stats')).length

    await wrapper.find('.remote-sync__btn').trigger('click')
    await flushPromises()

    const sync = requests.find((r) => r.url.startsWith('/api/submissions/sync'))
    expect(sync?.method).toBe('POST')
    expect(JSON.parse(String(sync?.body))).toEqual({ platformId: 'leetcode' })
    const toast = wrapper.find('.remote-sync__toast')
    expect(toast.classes()).toContain('remote-sync__toast--ok')
    expect(toast.text()).toContain('新入库 12 条')
    expect(toast.text()).toContain('站内共 120 条')
    // 完成后刷新统计
    expect(requests.filter((r) => r.url.startsWith('/api/stats')).length).toBe(statsGetsBefore + 1)
    expect(wrapper.find('.remote-sync__btn').attributes('disabled')).toBeUndefined()
  })

  it('shows an error toast with the backend message on sync failure, without reloading', async () => {
    handler = statsHandler({
      sync: { status: 502, body: { error: 'sync-failed', message: 'not logged in' } },
    })
    const wrapper = mount(StatsApp)
    await flushPromises()
    const statsGetsBefore = requests.filter((r) => r.url.startsWith('/api/stats')).length

    await wrapper.find('.remote-sync__btn').trigger('click')
    await flushPromises()

    const toast = wrapper.find('.remote-sync__toast')
    expect(toast.classes()).toContain('remote-sync__toast--error')
    expect(toast.text()).toContain('同步失败')
    expect(toast.text()).toContain('not logged in')
    expect(requests.filter((r) => r.url.startsWith('/api/stats')).length).toBe(statsGetsBefore)
  })

  it('guards against re-entry while a sync is pending', async () => {
    let releaseSync: (() => void) | null = null
    const base = statsHandler()
    handler = async (url, init) => {
      if (url.startsWith('/api/submissions/sync')) {
        await new Promise<void>((resolve) => {
          releaseSync = resolve
        })
      }
      return base(url, init)
    }
    const wrapper = mount(StatsApp)
    await flushPromises()

    await wrapper.find('.remote-sync__btn').trigger('click')
    await flushPromises()

    // 挂起期间:按钮禁用 + 进行中文案,无 toast
    const pending = wrapper.find('.remote-sync__btn')
    expect(pending.attributes('disabled')).toBeDefined()
    expect(pending.text()).toContain('同步中')
    expect(wrapper.find('.remote-sync__toast').exists()).toBe(false)

    releaseSync!()
    await flushPromises()
    expect(wrapper.find('.remote-sync__btn').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('.remote-sync__toast--ok').exists()).toBe(true)
    expect(requests.filter((r) => r.url.startsWith('/api/submissions/sync')).length).toBe(1)
  })
})

// ---------------------------------------------------------------------------
// 题面页「加入复习」两态
// ---------------------------------------------------------------------------

const DETAIL_BODY = {
  platform: 'leetcode',
  remoteId: 'two-sum',
  title: 'Two Sum',
  difficulty: 'Easy',
  url: 'https://leetcode.com/problems/two-sum/',
  content: '<p>Given an array.</p>',
  contentFormat: 'HTML',
  source: 'cache',
}

/** 题面页 handler(detail/solutions/custom.css + 复习 today 可覆盖)。 */
function problemViewHandler(options: { today?: unknown } = {}): FetchHandler {
  return (url) => {
    if (url.startsWith('/api/review/today')) {
      return {
        status: 200,
        body:
          options.today ?? {
            overdue: [],
            today: [
              {
                problemId: 'leetcode:add-two',
                platformId: 'leetcode',
                remoteId: 'add-two',
                title: 'Add Two Numbers',
                nextDate: DUE_TODAY,
              },
            ],
            upcoming: [],
            totalTracked: 1,
          },
      }
    }
    if (url.includes('/solutions')) {
      return { status: 200, body: { platform: 'leetcode', remoteId: 'two-sum', items: [] } }
    }
    if (url.startsWith('/api/problem/')) {
      return { status: 200, body: DETAIL_BODY }
    }
    if (url.startsWith('/api/custom.css')) {
      return { status: 200, body: '', text: '' }
    }
    return undefined
  }
}

describe('problem-view add-review button (WP64)', () => {
  it('starts as 加入复习, calls add with the platform:remoteId key, then flips to 已排期至', async () => {
    window.history.replaceState({}, '', '/problem-view/?platform=leetcode&remoteId=two-sum')
    const planFirst = DUE_UPCOMING
    handler = (url, init) => {
      if (url.startsWith('/api/review/problems/') && url.endsWith('/add')) {
        expect(init?.method).toBe('POST')
        return { status: 200, body: { problemId: 'leetcode:two-sum', planDates: [planFirst, planFirst + 86_400] } }
      }
      return problemViewHandler()(url, init)
    }
    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    // 未排期态:可点
    const button = wrapper.find('.statement-header__review')
    expect(button.text()).toBe('加入复习')
    expect(button.attributes('disabled')).toBeUndefined()

    await button.trigger('click')
    await flushPromises()

    const add = requests.find((r) => r.url.includes('/add'))
    expect(add?.url).toBe('/api/review/problems/leetcode%3Atwo-sum/add')
    // 加入后:两态切换为首个别到期日并禁用
    expect(button.text()).toBe(`已排期至 ${epochDay(planFirst)}`)
    expect(button.attributes('disabled')).toBeDefined()
  })

  it('shows 已排期至 directly when the problem already has a schedule', async () => {
    window.history.replaceState({}, '', '/problem-view/?platform=leetcode&remoteId=two-sum')
    handler = (url, init) =>
      problemViewHandler({
        today: {
          overdue: [],
          today: [],
          upcoming: [
            {
              problemId: 'leetcode:two-sum',
              platformId: 'leetcode',
              remoteId: 'two-sum',
              title: 'Two Sum',
              nextDate: DUE_UPCOMING,
            },
          ],
          totalTracked: 1,
        },
      })(url, init)
    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    const button = wrapper.find('.statement-header__review')
    expect(button.text()).toBe(`已排期至 ${epochDay(DUE_UPCOMING)}`)
    expect(button.attributes('disabled')).toBeDefined()
    // 已排期 → 不再发 add
    expect(requests.some((r) => r.url.includes('/add'))).toBe(false)
  })
})
