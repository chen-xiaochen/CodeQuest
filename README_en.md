🌐 Language:
[中文](README.md) | [English](README_en.md)

> **Notice**: **CodeQuest** is a derivative work based on [LeetCode Epiphany](https://github.com/WenjunHuang/CodeEpiphany) (Apache License 2.0, © Wenjun Huang). Modifications are released under Apache 2.0 as well. The original project is copyrighted by its author; see [LICENSE](LICENSE).

# CodeQuest 🌟

[![CI](https://github.com/chen-xiaochen/CodeEpiphany/actions/workflows/ci-main.yml/badge.svg)](https://github.com/chen-xiaochen/CodeEpiphany/actions/workflows/ci-main.yml)
[![License](https://img.shields.io/github/license/chen-xiaochen/CodeEpiphany)](LICENSE)
[![GitHub issues](https://img.shields.io/github/issues/chen-xiaochen/CodeEpiphany)](https://github.com/chen-xiaochen/CodeEpiphany/issues)

**A multi-platform competitive programming plugin for JetBrains IDEs** — query problems, browse statements, write multiple solutions, submit in one click, and review your statistics, across six OJ platforms from a single plugin.

As of v1.0.0 the plugin has been fully rewritten in **Kotlin + Gradle**: a capability-based OJ SPI with strict layering, a unified JCEF frontend, and 517 Kotlin + 95 frontend tests as guardrails. The legacy Scala/sbt codebase has been removed entirely. Requires IntelliJ Platform **2026.2+ (build 262)**.

## Supported Platforms & Capability Matrix

| Platform | Auth | Problems | Submit | Contest | Solutions |
|---|:---:|:---:|:---:|:---:|:---:|
| [LeetCode](https://leetcode.com) | ✅ | ✅ ¹ | ✅ | — | ✅ ² |
| [LeetCode CN](https://leetcode.cn) | ✅ | ✅ | ✅ | — | ✅ |
| [CodeForces](https://codeforces.com) | ✅ | ✅ | ✅ ³ | ✅ ⁴ | — |
| [AtCoder](https://atcoder.jp) | ✅ | ✅ ⁵ | ✅ ³ | — | — |
| [Luogu](https://www.luogu.com.cn) | ✅ | ✅ | ✅ ⁶ | — | ✅ ⁷ |
| [HackerRank](https://www.hackerrank.com) | ✅ | ✅ ⁸ | ✅ | — | — |

Login is uniform cookie-paste across platforms: log in to the OJ in your browser, copy the Cookie request header, and paste it into the dialog the IDE shows on first use. Validated cookies are persisted (PasswordSafe), so they survive IDE restarts. Capability boundaries, honestly noted:

1. The leetcode.com problem-list GraphQL requires login (anonymous requests get 401 — a site-side restriction); LeetCode CN works anonymously.
2. leetcode.com solution lists return 401 anonymously (login required); LeetCode premium-only articles fail with an honest error — the paywall is not bypassed.
3. Submit channels are protected by Cloudflare Turnstile (CodeForces/AtCoder); the plugin opens a JCEF dialog to guide you through the challenge before resuming.
4. CodeForces contests support list/detail/24h subscribable reminders; "join" goes through an external browser link; rankings are not promised (no public API).
5. AtCoder has no official public problem-list API; list data comes from the [kenkoooo](https://kenkoooo.com) third-party mirror.
6. Luogu submissions may trigger Yidun/image captchas; this build reports the error honestly instead of failing silently.
7. Luogu solutions support list + in-app article reading; posts beyond the first list page are unreachable (platform API limitation).
8. HackerRank problem lists work anonymously; problem details require login.

## Key Features

- 🔤 **Challenge workspace**: multiple solutions per problem, each with its own language/template/entry file; `begin/end` region markers define the code to submit; a test-case panel for editing cases
- ⚡ **One-click submit**: editor right-click *Submit to OJ* → shared polling state machine (exponential backoff) → AC/failure balloon notifications → submissions stored with code snapshots (history survives workspace changes)
- 📖 **Statement rendering**: HTML/Markdown pipelines with KaTeX formulas and highlight.js; statements cached locally for offline review
- 📊 **Statistics**: summary cards (total/accepted/AC rate/streak) + GitHub-style heatmap + 30-day trend chart, auto-refreshing after each submission
- 🔎 **Cross-problem search**: SQLite FTS5 full-text search over titles/statements/solution notes (with matched-source markers) + tag AND filtering + platform filter; doubles as a local problem browser when no keyword is given
- 📚 **Solution reader**: in-app reader with back/forward/refresh/open-externally toolbar; works anonymously on LeetCode CN, after login on Luogu
- 🏆 **Contests**: CodeForces contest list/detail and subscribable reminders (balloon notification 24h before start)

## Architecture

Single Gradle module + strict package layering + capability-based OJ SPI (dependency direction `ui → platform → spi → core`; `infra` implements `core` repository interfaces):

```
com.codequest
├── core        domain models + use-case services + repository interfaces (no IntelliJ deps)
├── spi         OJPlatform capability interfaces + registry (Extension Points)
├── platform    leetcode / codeforces / atcoder / luogu / hackerrank adapters
├── infra       db (Exposed + SQLite) / http (ktor) / secrets / settings / messaging
├── ui          toolwindow shells + JCEF bridge + actions + anti-bot dialogs
frontend/       unified webview (Vite + Vue3 + TS, six per-page entries)
```

- **Layering is enforced, not conventional**: the `checkLayerDependencies` task in `./gradlew check` statically scans imports; reverse/skip-layer imports and cross-OJ imports fail the build
- **Data over HTTP, commands over the bridge**: the UI is a Swing shell + JCEF content area; pages fetch data via in-plugin ResourceHttpServer REST endpoints (`/api/problems`, `/api/stats`, `/api/search`, ...) while JCEF messaging is reserved for commands/events — no large-payload message serialization stalls

## Adding a New OJ Platform (SPI Guide)

Platforms compose capability interfaces as needed (`AuthCapable` / `ProblemQueryCapable` / `SubmissionCapable` / `ContestCapable` / `SolutionViewCapable`), register via plugin.xml Extension Points, and are discovered through `OJPlatformRegistry` by capability — **core/ui stay unaware of new platforms**. Validated with the LeetCode dual sites in M3 and four-platform mass production in M4: zero core changes, ~8-9 files / ~1k lines per platform on average.

1. Create the `kotlin/src/main/kotlin/com/codequest/platform/<oj>/` package: `XxxApi` (pure HTTP mapping, unit-testable with MockEngine) / `XxxAuth` / `XxxProblemService` / `XxxSubmissionService` (only the `fetchRemoteStatus()` polling hook) / `XxxLanguages` / `XxxPlatform` (assembly root)
2. Declare two lines in plugin.xml: `ojPlatformProvider` + `ojAuthPromptProvider`
3. Write HTTP-layer tests replaying recorded responses via ktor `MockEngine` — no real network

See [docs/spi-validation.md](docs/spi-validation.md) for evidence and known deviations.

## 🛠️ Installation

Requires **IntelliJ Platform 2026.2+ (build 262)**. This fork is not published on JetBrains Marketplace; install manually:

1. Download the zip from the releases page, or build it yourself (see the [development guide](#-development))
2. Open your JetBrains IDE, go to **Settings → Plugins → ⚙️ → Install Plugin from Disk**
3. Pick the downloaded zip and restart the IDE

**Database location**: problem caches, submissions and statistics live in a local SQLite database, defaulting to `~/.codequest/codequest.db`. You can change the folder under **Settings → Tools → CodeQuest** — point multiple IDEs (PyCharm/CLion/IDEA, ...) at the same folder to share your data. Data from the legacy Scala version (fork 0.1.0 and earlier) is not migrated.

## 💻 Development

Requirements: JDK 21 + Node.js 20+.

```bash
# Backend gates: detekt + ktlint + layer-dependency check + all tests + packaging
./gradlew check test buildPlugin          # artifact: build/distributions/codequest-<version>.zip

# Frontend (run before Gradle; build output is embedded into the plugin zip by copyWebviewDist)
cd frontend
npm ci
npm test                                  # Vitest
npm run build                             # → frontend/dist/

# Sandbox run (opens an empty project to trigger plugin startup)
./gradlew runIde

# Format Kotlin code
./gradlew ktlintFormat
```

Gradle never invokes npm: frontend artifacts are not committed, so **run `npm run build` before Gradle** (the build fails with an actionable message if `frontend/dist` is missing). See [CONTRIBUTING.md](CONTRIBUTING.md) and [frontend/README.md](frontend/README.md).

## 🔗 Learn More

- **[SPI validation conclusions](docs/spi-validation.md)** (cost of adding an OJ / zero-touch layering evidence / known deviations)
- Manual acceptance checklists: [M3 first platform](docs/m3-manual-checklist.md) / [M4 four platforms](docs/m4-manual-checklist.md) / [M5 enhancements](docs/m5-manual-checklist.md)
- **[Release checklist](docs/release-checklist.md)** · **[Contributing](CONTRIBUTING.md)**
- **[🐛 Report Issues](https://github.com/chen-xiaochen/CodeEpiphany/issues)**

## License

[Apache License 2.0](LICENSE). This project is a fork of [LeetCode Epiphany](https://github.com/WenjunHuang/CodeEpiphany) (Apache 2.0, © Wenjun Huang) and keeps the upstream copyright and license notices as required by Apache 2.0.

---
