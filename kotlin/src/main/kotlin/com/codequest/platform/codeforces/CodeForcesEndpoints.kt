package com.codequest.platform.codeforces

import com.codequest.core.model.PlatformId
import com.codequest.spi.TurnstileConfig

/**
 * CodeForces 平台身份与端点常量(WP24)。
 *
 * 协议事实(旧 Scala 实现逐字核对):
 * - 公开 REST:`GET /api/problemset.problems[?problemsetName=acmsguru]`、
 *   `GET /api/contest.list?gym=false`、`GET /api/contest.standings?contestId=`(匿名可用);
 * - 题面页:`/problemset/problem/{contestId}/{index}`;acmsguru 走
 *   `/problemsets/acmsguru/problem/99999/{index}`;
 * - 提交页(表单 POST):`/problemset/submit`;acmsguru 走 `/problemsets/acmsguru/submit`;
 * - 提交状态(表单 POST):`/data/submitSource?rv=<9位hex>`;
 * - 登录态校验页:`/settings/general`(HTML `div.userbox`)。
 */
val CODEFORCES_PLATFORM_ID: PlatformId = PlatformId("codeforces")

const val CODEFORCES_DOMAIN: String = "codeforces.com"

/** acmsguru 题目的合成 contestId(旧实现 `problem.copy(contestId = Some(99999))` 事实)。 */
const val ACMSGURU_CONTEST_ID: Long = 99_999L

private fun baseUrl(): String = "https://$CODEFORCES_DOMAIN"

private fun apiUrl(pathWithQuery: String): String = "https://$CODEFORCES_DOMAIN/api/$pathWithQuery"

/** 主站根(cookie 注入基准)。 */
val CODEFORCES_HOME_URL: String = baseUrl() + "/"

/** 登录态校验页(HTML)。 */
val CODEFORCES_SETTINGS_URL: String = baseUrl() + "/settings/general"

/** 题库(全量,含 problems + problemStatistics,本适配器只消费 problems)。 */
val CODEFORCES_PROBLEMSET_URL: String = apiUrl("problemset.problems")

/** acmsguru 题库(第二次调用,旧实现合并两份数据)。 */
val CODEFORCES_PROBLEMSET_ACMSGURU_URL: String = apiUrl("problemset.problems?problemsetName=acmsguru")

/** 竞赛列表(gym=false,公开匿名)。 */
val CODEFORCES_CONTEST_LIST_URL: String = apiUrl("contest.list?gym=false")

/** 竞赛排名/题目列表(匿名仅支持裸 contestId,不支持 handles 过滤——旧实现注释事实)。 */
fun codeForcesStandingsUrl(contestId: Long): String = apiUrl("contest.standings?contestId=$contestId")

/** 题面页 URL(acmsguru 题目走 problemsets 路径,其余走 problemset)。 */
fun codeForcesProblemPageUrl(
    contestId: Long,
    index: String,
): String =
    if (contestId == ACMSGURU_CONTEST_ID) {
        baseUrl() + "/problemsets/acmsguru/problem/$contestId/$index"
    } else {
        baseUrl() + "/problemset/problem/$contestId/$index"
    }

/** 提交表单端点(acmsguru 分支同旧实现)。 */
fun codeForcesSubmitUrl(contestId: Long): String =
    if (contestId == ACMSGURU_CONTEST_ID) {
        baseUrl() + "/problemsets/acmsguru/submit"
    } else {
        baseUrl() + "/problemset/submit"
    }

/** 提交状态轮询端点;[rv] 为 9 位 hex 缓存穿透参数(旧实现事实)。 */
fun codeForcesSubmitSourceUrl(rv: String): String = baseUrl() + "/data/submitSource?rv=$rv"

/** 竞赛页(BEFORE 阶段用 [codeForcesContestRegistrationUrl] 注册页,旧 contestUrl 事实)。 */
fun codeForcesContestUrl(contestId: Long): String = baseUrl() + "/contest/$contestId"

/** 竞赛注册页(报名期「参加」外链目标)。 */
fun codeForcesContestRegistrationUrl(contestId: Long): String = baseUrl() + "/contestRegistration/$contestId"

/** 轮询提交状态的 Referer(旧实现 GET /contest/{id}/my 语义)。 */
internal fun codeForcesMySubmissionsUrl(contestId: Long): String = baseUrl() + "/contest/$contestId/my"

/**
 * 提交页 Turnstile 配置(WP24):逐字段移植旧 `CloudflareTurnstileDialog.TurnstileConfig`
 * (CodeForcesSubmissionService.getCSRFAndCloudflare 常量):
 * 弹窗加载提交页 → 移除 `div.turnstile-container` → 注入挂件 → 取
 * `csrf,turnstile,ftaa,bfaa` 四元组(ftaa/bfaa 来自页面全局变量)。
 */
val CODEFORCES_SUBMIT_TURNSTILE: TurnstileConfig =
    TurnstileConfig(
        platformId = CODEFORCES_PLATFORM_ID,
        domain = CODEFORCES_DOMAIN,
        pageUrl = baseUrl() + "/problemset/submit",
        sitekey = "0x4AAAAAACDz1ltMYNJCAQZS",
        challengeSelector = "div.turnstile-container",
        extraJs = "const ftaa = window._ftaa; const bfaa = window._bfaa;",
        resultExpression = """csrf + "," + turnstile + "," + ftaa + "," + bfaa""",
    )
