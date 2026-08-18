package com.codequest.platform.hackerrank

import com.codequest.core.model.PlatformId

/**
 * HackerRank 平台身份与端点常量(WP27,M4 收官平台)。
 *
 * 协议事实(旧 Scala 实现逐字核对,hackerrank/services/HackerRankApi.scala——
 * 旧实现全部走 REST + HTML 内嵌 JSON,**无 GraphQL**,本实现同样只走 REST):
 * - 登录态:`GET /community/v1/promotion_slots/banner-dashboard`,2xx 即已登录
 *   (旧 checkLogin 唯一判定);
 * - 题库/用户:`GET /dashboard`,HTML 内 `script#__NEXT_DATA__` 的 JSON:
 *   `props.pageProps.prefetchedData.profile`(用户)与
 *   `props.pageProps.prefetchedData.contest.categories`(领域/题库树,含 children
 *   子域);旧 initialize() 按 id 升序排序后追加 Project Euler 特例域;
 * - 列表:`GET /rest/contests/master/tracks/{domain}/challenges`(master)或
 *   `GET /rest/contests/projecteuler/challenges`(projecteuler,无 tracks 段),
 *   query 参数 `offset`/`limit` + 多值过滤 `filters[subdomains][]`/
 *   `filters[status][]`/`filters[difficulty][]`/`filters[skills][]`,响应
 *   `{total, models:[...]}`;
 * - 关键字搜索:`GET /appsearch?contest_slug={contest}&query={keyword}`,
 *   响应 `{challenges:[{contest_slug, challenge_slug, challenge_name, ...}]}`;
 * - 题目详情/CSRF:`GET /challenges/{slug}/problem`(master)或
 *   `GET /contests/projecteuler/challenges/{slug}/problem`,HTML 内
 *   `script#initialData` 为 URL 编码 JSON,路径
 *   `community.challenges.challenge["{contest}/{slug}"].detail`;CSRF token 在
 *   `meta#csrf-token` 的 content 属性;
 * - 提交:`POST /rest/contests/{contest}/challenges/{slug}/submissions`,
 *   头 `x-csrf-token`,JSON body `{language, contest_slug, challenge_slug, code}`,
 *   响应 `{model:{id}}`;
 * - 提交结果:`GET /rest/contests/{contest}/challenges/{slug}/submissions/{id}`,
 *   响应 `{model:{status, score_processed, ...}}`,旧实现 3s 间隔轮询至
 *   score_processed == 3。
 *
 * ## remoteId 约定
 *
 * HackerRank 题目由 (contestSlug, challengeSlug) 二元组寻址;SPI 的 remoteId 为
 * 单串,约定:**master 竞赛题为裸 slug**(如 "solve-me-first",主流量形态);
 * **projecteuler 竞赛题为 `projecteuler/<slug>`**(避免与 master 命名空间碰撞)。
 * 解析见 [parseChallengeRemoteId]。
 */
val HACKERRANK_PLATFORM_ID: PlatformId = PlatformId("hackerrank")

const val HACKERRANK_DOMAIN: String = "www.hackerrank.com"

/** master 竞赛 slug(旧 HackerRankContest.Master)。 */
const val HACKERRANK_MASTER_CONTEST: String = "master"

/** projecteuler 竞赛 slug(旧 HackerRankContest.ProjectEuler,无 tracks 段特例)。 */
const val HACKERRANK_PROJECT_EULER_CONTEST: String = "projecteuler"

private fun baseUrl(): String = "https://$HACKERRANK_DOMAIN"

/** 主站根(cookie 注入基准)。 */
val HACKERRANK_HOME_URL: String = baseUrl() + "/"

/** 登录态校验端点(2xx 即已登录,旧 checkLogin 事实)。 */
val HACKERRANK_LOGIN_CHECK_URL: String = baseUrl() + "/community/v1/promotion_slots/banner-dashboard"

/** dashboard(用户信息 + 题库领域树,数据在 `__NEXT_DATA__` 内)。 */
val HACKERRANK_DASHBOARD_URL: String = baseUrl() + "/dashboard"

/** 题库列表端点:master 走 tracks 段,projecteuler 直挂 challenges(旧实现事实)。 */
fun hackerrankChallengeListUrl(
    contestSlug: String,
    domainSlug: String,
): String =
    if (contestSlug == HACKERRANK_PROJECT_EULER_CONTEST) {
        baseUrl() + "/rest/contests/$contestSlug/challenges"
    } else {
        baseUrl() + "/rest/contests/$contestSlug/tracks/$domainSlug/challenges"
    }

/** 关键字搜索端点(跨题库 appsearch)。 */
fun hackerrankAppSearchUrl(
    contestSlug: String,
    keyword: String,
): String = baseUrl() + "/appsearch?contest_slug=$contestSlug&query=$keyword"

/** 题目页(master:`/challenges/{slug}/problem`;projecteuler 带 contests 段)。 */
fun hackerrankChallengeProblemUrl(
    contestSlug: String,
    challengeSlug: String,
): String =
    if (contestSlug == HACKERRANK_PROJECT_EULER_CONTEST) {
        baseUrl() + "/contests/$contestSlug/challenges/$challengeSlug/problem"
    } else {
        baseUrl() + "/challenges/$challengeSlug/problem"
    }

/** 提交端点(JSON POST,头带 x-csrf-token)。 */
fun hackerrankSubmitUrl(
    contestSlug: String,
    challengeSlug: String,
): String = baseUrl() + "/rest/contests/$contestSlug/challenges/$challengeSlug/submissions"

/** 提交结果端点(轮询)。 */
fun hackerrankSubmissionUrl(
    contestSlug: String,
    challengeSlug: String,
    submissionId: String,
): String = hackerrankSubmitUrl(contestSlug, challengeSlug) + "/$submissionId"

/** remoteId 组装:master 裸 slug,其余竞赛带前缀。 */
fun hackerrankChallengeRemoteId(
    contestSlug: String,
    challengeSlug: String,
): String =
    if (contestSlug == HACKERRANK_MASTER_CONTEST || contestSlug.isEmpty()) {
        challengeSlug
    } else {
        "$contestSlug/$challengeSlug"
    }

/**
 * 解析题目标识:含 `/` 时 contest=首段、slug=末段;否则 contest=master、整体为 slug。
 * 非法形态(空串/仅 `/`)抛领域异常。
 */
fun parseChallengeRemoteId(remoteId: String): Pair<String, String> {
    if (remoteId.isEmpty() || remoteId == "/") {
        throw com.codequest.core.model.OJException(
            "非法 HackerRank 题目标识: '$remoteId'(约定:master 裸 slug,或 <contest>/<slug>)",
        )
    }
    val contest = remoteId.substringBefore('/', "")
    val slug = remoteId.substringAfter('/', "")
    if (contest.isEmpty()) return HACKERRANK_MASTER_CONTEST to remoteId
    if (slug.isEmpty()) {
        throw com.codequest.core.model.OJException(
            "非法 HackerRank 题目标识: '$remoteId'(约定:master 裸 slug,或 <contest>/<slug>)",
        )
    }
    return contest to slug
}
