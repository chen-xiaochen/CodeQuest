/**
 * WP21:submissions 页真实列表(端点 mock)——行渲染/verdict 徽标/耗时内存格式化/
 * challengeId 过滤转发/分页追加/桥 `submission:updated` 推送刷新/错误态。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import SubmissionsApp from '../../src/pages/submissions/App.vue'
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
})

function submissionItem(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    challengeId: 'c-1',
    challengeName: 'Two Sum',
    platformId: 'leetcode',
    remoteSubmissionId: `remote-${id}`,
    language: 'Kotlin',
    verdict: { type: 'Accepted' },
    timeMs: 12,
    memoryKb: 16384,
    submittedAt: '2026-08-15T10:00:00Z',
    ...overrides,
  }
}

function mockListPage(items: unknown[], hasMore = false): void {
  handler = (url) => {
    if (url.startsWith('/api/submissions')) {
      const offset = Number(new URLSearchParams(url.split('?')[1]).get('offset') ?? '0')
      return { status: 200, body: { challengeId: null, offset, limit: 50, items, hasMore } }
    }
    return undefined
  }
}

describe('submissions page', () => {
  it('renders rows with problem, language, verdict badge, time, memory and timestamp', async () => {
    mockListPage([
      submissionItem('s-1'),
      submissionItem('s-2', { verdict: { type: 'WrongAnswer' }, timeMs: null, memoryKb: null, challengeName: undefined }),
    ])
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    const rows = wrapper.findAll('.submissions tbody tr')
    expect(rows.length).toBe(2)

    expect(rows[0].text()).toContain('Two Sum')
    expect(rows[0].text()).toContain('Kotlin')
    expect(rows[0].find('.verdict-badge--accepted').exists()).toBe(true)
    expect(rows[0].text()).toContain('12 ms')
    expect(rows[0].text()).toContain('16.0 MB')
    expect(rows[0].text()).toContain('#remote-s-1')

    // challengeName 缺省回退 challengeId;null 指标渲染 —
    expect(rows[1].text()).toContain('c-1')
    expect(rows[1].find('.verdict-badge--rejected').exists()).toBe(true)
    expect(rows[1].text()).toContain('—')
  })

  it('forwards challengeId filter from the URL query string', async () => {
    const originalSearch = window.location.search
    window.history.replaceState(null, '', '/submissions/?challengeId=c-42')
    mockListPage([])

    mount(SubmissionsApp)
    await flushPromises()

    expect(fetchCalls[0]).toContain('challengeId=c-42')
    window.history.replaceState(null, '', originalSearch || '/')
  })

  it('appends the next page when clicking load more', async () => {
    const first = [submissionItem('s-1')]
    const second = [submissionItem('s-2')]
    handler = (url) => {
      if (!url.startsWith('/api/submissions')) return undefined
      const offset = Number(new URLSearchParams(url.split('?')[1]).get('offset') ?? '0')
      const page = offset === 0 ? first : second
      return { status: 200, body: { challengeId: null, offset, limit: 50, items: page, hasMore: offset === 0 } }
    }

    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    expect(wrapper.findAll('.submissions tbody tr').length).toBe(1)
    await wrapper.find('.pager__more').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.submissions tbody tr').length).toBe(2)
    expect(fetchCalls[1]).toContain('offset=1')
  })

  it('refetches the first page on submission:updated bridge push', async () => {
    mockListPage([submissionItem('s-1')])

    const wrapper = mount(SubmissionsApp)
    await flushPromises()
    expect(fetchCalls.length).toBe(1)

    // Kotlin 侧 postMessage → window.__cqBridge.emit(信封)
    window.__cqBridge!.emit({
      topic: 'submission:updated',
      payload: JSON.stringify({ challengeId: 'c-1', submissionId: 's-9', verdict: { type: 'Accepted' } }),
    })
    await flushPromises()

    expect(fetchCalls.length).toBe(2)
    expect(wrapper.findAll('.submissions tbody tr').length).toBe(1)
  })

  it('shows error state with backend error code', async () => {
    handler = (url) => {
      if (url.startsWith('/api/submissions')) {
        return { status: 400, body: { error: 'bad-request', message: 'limit must be positive' } }
      }
      return undefined
    }

    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    expect(wrapper.find('.state--error').text()).toContain('bad-request')
  })

  it('shows empty state when there are no submissions', async () => {
    mockListPage([])
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    expect(wrapper.text()).toContain('No submissions yet')
  })
})
