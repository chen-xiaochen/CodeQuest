/**
 * WP69(1.8.0):力扣 CN 每日一题卡片 + 竞赛 tab 接入——
 * 题库页「今日题目」三态(CN 正常/非 CN/失败与全 null 降级)+ 字段渲染 +
 * 点击 nav:openProblem payload;竞赛页力扣 tab 能力探测出现、LC 竞赛行
 * phase 中文/时长格式化、详情题目行点题 payload(platform=leetcodecn)、
 * LC 竞赛提醒订阅(通用 REST)。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProblemsApp from '../../src/pages/problems/App.vue'
import ContestApp from '../../src/pages/contest/App.vue'
import { resetGlobalBridgeHook } from '../../src/bridge/cq-bridge'
import { clearUserContestCache } from '../../src/lib/api'

type MockResponse = { status: number; body: unknown }
type FetchHandler = (
  url: string,
  init?: RequestInit,
) => MockResponse | undefined

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
      const result = handler(url, init)
      return result ? jsonResponse(result.status, result.body) : jsonResponse(404, { error: 'not-mocked' })
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

const DAILY_BODY = {
  date: '2026-10-03',
  frontendQuestionId: '3169',
  title: '无需排序的数组计数',
  titleSlug: 'count-the-number-of-without-sort',
  difficulty: 'medium',
  tags: [
    { name: '数组', slug: 'array' },
    { name: '计数', slug: 'counting' },
  ],
}

/**
 * 题库页 handler(defaultPlatform 决定默认选中;daily 可覆盖为
 * MockResponse 控制成功/失败/全 null)。
 */
function problemsHandler(options: {
  platforms?: Array<{ id: string; displayName: string }>
  daily?: MockResponse
} = {}): FetchHandler {
  const platforms = options.platforms ?? [{ id: 'leetcodecn', displayName: '力扣' }]
  return (url) => {
    if (url.startsWith('/api/platforms')) {
      return { status: 200, body: { platforms } }
    }
    if (url.startsWith('/api/auth/status')) {
      return {
        status: 200,
        body: { items: platforms.map((entry) => ({ platform: entry.id, loggedIn: true, userId: 'u1' })) },
      }
    }
    if (url.startsWith('/api/companies') || url.startsWith('/api/favorites')) {
      return { status: 404, body: { error: 'unknown-platform' } }
    }
    if (url.startsWith('/api/problems')) {
      return { status: 200, body: { platform: 'leetcodecn', offset: 0, limit: 50, items: [], hasMore: false } }
    }
    if (url.startsWith('/api/rating/user-contest')) {
      return { status: 200, body: { rating: null, attendedContestsCount: null } }
    }
    if (url.startsWith('/api/daily-question')) {
      return options.daily ?? { status: 200, body: DAILY_BODY }
    }
    return undefined
  }
}

describe('problems page daily question card (WP69)', () => {
  it('renders the card with date, id, title, difficulty badge and tag chips on leetcodecn', async () => {
    handler = problemsHandler()
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const card = wrapper.find('.daily')
    expect(card.exists()).toBe(true)
    expect(card.text()).toContain('今日题目')
    expect(card.text()).toContain('2026-10-03')
    expect(card.text()).toContain('3169')
    expect(card.text()).toContain('无需排序的数组计数')
    expect(card.find('.diff-badge--medium').exists()).toBe(true)
    const chips = card.findAll('.daily__tag')
    expect(chips.length).toBe(2)
    expect(chips[0].text()).toBe('数组')
    expect(chips[1].text()).toBe('计数')
  })

  it('sends nav:openProblem with platformId=leetcodecn and remoteId=titleSlug on card click', async () => {
    const sent: string[] = []
    window.cqSend = (message: string) => {
      sent.push(message)
    }
    handler = problemsHandler()
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.find('.daily').trigger('click')

    expect(sent.length).toBe(1)
    const envelope = JSON.parse(sent[0]) as { topic: string; payload: string }
    expect(envelope.topic).toBe('nav:openProblem')
    expect(JSON.parse(envelope.payload)).toEqual({
      platformId: 'leetcodecn',
      remoteId: 'count-the-number-of-without-sort',
    })
  })

  it('does not render the card on non-cn platforms', async () => {
    handler = problemsHandler({ platforms: [{ id: 'leetcode', displayName: 'LeetCode' }] })
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    expect(wrapper.find('.daily').exists()).toBe(false)
    // 非 CN 不发每日一题请求
    expect(requests.some((r) => r.url.startsWith('/api/daily-question'))).toBe(false)
  })

  it('silently hides the card on endpoint failure (404) and on all-null degraded body', async () => {
    handler = problemsHandler({
      daily: { status: 404, body: { error: 'unknown-platform', message: 'no capability' } },
    })
    const failed = mount(ProblemsApp)
    await flushPromises()
    expect(failed.find('.daily').exists()).toBe(false)
    expect(failed.find('.state--error').exists()).toBe(false)

    handler = problemsHandler({
      daily: {
        status: 200,
        body: { date: null, frontendQuestionId: null, title: null, titleSlug: null, difficulty: null, tags: [] },
      },
    })
    const degraded = mount(ProblemsApp)
    await flushPromises()
    expect(degraded.find('.daily').exists()).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// 竞赛页:力扣 CN 接入(能力探测 tab / 行字段 / 详情点题 / 提醒订阅)
// ---------------------------------------------------------------------------

const CONTEST_PLATFORMS = [
  { id: 'codeforces', displayName: 'CodeForces' },
  { id: 'leetcodecn', displayName: '力扣' },
]

const LC_CONTESTS = [
  {
    remoteId: 'weekly-contest-500',
    title: '第 500 场周赛',
    startTime: '2026-10-04T03:00:00Z',
    durationMinutes: 90,
    phase: 'BEFORE',
    url: 'https://leetcode.cn/contest/weekly-contest-500',
  },
  {
    remoteId: 'biweekly-contest-120',
    title: '第 120 场双周赛',
    startTime: '2026-09-26T03:00:00Z',
    durationMinutes: 120,
    phase: 'FINISHED',
    url: 'https://leetcode.cn/contest/biweekly-contest-120',
  },
]

const LC_DETAIL = {
  platform: 'leetcodecn',
  remoteId: 'weekly-contest-500',
  title: '第 500 场周赛',
  startTime: '2026-10-04T03:00:00Z',
  durationMinutes: 90,
  phase: 'BEFORE',
  url: 'https://leetcode.cn/contest/weekly-contest-500',
  problems: [
    { remoteId: 'count-pairs', index: '1', title: '统计好三元组' },
    { remoteId: 'min-moves', index: '2', title: '最小操作次数' },
  ],
  joinable: false,
}

/** 竞赛页 handler(两平台均登录;LC 列表/详情/提醒可覆盖)。 */
function contestHandler(options: {
  lcContests?: MockResponse
  lcDetail?: MockResponse
} = {}): FetchHandler {
  return (url, init) => {
    if (url === '/api/auth/status') {
      return {
        status: 200,
        body: {
          items: CONTEST_PLATFORMS.map((entry) => ({
            platform: entry.id,
            loggedIn: true,
            userId: 'u1',
          })),
        },
      }
    }
    if (url === '/api/contests') {
      return { status: 200, body: { platforms: CONTEST_PLATFORMS } }
    }
    if (url === '/api/contests?platform=leetcodecn') {
      return options.lcContests ?? { status: 200, body: { platform: 'leetcodecn', items: LC_CONTESTS } }
    }
    if (url === '/api/contests/reminders?platform=leetcodecn') {
      if (init?.method === 'DELETE') return { status: 200, body: { deleted: true } }
      return { status: 200, body: { platform: 'leetcodecn', items: [] } }
    }
    if (url === '/api/contests/reminders' && init?.method === 'POST') {
      return {
        status: 200,
        body: { platform: 'leetcodecn', remoteId: 'weekly-contest-500', remindAt: '2026-10-03T03:00:00Z', notified: false },
      }
    }
    if (url === '/api/contest/weekly-contest-500?platform=leetcodecn') {
      return options.lcDetail ?? { status: 200, body: LC_DETAIL }
    }
    return undefined
  }
}

/** 切到「力扣」平台 tab 并等列表落定。 */
async function switchToLeetcodeTab(wrapper: ReturnType<typeof mount>): Promise<void> {
  const tabs = wrapper.findAll('.tabs__item')
  const lcTab = tabs.find((tab) => tab.text() === '力扣')
  await lcTab!.trigger('click')
  await flushPromises()
}

describe('contest page leetcodecn integration (WP69)', () => {
  it('lists leetcodecn tab via capability probing and defaults to the first platform', async () => {
    handler = contestHandler()
    const wrapper = mount(ContestApp)
    await flushPromises()

    const tabs = wrapper.findAll('.tabs__item')
    expect(tabs.length).toBe(2)
    expect(tabs[0].text()).toBe('CodeForces')
    expect(tabs[1].text()).toBe('力扣')
    expect(tabs[0].classes()).toContain('tabs__item--active')
  })

  it('renders leetcode contest rows with localized phase label and formatted duration', async () => {
    handler = contestHandler()
    const wrapper = mount(ContestApp)
    await flushPromises()
    await switchToLeetcodeTab(wrapper)

    const rows = wrapper.findAll('.contests__row')
    expect(rows.length).toBe(2)
    expect(rows[0].text()).toContain('weekly-contest-500')
    expect(rows[0].text()).toContain('第 500 场周赛')
    // 时长格式化:90 分钟 → 1 小时 30 分钟;phase 中文映射
    expect(rows[0].find('.contests__duration').text()).toBe('1 小时 30 分钟')
    expect(rows[0].find('.phase-badge--BEFORE').text()).toBe('未开始')
    expect(rows[1].find('.contests__duration').text()).toBe('2 小时')
    expect(rows[1].find('.phase-badge--FINISHED').text()).toBe('已结束')
  })

  it('expands contest problems and opens the statement with platformId=leetcodecn', async () => {
    const sent: string[] = []
    window.cqSend = (message: string) => {
      sent.push(message)
    }
    handler = contestHandler()
    const wrapper = mount(ContestApp)
    await flushPromises()
    await switchToLeetcodeTab(wrapper)

    // 展开(请求带当前平台参数)
    await wrapper.findAll('.contests__detail-toggle')[0].trigger('click')
    await flushPromises()
    expect(requests.some((r) => r.url === '/api/contest/weekly-contest-500?platform=leetcodecn')).toBe(true)

    const problems = wrapper.findAll('.contests__problem')
    expect(problems.length).toBe(2)
    expect(problems[0].find('.contests__problem-index').text()).toBe('Q1')
    expect(problems[0].text()).toContain('统计好三元组')
    expect(problems[1].find('.contests__problem-index').text()).toBe('Q2')

    // 点题 → nav:openProblem payload 带 platformId=leetcodecn + remoteId=titleSlug
    await problems[0].trigger('click')
    expect(sent.length).toBe(1)
    const envelope = JSON.parse(sent[0]) as { topic: string; payload: string }
    expect(envelope.topic).toBe('nav:openProblem')
    expect(JSON.parse(envelope.payload)).toEqual({ platformId: 'leetcodecn', remoteId: 'count-pairs' })
  })

  it('subscribes a reminder for a leetcode contest via the generic platform+remoteId REST', async () => {
    handler = contestHandler()
    const wrapper = mount(ContestApp)
    await flushPromises()
    await switchToLeetcodeTab(wrapper)

    await wrapper.findAll('input.contests__remind')[0].setValue(true)
    await flushPromises()

    const post = requests.find((r) => r.method === 'POST' && r.url === '/api/contests/reminders')
    expect(post).toBeDefined()
    expect(post?.body).toContain('"platform":"leetcodecn"')
    expect(post?.body).toContain('"remoteId":"weekly-contest-500"')
    // 订阅成功后开关保持勾选(本地 Set 回显)
    expect((wrapper.findAll('input.contests__remind')[0].element as HTMLInputElement).checked).toBe(true)
  })
})
