package com.codequest.platform.luogu

import com.codequest.core.model.PlatformId

/**
 * 洛谷平台身份与端点常量(WP26,M4 四平台之四:题解列表)。
 *
 * 协议事实(旧 Scala 实现逐字核对,luogu/services/LuoGuApi.scala;firecrawl rawHtml
 * 佐证 2026-08 仍有效——`_contentOnly=1` 响应为 HTML,数据在
 * `<script id="lentille-context" type="application/json">` 内,JSON 根为
 * `{instance,template,status,locale,data:{...}}`):
 * - 反爬通道:GET 首页 → 响应体含 `C3VK=(\w+);` 时注入同名 cookie 后重试,
 *   否则取 `meta[name=csrf-token]` 的 content 作 CSRF token;
 * - 公共请求头:`x-csrf-token` + `x-requested-with: XMLHttpRequest`;
 * - 题目列表:`GET /problem/list?page=&_contentOnly=1[&difficulty=&type=&tag=&keyword=]`
 *   → `data.problems.{perPage,count,result}`;
 * - 题目详情:`GET /problem/{pid}`(不带 `_contentOnly`,响应同样含 lentille-context)
 *   → `data.problem.*`(题面各字段为 Markdown 原文,含 LaTeX);
 * - 题解列表:`GET /problem/solution/{pid}?_contentOnly=1` → `data.solutions.result`
 *   (未登录返回 401 纯 JSON `UserUnloginException`,已实测);
 * - 登录态:`GET /user/setting?_contentOnly=1`(200 且 `<title>` 为已知登录态文案);
 * - 提交:`POST /fe/api/problem/submit/{pid}`,JSON body `{lang:int, enableO2:1, code}`,
 *   头另带 `Referer: /problem/{pid}`,响应 `{rid}`;403 = 验证码/权限(旧实现有
 *   易盾/图片验证码 UI,依赖未迁移的旧 webview 静态资源,本 WP 按错误映射兜底);
 * - 提交结果:`GET /record/{rid}`(无公共头),HTML 内
 *   `decodeURIComponent("...")` 的 URL 编码 JSON → `currentData.record.status`。
 *
 * ## remoteId 约定
 *
 * 平台侧题目标识 = 洛谷 pid(如 "P1001" / "B2001" / "CF123A"),自带题库前缀。
 */
val LUOGU_PLATFORM_ID: PlatformId = PlatformId("luogu")

const val LUOGU_DOMAIN: String = "www.luogu.com.cn"

private fun baseUrl(): String = "https://$LUOGU_DOMAIN"

/** 主站根(C3VK 反爬通道与 cookie 注入基准)。 */
val LUOGU_HOME_URL: String = baseUrl() + "/"

/** 登录态校验页(`_contentOnly=1`,200 且 title 为已知登录态文案即已登录)。 */
val LUOGU_USER_SETTING_URL: String = baseUrl() + "/user/setting?_contentOnly=1"

/** 题目列表页(单页 perPage=50,lentille 数据在 data.problems)。 */
fun luoguProblemListUrl(page: Int): String = baseUrl() + "/problem/list?page=$page&_contentOnly=1"

/** 题目详情页(lentille 数据在 data.problem,题面为 Markdown)。 */
fun luoguProblemUrl(pid: String): String = baseUrl() + "/problem/$pid"

/** 题解列表页(需登录;lentille 数据在 data.solutions)。 */
fun luoguSolutionListUrl(pid: String): String = baseUrl() + "/problem/solution/$pid?_contentOnly=1"

/** 提交端点(JSON POST;头带 x-csrf-token/x-requested-with/Referer)。 */
fun luoguSubmitUrl(pid: String): String = baseUrl() + "/fe/api/problem/submit/$pid"

/** 提交结果页(无公共头;JSON 藏在 decodeURIComponent(...) 内)。 */
fun luoguRecordUrl(recordId: String): String = baseUrl() + "/record/$recordId"

/** 题解文章浏览器兜底出口(solution 页锚点定位到具体楼层)。 */
fun luoguSolutionArticleUrl(
    pid: String,
    solutionId: String,
): String = baseUrl() + "/problem/solution/$pid#solution-$solutionId"
