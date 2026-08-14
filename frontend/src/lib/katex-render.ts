/**
 * KaTeX 本地渲染封装(WP12)。
 *
 * 资源本地化:katex.min.css(含字体)经 Vite 从 npm 包打包进产物 assets,
 * 全程不引 CDN;字体颜色跟随 theme.css 的 --cq-fg。
 */

import katex from 'katex'
import 'katex/dist/katex.min.css'

/** 渲染 TeX 片段为 HTML 字符串。displayMode=true 为独立展示公式。 */
export function renderMathToString(tex: string, displayMode = false): string {
  return katex.renderToString(tex, {
    displayMode,
    throwOnError: false,
    strict: 'ignore',
    output: 'html',
  })
}

/** 渲染到容器元素(替换其子内容)。 */
export function renderMath(el: HTMLElement | null, tex: string, displayMode = false): void {
  if (!el) return
  el.innerHTML = renderMathToString(tex, displayMode)
}
