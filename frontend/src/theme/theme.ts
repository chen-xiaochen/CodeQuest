/**
 * 主题参数解析与切换(WP12)。
 *
 * 优先级:URL query `?theme=dark|light` > localStorage(`cq-theme`)>
 * 系统偏好(prefers-color-scheme)。
 *
 * - query 参数出现即视为显式选择,同步持久化到 localStorage
 *   (Kotlin 侧后续也可通过加载 URL 注入主题参数)
 * - 结果落到 `<html data-theme="...">`,theme.css 据此切换两套变量
 */

import './theme.css'

export type ThemeName = 'light' | 'dark'

const STORAGE_KEY = 'cq-theme'
const QUERY_KEY = 'theme'

function normalize(value: string | null): ThemeName | undefined {
  return value === 'dark' || value === 'light' ? value : undefined
}

/** 按优先级解析当前应使用的主题。 */
export function resolveTheme(): ThemeName {
  const fromQuery = normalize(new URLSearchParams(window.location.search).get(QUERY_KEY))
  if (fromQuery) return fromQuery

  const fromStorage = normalize(localStorage.getItem(STORAGE_KEY))
  if (fromStorage) return fromStorage

  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

/** 应用主题到 <html data-theme> 并持久化。 */
export function applyTheme(theme: ThemeName): void {
  document.documentElement.dataset.theme = theme
  try {
    localStorage.setItem(STORAGE_KEY, theme)
  } catch {
    // 隐私模式等 localStorage 不可用场景:仅影响记忆,不影响本次切换
  }
}

/** 页面入口调用:解析 + 应用,返回所用主题。 */
export function initTheme(): ThemeName {
  const theme = resolveTheme()
  applyTheme(theme)
  return theme
}
