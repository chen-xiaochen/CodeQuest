/**
 * WP20:题面渲染管线——HTML(清洗+hljs)/ MD(marked+KaTeX+hljs+表格包装)。
 */
import { describe, expect, it } from 'vitest'
import { renderStatement } from '../../src/lib/statement'

function mountHtml(html: string): HTMLElement {
  const div = document.createElement('div')
  div.innerHTML = html
  return div
}

describe('renderStatement (HTML)', () => {
  const LC_SAMPLE = [
    '<p>Given an array of integers <code>nums</code> and an integer <code>target</code>.</p>',
    '<p><strong>Example 1:</strong></p>',
    '<pre><code class="language-kotlin">fun main() {\n    val n = readLine()!!.toInt()\n    println(n)\n}</code></pre>',
    '<ul><li><code>2 &lt;= nums.length</code></li></ul>',
    '<p><a href="https://leetcode.com/problems/two-sum/">source</a></p>',
  ].join('')

  it('renders LC HTML structure with sanitized markup', () => {
    const html = renderStatement(LC_SAMPLE, 'HTML')
    const root = mountHtml(html)
    expect(root.querySelector('p')?.textContent).toContain('Given an array')
    expect(root.querySelector('strong')?.textContent).toBe('Example 1:')
    expect(root.querySelector('ul li code')?.textContent).toContain('nums.length')
  })

  it('highlights pre>code blocks via hljs with token classes', () => {
    const html = renderStatement(LC_SAMPLE, 'HTML')
    const root = mountHtml(html)
    const code = root.querySelector('pre > code')
    expect(code).not.toBeNull()
    expect(code?.innerHTML).toContain('hljs-keyword')
  })

  it('keeps hostile markup out of the rendered output', () => {
    const html = renderStatement(`${LC_SAMPLE}<script>alert(1)</script><img src=x onerror=alert(2)>`, 'HTML')
    expect(html).not.toContain('script')
    expect(html).not.toContain('onerror')
  })

  it('opens external links in a new context (target/rel)', () => {
    const html = renderStatement(LC_SAMPLE, 'HTML')
    const link = mountHtml(html).querySelector('a[href="https://leetcode.com/problems/two-sum/"]')
    expect(link?.getAttribute('target')).toBe('_blank')
    expect(link?.getAttribute('rel')).toContain('noopener')
  })
})

describe('renderStatement (MARKDOWN)', () => {
  const MD_SAMPLE = [
    '## Trapping Rain Water',
    '',
    'Given `height` where inline math $a^2 + b^2 = c^2$ holds.',
    '',
    '$$\\int_0^1 x^2\\,dx = \\frac{1}{3}$$',
    '',
    '| Input | Output |',
    '| ----- | ------ |',
    '| [0,1] | 6      |',
    '',
    '```kotlin',
    'val answer = 0',
    '```',
    '',
    '[docs](https://example.com/docs)',
  ].join('\n')

  it('renders headings, inline markup and code fence with highlight', () => {
    const html = renderStatement(MD_SAMPLE, 'MARKDOWN')
    const root = mountHtml(html)
    expect(root.querySelector('h2')?.textContent).toBe('Trapping Rain Water')
    const code = root.querySelector('pre > code')
    expect(code?.innerHTML).toContain('hljs-keyword')
    expect(root.querySelector('code')?.textContent).toContain('height')
  })

  it('renders KaTeX for inline and display math', () => {
    const root = mountHtml(renderStatement(MD_SAMPLE, 'MARKDOWN'))
    expect(root.querySelectorAll('.katex').length).toBeGreaterThanOrEqual(2)
  })

  it('wraps tables for horizontal scrolling with a marker class', () => {
    const root = mountHtml(renderStatement(MD_SAMPLE, 'MARKDOWN'))
    const wrap = root.querySelector('.statement-table')
    expect(wrap?.querySelector('table')).not.toBeNull()
    expect(wrap?.querySelector('th')?.textContent).toContain('Input')
  })

  it('does not interpret dollar math in HTML statements', () => {
    const html = renderStatement('<p>costs $5 and $10</p>', 'HTML')
    expect(mountHtml(html).querySelectorAll('.katex').length).toBe(0)
  })
})
