/**
 * WP63:题库页评分呈现——评分列渲染与五档着色(分差/绝对退化/关闭)、
 * 区间本地过滤、排序循环、列显隐与着色模式 localStorage 持久化、
 * 账号区竞赛分徽标与「刷新评分」(成功重拉/失败 toast/防重入)。
 * WP70:排序扩五档(默认 → 题号 → 难度 → 评分↑ → 评分↓;题号/难度服务端
 * sortBy 重拉,评分档本地)+「隐藏已解决」本地过滤(持久化/多条件叠加)。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProblemsApp from '../../src/pages/problems/App.vue'
import { clearUserContestCache } from '../../src/lib/api'
import { resetGlobalBridgeHook } from '../../src/bridge/cq-bridge'

type MockResponse = { status: number; body: unknown }
/** handler 可返回 Promise(挂起可控,用于防重入用例)。 */
type FetchHandler = (url: string) => MockResponse | undefined | Promise<MockResponse | undefined>

let handler: FetchHandler = () => undefined

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  handler = () => undefined
  localStorage.clear()
  clearUserContestCache()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      const result = await handler(url)
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

/** LC 单平台 + 固定评分行 + 可配置用户竞赛分;计数器记录三类请求。 */
const RATING_ROWS = [
  { remoteId: 'r-mid', title: 'Mid', difficulty: 'Medium', url: 'u2', tags: [], rating: 1800 },
  { remoteId: 'r-low', title: 'Low', difficulty: 'Easy', url: 'u1', tags: [], rating: 1200 },
  { remoteId: 'r-high', title: 'High', difficulty: 'Hard', url: 'u3', tags: [], rating: 2600 },
  { remoteId: 'r-none', title: 'NoRating', difficulty: 'Easy', url: 'u4', tags: [] },
]

function ratingAwareHandler(options: {
  userContest?: { rating: number | null; attendedContestsCount: number | null }
  refresh?: MockResponse
  platforms?: Array<{ id: string; displayName: string }>
  counts?: { list?: number; userContest?: number; refresh?: number }
  rows?: Array<Record<string, unknown>>
} = {}): FetchHandler {
  const platforms = options.platforms ?? [{ id: 'leetcode', displayName: 'LeetCode' }]
  return (url) => {
    if (url.startsWith('/api/platforms')) {
      return { status: 200, body: { platforms } }
    }
    if (url.startsWith('/api/companies') || url.startsWith('/api/favorites')) {
      return { status: 404, body: { error: 'unknown-platform' } }
    }
    if (url.startsWith('/api/problems')) {
      if (options.counts) options.counts.list = (options.counts.list ?? 0) + 1
      return {
        status: 200,
        body: {
          platform: 'leetcode',
          offset: 0,
          limit: 50,
          items: options.rows ?? RATING_ROWS,
          hasMore: false,
        },
      }
    }
    if (url.startsWith('/api/rating/user-contest')) {
      if (options.counts) options.counts.userContest = (options.counts.userContest ?? 0) + 1
      return { status: 200, body: options.userContest ?? { rating: null, attendedContestsCount: null } }
    }
    if (url.startsWith('/api/rating/refresh')) {
      if (options.counts) options.counts.refresh = (options.counts.refresh ?? 0) + 1
      return options.refresh ?? { status: 200, body: { count: 2596, updatedAt: '2026-10-02T00:00:00Z' } }
    }
    return undefined
  }
}

function rowTitles(wrapper: ReturnType<typeof mount>): string[] {
  return wrapper.findAll('.problems__row .problems__title').map((node) => node.text().trim())
}

/** WP70:隐藏已解决用行——AC/TRIED/UNSOLVED 三态 + 缺省 status(未登录旧响应兜底)。 */
const HIDE_SOLVED_ROWS = [
  { remoteId: 's-ac', title: 'SolvedAC', difficulty: 'Easy', url: 'u1', tags: [], rating: 1200, status: 'AC' },
  { remoteId: 's-tried', title: 'Tried', difficulty: 'Medium', url: 'u2', tags: [], rating: 1800, status: 'TRIED' },
  { remoteId: 's-unsolved', title: 'Unsolved', difficulty: 'Hard', url: 'u3', tags: [], rating: 2200, status: 'UNSOLVED' },
  { remoteId: 's-legacy', title: 'LegacyNoStatus', difficulty: 'Easy', url: 'u4', tags: [] },
]

describe('problems page rating (WP63)', () => {
  it('renders the rating column with diff-tone classes when user contest rating exists', async () => {
    handler = ratingAwareHandler({ userContest: { rating: 1500, attendedContestsCount: 5 } })
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const cells = wrapper.findAll('.rating-num')
    expect(cells.length).toBe(4)
    // 行序:1800(diff +300 红)/1200(diff -300 绿)/2600(diff +1100 红)/null '-'
    expect(cells[0].classes()).toContain('rating-num--retreat')
    expect(cells[0].text()).toBe('1800')
    expect(cells[1].classes()).toContain('rating-num--instant')
    expect(cells[1].text()).toBe('1200')
    expect(cells[2].classes()).toContain('rating-num--retreat')
    expect(cells[3].text()).toBe('-')
    expect(cells[3].classes()).toEqual(['rating-num'])
    // 行 title 携带档位语义
    expect(cells[1].attributes('title')).toBe('评分 1200(秒杀)')
  })

  it('falls back to absolute tone bands when user contest has no rating', async () => {
    handler = ratingAwareHandler({ userContest: { rating: null, attendedContestsCount: null } })
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const cells = wrapper.findAll('.rating-num')
    // 绝对档:1200 绿 / 1800 紫(1800-2199)/ 2600 红 / null 不着色
    expect(cells[0].classes()).toContain('rating-num--normal')
    expect(cells[1].classes()).toContain('rating-num--instant')
    expect(cells[2].classes()).toContain('rating-num--retreat')
    expect(cells[3].classes()).toEqual(['rating-num'])
    // 双 null:账号区不显示竞赛分徽标
    expect(wrapper.find('.accounts__rating').exists()).toBe(false)
  })

  it('renders plain values without tone classes when color mode is off (persisted)', async () => {
    localStorage.setItem('cq-problems-rating-color-mode', 'off')
    handler = ratingAwareHandler({ userContest: { rating: 1500, attendedContestsCount: 5 } })
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    expect(wrapper.findAll('.rating-num').every((cell) => cell.classes())).toBeTruthy()
    for (const cell of wrapper.findAll('.rating-num')) {
      expect(cell.classes()).toEqual(['rating-num'])
    }
    // 默认选中「关闭」chip
    const offChip = wrapper.findAll('.rating-ctrl__chips .rating-chip')[2]
    expect(offChip.classes()).toContain('rating-chip--on')
  })

  it('persists color mode to localStorage when switching chips', async () => {
    handler = ratingAwareHandler()
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.findAll('.rating-ctrl__chips .rating-chip')[1].trigger('click')
    expect(localStorage.getItem('cq-problems-rating-color-mode')).toBe('absolute')
    expect(wrapper.findAll('.rating-ctrl__chips .rating-chip')[1].classes()).toContain('rating-chip--on')
  })

  it('hides the rating column when the stored visibility flag is off, and toggling persists back on', async () => {
    localStorage.setItem('cq-problems-rating-column', '0')
    handler = ratingAwareHandler()
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    expect(wrapper.find('.rating-num').exists()).toBe(false)
    expect(wrapper.find('input[type="number"]').exists()).toBe(false)
    expect(wrapper.find('.rating-ctrl__chips').exists()).toBe(false)

    await wrapper.find('input[type="checkbox"]').setValue(true)
    expect(wrapper.findAll('.rating-num').length).toBe(4)
    expect(localStorage.getItem('cq-problems-rating-column')).toBe('1')
  })

  it('filters loaded rows by rating range locally without refetching, and clears to restore', async () => {
    const counts = { list: 0 }
    handler = ratingAwareHandler({ counts })
    const wrapper = mount(ProblemsApp)
    await flushPromises()
    const callsAfterLoad = counts.list

    const inputs = wrapper.findAll('input[type="number"]')
    expect(wrapper.find('.filter-note').exists()).toBe(false)

    await inputs[0].setValue('1500')
    expect(rowTitles(wrapper)).toEqual(['Mid', 'High'])
    expect(wrapper.find('.filter-note').text()).toContain('已加载条目')

    await inputs[1].setValue('2000')
    expect(rowTitles(wrapper)).toEqual(['Mid'])

    // 清空恢复全部
    await inputs[0].setValue('')
    await inputs[1].setValue('')
    expect(rowTitles(wrapper)).toEqual(['Mid', 'Low', 'High', 'NoRating'])
    expect(wrapper.find('.filter-note').exists()).toBe(false)

    // 本地过滤全程零新列表请求
    expect(counts.list).toBe(callsAfterLoad)
  })

  it('cycles sort 默认 → 题号 → 难度 → 评分↑ → 评分↓ → 默认; server modes reload with sortBy, rating modes local', async () => {
    const counts = { list: 0 }
    const urls: string[] = []
    const base = ratingAwareHandler({ counts })
    handler = (url) => {
      if (url.startsWith('/api/problems')) urls.push(url)
      return base(url)
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()
    const callsAfterLoad = counts.list
    expect(urls[urls.length - 1]).not.toContain('sortBy=')

    const sortBtn = wrapper.find('.rating-sort')
    expect(sortBtn.text()).toContain('默认序')

    // → 题号:服务端档,reload 带 sortBy=id
    await sortBtn.trigger('click')
    await flushPromises()
    expect(sortBtn.text()).toContain('题号')
    expect(counts.list).toBe(callsAfterLoad + 1)
    expect(urls[urls.length - 1]).toContain('sortBy=id')

    // → 难度:换服务端档,reload 带 sortBy=difficulty
    await sortBtn.trigger('click')
    await flushPromises()
    expect(sortBtn.text()).toContain('难度')
    expect(counts.list).toBe(callsAfterLoad + 2)
    expect(urls[urls.length - 1]).toContain('sortBy=difficulty')

    // → 评分↑:离开服务端档,reload 不带 sortBy;本地排序生效,按钮标注「仅已加载」
    await sortBtn.trigger('click')
    await flushPromises()
    expect(sortBtn.text()).toContain('评分↑')
    expect(sortBtn.attributes('title')).toContain('仅对已加载')
    expect(counts.list).toBe(callsAfterLoad + 3)
    expect(urls[urls.length - 1]).not.toContain('sortBy=')
    expect(rowTitles(wrapper)).toEqual(['Low', 'Mid', 'High', 'NoRating'])

    // → 评分↓:评分档间切换纯本地,零新请求,无分题恒沉底
    await sortBtn.trigger('click')
    await flushPromises()
    expect(sortBtn.text()).toContain('评分↓')
    expect(counts.list).toBe(callsAfterLoad + 3)
    expect(rowTitles(wrapper)).toEqual(['High', 'Mid', 'Low', 'NoRating'])

    // → 默认:评分档回默认同样纯本地
    await sortBtn.trigger('click')
    await flushPromises()
    expect(sortBtn.text()).toContain('默认序')
    expect(counts.list).toBe(callsAfterLoad + 3)
    expect(rowTitles(wrapper)).toEqual(['Mid', 'Low', 'High', 'NoRating'])
  })

  it('favorite mode: server sort levels skipped in cycle; entering favorite resets to 默认 without sortBy', async () => {
    const counts = { list: 0 }
    const urls: string[] = []
    const base = ratingAwareHandler({ counts })
    handler = (url) => {
      if (url.startsWith('/api/favorites')) {
        return {
          status: 200,
          body: {
            platform: 'leetcode',
            studyPlans: [{ slug: 'plan-a', name: '计划A' }],
            myFavorites: [],
          },
        }
      }
      if (url.startsWith('/api/problems')) urls.push(url)
      return base(url)
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()
    const callsAfterLoad = counts.list

    // 普通模式先切到题号档(服务端)
    const sortBtn = wrapper.find('.rating-sort')
    await sortBtn.trigger('click')
    await flushPromises()
    expect(sortBtn.text()).toContain('题号')
    expect(urls[urls.length - 1]).toContain('sortBy=id')

    // 选题单(第 4 个 select:平台/难度/状态/题单)→ 排序档重置默认,重拉不带 sortBy
    await wrapper.findAll('select')[3].setValue('plan-a')
    await flushPromises()
    expect(sortBtn.text()).toContain('默认序')
    expect(counts.list).toBe(callsAfterLoad + 2)
    expect(urls[urls.length - 1]).toContain('favorite=plan-a')
    expect(urls[urls.length - 1]).not.toContain('sortBy=')

    // 题单模式循环只走本地三态(默认 → 评分↑ → 评分↓),全程零新请求
    await sortBtn.trigger('click')
    await flushPromises()
    expect(sortBtn.text()).toContain('评分↑')
    await sortBtn.trigger('click')
    await flushPromises()
    expect(sortBtn.text()).toContain('评分↓')
    await sortBtn.trigger('click')
    await flushPromises()
    expect(sortBtn.text()).toContain('默认序')
    expect(counts.list).toBe(callsAfterLoad + 2)
  })

  it('resets sort to 默认 on platform switch', async () => {
    const urls: string[] = []
    const base = ratingAwareHandler({
      platforms: [
        { id: 'leetcode', displayName: 'LeetCode' },
        { id: 'leetcodecn', displayName: '力扣' },
      ],
    })
    handler = (url) => {
      if (url.startsWith('/api/problems')) urls.push(url)
      return base(url)
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const sortBtn = wrapper.find('.rating-sort')
    await sortBtn.trigger('click')
    await flushPromises()
    expect(sortBtn.text()).toContain('题号')
    expect(urls[urls.length - 1]).toContain('sortBy=id')

    await wrapper.findAll('select')[0].setValue('leetcodecn')
    await flushPromises()
    expect(sortBtn.text()).toContain('默认序')
    expect(urls[urls.length - 1]).not.toContain('sortBy=')
  })

  it('hides solved rows locally (AC hidden, TRIED/UNSOLVED/status-less kept) and persists the flag', async () => {
    handler = ratingAwareHandler({ rows: HIDE_SOLVED_ROWS })
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    expect(rowTitles(wrapper)).toEqual(['SolvedAC', 'Tried', 'Unsolved', 'LegacyNoStatus'])

    const box = wrapper.find('.hide-solved__check input')
    await box.setValue(true)
    expect(rowTitles(wrapper)).toEqual(['Tried', 'Unsolved', 'LegacyNoStatus'])
    expect(localStorage.getItem('cq-problems-hide-solved')).toBe('1')
    // 本地过滤激活 → filter-note 出现
    expect(wrapper.find('.filter-note').text()).toContain('已加载条目')

    // 取消恢复全部并持久化回 '0'
    await box.setValue(false)
    expect(rowTitles(wrapper)).toHaveLength(4)
    expect(localStorage.getItem('cq-problems-hide-solved')).toBe('0')
    expect(wrapper.find('.filter-note').exists()).toBe(false)
  })

  it('restores hide-solved from storage and stacks with status filter + rating range (pager count)', async () => {
    localStorage.setItem('cq-problems-hide-solved', '1')
    handler = ratingAwareHandler({ rows: HIDE_SOLVED_ROWS })
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    // 持久化恢复:挂载即隐藏 AC 行,分页计数合并为 x/总数
    expect(rowTitles(wrapper)).toEqual(['Tried', 'Unsolved', 'LegacyNoStatus'])
    expect(wrapper.find('.pager__count').text()).toContain('3/4')

    // 叠加状态筛选(未解决)
    await wrapper.findAll('select')[2].setValue('UNSOLVED')
    expect(rowTitles(wrapper)).toEqual(['Unsolved', 'LegacyNoStatus'])
    expect(wrapper.find('.pager__count').text()).toContain('2/4')

    // 再叠加评分区间 ≥2000(本地,多条件合并;无分行被区间排除)
    const inputs = wrapper.findAll('input[type="number"]')
    await inputs[0].setValue('2000')
    expect(rowTitles(wrapper)).toEqual(['Unsolved'])
    expect(wrapper.find('.pager__count').text()).toContain('1/4')
    expect(wrapper.find('.filter-note').text()).toContain('已加载条目')
  })

  it('shows the contest rating badge on the current platform account row', async () => {
    handler = ratingAwareHandler({
      platforms: [
        { id: 'leetcode', displayName: 'LeetCode' },
        { id: 'leetcodecn', displayName: '力扣' },
      ],
      userContest: { rating: 1567.5, attendedContestsCount: 12 },
    })
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    const badge = wrapper.find('.accounts__rating')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('竞赛分 1568')
    // 徽标只出现在当前平台(默认 leetcode)行
    expect(wrapper.findAll('.accounts__rating').length).toBe(1)
  })

  it('refetches user contest on platform switch (session cache invalidated per switch)', async () => {
    const counts = { userContest: 0 }
    handler = ratingAwareHandler({
      platforms: [
        { id: 'leetcode', displayName: 'LeetCode' },
        { id: 'leetcodecn', displayName: '力扣' },
      ],
      userContest: { rating: 1800, attendedContestsCount: 3 },
      counts,
    })
    const wrapper = mount(ProblemsApp)
    await flushPromises()
    expect(counts.userContest).toBe(1)

    // 切平台:竞赛分缓存随平台切换失效,重拉
    await wrapper.findAll('select')[0].setValue('leetcodecn')
    await flushPromises()
    expect(counts.userContest).toBe(2)
    expect(wrapper.find('.accounts__rating').text()).toBe('竞赛分 1800')

    // 切回:同样重拉(单槽缓存,平台切换即失效)
    await wrapper.findAll('select')[0].setValue('leetcode')
    await flushPromises()
    expect(counts.userContest).toBe(3)
  })

  it('refreshes ratings: toast + user-contest cache cleared + list refetched', async () => {
    const counts = { list: 0, userContest: 0, refresh: 0 }
    handler = ratingAwareHandler({
      userContest: { rating: 1500, attendedContestsCount: 5 },
      counts,
    })
    const wrapper = mount(ProblemsApp)
    await flushPromises()
    expect(counts.list).toBe(1)
    expect(counts.userContest).toBe(1)

    await wrapper.find('.accounts__refresh').trigger('click')
    await flushPromises()

    expect(counts.refresh).toBe(1)
    expect(counts.list).toBe(2)
    expect(counts.userContest).toBe(2)
    const toast = wrapper.find('.rating-toast')
    expect(toast.classes()).toContain('rating-toast--ok')
    expect(toast.text()).toContain('2596')
    expect(wrapper.find('.accounts__refresh').attributes('disabled')).toBeUndefined()
  })

  it('shows an error toast with the backend message when refresh fails, without reloading', async () => {
    const counts = { list: 0, refresh: 0 }
    handler = ratingAwareHandler({
      refresh: { status: 502, body: { error: 'refresh-failed', message: 'zerotrac down' } },
      counts,
    })
    const wrapper = mount(ProblemsApp)
    await flushPromises()
    expect(counts.list).toBe(1)

    await wrapper.find('.accounts__refresh').trigger('click')
    await flushPromises()

    const toast = wrapper.find('.rating-toast')
    expect(toast.classes()).toContain('rating-toast--error')
    expect(toast.text()).toContain('zerotrac down')
    expect(counts.list).toBe(1)
  })

  it('guards against re-entry while a refresh is pending', async () => {
    let releaseRefresh: (() => void) | null = null
    const base = ratingAwareHandler({ counts: { refresh: 0 } })
    handler = async (url) => {
      if (url.startsWith('/api/rating/refresh')) {
        await new Promise<void>((resolve) => {
          releaseRefresh = resolve
        })
      }
      return base(url)
    }
    const wrapper = mount(ProblemsApp)
    await flushPromises()

    await wrapper.find('.accounts__refresh').trigger('click')
    await flushPromises()

    // 挂起期间:按钮禁用 + 进行中文案,无 toast
    const pending = wrapper.find('.accounts__refresh')
    expect(pending.attributes('disabled')).toBeDefined()
    expect(pending.text()).toContain('刷新评分中')
    expect(wrapper.find('.rating-toast').exists()).toBe(false)

    releaseRefresh!()
    await flushPromises()
    expect(wrapper.find('.accounts__refresh').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('.rating-toast--ok').exists()).toBe(true)
  })
})
