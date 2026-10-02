/**
 * WP65(1.7.0 F4):题面页题目计时器——header 角标渲染/计时起表(题面数据到达)、
 * `submission:updated` AC 命中当前题停表定格 + toast「本题耗时」、
 * 不匹配/非 AC 不停表、开关(localStorage `cq-problem-timer-enabled`)关闭
 * 不渲染角标也不起表。
 *
 * 计时跨秒断言用真实时钟等待 ~1.1s(角标 500ms tick),文本比较取停表前
 * 读数做基线,不硬编码秒数,避免慢机抖动。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProblemViewApp from '../../src/pages/problem-view/App.vue'
import { TOPIC_AUTH_LOGIN_RESULT, TOPIC_SUBMISSION_UPDATED, resetGlobalBridgeHook } from '../../src/bridge/cq-bridge'

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

const DETAIL = {
  platform: 'leetcode',
  remoteId: 'two-sum',
  title: 'Two Sum',
  difficulty: 'easy',
  url: 'https://leetcode.com/problems/two-sum/',
  content: '# Two Sum\n\n## Description\n\nReturn indices.',
  contentFormat: 'MARKDOWN',
  source: 'remote',
}

function acceptedPayload(overrides: Record<string, unknown> = {}) {
  return {
    challengeId: 'ch-1',
    submissionId: 'sub-1',
    challengeName: 'Two Sum',
    platformId: 'leetcode',
    problemRemoteId: 'two-sum',
    verdict: { type: 'Accepted' },
    ...overrides,
  }
}

function emitSubmissionUpdated(payload: unknown): void {
  window.__cqBridge!.emit({
    topic: TOPIC_SUBMISSION_UPDATED,
    payload: JSON.stringify(payload),
  })
}

/** 真实时钟等待(ms),让角标 tick 跨过秒边界。 */
function wait(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

beforeEach(() => {
  handler = () => undefined
  localStorage.clear()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      const result = handler(url)
      return result ? jsonResponse(result.status, result.body) : jsonResponse(404, { error: 'not-mocked' })
    }),
  )
  window.history.replaceState({}, '', '/problem-view/?platform=leetcode&remoteId=two-sum')
})

afterEach(() => {
  vi.unstubAllGlobals()
  delete window.cqSend
  window.__cqBridge = undefined
  resetGlobalBridgeHook()
  localStorage.clear()
})

describe('problem-view timer (WP65)', () => {
  it('renders badge, ticks after problem data load and resets on reload', async () => {
    handler = (url) => {
      if (url.startsWith('/api/problem/two-sum') && !url.includes('/solutions')) {
        return { status: 200, body: DETAIL }
      }
      if (url.includes('/solutions')) {
        return { status: 200, body: { items: [] } }
      }
      return undefined
    }

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    // 题面数据到达即起表:角标出现,从 00:00 起跳
    const badge = wrapper.find('.statement-header__timer')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('00:00')

    await wait(1100)
    expect(badge.text()).not.toBe('00:00')

    // 同平台登录成功 → 重拉题面 → 收到题面数据即重置起表
    // (重拉走 loading 分支,header 重建,须重新查询角标节点)
    window.__cqBridge!.emit({
      topic: TOPIC_AUTH_LOGIN_RESULT,
      payload: JSON.stringify({ ok: true, platform: 'leetcode' }),
    })
    await flushPromises()
    expect(wrapper.find('.statement-header__timer').text()).toBe('00:00')
  })

  it('stops and freezes with toast on accepted submission for current problem', async () => {
    handler = (url) => {
      if (url.startsWith('/api/problem/two-sum') && !url.includes('/solutions')) {
        return { status: 200, body: DETAIL }
      }
      if (url.includes('/solutions')) {
        return { status: 200, body: { items: [] } }
      }
      return undefined
    }

    const wrapper = mount(ProblemViewApp)
    await flushPromises()
    await wait(1100)
    const badge = wrapper.find('.statement-header__timer')
    const frozenAt = badge.text()
    expect(frozenAt).not.toBe('00:00')

    emitSubmissionUpdated(acceptedPayload())
    await flushPromises()

    // 停表定格 + toast「本题耗时」
    expect(wrapper.find('.timer-toast').text()).toBe(`本题耗时 ${frozenAt}`)

    // 已停表:时钟继续走,角标定格不变;重复 AC 事件无副作用
    await wait(1100)
    expect(badge.text()).toBe(frozenAt)
    emitSubmissionUpdated(acceptedPayload())
    await flushPromises()
    expect(wrapper.find('.timer-toast').text()).toBe(`本题耗时 ${frozenAt}`)
  })

  it('ignores accepted submissions for other problems', async () => {
    handler = (url) => {
      if (url.startsWith('/api/problem/two-sum') && !url.includes('/solutions')) {
        return { status: 200, body: DETAIL }
      }
      if (url.includes('/solutions')) {
        return { status: 200, body: { items: [] } }
      }
      return undefined
    }

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    emitSubmissionUpdated(acceptedPayload({ platformId: 'codeforces' }))
    emitSubmissionUpdated(acceptedPayload({ problemRemoteId: 'add-two-numbers' }))
    await flushPromises()

    // 不匹配当前题:不停表、无 toast,角标照常计时
    expect(wrapper.find('.timer-toast').exists()).toBe(false)
    expect(wrapper.find('.statement-header__timer').exists()).toBe(true)
  })

  it('ignores non-accepted verdicts for current problem', async () => {
    handler = (url) => {
      if (url.startsWith('/api/problem/two-sum') && !url.includes('/solutions')) {
        return { status: 200, body: DETAIL }
      }
      if (url.includes('/solutions')) {
        return { status: 200, body: { items: [] } }
      }
      return undefined
    }

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    emitSubmissionUpdated(acceptedPayload({ verdict: { type: 'WrongAnswer' } }))
    emitSubmissionUpdated(acceptedPayload({ verdict: { type: 'Pending' } }))
    await flushPromises()

    expect(wrapper.find('.timer-toast').exists()).toBe(false)
    expect(wrapper.find('.statement-header__timer').exists()).toBe(true)
  })

  it('hides badge and never starts when disabled by localStorage flag', async () => {
    localStorage.setItem('cq-problem-timer-enabled', '0')
    handler = (url) => {
      if (url.startsWith('/api/problem/two-sum') && !url.includes('/solutions')) {
        return { status: 200, body: DETAIL }
      }
      if (url.includes('/solutions')) {
        return { status: 200, body: { items: [] } }
      }
      return undefined
    }

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    // 关:角标不渲染
    expect(wrapper.find('.statement-header__timer').exists()).toBe(false)

    // 关:AC 命中也不起表/不停表(未起表,无 toast)
    emitSubmissionUpdated(acceptedPayload())
    await flushPromises()
    expect(wrapper.find('.timer-toast').exists()).toBe(false)
  })
})
