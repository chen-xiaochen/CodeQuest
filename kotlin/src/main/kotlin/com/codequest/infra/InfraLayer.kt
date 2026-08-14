/**
 * `infra` 层:基础设施实现,实现 `core` 仓库接口,不依赖 ui/platform。
 *
 * - db:Exposed + JDBC SQLite(DAO、schema、FTS5),单写协程 + WAL 模式
 * - http:ktor-client(OkHttp 引擎)+ 每平台 CookieJar 的 SessionStore
 * - secrets:登录凭据持久化(SensitiveDataStore 同机制)
 * - settings:设置存取
 * - messaging:ResourceHttpServer(本地静态资源 + REST 数据端点)
 * - anti-bot:Cloudflare Turnstile / 登录 JCEF 弹窗公共组件
 */
