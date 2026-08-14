# CodeQuest Webview Frontend

CodeQuest IntelliJ 插件(Kotlin 重写)的统一 webview 前端。Vite + Vue3 + TS,
**六个页面各自独立 entry**(按 toolwindow 按需加载,不做 SPA 大包)。

## 页面与入口

| 路由(资源相对路径) | html 入口 | 页面逻辑 | 挂载的 toolwindow |
|---|---|---|---|
| `/problems/` | `problems/index.html` | `src/pages/problems/` | 主窗口 Problems |
| `/contest/` | `contest/index.html` | `src/pages/contest/` | 主窗口 Contest |
| `/stats/` | `stats/index.html` | `src/pages/stats/` | 主窗口 Statistics |
| `/search/` | `search/index.html` | `src/pages/search/` | 主窗口 Search |
| `/problem-view/` | `problem-view/index.html` | `src/pages/problem-view/` | 编辑器侧题面 |
| `/submissions/` | `submissions/index.html` | `src/pages/submissions/` | 编辑器侧提交结果 |
| `/`(落地页) | `index.html` | `src/landing/` | 沙箱验收入口 |

html 入口按 Vite 多页标准布局放在根级 `<page>/` 目录(产物路径即
`dist/<page>/index.html`,与 `ResourceHttpServer` 的 `/<page>/` 路由对齐);
页面逻辑(`main.ts` / `App.vue`)统一在 `src/pages/<page>/` 下。

## 开发

```bash
cd frontend
npm install        # 慢/被墙可加 --registry=https://registry.npmmirror.com
npm run dev        # http://localhost:5173/ ,/problems/ 等路由已配 dev 重写
npm test           # Vitest(桥协议 mock + 页面渲染冒烟)
npm run build      # vue-tsc 类型检查 + vite build → frontend/dist/
```

## 构建流水线(两步)

Gradle 不直接调用 npm(职责分离):

```bash
cd frontend && npm install && npm run build   # 1) 产出 frontend/dist/
./gradlew buildPlugin                          # 2) copyWebviewDist 同步产物到
                                               #    kotlin/src/main/resources/webview/
                                               #    (ResourceHttpServer 静态根)并打入 zip
```

- `copyWebviewDist` 是 `processResources` 的依赖;`frontend/dist` 缺失时构建
  会给出可行动的失败信息(先跑 npm build)
- `frontend/dist/` 与 `kotlin/src/main/resources/webview/` 均不入库(`.gitignore`),
  CI 需先跑 npm build 再跑 Gradle
- `frontend/package-lock.json` 入库(可复现安装;对全局忽略规则的例外)

## 桥协议(JS 侧客户端)

见 `src/bridge/cq-bridge.ts` 头注释。要点:

- JS → Kotlin:`window.cqSend(JSON.stringify({topic, payload}))`,
  **payload 必须是 JSON 字符串**(Kotlin 侧以 `jsonPrimitive.content` 读取)
- Kotlin → JS:`window.__cqBridge.emit({topic, payload})`(对象字面量)
- dev 浏览器无注入环境自动 mock 回环,`bridge:echo` 直接回发
  `bridge:echo:reply`(对齐 Kotlin 侧 JcefToolWindowSupport 注册的 echo handler)
- 沙箱自测入口:落地页 `/` 与 `/problem-view/` 页面上的
  "Bridge self-test" 按钮;纯浏览器点它走 mock 回环,IDE 内走真实桥往返

## 主题

`src/theme/theme.css` 定义 `--cq-*` 变量体系,每个变量优先读 `--jb-*` 注入值
(Kotlin 侧后续从 IDE 主题注入),缺省用本地亮/暗两套默认色;
`src/theme/theme.ts` 按 `?theme=` query > localStorage > 系统偏好 解析,
落到 `<html data-theme>`。

## 共享库

- `src/lib/highlight.ts`:highlight.js lib/core + 按需注册
  cpp/java/kotlin/python/rust/go/javascript/typescript/csharp(含常见别名),
  token 色由 theme.css 变量驱动
- `src/lib/katex-render.ts`:KaTeX;css 与字体经 npm 包由 Vite 打进产物
  assets(本地资源,不引 CDN)
