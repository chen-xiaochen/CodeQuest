/**
 * WP30:search 页真实渲染(端点 mock)——结果行/平台徽标/难度/命中来源 chips/
 * 标签多选触发检索(AND 参数)/点击行发 nav:openProblem 桥消息/加载更多分页/
 * 空态与错误态。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import SearchApp from '../../src/pages/search/App.vue'

type FetchHandler = (url: string) => { status: number; body: unknown } | undefined

let handler: FetchHandler = () => undefined
let searchCalls: string[] = []

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
  searchCalls = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.startsWith('/api/search')) searchCalls.push(url)
      const result = handler(url)
      return result ? jsonResponse(result.status, result.body) : jsonResponse(404, { error: 'not-mocked' })
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  delete window.cqSend
})

const TAGS = [
  { name: 'dp', count: 12 },
  { name: 'graph', count: 5 },
]

const ITEMS = [
  {
    platform: 'codeforces',
    remoteId: '1927A',
    title: '花火矩阵',
    difficulty: 'easy',
    url: 'https://codeforces.com/problem/1927/A',
    tags: ['dp', 'graph'],
    matchedSources: ['title', 'content'],
  },
  {
    platform: 'leetcode',
    remoteId: 'two-sum',
    title: 'Two Sum',
    difficulty: 'Medium',
    url: 'https://leetcode.com/problems/two-sum/',
    tags: [],
    matchedSources: ['note'],
  },
]

function mockSearch(
  items: Array<Record<string, unknown>> = ITEMS,
  overrides: Record<string, unknown> = {},
): void {
  handler = (url) => {
    if (url.startsWith('/api/platforms')) {
      return {
        status: 200,
        body: { platforms: [{ id: 'leetcode', displayName: 'LeetCode' }, { id: 'codeforces', displayName: 'Codeforces' }] },
      }
    }
    if (url.startsWith('/api/tags')) {
      return { status: 200, body: { tags: TAGS } }
    }
    if (url.startsWith('/api/search')) {
      const offset = Number(new URLSearchParams(url.split('?')[1]).get('offset') ?? '0')
      return { status: 200, body: { offset, limit: 50, total: items.length, items, hasMore: false, tags: [], ...overrides } }
    }
    return undefined
  }
}

describe('search page', () => {
  it('renders result rows with platform badge, difficulty and matched-source chips', async () => {
    mockSearch()
    const wrapper = mount(SearchApp)
    await flushPromises()

    const rows = wrapper.findAll('.results__row')
    expect(rows.length).toBe(2)
    expect(rows[0].text()).toContain('花火矩阵')
    expect(rows[0].text()).toContain('1927A')
    expect(rows[0].find('.platform-badge').text()).toBe('codeforces')
    expect(rows[0].find('.diff-badge--easy').exists()).toBe(true)
    const chips = rows[0].findAll('.source-chip')
    expect(chips.map((chip) => chip.text())).toEqual(['标题', '题面'])
    expect(chips[0].classes()).toContain('source-chip--title')
    expect(chips[1].classes()).toContain('source-chip--content')

    expect(rows[1].find('.diff-badge--medium').exists()).toBe(true)
    expect(rows[1].findAll('.source-chip')[0].classes()).toContain('source-chip--note')
  })

  it('renders the tag filter panel with counts from /api/tags', async () => {
    mockSearch()
    const wrapper = mount(SearchApp)
    await flushPromises()

    const chips = wrapper.findAll('.tags__chip')
    expect(chips.length).toBe(2)
    expect(chips[0].text()).toContain('dp')
    expect(chips[0].text()).toContain('12')
  })

  it('requests the search endpoint with tags and platform filters when toggled', async () => {
    mockSearch()
    const wrapper = mount(SearchApp)
    await flushPromises()
    searchCalls = []

    await wrapper.findAll('.tags__chip')[0].trigger('click')
    await flushPromises()
    await wrapper.findAll('.tags__chip')[1].trigger('click')
    await flushPromises()
    await wrapper.findAll('select')[0].setValue('leetcode')
    await flushPromises()

    expect(searchCalls.length).toBeGreaterThanOrEqual(3)
    const last = searchCalls[searchCalls.length - 1]
    // AND 语义的多个标签逗号串联(URLSearchParams 序列化为 %2C,按解码语义断言)
    expect(decodeURIComponent(last)).toContain('tags=dp,graph')
    expect(last).toContain('platform=leetcode')
  })

  it('deselects a selected tag on second click and refetches', async () => {
    mockSearch()
    const wrapper = mount(SearchApp)
    await flushPromises()
    searchCalls = []

    await wrapper.findAll('.tags__chip')[0].trigger('click')
    await flushPromises()
    expect(wrapper.find('.tags__chip--on').exists()).toBe(true)
    expect(searchCalls[0]).toContain('tags=dp')

    await wrapper.findAll('.tags__chip')[0].trigger('click')
    await flushPromises()
    expect(wrapper.find('.tags__chip--on').exists()).toBe(false)
    expect(searchCalls[1]).not.toContain('tags=')
  })

  it('sends nav:openProblem bridge message with platformId and remoteId on row click', async () => {
    mockSearch()
    const sent: string[] = []
    window.cqSend = (message: string) => {
      sent.push(message)
    }
    const wrapper = mount(SearchApp)
    await flushPromises()

    await wrapper.findAll('.results__row')[0].trigger('click')

    expect(sent.length).toBe(1)
    const envelope = JSON.parse(sent[0]) as { topic: string; payload: string }
    expect(envelope.topic).toBe('nav:openProblem')
    expect(JSON.parse(envelope.payload)).toEqual({ platformId: 'codeforces', remoteId: '1927A' })
  })

  it('appends next page when clicking load more', async () => {
    const pageTwo = ITEMS.map((item) => ({ ...item, remoteId: `${item.remoteId}-p2` }))
    handler = (url) => {
      if (url.startsWith('/api/platforms')) {
        return { status: 200, body: { platforms: [] } }
      }
      if (url.startsWith('/api/tags')) {
        return { status: 200, body: { tags: [] } }
      }
      if (url.startsWith('/api/search')) {
        const offset = Number(new URLSearchParams(url.split('?')[1]).get('offset') ?? '0')
        const page = offset === 0 ? ITEMS : pageTwo
        return {
          status: 200,
          body: { offset, limit: 50, total: ITEMS.length + pageTwo.length, items: page, hasMore: offset === 0, tags: [] },
        }
      }
      return undefined
    }
    const wrapper = mount(SearchApp)
    await flushPromises()

    expect(wrapper.findAll('.results__row').length).toBe(2)
    await wrapper.find('.pager__more').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.results__row').length).toBe(4)
    expect(wrapper.text()).toContain('1927A-p2')
    expect(wrapper.text()).toContain('已加载 4 / 共 4 条')
  })

  it('shows filtered empty state when a keyword yields no rows', async () => {
    handler = (url) => {
      if (url.startsWith('/api/platforms')) {
        return { status: 200, body: { platforms: [] } }
      }
      if (url.startsWith('/api/tags')) {
        return { status: 200, body: { tags: [] } }
      }
      if (url.startsWith('/api/search')) {
        return { status: 200, body: { offset: 0, limit: 50, total: 0, items: [], hasMore: false, tags: [] } }
      }
      return undefined
    }
    const wrapper = mount(SearchApp)
    await flushPromises()
    await wrapper.find('input[type="search"]').setValue('不存在')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.findAll('.results__row').length).toBe(0)
    expect(wrapper.text()).toContain('没有匹配结果')
  })

  it('shows browse-mode empty state when nothing is indexed', async () => {
    handler = (url) => {
      if (url.startsWith('/api/platforms')) {
        return { status: 200, body: { platforms: [] } }
      }
      if (url.startsWith('/api/tags')) {
        return { status: 200, body: { tags: [] } }
      }
      if (url.startsWith('/api/search')) {
        return { status: 200, body: { offset: 0, limit: 50, total: 0, items: [], hasMore: false, tags: [] } }
      }
      return undefined
    }
    const wrapper = mount(SearchApp)
    await flushPromises()

    expect(wrapper.text()).toContain('本地库还没有已缓存的题目')
  })

  it('shows error state with backend error code', async () => {
    handler = (url) => {
      if (url.startsWith('/api/platforms')) {
        return { status: 200, body: { platforms: [] } }
      }
      if (url.startsWith('/api/tags')) {
        return { status: 200, body: { tags: [] } }
      }
      if (url.startsWith('/api/search')) {
        return { status: 400, body: { error: 'bad-request', message: 'limit must be an integer' } }
      }
      return undefined
    }
    const wrapper = mount(SearchApp)
    await flushPromises()

    expect(wrapper.find('.state--error').text()).toContain('bad-request')
  })

  // -----------------------------------------------------------------
  // WP37:状态列与本地筛选
  // -----------------------------------------------------------------

  const STATUS_ITEMS = [
    { ...ITEMS[0], status: 'AC' },
    { ...ITEMS[1], status: 'TRIED' },
    { platform: 'leetcode', remoteId: 'fresh-one', title: 'Fresh', difficulty: 'Easy', url: 'u', tags: [], matchedSources: [] },
    { ...ITEMS[0], remoteId: 'locked-one', title: 'Locked', status: 'TRIED', paidOnly: true },
  ]

  it('renders the status icon column per result row', async () => {
    mockSearch(STATUS_ITEMS)
    const wrapper = mount(SearchApp)
    await flushPromises()

    const rows = wrapper.findAll('.results__row')
    expect(rows.length).toBe(4)
    expect(rows[0].find('.status-icon--ac').exists()).toBe(true)
    expect(rows[0].find('.status-icon').attributes('title')).toBe('已解决')
    expect(rows[1].find('.status-icon--tried').exists()).toBe(true)
    expect(rows[2].find('.status-icon--unsolved').exists()).toBe(true)
    expect(rows[2].find('.status-icon').attributes('title')).toBe('')
    expect(rows[3].find('.status-icon--locked').exists()).toBe(true)
    expect(rows[3].find('.status-icon--locked').attributes('title')).toBe('会员题(已锁定)')
  })

  it('filters loaded results by status locally without refetching', async () => {
    mockSearch(STATUS_ITEMS)
    const wrapper = mount(SearchApp)
    await flushPromises()
    const callsAfterLoad = searchCalls.length

    // 平台下拉为 select[0],状态下拉为 select[1]
    const statusSelect = wrapper.findAll('select')[1]
    await statusSelect.setValue('AC')
    expect(wrapper.findAll('.results__row').length).toBe(1)
    expect(wrapper.text()).toContain('1927A')

    await statusSelect.setValue('UNSOLVED')
    expect(wrapper.findAll('.results__row').length).toBe(1)
    expect(wrapper.text()).toContain('fresh-one')

    await statusSelect.setValue('')
    expect(wrapper.findAll('.results__row').length).toBe(4)

    // 本地过滤:筛选全程零新请求
    expect(searchCalls.length).toBe(callsAfterLoad)
  })

  it('notes the loaded-items-only scope while the status filter is active', async () => {
    mockSearch(STATUS_ITEMS)
    const wrapper = mount(SearchApp)
    await flushPromises()

    expect(wrapper.find('.filter-note').exists()).toBe(false)
    await wrapper.findAll('select')[1].setValue('TRIED')
    expect(wrapper.find('.filter-note').text()).toContain('已加载条目')
  })
})
