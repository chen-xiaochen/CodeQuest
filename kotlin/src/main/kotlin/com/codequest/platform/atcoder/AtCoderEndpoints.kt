package com.codequest.platform.atcoder

import com.codequest.core.model.PlatformId
import com.codequest.spi.TurnstileConfig

/**
 * AtCoder 平台身份与端点常量(WP25)。
 *
 * 协议事实(旧 Scala 实现逐字核对,atcoder/services/AtCoderApi.scala):
 * - 题库列表:kenkoooo 镜像资源 `GET https://kenkoooo.com/atcoder/resources/merged-problems.json`
 *   (全量 JSON,字段 snake_case)——旧实现从未爬 atcoder.jp 的 contests 页拿列表;
 * - 难度表:`GET https://kenkoooo.com/atcoder/resources/problem-models.json`
 *   (`{problemId: {difficulty: IRT 值}}`,经 [calculateDisplayDifficulty] 换算展示难度);
 * - 题面页:`/contests/{contestId}/tasks/{problemId}`(HTML,题面在 `#main-container` 内
 *   `div.row` 的第二个直接子 div);
 * - 登录态校验页:`/settings`(HTML 含 `#user-nav-tabs` 即已登录);
 * - 提交(表单 POST):`/contests/{contestId}/submit`,字段
 *   `data.TaskScreenName / data.LanguageId / sourceCode / csrf_token / cf-turnstile-response`;
 * - 提交状态:`GET /contests/{contestId}/submissions/{submissionId}`
 *   (`td#judge-status > span` 的 title/文本)。
 *
 * ## remoteId 约定
 *
 * 平台侧题目标识 = AtCoder problem id(TaskScreenName,即 kenkoooo `id` 字段),
 * 形如 `"abc390_g"`;自身携带 `contestId/problemId` 结构:`<contestId>_<problemIndex>`,
 * contestId 取最后一个 `_` 之前段(见 [parseProblemRemoteId])。
 */
val ATCODER_PLATFORM_ID: PlatformId = PlatformId("atcoder")

const val ATCODER_DOMAIN: String = "atcoder.jp"

private fun baseUrl(): String = "https://$ATCODER_DOMAIN"

/** 主站根(cookie 注入基准)。 */
val ATCODER_HOME_URL: String = baseUrl() + "/"

/** 登录态校验页(HTML;旧实现用 /settings 而非 CF 的 /settings/general)。 */
val ATCODER_SETTINGS_URL: String = baseUrl() + "/settings"

/** 全量题库(kenkoooo 镜像,旧实现事实)。 */
const val ATCODER_PROBLEMS_URL: String = "https://kenkoooo.com/atcoder/resources/merged-problems.json"

/** 难度模型表(kenkoooo 镜像;difficulty 为 IRT 值,需换算展示难度)。 */
const val ATCODER_PROBLEM_MODELS_URL: String = "https://kenkoooo.com/atcoder/resources/problem-models.json"

/** 题面页 URL(contestId + problemId 均来自 remoteId 拆分)。 */
fun atCoderTaskUrl(
    contestId: String,
    problemId: String,
): String = baseUrl() + "/contests/$contestId/tasks/$problemId"

/** 提交表单端点(POST,表单编码)。 */
fun atCoderSubmitUrl(contestId: String): String = baseUrl() + "/contests/$contestId/submit"

/** 单条提交详情页(轮询判定来源)。 */
fun atCoderSubmissionUrl(
    contestId: String,
    submissionId: String,
): String = baseUrl() + "/contests/$contestId/submissions/$submissionId"

/**
 * 提交页 Turnstile 配置(WP25):逐字段移植旧
 * `AtCoderSubmissionService.getCSRFAndCloudflare` 的 CloudflareTurnstileDialog 常量——
 * 弹窗加载该 contest 的提交页 → 移除 `div.cf-challenge` → 注入挂件 → 取
 * `csrf,turnstile` 二元组(extraJs 为空,AtCoder 无 CF 的 ftaa/bfaa)。
 * pageUrl 随 contest 变化,故为函数而非常量。
 */
fun atCoderSubmitTurnstile(contestId: String): TurnstileConfig =
    TurnstileConfig(
        platformId = ATCODER_PLATFORM_ID,
        domain = ATCODER_DOMAIN,
        pageUrl = atCoderSubmitUrl(contestId),
        sitekey = "0x4AAAAAAA6HJUmmLP7mLxx0",
        challengeSelector = "div.cf-challenge",
        extraJs = "",
        resultExpression = """csrf + "," + turnstile""",
    )
