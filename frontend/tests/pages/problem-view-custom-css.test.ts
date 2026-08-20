/**
 * WP39:题面页自定义 CSS 注入——加载后 fetch `/api/custom.css`,
 * 非空则把 `<style data-cq-custom-css>` 注入 document.head(空/失败静默跳过,
 * 不影响题面渲染);卸载时移除注入节点。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ProblemViewApp from '../../src/pages/problem-view/App.vue'
import { resetGlobalBridgeHook } from '../../src/bridge/cq-bridge'

const CUSTOM_CSS = '.statement { font-size: 15px; }'

const DETAIL = {
  platform: 'luogu',
  remoteId: 'P1001',
  title: 'A+B Problem',
  difficulty: '入门',
  url: 'https://www.luogu.com.cn/problem/P1001',
  content: '# P1001 A+B Problem',
  contentFormat: 'MARKDOWN',
  source: 'remote',
}

type CssHandler = (url: string) => Response | undefined

let cssHandler: CssHandler = () => undefined

beforeEach(() => {
  cssHandler = () => undefined
  document.querySelectorAll('style[data-cq-custom-css]').forEach((el) => el.remove())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/custom.css') {
        const response = cssHandler(url)
        if (response) return response
      }
      if (url.startsWith('/api/problem/')) {
        const body =
          url.includes('/solutions') && !url.includes('/solutions/')
            ? { platform: 'luogu', remoteId: 'P1001', items: [] }
            : DETAIL
        return new Response(JSON.stringify(body), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response(JSON.stringify({ error: 'not-mocked' }), { status: 404 })
    }),
  )
  window.history.replaceState({}, '', '/problem-view/?platform=luogu&remoteId=P1001')
})

afterEach(() => {
  vi.unstubAllGlobals()
  delete window.cqSend
  window.__cqBridge = undefined
  resetGlobalBridgeHook()
  document.querySelectorAll('style[data-cq-custom-css]').forEach((el) => el.remove())
})

function cssResponse(css: string): Response {
  return new Response(css, { status: 200, headers: { 'Content-Type': 'text/css' } })
}

function customStyle(): HTMLStyleElement | null {
  return document.querySelector('style[data-cq-custom-css]')
}

describe('problem-view custom CSS (WP39)', () => {
  it('injects style element with fetched css on mount', async () => {
    cssHandler = () => cssResponse(CUSTOM_CSS)

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    const style = customStyle()
    expect(style).not.toBeNull()
    expect(style?.textContent).toBe(CUSTOM_CSS)
    // 题面主体不受影响
    expect(wrapper.find('.statement').text()).toContain('A+B Problem')
  })

  it('skips injection when css is empty or whitespace', async () => {
    cssHandler = () => cssResponse('   ')

    mount(ProblemViewApp)
    await flushPromises()

    expect(customStyle()).toBeNull()
  })

  it('silently skips injection when endpoint fails', async () => {
    cssHandler = () => new Response('boom', { status: 500 })

    const wrapper = mount(ProblemViewApp)
    await flushPromises()

    expect(customStyle()).toBeNull()
    expect(wrapper.find('.statement').text()).toContain('A+B Problem')
  })

  it('removes injected style on unmount', async () => {
    cssHandler = () => cssResponse(CUSTOM_CSS)

    const wrapper = mount(ProblemViewApp)
    await flushPromises()
    expect(customStyle()).not.toBeNull()

    wrapper.unmount()
    expect(customStyle()).toBeNull()
  })
})
