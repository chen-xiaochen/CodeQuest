# Contributing to CodeQuest

Thanks for your interest in improving CodeQuest! This is a Kotlin + Gradle project (a fork of
LeetCode Epiphany, Apache-2.0) targeting IntelliJ Platform 2026.2+.

## Development environment

- **JDK 21** (Gradle toolchain; the Gradle wrapper is included, no local Gradle install needed)
- **Node.js 20+** (for the webview frontend)
- IntelliJ IDEA 2026.2+ recommended (open the repo root; the `runIde` sandbox works from CLI too)

## Project layout & layering

```
com.codequest
├── core        domain models + use-case services + repository interfaces (no IntelliJ deps)
├── spi         OJPlatform capability interfaces + registry (Extension Points)
├── platform    leetcode / codeforces / atcoder / luogu / hackerrank adapters
├── infra       db (Exposed + SQLite) / http (ktor) / secrets / settings / messaging
├── ui          toolwindow shells + JCEF bridge + actions + anti-bot dialogs
frontend/       unified webview (Vite + Vue3 + TS, six per-page entries)
```

Dependency direction: `ui → platform → spi → core`, with `infra` implementing `core` repository
interfaces. **This is enforced by the build**, not by convention: the `checkLayerDependencies`
task scans imports and fails on reverse/skip-layer imports or cross-OJ imports. Platform adapters
must not import `infra`/`ui`; they talk HTTP through the `OJTransport` SPI and receive credential
stores and auth prompts via Extension Points.

## Build & test

The pipeline is two steps — Gradle never invokes npm, and frontend artifacts are not committed:

```bash
# 1) Frontend: install, test, build (produces frontend/dist/)
cd frontend
npm ci
npm test          # Vitest
npm run build     # vue-tsc typecheck + vite build

# 2) Backend gates + packaging (embeds frontend/dist/ via copyWebviewDist)
./gradlew check test buildPlugin    # artifact: build/distributions/codequest-<version>.zip
```

- `./gradlew check` runs detekt (`config/detekt.yml`), ktlint, the layer-dependency check and all
  Kotlin tests; keep it green before every commit.
- `./gradlew runIde` launches a sandbox IDE with the plugin loaded (auto-opens an empty project).
- `./gradlew ktlintFormat` auto-formats Kotlin sources.

Run `npm run build` before Gradle tasks that need resources — `copyWebviewDist` fails with an
actionable message if `frontend/dist/` is missing.

## Tests

- **Kotlin**: unit/integration tests live next to the code under `kotlin/src/test/kotlin`.
  Platform HTTP layers are tested with ktor `MockEngine` replaying recorded fixtures — no real
  network in tests.
- **Frontend**: Vitest component tests with a mocked bridge (`frontend/tests/`).

## Adding a new OJ platform

New platform = one package + two plugin.xml declarations, zero core/ui changes. See
[docs/spi-validation.md](docs/spi-validation.md) for the file checklist and production evidence
from the six existing platforms.

## Commit style

Conventional Commits with an optional scope, e.g.:

```
feat(platform): add HackerRank adapter (WP27)
fix(stats): heatmap counts off by one on partial days
docs: rewrite README for the Kotlin rewrite
chore!: remove legacy scala sources (WP33)
```

- Types: `feat` / `fix` / `docs` / `chore` / `refactor` / `test` / `perf`; breaking changes add `!`.
- One logical change per commit; update `CHANGELOG.md` (Keep a Changelog format) for user-visible changes.
- Commits are made locally and pushed by the maintainer (automation does not push).

## Reporting issues

Open an issue at https://github.com/chen-xiaochen/CodeEpiphany/issues with your IDE version,
plugin version, and the relevant excerpt from `idea.log`.

## License

By contributing, you agree your contributions are licensed under Apache License 2.0, like the
rest of this project (which itself derives from LeetCode Epiphany, © Wenjun Huang, Apache-2.0).
