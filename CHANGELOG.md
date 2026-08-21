## 1.2.3
### Fixed
- Settings template editor rewired to eliminate cross-key text leaks: the style/language dropdowns no longer use the UI DSL two-way `bindItem` binding, whose programmatic selection events during init/reset could fire the switch handler while the internal style/language state was still stale — flushing the editor text into the wrong `(style, language)` key. This was the root cause of "configuring Java makes every language show the same Java template" and "start-coding creates a .kt file": the saved text landed on the wrong keys while `leetcode:java` stayed empty, letting the leftover polluted `leetcode:kotlin` entry win the default-language pick.
- Dropdowns are now initialized to match the internal selection before listeners attach, programmatic selections are suppressed during reset, and only genuine user selections trigger the flush/refill sequence.

## 1.2.2
### Fixed
- Settings template browsing no longer implicitly enables a language: switching the style/language dropdown away from an untouched built-in default (never configured, text unchanged) keeps it unconfigured, instead of silently persisting the default text as "configured" and enabling that language. Explicitly clicking OK/Apply on a tab still enables it as before. This also fixes the confusing flow where configuring Java (from the default Kotlin tab) accidentally enabled Kotlin too.
- The settings page now opens on a configured language for the initial style (Java preferred, per template availability) instead of always landing on the first language (Kotlin), so returning to settings after saving a Java template shows the Java tab.

## 1.2.1
### Fixed
- Problems list status now reflects the actual solve state from the platform: LeetCode / LeetCode CN pass the logged-in user's per-question GraphQL `status` through and merge it with local submission history (AC > TRIED > UNSOLVED), so problems solved on the website show as solved even when never submitted from the plugin. Field-tested caveat: the CN list API does not distinguish "not attempted" from "attempted but not AC" (both arrive as non-"ac" values), so only "ac" maps to solved — the "tried" state remains driven by local submissions.
- Unsolved problems now render a visible grey hollow-circle badge with a tooltip, replacing the previous blank marker, so all four states (solved ✓ / tried ● / unsolved ○ / premium 🔒) are distinguishable at a glance on the problems and local-search lists.

## 1.2.0
### Added
- Panel consolidation: tool windows 4→3 — the submissions window is merged into the challenge window as a "Submissions" tab (JCEF, filtered by the selected challenge and refreshed as the selection changes), and the main sidebar keeps Problems / Contest / Statistics only. Cross-problem search moves into a "Local search" subview of the Problems page (the `/search/` route and landing entry remain).
- Platform login gating: platform dropdowns on the Problems and Contest pages only list logged-in platforms; when none are logged in the page falls back to LeetCode CN with a hint bar. The account area keeps all six platforms with usernames and explicit login/logout buttons, and refreshes the dropdown immediately after login/logout.
- Silent session restore on IDE restart: new `POST /api/auth/refresh` endpoint concurrently re-verifies stored credentials for all platforms without any prompts, and `GET /api/auth/status` now passes through the current session userId. Page load renders from status first, then refreshes incrementally after restore.
- Code templates by style × language: ACM (stdin/stdout) and LeetCode (Solution class) styles mapped per platform, stored under `style:language` keys with one-time dual-write migration from the old language-only keys (existing configs appear under both styles). Only explicitly saved templates are enabled at runtime — unconfigured languages no longer appear in the new-challenge language dropdown (Java is the default when configured), and one-click "start coding" prefers Java, then the first usable language, or guides to settings when nothing is configured.
- Settings page template editor upgraded to style + language dropdowns with a help dialog (template variables, submit-region markers, file naming rules, style-to-platform mapping) and "restore default text" as an editing start point that does not change the enabled set.
- UI localization to Chinese: all Kotlin-side hardcoded UI strings moved into the plugin bundle (Chinese IDEs get Chinese, English IDEs get English) covering the three sidebar tabs, challenge workspace, dialogs and error messages; the six webview pages are fully localized.
### Fixed
- Challenge code files now follow the configured database folder: with a folder set, new workspaces are generated under `<folder>/codequest/` next to `codequest.db` (previously they always went under the project root regardless of the setting). Workspaces created before the change keep resolving at their existing location. Default (empty) landing spot is now the tidier `<project>/codequest/` subdirectory.
- New Solution files re-render with the current settings template override (generation-time read, matching the legacy LanguageSettings behavior) instead of the template stored when the workspace was created.
- A database-folder switch failure no longer fails the whole settings apply: templates/CSS stay saved, the failure is logged, and the new path takes effect after restart.
- Problems/search list rows are single-line (status badge / id / title / tags / difficulty) so titles align across rows, and solved problems show a filled green ✓ badge (tried = hollow ring) for at-a-glance status.

## 1.1.0
### Added
- Submission result details panel (`/api/submission/:id/detail`): LeetCode / LeetCode CN include the error-testcase triple (input / expected / actual output), HackerRank shows testcase counts; CodeForces / AtCoder / Luogu link to the site as before (no public API). Submissions rows expand into Details / Code tabs — code review renders the stored snapshot with syntax highlighting and copy, no network needed.
- Problem status column on the problems and search pages: 🔒 paid / ✓ AC / ● tried icons aggregated from local submission history (AC is sticky; multiple challenges take the highest), with a local status filter. `paidOnly` now flows through from platform data so the lock icon is real.
- Account menu in the problems page header: per-platform login state badge with login / logout (`/api/auth/status`, `auth:logout` bridge).
- Settings: per-language code template editors (default = built-in, with reset) wired into every challenge-creation path, plus custom CSS injected into the problem view.
- Solution ordering dropdown on LeetCode / LeetCode CN (hot / newest / oldest / most upvoted, matching the legacy orderBy semantics).
- Webview zoom toolbar on the problem view (− / 100% / +), 1.2× per step, applied to the whole page.
- LeetCode company question lists: company and interview-period dropdowns on the problems page (`questionCompanyTagsV2` + `favoriteQuestionList`).
- Login entry from browse flows (e.g. HackerRank statement without credentials now offers login instead of a bare 401) and one-click "Start coding" from the problem view.
### Fixed
- LeetCode CN problem list showed English titles; `titleCn` now takes precedence when present (as the legacy implementation did).

## 1.0.0
### Changed
- Full rewrite from Scala/sbt to Kotlin 2.x + Gradle 8 (intellij-platform-gradle-plugin 2.x), package renamed to `com.codequest`; the legacy Scala/sbt codebase and the four old webview apps are removed, CI is converged to a single Gradle track.
- IDE baseline raised to IntelliJ 2026.2+ (build 262) only, with JCEF declared as an explicit plugin dependency (`com.intellij.modules.jcef`).
- UI rebuilt as a Swing shell plus one unified JCEF frontend (a single Vite/Vue3/TS app with six per-page entries); page data is served by an in-plugin ResourceHttpServer (REST endpoints such as `/api/problems`, `/api/stats`, `/api/search`) while JCEF messaging is reserved for commands/events, removing large-payload serialization stalls.
### Added
- Capability-based OJ SPI (`AuthCapable` / `ProblemQueryCapable` / `SubmissionCapable` / `ContestCapable` / `SolutionViewCapable`) with Extension-Point registration; adding an OJ = one `platform/<oj>/` package + plugin.xml declarations, zero core/ui changes (validated on all six platforms, see `docs/spi-validation.md`).
- Six platforms on the new SPI: LeetCode, LeetCode CN, CodeForces, AtCoder, Luogu, HackerRank — cookie-paste login persisted via PasswordSafe, and a shared submission polling state machine (exponential backoff) replacing per-platform polling copies.
- Cloudflare Turnstile anti-bot challenges handled through a shared JCEF dialog (CodeForces/AtCoder submit channels).
- Challenge workspace: multiple solutions per problem with per-solution language/template and begin/end submit regions; test-case management.
- Submission history stored with code snapshots (review does not depend on workspace files).
- CodeForces contest list/detail with subscribable 24h reminders (join via external link; rankings not promised — no public API).
- Statistics view: summary cards (total/accepted/AC rate/streak), GitHub-style heatmap and 30-day trend (`/api/stats` with platform filter).
- Cross-problem full-text search over title/statement/solution notes (SQLite FTS5 with LIKE fallback for short CJK keywords) plus tag AND filtering.
- In-app solution article reader (back/forward/refresh toolbar) for Luogu and LeetCode/CN.

## 0.1.0
### Changed
- Fork from LeetCode Epiphany 1.12.1 (Apache-2.0). Rebrand plugin id/name to `com.chenxiaochen.codequest` / CodeQuest.
### Fixed
- Declare `com.intellij.modules.jcef` dependency so the plugin loads on IntelliJ 2026.2+ (JCEF was split into a standalone plugin since build 262, causing `NoClassDefFoundError: org/cef/handler/CefLoadHandlerAdapter` without this declaration).
### Added
- IntelliJ 2026.2 (build 262) build target; `untilBuild` is now bounded per version (252.\*, 262.\*) instead of open-ended `null`.
- `JBCefApp.isSupported()` guard in `BaseJCefWebView` for a friendlier error when JCEF is unavailable.
- Local packaging script `scripts/package.sh` and manual release checklist `docs/release-checklist.md`.

## 1.12.1
### Fixed
- Using local katex for luogu and atcoder description rendering to avoid network issues.
- Change http referer to no-referer in description and leetcode solution webview to avoid image loading failure.
 
## 1.12.0
### Added
- Add run configuration creation support for ACM-liked challenges with test case as input.
 
## 1.11.0
### Added
- Add custom style support for challenge description rendering.
- Add go-back button in LuoGu solution webview.
 
## 1.10.2
### Fixed
- Resolve Cloudflare Turnstile verification issue on CodeForces submissions.
 
## 1.10.1
### Fixed
- Fix rendering issues in some LeetCode problem descriptions that use Markdown format.

## 1.10.0
### Added
- File names can be paths and support Velocity templates.
 
## 1.9.3
### Fixed
- Fix illegal characters in file name which come from some challenge titles   
 

## 1.9.1
### Fixed
- LeetCode: Use v2 api to query questions 

## 1.9.0
### Added
- HackerRank submission result support
- HackerRank user profile view support
### Fixed
- HackerRank login issue
 
## 1.8.0
### Added
- Add leetcode sql question support
 
## 1.7.4
### Fixed
- Fix luogu template document issue
 
## 1.7.3
### Fixed
- Fix luogu 2-factor-login issue
 
## 1.7.2
### Fixed
- Fix leetcode Golang question issue
 
## 1.7.0
### Added
- Add submission result view in embedded browser for AtCoder
### Fixed
- Automatically create missing directories when creating new code file
 
## 1.6.2
### Fixed
- Fix AtCode submission with cloudflare turnstile issue. [#62](https://github.com/WenjunHuang/CodeEpiphany/issues/62) 
 
## 1.6.1
### Fixed
- Fix proxy username and password not working issue
### Changed
- Optimize compatibility with JetBrains IDEs 2025.2
 
## 1.6.0
### Added
- Add submission result view in embedded browser for CodeForces, LuoGu
### Fixed
- Optimize api infrastructure

## 1.5.0
### Added
- Add user profile view for LeetCode, LeetCodeCN, LuoGu, CodeForces, AtCoder and HackerRank
- Add submission result view in embedded browser for LeetCode, LeetCodeCN
 
## 1.4.0
### Added
- Add plugin update notification
- Add plugin donation link
 
## 1.3.4

### Fixed
- Support LuoGu's netease yidun captcha code when submitting answer. fix [#60](https://github.com/WenjunHuang/CodeEpiphany/issues/60)
 
## 1.3.3

### Fixed
- LuoGu question query api changed, fix query issue
  
## 1.3.2

### Added

- Add LuoGu question solution

## 1.3.1

### Fixed

- Fix leetcode/leetcodecn test cases data not correctly parsed
  issue [#48](https://github.com/WenjunHuang/CodeEpiphany/issues/48)
- [#49](https://github.com/WenjunHuang/CodeEpiphany/issues/49)

## 1.3.0

### Added

- Add TestCase support for LeetCode, LeetCodeCN, LuoGu, CodeForces, AtCoder and HackerRank
- Add TestCase variable in code file template for LeetCode, LeetCodeCN, LuoGu, CodeForces, AtCoder and HackerRank

### Fixed

- [#16](https://github.com/WenjunHuang/CodeEpiphany/issues/36)
- [#29](https://github.com/WenjunHuang/CodeEpiphany/issues/29)
- [#37](https://github.com/WenjunHuang/CodeEpiphany/issues/37)
- [#38](https://github.com/WenjunHuang/CodeEpiphany/issues/38)
- [#46](https://github.com/WenjunHuang/CodeEpiphany/issues/46)

## 1.2.3

- Fix webview scrollbar style not compatible with IDE theme issue
- Fix user avatar icon size is too big for leetcode solution view in macos
- Fix codeforces code submission result issue

## 1.2.2

### Modified

- Fix chinese translation issues
- Modify readme and description documentation

## 1.2.0

### Added

- LeetCode and LeetCodeCN official solutions view support

### Modified

- Rename the old 'solutions' feature to 'my notes'

## 1.1.0

### Added

- Add [Browser Competitive-Companion](https://github.com/jmerle/competitive-companion) support
  to [CodeForces](https://codeforces.com/)

## 1.0.4

## Fixed

- Fix CodeForces login issue
- Fix code file template return empty string issue

## 1.0.2

### Changed

- Change leetcode icons

## 1.0.1

### Fixed

- Fix luogu user cookie expiration causing login failure
  issue.[#17](https://github.com/WenjunHuang/CodeEpiphany/issues/17)

### Changed

- Change description

## 1.0.0

### Added

- Automatically save search queries
- Save latest opened codedojo toolwindow and automatically open it when the IDE is restarted

## 0.9.8

### Changed

- Fix luogu login change

## 0.9.3

### Changed

- Fix leetcode company combobox dropdown slow

## 0.9.2

### Changed

- Fix a few bugs

## 0.9.0

### Added

- Add [LuoGu/洛谷](https://www.luogu.com.cn/) support

## 0.8.1

### Added

- Implement company question search for LeetCodeCN and LeetCode

## 0.8.0

### Changed

- Support Idea Platform from version 2023.3

## 0.7.1

### Added

- Implement [#5](https://github.com/WenjunHuang/CodeEpiphany/issues/5)

## 0.7.0

### Added

- Add [AtCoder](https://atcoder.jp) support

### Changed

- Fix [#10](https://github.com/WenjunHuang/CodeEpiphany/issues/10)
- Refine codeforces's language version
- Add 'view in browser' link for LeetCode, LeetCodeCN and HackerRank submissions
- Repair some UI problems

## 0.6.6

### Changed

- Fix the issue of missing text for action 'Surround Region'
- Fix leetcode result table display issue

## 0.6.5

### Added

- Implement [#6](https://github.com/WenjunHuang/CodeEpiphany/issues/6)
- Implement [#4](https://github.com/WenjunHuang/CodeEpiphany/issues/4)
- Add start and end markers for code regions to templates.

### Changed

- Offer simple templates for Code Forces challenges.
- Optimize some UI elements, including the difficulty color scheme and the login waiting indicator.

### Deprecated

### Removed

## 0.6.4

### Added

### Changed

- Fix [#3](https://github.com/WenjunHuang/CodeEpiphany/issues/3)
- Fix a database unique index constraint that prevented adding python and python3 simultaneously.
- Refactor code structure

### Deprecated

### Removed

## 0.6.3

### Added

### Changed

- optimize challenge submission result message
- Resolve the issue of missing language information when opening a challenge.
- fix [#2](https://github.com/WenjunHuang/CodeEpiphany/issues/2)

### Deprecated

### Removed

