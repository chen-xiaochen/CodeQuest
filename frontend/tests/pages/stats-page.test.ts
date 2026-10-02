/**
 * WP29:stats 页真实渲染(端点 mock)——汇总卡片数值/热力图格子数与色阶/
 * 月份标签/趋势条形数与高度/空库占位/错误态/桥 `submission:updated` 刷新。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import StatsApp from '../../src/pages/stats/App.vue'
import { resetGlobalBridgeHook } from '../../src/bridge/cq-bridge'

type FetchHandler = (url: string) => { status: number; body: unknown } | undefined

let handler: FetchHandler = () => undefined
let fetchCalls: string[] = []

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
  fetchCalls = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      fetchCalls.push(url)
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
  document.documentElement.removeAttribute('data-theme')
})

/** 与页面同公式的对齐前置空白(UTC,周一为 0)。 */
function expectedPads(firstDate: string): number {
  return (new Date(`${firstDate}T00:00:00Z`).getUTCDay() + 6) % 7
}

function dailyCounts(
  firstDate: string,
  days: number,
  counts: number[],
): { date: string; count: number }[] {
  const start = new Date(`${firstDate}T00:00:00Z`)
  return Array.from({ length: days }, (_, index) => {
    const date = new Date(start.getTime() + index * 86_400_000).toISOString().slice(0, 10)
    return { date, count: counts[index] ?? 0 }
  })
}

function mockStats(overrides: Record<string, unknown> = {}): void {
  const heatmap = dailyCounts('2026-08-05', 14, [0, 1, 2, 0, 0, 4, 0, 0, 6, 0, 0, 0, 0, 10])
  const trend = dailyCounts('2026-08-12', 7, [1, 0, 2, 0, 3, 0, 5])
  handler = (url) => {
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
          heatmap,
          trend,
          ...overrides,
        },
      }
    }
    return undefined
  }
}

describe('stats page', () => {
  it('renders summary cards from the endpoint payload', async () => {
    mockStats()
    const wrapper = mount(StatsApp)
    await flushPromises()

    const cards = wrapper.findAll('.card')
    expect(cards.length).toBe(4)
    expect(cards[0].text()).toContain('总提交')
    expect(cards[0].text()).toContain('42')
    expect(cards[1].text()).toContain('通过数')
    expect(cards[1].text()).toContain('21')
    expect(cards[2].text()).toContain('AC 率')
    expect(cards[2].text()).toContain('50.0%')
    expect(cards[3].text()).toContain('当前连续')
    expect(cards[3].text()).toContain('3 天')
    expect(cards[3].text()).toContain('最长连续 9 天')
  })

  it('renders one heatmap cell per day plus monday alignment pads with level classes', async () => {
    mockStats()
    const wrapper = mount(StatsApp)
    await flushPromises()

    const days = wrapper.find('.heatmap__days')
    const cells = days.findAll('.heat-cell')
    const pads = days.findAll('.heat-pad')
    expect(cells.length).toBe(14)
    expect(pads.length).toBe(expectedPads('2026-08-05'))

    // 色阶:0 → l0,10 → l4,2 → l1
    expect(cells[0].classes()).toContain('heat-cell--l0')
    expect(cells[13].classes()).toContain('heat-cell--l4')
    expect(cells[2].classes()).toContain('heat-cell--l1')

    // title 原生提示带日期与数量(WP50 中文)
    expect(cells[13].attributes('title')).toBe('2026-08-18：10 次提交')

    // 月份标签:首列必有(首周月份)
    const monthLabels = wrapper.findAll('.heatmap__month')
    expect(monthLabels.length).toBeGreaterThanOrEqual(1)
    expect(monthLabels[0].attributes('style')).toContain('grid-column-start')
  })

  it('renders one trend bar per day with proportional height', async () => {
    mockStats()
    const wrapper = mount(StatsApp)
    await flushPromises()

    const bars = wrapper.findAll('.trend__bar')
    expect(bars.length).toBe(7)
    // max=5:末条 100%,首条 1/5=20%,零计数条仍渲染(min-height 占位)
    expect(bars[6].attributes('style')).toContain('height: 100%')
    expect(bars[0].attributes('style')).toContain('height: 20%')
    expect(bars[1].attributes('style')).toContain('height: 0%')
    expect(wrapper.text()).toContain('单日最高 5')
  })

  it('shows friendly placeholder when the submission history is empty', async () => {
    mockStats({
      summary: {
        totalSubmissions: 0,
        acceptedSubmissions: 0,
        acceptanceRate: 0,
        currentStreakDays: 0,
        longestStreakDays: 0,
      },
      heatmap: dailyCounts('2026-08-05', 3, []),
      trend: dailyCounts('2026-08-16', 3, []),
    })
    const wrapper = mount(StatsApp)
    await flushPromises()

    expect(wrapper.find('.placeholder').exists()).toBe(true)
    expect(wrapper.text()).toContain('还没有提交记录')
    expect(wrapper.find('.cards').exists()).toBe(false)
  })

  it('shows error state with backend error code and recovers on retry', async () => {
    handler = (url) => {
      if (url.startsWith('/api/stats')) {
        return { status: 400, body: { error: 'bad-request', message: 'days must be an integer' } }
      }
      return undefined
    }
    const wrapper = mount(StatsApp)
    await flushPromises()

    expect(wrapper.find('.state--error').text()).toContain('bad-request')

    mockStats()
    await wrapper.find('.state__retry').trigger('click')
    await flushPromises()

    expect(wrapper.find('.state--error').exists()).toBe(false)
    expect(wrapper.findAll('.card').length).toBe(4)
  })

  it('requests the unfiltered endpoint by default', async () => {
    mockStats()
    mount(StatsApp)
    await flushPromises()

    expect(fetchCalls[0]).toBe('/api/stats')
  })

  it('refetches on submission:updated bridge push', async () => {
    mockStats()
    const wrapper = mount(StatsApp)
    await flushPromises()
    // WP64:mount 还会拉 /api/platforms(同步平台下拉),按 stats 前缀过滤计数
    const statsGets = () => fetchCalls.filter((url) => url.startsWith('/api/stats')).length
    expect(statsGets()).toBe(1)

    window.__cqBridge!.emit({
      topic: 'submission:updated',
      payload: JSON.stringify({ challengeId: 'c-1', submissionId: 's-9', verdict: { type: 'Accepted' } }),
    })
    await flushPromises()

    expect(statsGets()).toBe(2)
    expect(wrapper.findAll('.card').length).toBe(4)
  })
})
