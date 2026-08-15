/**
 * WP20:problem-view 页真实题面渲染(端点 mock)——参数解析/拉取/清洗+高亮/空态。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProblemViewApp from '../../src/pages/problem-view/App.vue'

const DETAIL_BODY = {
  platform: 'leetcode',
  remoteId: 'two-sum',
  title: 'Two Sum',
  difficulty: 'Easy',
  url: 'https://leetcode.com/problems/two-sum/',
  content:
    '<p>Given an array <code>nums</code>.</p>' +
    '<pre><code class="language-kotlin">fun main() {\n    val n = 1\n}</code></pre>' +
    '<script>alert(1)</script>',
  contentFormat: 'HTML',
  source: 'cache',
}

afterEach(() => {
  vi.unstubAllGlobals()
})

function stubFetch(): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(JSON.stringify(DETAIL_BODY), { status: 200 })),
  )
}

describe('problem-view page', () => {
  it('renders statement from query params with sanitized highlighted content', async () => {
    window.history.replaceState({}, '', '/problem-view/?platform=leetcode&remoteId=two-sum')
    stubFetch()
    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    expect(wrapper.find('.statement-header__title').text()).toContain('Two Sum')
    expect(wrapper.find('.diff-badge--easy').exists()).toBe(true)
    const statement = wrapper.find('.statement')
    expect(statement.exists()).toBe(true)
    expect(statement.find('p').text()).toContain('Given an array')
    expect(statement.find('pre > code').html()).toContain('hljs-keyword')
    expect(statement.html()).not.toContain('script')
  })

  it('shows empty state when no problem is selected', async () => {
    window.history.replaceState({}, '', '/problem-view/')
    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    expect(wrapper.text()).toContain('No problem selected')
  })

  it('shows error state and retry on endpoint failure', async () => {
    window.history.replaceState({}, '', '/problem-view/?platform=leetcode&remoteId=two-sum')
    let failing = true
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        failing
          ? new Response(JSON.stringify({ error: 'auth-expired', message: 'login required' }), { status: 401 })
          : new Response(JSON.stringify(DETAIL_BODY), { status: 200 }),
      ),
    )
    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    expect(wrapper.find('.state--error').text()).toContain('auth-expired')

    failing = false
    await wrapper.find('.state__retry').trigger('click')
    await flushPromises()

    expect(wrapper.find('.statement').exists()).toBe(true)
  })
})
