/**
 * highlight.js 按需封装(WP12):lib/core + 刷题常用语言按需注册,
 * 不引入全量语言包,也不使用外置主题 css(token 色由 theme.css 变量驱动)。
 */

import hljs from 'highlight.js/lib/core'
import cpp from 'highlight.js/lib/languages/cpp'
import java from 'highlight.js/lib/languages/java'
import kotlin from 'highlight.js/lib/languages/kotlin'
import python from 'highlight.js/lib/languages/python'
import rust from 'highlight.js/lib/languages/rust'
import go from 'highlight.js/lib/languages/go'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import csharp from 'highlight.js/lib/languages/csharp'

const LANGUAGES = {
  cpp,
  java,
  kotlin,
  python,
  rust,
  go,
  javascript,
  typescript,
  csharp,
} as const

for (const [name, definition] of Object.entries(LANGUAGES)) {
  hljs.registerLanguage(name, definition)
}

/** 常用别名(用户提交语言名可能是 c++ / py / kt 等)。 */
const ALIASES: Record<string, string> = {
  'c++': 'cpp',
  c: 'cpp',
  py: 'python',
  python3: 'python',
  kt: 'kotlin',
  js: 'javascript',
  ts: 'typescript',
  'c#': 'csharp',
  golang: 'go',
  rs: 'rust',
}

/** 已注册语言(含别名解析)。 */
export const SUPPORTED_LANGUAGES: readonly string[] = Object.keys(LANGUAGES)

function resolveLanguage(language?: string): string | undefined {
  if (!language) return undefined
  const lower = language.toLowerCase()
  const resolved = ALIASES[lower] ?? lower
  return hljs.getLanguage(resolved) ? resolved : undefined
}

/** 高亮一段代码,返回 HTML 字符串;未知语言降级为纯文本 HTML 转义。 */
export function highlightCode(code: string, language?: string): string {
  const resolved = resolveLanguage(language)
  if (resolved) {
    return hljs.highlight(code, { language: resolved }).value
  }
  return escapeHtml(code)
}

function escapeHtml(text: string): string {
  return text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}

/** 渲染到容器元素(便捷路径,输出包一层 pre>code.hljs)。 */
export function renderCode(el: HTMLElement | null, code: string, language?: string): void {
  if (!el) return
  const pre = document.createElement('pre')
  pre.className = 'hljs'
  const node = document.createElement('code')
  node.className = language ? `language-${language}` : ''
  node.innerHTML = highlightCode(code, language)
  pre.appendChild(node)
  el.replaceChildren(pre)
}
