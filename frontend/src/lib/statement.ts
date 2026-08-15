/**
 * 题面渲染管线(WP20):不可信题面原文 → 安全且增强的 HTML 字符串。
 *
 * HTML 管线(LeetCode):白名单清洗(sanitize.ts)→ pre>code 高亮 → 链接新窗口 → 表格包装。
 * MD 管线:marked 解析(GFM)→ 清洗 → 代码高亮 → 文本级 KaTeX($..$ / $$..$$,
 * 仅 MD 管线启用,避免 LC 正文里的货币符号误伤)→ 链接 → 表格包装。
 *
 * 顺序约定:先清洗后增强——增强产物(hljs/KaTeX 标记)是可信标记,不回清洗。
 */
import { marked } from 'marked'
import { highlightCode } from './highlight'
import { renderMathToString } from './katex-render'
import { sanitizeStatementHtml } from './sanitize'

export type StatementFormat = 'HTML' | 'MARKDOWN'

/** 行内 $...$ 与展示 $$...$$ 公式(仅 MARKDOWN 管线启用)。 */
const MATH_PATTERN = /\$\$([\s\S]+?)\$\$|\$([^$\n]+?)\$/g

const LANGUAGE_CLASS = /language-([\w+#.-]+)/

export function renderStatement(
  content: string,
  format: StatementFormat,
): string {
  const rawHtml = format === 'MARKDOWN' ? markdownToHtml(content) : content
  const root = document.createElement('div')
  root.innerHTML = sanitizeStatementHtml(rawHtml)
  enhanceCodeBlocks(root)
  if (format === 'MARKDOWN') {
    enhanceMath(root)
  }
  enhanceLinks(root)
  wrapTables(root)
  return root.innerHTML
}

function markdownToHtml(markdown: string): string {
  return marked.parse(markdown, {
    async: false,
    gfm: true,
    breaks: false,
  })
}

/** pre>code 代码块高亮(language-xxx 提示,未知语言降级转义)。 */
function enhanceCodeBlocks(root: HTMLElement): void {
  root.querySelectorAll('pre > code').forEach((code) => {
    const language = LANGUAGE_CLASS.exec(code.className)?.[1]
    code.innerHTML = highlightCode(code.textContent ?? '', language)
  })
}

/** 文本节点级 KaTeX 渲染(跳过 pre/code/a 内文本)。 */
function enhanceMath(root: HTMLElement): void {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node: Text) {
      return node.parentElement?.closest('pre, code, a')
        ? NodeFilter.FILTER_REJECT
        : NodeFilter.FILTER_ACCEPT
    },
  })
  const targets: Text[] = []
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    targets.push(node as Text)
  }
  targets.forEach(applyMath)
}

function applyMath(node: Text): void {
  const text = node.data
  MATH_PATTERN.lastIndex = 0
  if (!MATH_PATTERN.test(text)) return
  MATH_PATTERN.lastIndex = 0

  const fragment = document.createDocumentFragment()
  let cursor = 0
  for (const match of text.matchAll(MATH_PATTERN)) {
    const start = match.index ?? 0
    fragment.appendChild(document.createTextNode(text.slice(cursor, start)))
    const display = match[1] !== undefined
    const holder = document.createElement(display ? 'div' : 'span')
    holder.className = display ? 'statement-math' : 'statement-math-inline'
    holder.innerHTML = renderMathToString(match[1] ?? match[2] ?? '', display)
    fragment.appendChild(holder)
    cursor = start + match[0].length
  }
  fragment.appendChild(document.createTextNode(text.slice(cursor)))
  node.replaceWith(fragment)
}

/** 题面外链一律新窗口语义打开。 */
function enhanceLinks(root: HTMLElement): void {
  root.querySelectorAll('a[href]').forEach((link) => {
    link.setAttribute('target', '_blank')
    link.setAttribute('rel', 'noopener noreferrer')
  })
}

/** 表格包一层横向滚动容器(工具窗口宽度有限)。 */
function wrapTables(root: HTMLElement): void {
  root.querySelectorAll('table').forEach((table) => {
    if (table.parentElement?.classList.contains('statement-table')) return
    const wrap = document.createElement('div')
    wrap.className = 'statement-table'
    table.replaceWith(wrap)
    wrap.appendChild(table)
  })
}
