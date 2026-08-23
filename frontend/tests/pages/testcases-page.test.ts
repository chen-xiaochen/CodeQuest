/**
 * 1.5.0:testcases 页(右窗「测试用例」tab)——URL challengeId 拉列表/来源徽标/
 * 行内编辑 CRUD 调用/云端测试结果区三态(空/汇总表格/CE 全文)/桥推送采纳新挑战。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import TestCasesApp from '../../src/pages/testcases/App.vue'
import { TOPIC_TESTRUN_RESULT, resetGlobalBridgeHook } from '../../src/bridge/cq-bridge'

interface CaseRow {
  id: string
  input: string
  expected: string
  source: 'PLATFORM' | 'USER'
}

let items: CaseRow[] = []
let posted: Array<Record<string, unknown>> = []
let deleted: string[] = []

let activeWrapper: { unmount: () => void } | null = null

afterEach(() => {
  activeWrapper?.unmount()
  activeWrapper = null
  resetGlobalBridgeHook()
  window.__cqBridge = undefined // 旧单例 emit 闭包与新实例断链,清钩子重装
  vi.unstubAllGlobals()
  items = []
  posted = []
  deleted = []
})

function stubFetch(): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/api/testcases') && (!init || init.method === undefined || init.method === 'GET')) {
        return new Response(
          JSON.stringify({ challengeId: 'c-1', challengeName: '两数之和', items }),
          { status: 200 },
        )
      }
      if (init?.method === 'POST' && url.includes('/api/testcases/update')) {
        const body = JSON.parse(String(init.body)) as Record<string, unknown>
        const hit = items.find((it) => it.id === body.id)
        if (hit) {
          hit.input = String(body.input)
          hit.expected = String(body.expected)
        }
        return new Response(JSON.stringify({ ok: true }), { status: 200 })
      }
      if (init?.method === 'POST' && url.includes('/api/testcases')) {
        posted.push(JSON.parse(String(init.body)) as Record<string, unknown>)
        return new Response(JSON.stringify({ ok: true }), { status: 200 })
      }
      if (init?.method === 'DELETE') {
        deleted.push(new URL(url, 'http://localhost').searchParams.get('id') ?? '')
        return new Response(JSON.stringify({ ok: true }), { status: 200 })
      }
      return new Response(JSON.stringify({ error: 'not-found' }), { status: 404 })
    }),
  )
}

async function mountWithChallenge(): Promise<ReturnType<typeof mount>> {
  window.history.replaceState({}, '', '/testcases/?challengeId=c-1')
  stubFetch()
  const wrapper = mount(TestCasesApp)
  activeWrapper = wrapper
  await flushPromises()
  return wrapper
}

describe('testcases page', () => {
  it('renders empty state when no challenge is selected', async () => {
    window.history.replaceState({}, '', '/testcases/')
    const wrapper = mount(TestCasesApp)
    await flushPromises()

    expect(wrapper.text()).toContain('先在「题面」页打开题目')
  })

  it('loads list with source badges and challenge name', async () => {
    items = [
      { id: 't-1', input: '[2,7,11,15]\n9', expected: '', source: 'PLATFORM' },
      { id: 't-2', input: '1 2 3', expected: '6', source: 'USER' },
    ]
    const wrapper = await mountWithChallenge()

    expect(wrapper.text()).toContain('两数之和')
    expect(wrapper.findAll('.case__summary')).toHaveLength(2)
    expect(wrapper.find('.badge--platform').text()).toBe('平台样例')
    expect(wrapper.find('.badge--user').text()).toBe('手动')
  })

  it('creates a case via editor and reloads', async () => {
    const wrapper = await mountWithChallenge()
    await wrapper.find('.toolbar__add').trigger('click')

    const draft = wrapper.find('textarea')
    await draft.setValue('3 4')
    await wrapper.find('.btn--primary').trigger('click')
    await flushPromises()

    expect(posted).toHaveLength(1)
    expect(posted[0]).toMatchObject({ challengeId: 'c-1', input: '3 4' })
  })

  it('updates an existing case through inline editor', async () => {
    items = [{ id: 't-1', input: 'old', expected: 'x', source: 'USER' }]
    const wrapper = await mountWithChallenge()

    await wrapper.find('.case__summary').trigger('click')
    const editor = wrapper.findAll('textarea')
    await editor[0]!.setValue('new input')
    await wrapper.find('.btn--primary').trigger('click')
    await flushPromises()

    expect(items[0]!.input).toBe('new input')
  })

  it('deletes a case from inline editor', async () => {
    items = [{ id: 't-9', input: 'a', expected: 'b', source: 'USER' }]
    const wrapper = await mountWithChallenge()

    await wrapper.find('.case__summary').trigger('click')
    await wrapper.find('.btn--danger').trigger('click')
    await flushPromises()

    expect(deleted).toEqual(['t-9'])
  })

  it('shows placeholder before first cloud test result', async () => {
    const wrapper = await mountWithChallenge()

    expect(wrapper.find('.results').text()).toContain('提交测试')
    expect(wrapper.find('.results__table').exists()).toBe(false)
  })

  it('renders pushed summary table from testrun:result bridge event', async () => {
    const wrapper = await mountWithChallenge()

    window.__cqBridge?.emit(
      JSON.stringify({
        topic: TOPIC_TESTRUN_RESULT,
        payload: JSON.stringify({
          challengeId: 'c-1',
          passed: 1,
          total: 2,
          rows: [
            { input: '1', expected: '2', actual: '2', passed: true },
            { input: '3', expected: '4', actual: '5', passed: false },
          ],
        }),
      }),
    )
    await flushPromises()

    expect(wrapper.find('.results__summary').text()).toContain('1/2')
    expect(wrapper.findAll('.results__table tbody tr')).toHaveLength(2)
    expect(wrapper.find('.mark--pass').text()).toBe('通过')
    expect(wrapper.find('.mark--fail').text()).toBe('失败')
  })

  it('renders compile error text instead of table when errorText pushed', async () => {
    const wrapper = await mountWithChallenge()

    window.__cqBridge?.emit(
      JSON.stringify({
        topic: TOPIC_TESTRUN_RESULT,
        payload: JSON.stringify({
          challengeId: 'c-1',
          passed: 0,
          total: 0,
          rows: [],
          errorKind: 'compile',
          errorText: 'Line 3: expectation failed',
        }),
      }),
    )
    await flushPromises()

    expect(wrapper.find('.results__error').text()).toContain('Line 3: expectation failed')
    expect(wrapper.find('.results__table').exists()).toBe(false)
    expect(wrapper.text()).toContain('编译错误')
  })
})
