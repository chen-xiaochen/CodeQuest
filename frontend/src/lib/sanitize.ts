/**
 * 题面 HTML 白名单清洗(WP20)。
 *
 * OJ 题面(LeetCode content 等)是不可信 HTML,进入 v-html 前必须过本清洗:
 * - 标签白名单:常见结构标签保留;script/style/iframe/object/embed 等整体删除;
 *   其余未知标签「解包」(保留内部内容,去掉标签本身)
 * - 属性白名单:默认无属性;code/pre 允许 class(language-* 提示);
 *   a 允许 href/title、img 允许 src/alt/width/height;on* 事件属性一概不在白名单
 * - URL 协议白名单:链接 http/https/mailto、图片 http/https;拦截 javascript:/data:
 * - 注释节点全部移除
 *
 * 单测锁定 XSS 基本盘(tests/lib/sanitize.test.ts)。
 */

/** 保留的结构标签。 */
const ALLOWED_TAGS = new Set([
  'a', 'abbr', 'b', 'blockquote', 'br', 'code', 'dd', 'del', 'details', 'div',
  'dl', 'dt', 'em', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'hr', 'i', 'img', 'ins',
  'kbd', 'li', 'mark', 'ol', 'p', 'pre', 'q', 's', 'samp', 'small', 'span',
  'strong', 'sub', 'sup', 'table', 'tbody', 'td', 'tfoot', 'th', 'thead', 'tr',
  'u', 'ul',
])

/** 连内容一起删除的标签(其文本不是正文)。 */
const DROP_WITH_CONTENT = new Set([
  'script', 'style', 'iframe', 'object', 'embed', 'link', 'meta', 'base',
  'form', 'input', 'button', 'textarea', 'select', 'option', 'video', 'audio',
  'canvas', 'svg', 'math', 'noscript', 'template', 'frame', 'frameset',
  'applet', 'title', 'head',
])

/** 各标签允许的属性(白名单外一律移除)。 */
const ALLOWED_ATTRS: Record<string, readonly string[]> = {
  a: ['href', 'title'],
  img: ['src', 'alt', 'width', 'height'],
  abbr: ['title'],
  code: ['class'],
  pre: ['class'],
}

/** 链接允许的协议。 */
const SAFE_URL_SCHEMES = ['http:', 'https:', 'mailto:']

/** 图片资源允许的协议。 */
const IMAGE_URL_SCHEMES = ['http:', 'https:']

/**
 * 协议判定前剥离控制字符与空白(浏览器解析 URL 时忽略它们,
 * 防 "jav" + 制表符 + "ascript:" 类绕过)。不使用正则转义序列,避免工具链转写事故。
 */
function stripControlAndSpace(value: string): string {
  let out = ''
  for (const ch of value) {
    const code = ch.codePointAt(0) ?? 0
    if (code > 0x20) out += ch
  }
  return out
}

function isSafeUrl(
  value: string,
  schemes: readonly string[],
): boolean {
  const trimmed = stripControlAndSpace(value)
  const colon = trimmed.indexOf(':')
  if (colon > 0 && /^[a-z][a-z0-9+.-]*$/i.test(trimmed.slice(0, colon))) {
    return schemes.includes(`${trimmed.slice(0, colon).toLowerCase()}:`)
  }
  // 相对/锚点 URL:无协议,安全
  return true
}

/** 清洗不可信 HTML,返回安全的 HTML 字符串(白名单标签/属性,协议拦截)。 */
export function sanitizeStatementHtml(dirty: string): string {
  const parsed = new DOMParser().parseFromString(dirty, 'text/html')
  clean(parsed.body)
  const wrap = document.createElement('div')
  wrap.append(...Array.from(parsed.body.childNodes))
  return wrap.innerHTML
}

function clean(root: HTMLElement): void {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_COMMENT)
  const removals: Node[] = []
  const unwraps: Element[] = []
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    if (node.nodeType === Node.COMMENT_NODE) {
      removals.push(node)
      continue
    }
    const el = node as Element
    const tag = el.tagName.toLowerCase()
    if (DROP_WITH_CONTENT.has(tag)) {
      removals.push(el)
    } else if (!ALLOWED_TAGS.has(tag)) {
      unwraps.push(el)
    } else {
      cleanAttributes(el)
    }
  }
  removals.forEach((node) => node.parentNode?.removeChild(node))
  unwraps.forEach((el) => {
    const parent = el.parentNode
    if (!parent) return
    while (el.firstChild) parent.insertBefore(el.firstChild, el)
    el.remove()
  })
}

function cleanAttributes(el: Element): void {
  const tag = el.tagName.toLowerCase()
  const allowed = ALLOWED_ATTRS[tag] ?? []
  for (const attr of Array.from(el.attributes)) {
    const name = attr.name.toLowerCase()
    const kept = allowed.includes(name) && attr.value.trim().length > 0
    if (!kept) {
      el.removeAttribute(attr.name)
      continue
    }
    if ((name === 'href' || name === 'src') && !isSafeUrl(attr.value, name === 'src' ? IMAGE_URL_SCHEMES : SAFE_URL_SCHEMES)) {
      el.removeAttribute(attr.name)
    }
  }
}
