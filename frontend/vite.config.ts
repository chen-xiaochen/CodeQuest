import { fileURLToPath } from 'node:url'
import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 七个页面 + 一个落地页,各自独立 entry(不做 SPA 大包):
 * 构建产物 dist/<page>/index.html,由 ResourceHttpServer 按资源相对路径提供。
 */
export const PAGES = [
  'problems',
  'contest',
  'stats',
  'search',
  'problem-view',
  'submissions',
  'testcases',
] as const

export type PageName = (typeof PAGES)[number]

const rootDir = new URL('.', import.meta.url)

function pageInput(): Record<string, string> {
  const input: Record<string, string> = {
    index: fileURLToPath(new URL('index.html', rootDir)),
  }
  for (const page of PAGES) {
    // html 入口放在根级 <page>/ 目录(Vite 多页标准布局),
    // 产物即 dist/<page>/index.html,与 ResourceHttpServer 的 /<page>/ 路由对齐;
    // 页面逻辑(main.ts/App.vue)仍在 src/pages/<page>/ 下。
    input[page] = fileURLToPath(new URL(`${page}/index.html`, rootDir))
  }
  return input
}

/**
 * dev server 路由重写:开发时以 /<page>/ 访问页面,
 * 映射到源码路径 src/pages/<page>/index.html(与构建产物路径保持一致的体验)。
 */
function devPageRoutes(): Plugin {
  return {
    name: 'cq-dev-page-routes',
    configureServer(server) {
      server.middlewares.use((req, _res, next) => {
        const match = /^\/([\w-]+)\/?$/.exec(req.url ?? '')
        if (match && (PAGES as readonly string[]).includes(match[1])) {
          req.url = `/src/pages/${match[1]}/index.html`
        }
        next()
      })
    },
  }
}

export default defineConfig({
  plugins: [vue(), devPageRoutes()],
  // 相对基准:页面经 http://127.0.0.1:<port>/<page>/ 提供,产物内资源引用保持相对路径
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      input: pageInput(),
      output: {
        entryFileNames: 'assets/[name]-[hash].js',
        chunkFileNames: 'assets/chunk-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]',
      },
    },
  },
})
