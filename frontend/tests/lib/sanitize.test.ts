/**
 * WP20:题面 HTML 清洗(XSS 基本盘)——白名单标签/属性、script 移除、
 * on* 事件属性移除、javascript: 协议拦截。
 */
import { describe, expect, it } from 'vitest'
import { sanitizeStatementHtml } from '../../src/lib/sanitize'

describe('sanitizeStatementHtml', () => {
  it('keeps whitelisted structural markup intact', () => {
    const html =
      '<p>hello <strong>bold</strong> <em>italic</em></p>' +
      '<ul><li>one</li><li>two</li></ul>' +
      '<table><thead><tr><th>a</th></tr></thead><tbody><tr><td>1</td></tr></tbody></table>'
    expect(sanitizeStatementHtml(html)).toContain('<strong>bold</strong>')
    expect(sanitizeStatementHtml(html)).toContain('<li>two</li>')
    expect(sanitizeStatementHtml(html)).toContain('<table>')
    expect(sanitizeStatementHtml(html)).toContain('<td>1</td>')
  })

  it('removes script elements entirely (including content)', () => {
    const html = '<p>ok</p><script>alert(1)</script><p>after</p>'
    const clean = sanitizeStatementHtml(html)
    expect(clean).not.toContain('script')
    expect(clean).not.toContain('alert(1)')
    expect(clean).toContain('<p>ok</p>')
    expect(clean).toContain('<p>after</p>')
  })

  it('removes style, iframe, object and embed elements', () => {
    const html =
      '<style>body{}</style><iframe src="https://evil"></iframe>' +
      '<object data="https://evil"></object><embed src="https://evil">' +
      '<p>text</p>'
    const clean = sanitizeStatementHtml(html)
    expect(clean).not.toContain('iframe')
    expect(clean).not.toContain('object')
    expect(clean).not.toContain('embed')
    expect(clean).not.toContain('<style')
    expect(clean).toContain('<p>text</p>')
  })

  it('strips inline event handler attributes like onerror/onclick/onmouseover', () => {
    const html =
      '<img src="https://ok.example/x.png" alt="x" onerror="alert(1)">' +
      '<p onclick="alert(2)">para</p>' +
      '<a href="https://ok.example/" onclick="steal()">link</a>'
    const clean = sanitizeStatementHtml(html)
    expect(clean).not.toContain('onerror')
    expect(clean).not.toContain('onclick')
    expect(clean).toContain('<img src="https://ok.example/x.png"')
    expect(clean).toContain('alt="x"')
    expect(clean).toContain('<a href="https://ok.example/">link</a>')
  })

  it('blocks javascript: and data: urls but keeps http/https/mailto hrefs', () => {
    const html =
      '<a href="javascript:alert(1)">evil</a>' +
      '<a href="JaVaScRiPt:alert(2)">sneaky</a>' +
      '<a href="https://example.com/">good</a>' +
      '<a href="mailto:a@b.c">mail</a>'
    const clean = sanitizeStatementHtml(html)
    expect(clean).not.toContain('javascript:')
    expect(clean.toLowerCase()).not.toContain('javasc' + 'ript:')
    expect(clean).toContain('<a href="https://example.com/">good</a>')
    expect(clean).toContain('mail')
  })

  it('blocks non-http image sources', () => {
    const clean = sanitizeStatementHtml('<img src="data:image/png;base64,xxxx" alt="d">')
    expect(clean).not.toContain('data:image')
  })

  it('unwraps unknown tags but keeps their inner content', () => {
    const html = '<article><section><p>inside</p></section></article>'
    const clean = sanitizeStatementHtml(html)
    expect(clean).toContain('<p>inside</p>')
    expect(clean).not.toContain('<article')
    expect(clean).not.toContain('<section')
  })

  it('drops disallowed attributes such as style and id', () => {
    const clean = sanitizeStatementHtml('<p style="color:red" id="p1" class="c">t</p>')
    expect(clean).not.toContain('style=')
    expect(clean).not.toContain('id=')
    // class 不在全局白名单(仅 code/pre 允许 language-*)
    expect(clean).not.toContain('class=')
  })

  it('keeps language class on code/pre for highlight hints', () => {
    const clean = sanitizeStatementHtml('<pre><code class="language-kotlin">val x = 1</code></pre>')
    expect(clean).toContain('class="language-kotlin"')
  })

  it('removes html comments', () => {
    const clean = sanitizeStatementHtml('<p>a</p><!-- secret --><p>b</p>')
    expect(clean).not.toContain('secret')
  })
})
