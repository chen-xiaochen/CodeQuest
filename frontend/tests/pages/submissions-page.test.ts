/**
 * WP21:submissions 页真实列表(端点 mock)——行渲染/verdict 徽标/耗时内存格式化/
 * challengeId 过滤转发/分页追加/桥 `submission:updated` 推送刷新/错误态。
 * WP36:行点击详情面板——三件套/extras/message/viewUrl/展开互斥/缓存/错误态/加载态。
 * WP40:详情面板 Details/Code 切换——Code 态高亮代码块/空快照降级/tab 按行独立/
 * 未知语言纯文本降级/复制按钮。
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

// ---------------------------------------------------------------------------
// WP36:行点击详情面板(/api/submission/{id}/detail 端点 mock)
// ---------------------------------------------------------------------------

function detailBody(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    challengeId: 'c-1',
    challengeName: 'Two Sum',
    platformId: 'leetcode',
    remoteSubmissionId: `remote-${id}`,
    language: 'Kotlin',
    verdict: { type: 'WrongAnswer' },
    timeMs: 68,
    memoryKb: 41200,
    submittedAt: '2026-08-15T10:00:00Z',
    code: 'class Solution',
    message: 'Wrong Answer',
    errorCase: {
      input: 'nums = [2,7,11,15]\ntarget = 9',
      expectedOutput: '[0,1]',
      actualOutput: '[2,2]',
    },
    extras: { testcases: '12/14', runtimePercentile: '42.10%' },
    viewUrl: `https://leetcode.com/problems/two-sum/submissions/remote-${id}/`,
    ...overrides,
  }
}

function mockListAndDetail(
  items: Array<{ id: string; overrides?: Record<string, unknown> }>,
): void {
  const byId = new Map(items.map((item) => [item.id, detailBody(item.id, item.overrides)]))
  handler = (url) => {
    if (url.startsWith('/api/submissions')) {
      return {
        status: 200,
        body: {
          challengeId: null,
          offset: 0,
          limit: 50,
          items: items.map(({ id }) => submissionItem(id)),
          hasMore: false,
        },
      }
    }
    const match = url.match(/^\/api\/submission\/([^/]+)\/detail$/)
    if (match) {
      const body = byId.get(match[1])
      return body ? { status: 200, body } : { status: 404, body: { error: 'not-found' } }
    }
    return undefined
  }
}

describe('submissions detail panel (WP36)', () => {
  it('expands a detail panel with error case triplet, message, extras and view url', async () => {
    mockListAndDetail([{ id: 's-1' }])
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    expect(wrapper.find('.detail-row').exists()).toBe(false)
    await wrapper.find('.submissions__row').trigger('click')
    await flushPromises()

    expect(fetchCalls).toContain('/api/submission/s-1/detail')
    expect(wrapper.find('.detail-row').exists()).toBe(true)
    expect(wrapper.find('.detail__message').text()).toContain('Wrong Answer')

    // 三件套三段齐全,等宽 pre 承载内容
    const cases = wrapper.findAll('.detail__case')
    expect(cases.length).toBe(3)
    expect(cases[0].find('.detail__case-label').text()).toBe('Input')
    expect(cases[0].find('.detail__case-value').text()).toContain('nums = [2,7,11,15]')
    expect(cases[1].find('.detail__case-label').text()).toBe('Expected output')
    expect(cases[1].find('.detail__case-value').text()).toContain('[0,1]')
    expect(cases[2].find('.detail__case-label').text()).toBe('Actual output')
    expect(cases[2].find('.detail__case-value').text()).toContain('[2,2]')

    // extras 键值对 + 平台站外链 + 合并后的指标
    const extras = wrapper.find('.detail__extras').text()
    expect(extras).toContain('testcases')
    expect(extras).toContain('12/14')
    expect(wrapper.find('.detail__view-url').attributes('href')).toBe(
      'https://leetcode.com/problems/two-sum/submissions/remote-s-1/',
    )
    expect(wrapper.find('.detail__meta').text()).toContain('68 ms')
  })

  it('keeps expansion mutually exclusive, collapses on second click and caches by id', async () => {
    mockListAndDetail([{ id: 's-1' }, { id: 's-2' }])
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    const rows = wrapper.findAll('.submissions__row')
    await rows[0].trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.detail-row').length).toBe(1)

    // 展开另一行:互斥,旧面板收起(新面板数据指向 s-2 的远端提交)
    await rows[1].trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.detail-row').length).toBe(1)
    expect(wrapper.find('.detail__view-url').attributes('href')).toContain('remote-s-2')

    // 再点同一行:收起
    await wrapper.findAll('.submissions__row')[1].trigger('click')
    await flushPromises()
    expect(wrapper.find('.detail-row').exists()).toBe(false)

    // 重开第一行:按 id 缓存,不重复拉取
    const detailCallsBefore = fetchCalls.filter((url) => url.includes('/detail')).length
    await wrapper.findAll('.submissions__row')[0].trigger('click')
    await flushPromises()
    expect(wrapper.find('.detail-row').exists()).toBe(true)
    expect(fetchCalls.filter((url) => url.includes('/detail')).length).toBe(detailCallsBefore)
  })

  it('shows detail error state with backend error code and retries', async () => {
    mockListPage([submissionItem('s-1')])
    handler = (url) => {
      if (url.startsWith('/api/submissions')) {
        return { status: 200, body: { challengeId: null, offset: 0, limit: 50, items: [submissionItem('s-1')], hasMore: false } }
      }
      if (url === '/api/submission/s-1/detail') {
        return { status: 502, body: { error: 'oj-error', message: 'LeetCode 详情拉取失败' } }
      }
      return undefined
    }
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    await wrapper.find('.submissions__row').trigger('click')
    await flushPromises()

    const panel = wrapper.find('.detail-state--error')
    expect(panel.exists()).toBe(true)
    expect(panel.text()).toContain('oj-error')

    await panel.find('button').trigger('click')
    await flushPromises()
    expect(fetchCalls.filter((url) => url === '/api/submission/s-1/detail').length).toBe(2)
  })

  it('renders merged local fields only when platform detail has nothing extra', async () => {
    mockListAndDetail([
      {
        id: 's-1',
        overrides: {
          verdict: { type: 'Accepted' },
          timeMs: null,
          memoryKb: null,
          message: undefined,
          errorCase: null,
          extras: null,
          viewUrl: null,
        },
      },
    ])
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    await wrapper.find('.submissions__row').trigger('click')
    await flushPromises()

    // 本地兜底字段仍在;三件套/extras/外链缺失不渲染,也不视为错误
    expect(wrapper.find('.detail-state--error').exists()).toBe(false)
    expect(wrapper.find('.detail__case').exists()).toBe(false)
    expect(wrapper.find('.detail__extras').exists()).toBe(false)
    expect(wrapper.find('.detail__view-url').exists()).toBe(false)
    const meta = wrapper.find('.detail__meta').text()
    expect(meta).toContain('Accepted')
    expect(meta).toContain('Kotlin')
    expect(meta).toContain('—')
  })

  it('shows loading state while the detail request is in flight', async () => {
    mockListPage([submissionItem('s-1')])
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        if (url === '/api/submission/s-1/detail') {
          await gate
          return jsonResponse(200, detailBody('s-1'))
        }
        return jsonResponse(200, {
          challengeId: null,
          offset: 0,
          limit: 50,
          items: [submissionItem('s-1')],
          hasMore: false,
        })
      }),
    )

    await wrapper.find('.submissions__row').trigger('click')
    await flushPromises()
    expect(wrapper.find('.detail-state').text()).toContain('Loading submission details…')
    expect(wrapper.find('.detail__case').exists()).toBe(false)

    release()
    await flushPromises()
    expect(wrapper.find('.detail__case').exists()).toBe(true)
  })
})

// ---------------------------------------------------------------------------
// WP40:详情面板 Details/Code 切换(本地 codeSnapshot 回看,零新端点)
// ---------------------------------------------------------------------------

const KOTLIN_SNIPPET = 'fun main() {\n    println(42)\n}'

function codeTabButton(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('.detail__tab').find((button) => button.text() === 'Code')!
}

describe('submissions detail panel code view (WP40)', () => {
  it('defaults to the Details tab and switches to a highlighted code block on Code', async () => {
    mockListAndDetail([{ id: 's-1', overrides: { code: KOTLIN_SNIPPET, language: 'Kotlin' } }])
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    await wrapper.find('.submissions__row').trigger('click')
    await flushPromises()

    // 默认 Details 态:meta 可见,代码块未渲染
    expect(wrapper.find('.detail__meta').exists()).toBe(true)
    expect(wrapper.find('.detail__code').exists()).toBe(false)

    await codeTabButton(wrapper).trigger('click')

    // Code 态:高亮代码块 pre>code.hljs,kotlin 注册语言产生 token span
    expect(wrapper.find('.detail__meta').exists()).toBe(false)
    const code = wrapper.find('.detail__code code.hljs')
    expect(code.exists()).toBe(true)
    expect(code.text()).toContain('fun main()')
    expect(code.element.innerHTML).toContain('hljs-keyword')
    expect(codeTabButton(wrapper).attributes('aria-selected')).toBe('true')
  })

  it('shows a placeholder when the code snapshot is empty', async () => {
    mockListAndDetail([{ id: 's-1', overrides: { code: '   ' } }])
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    await wrapper.find('.submissions__row').trigger('click')
    await flushPromises()
    await codeTabButton(wrapper).trigger('click')

    expect(wrapper.find('.detail__code').exists()).toBe(false)
    expect(wrapper.find('.detail__code-empty').text()).toContain(
      'No code snapshot stored for this submission',
    )
  })

  it('keeps tab state per row and restores it when a panel is reopened', async () => {
    mockListAndDetail([
      { id: 's-1', overrides: { code: KOTLIN_SNIPPET } },
      { id: 's-2', overrides: { code: KOTLIN_SNIPPET } },
    ])
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    const rows = wrapper.findAll('.submissions__row')
    await rows[0].trigger('click')
    await flushPromises()
    await codeTabButton(wrapper).trigger('click')
    expect(wrapper.find('.detail__code').exists()).toBe(true)

    // 收起 s-1,展开 s-2:s-2 不受 s-1 的 Code 态影响,默认 Details
    await rows[0].trigger('click')
    await wrapper.findAll('.submissions__row')[1].trigger('click')
    await flushPromises()
    expect(wrapper.find('.detail__meta').exists()).toBe(true)
    expect(wrapper.find('.detail__code').exists()).toBe(false)

    // 收起 s-2,重开 s-1:保持上次选择的 Code 态
    await wrapper.findAll('.submissions__row')[1].trigger('click')
    await wrapper.findAll('.submissions__row')[0].trigger('click')
    await flushPromises()
    expect(wrapper.find('.detail__code').exists()).toBe(true)
  })

  it('falls back to plain text for unregistered languages', async () => {
    mockListAndDetail([{ id: 's-1', overrides: { code: '++++++++[>++++<-]>', language: 'Brainfuck' } }])
    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    await wrapper.find('.submissions__row').trigger('click')
    await flushPromises()
    await codeTabButton(wrapper).trigger('click')

    // 仍渲染在 pre>code.hljs 中,但无 hljs token span(纯文本转义降级)
    const code = wrapper.find('.detail__code code.hljs')
    expect(code.exists()).toBe(true)
    expect(code.text()).toContain('++++++++[>++++<-]>')
    expect(code.element.innerHTML).not.toContain('hljs-')
  })

  it('copies the snapshot to the clipboard from the Code tab', async () => {
    mockListAndDetail([{ id: 's-1', overrides: { code: KOTLIN_SNIPPET } }])
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    })

    const wrapper = mount(SubmissionsApp)
    await flushPromises()

    await wrapper.find('.submissions__row').trigger('click')
    await flushPromises()
    await codeTabButton(wrapper).trigger('click')

    await wrapper.find('.detail__copy').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith(KOTLIN_SNIPPET)
    expect(wrapper.find('.detail__copy').text()).toBe('Copied')
  })
})
