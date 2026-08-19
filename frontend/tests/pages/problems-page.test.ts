/**
 * WP20:problems 页真实列表渲染(端点 mock)——行渲染/难度徽标/分页追加/
 * 点击行发 nav:openProblem 桥消息/错误态。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProblemsApp from '../../src/pages/problems/App.vue'
import { TOPIC_AUTH_LOGIN, TOPIC_AUTH_LOGIN_RESULT, resetGlobalBridgeHook } from '../../src/bridge/cq-bridge'

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
})
