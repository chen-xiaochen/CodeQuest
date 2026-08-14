/**
 * theme.ts:主题参数从 URL query / localStorage 读取,落到 <html data-theme>。
 */
import { afterEach, describe, expect, it } from 'vitest'
import { applyTheme, initTheme, resolveTheme, type ThemeName } from '../../src/theme/theme'

function setUrl(query: string) {
  window.history.replaceState(null, '', query)
}

describe('theme', () => {
  afterEach(() => {
    localStorage.clear()
    setUrl('/')
    document.documentElement.removeAttribute('data-theme')
  })

  it('URL query 最高优先', () => {
    setUrl('/problems/?theme=dark')
    localStorage.setItem('cq-theme', 'light')
    expect(resolveTheme()).toBe<ThemeName>('dark')
  })

  it('无 query 时读 localStorage', () => {
    setUrl('/')
    localStorage.setItem('cq-theme', 'dark')
    expect(resolveTheme()).toBe<ThemeName>('dark')
  })

  it('applyTheme 写 data-theme 并持久化', () => {
    applyTheme('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem('cq-theme')).toBe('dark')
  })

  it('initTheme 返回并应用主题', () => {
    setUrl('/?theme=light')
    localStorage.setItem('cq-theme', 'dark')
    expect(initTheme()).toBe<ThemeName>('light')
    expect(document.documentElement.dataset.theme).toBe('light')
  })
})
