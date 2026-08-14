/**
 * highlight.ts / katex-render.ts 封装可用性测试:
 * 按需注册的语言可高亮,KaTeX 本地渲染产出公式标记。
 */
import { describe, expect, it } from 'vitest'
import { highlightCode, SUPPORTED_LANGUAGES } from '../../src/lib/highlight'
import { renderMathToString } from '../../src/lib/katex-render'

describe('highlight.ts', () => {
  it('按需注册的语言都能产出高亮 HTML', () => {
    for (const language of ['cpp', 'java', 'kotlin', 'python', 'rust', 'go', 'javascript', 'typescript', 'csharp']) {
      expect(SUPPORTED_LANGUAGES).toContain(language)
      const html = highlightCode('int main() { return 0; }', language)
      expect(html).toContain('hljs-keyword')
    }
  })

  it('未知语言降级为纯文本转义', () => {
    const html = highlightCode('<script>', 'no-such-lang')
    expect(html).not.toContain('<script>')
    expect(html).toContain('&lt;script&gt;')
  })
})

describe('katex-render.ts', () => {
  it('行内公式渲染为 katex 标记', () => {
    const html = renderMathToString('a^2 + b^2 = c^2', false)
    expect(html).toContain('katex')
  })

  it('display 模式渲染 display 标记', () => {
    const html = renderMathToString('\\int_0^1 x\\,dx', true)
    expect(html).toContain('katex-display')
  })
})
