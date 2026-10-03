/**
 * 站内历史混合展示(1.9.x):本地提交 + remote_submission 合并行——
 * 提交时间倒序合并/同键本地优先(站内重复行不显示)/「站内」来源徽标/
 * 站内行轻量展开(字段+外链,无 Code 态)/challengeId 定向视图纯本地不发站内请求/
 * 站内源失败静默降级纯本地。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import SubmissionsApp from '../../src/pages/submissions/App.vue'
import { resetGlobalBridgeHook } from '../../src/bridge/cq-bridge'

type FetchHandler = (url: string) => { status: number; body: unknown } | undefined

let handler: FetchHandler = () => undefined
let fetchCalls: string[] = []

function jsonResponse(status: number, body: unknown): Response {
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
  window.history.replaceState(null, '', '/')
})

function localItem(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    challengeId: 'c-1',
    challengeName: 'Two Sum',
    platformId: 'leetcodecn',
    remoteSubmissionId: `remote-${id}`,
    language: 'Kotlin',
    verdict: { type: 'Accepted' },
    timeMs: 12,
    memoryKb: 16384,
    submittedAt: '2026-10-02T10:00:00Z',
    ...overrides,
  }
}

function remoteItem(remoteId: string, overrides: Record<string, unknown> = {}) {
  return {
    platformId: 'leetcodecn',
    remoteId,
    titleSlug: 'add-two-numbers',
    title: '两数相加',
    statusDisplay: 'AC',
    lang: 'kotlin',
    runtimeMs: 8,
    memoryKb: 40960,
    submittedAt: '2026-10-03T10:00:00Z',
    ...overrides,
  }
}

/** 双源 mock:本地列表 + 站内历史(跨平台页)。 */
function mockBoth(
  local: unknown[],
  remote: unknown[],
  opts: { localHasMore?: boolean; remoteTotal?: number; remoteStatus?: number } = {},
): void {
  handler = (url) => {
    if (url.startsWith('/api/submissions/remote')) {
      if (opts.remoteStatus !== undefined) {
        return { status: opts.remoteStatus, body: { error: 'oj-error', message: 'boom' } }
      }
      return { status: 200, body: { platform: null, total: opts.remoteTotal ?? remote.length, offset: 0, limit: 50, items: remote } }
    }
    if (url.startsWith('/api/submissions')) {
      return { status: 200, body: { challengeId: null, offset: 0, limit: 50, items: local, hasMore: opts.localHasMore ?? false } }
    }
    return undefined
  }
}

describe('submissions remote merge', () => {
  it('merges local and remote rows sorted by submittedAt desc with 站内 badge', async () => {
    mockBoth(
      [localItem('s-1', { submittedAt: '2026-10-02T10:00:00Z' })],
      [
        remoteItem('r-9', { submittedAt: '2026-10-03T10:00:00Z' }),
        remoteItem('r-8', { submittedAt: '2026-10-01T10:00:00Z', statusDisplay: 'Wrong Answer', runtimeMs: null }),
      ],
    )
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    const rows = wrapper.findAll('.submissions tbody tr')
    expect(rows.length).toBe(3) // 3 数据行(未展开)
    // 倒序:站内 10-03 → 本地 10-02 → 站内 10-01
    expect(rows[0].text()).toContain('两数相加')
    expect(rows[0].find('.submissions__remote-badge').exists()).toBe(true)
    expect(rows[1].text()).toContain('Two Sum')
    expect(rows[1].find('.submissions__remote-badge').exists()).toBe(false)
    expect(rows[2].text()).toContain('两数相加')
    // 站内判定映射:AC→通过(绿),Wrong Answer→答案错误(红);null 指标 —
    expect(rows[0].find('.verdict-badge--accepted').exists()).toBe(true)
    expect(rows[2].find('.verdict-badge--rejected').exists()).toBe(true)
    expect(rows[2].text()).toContain('—')
    expect(rows[0].text()).toContain('#r-9')
  })

  it('prefers local row when same (platform, remoteId) exists in both sources', async () => {
    mockBoth(
      [localItem('s-1', { remoteSubmissionId: 'r-9', submittedAt: '2026-10-03T10:00:00Z' })],
      [remoteItem('r-9', { submittedAt: '2026-10-03T09:00:00Z' })],
    )
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    const rows = wrapper.findAll('.submissions tbody tr')
    expect(rows.length).toBe(1)
    expect(rows[0].text()).toContain('Two Sum') // 本地行(非站内行)
    expect(rows[0].find('.submissions__remote-badge').exists()).toBe(false)
  })

  it('expands remote row inline with fields and external link, no code tab', async () => {
    mockBoth([], [remoteItem('r-9')])
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    await wrapper.find('.submissions__row--remote').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.submissions__row--remote')[0].attributes('aria-expanded')).toBe('true')

    const detail = wrapper.find('.detail--remote')
    expect(detail.exists()).toBe(true)
    expect(detail.text()).toContain('两数相加')
    expect(detail.text()).toContain('add-two-numbers')
    expect(detail.text()).toContain('通过')
    const link = detail.find('.detail__view-url')
    expect(link.attributes('href')).toBe('https://leetcode.cn/submissions/detail/r-9/')
    // 站内行无详情拉取(无 /detail 请求)且无 Code tab
    expect(fetchCalls.some((url) => url.includes('/detail'))).toBe(false)
    expect(wrapper.find('.detail__tab').exists()).toBe(false)
  })

  it('skips remote source entirely in challengeId scoped view', async () => {
    window.history.replaceState(null, '', '/submissions/?challengeId=c-42')
    mockBoth([], [remoteItem('r-9')])

    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    expect(fetchCalls.some((url) => url.includes('/api/submissions/remote'))).toBe(false)
    expect(wrapper.findAll('.submissions tbody tr').length).toBe(0)
  })

  it('degrades silently to local-only view when remote source fails', async () => {
    mockBoth([localItem('s-1')], [], { remoteStatus: 502 })
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    expect(wrapper.find('.state--error').exists()).toBe(false) // 本地视图不受打扰
    expect(wrapper.findAll('.submissions tbody tr').length).toBe(1)
    expect(wrapper.text()).toContain('Two Sum')
  })
})
