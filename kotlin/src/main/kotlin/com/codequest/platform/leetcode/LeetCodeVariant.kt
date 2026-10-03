package com.codequest.platform.leetcode

import com.codequest.core.model.PlatformId

/**
 * LeetCode 双站点变体(WP19):leetcode.com 与 leetcode.cn 同码不同 id + domain。
 *
 * ## 选型说明:枚举变体 + 单平台类,而非两个 object
 *
 * 旧实现(CN/COM GraphQL 查询、变量结构差异)证明两站点**协议有差异**,
 * 差异点(题目列表查询/题解查询)由 [LeetCodeApi] 按 [isCn] 分支;而平台身份
 * (id/domain/显示名)是纯数据。用枚举承载身份、单一 [LeetCodePlatform] 类承载行为,
 * 避免「两个 object 各自组装 auth/problem/submission」的复制;测试与 provider
 * 均按变体参数化。题目/提交等公共端点(`/graphql/`、`/problems/{slug}/submit/`、
 * `/submissions/detail/{id}/check/`)两站点结构一致(旧实现事实),不做双域名
 * 行为差异探测(YAGNI)。
 */
enum class LeetCodeVariant(
    val id: PlatformId,
    val domain: String,
    val displayName: String,
) {
    LEETCODE(PlatformId("leetcode"), "leetcode.com", "LeetCode"),
    LEETCODE_CN(PlatformId("leetcodecn"), "leetcode.cn", "力扣"),
    ;

    val isCn: Boolean
        get() = this == LEETCODE_CN

    /** GraphQL 端点(旧实现固定带尾斜杠)。 */
    val graphqlUrl: String
        get() = "https://$domain/graphql/"

    /** 主站根(Referer / cookie 注入基准)。 */
    val homeUrl: String
        get() = "https://$domain/"

    /** csrftoken 获取端点(GET,响应 Set-Cookie)。 */
    val csrfUrl: String
        get() = "https://$domain/api/home/"

    /** 提交端点(REST POST,question_id + typed_code)。 */
    fun submitUrl(problemSlug: String): String = "https://$domain/problems/$problemSlug/submit/"

    /** 云端测试端点(WP53,REST POST,lang + data_input + question_id + typed_code)。 */
    fun interpretSolutionUrl(problemSlug: String): String = "https://$domain/problems/$problemSlug/interpret_solution/"

    /** 提交结果轮询端点(REST GET)。 */
    fun submissionCheckUrl(submissionId: String): String = "https://$domain/submissions/detail/$submissionId/check/"

    /** 我的收藏夹清单端点(WP56,REST 备选,旧插件端点事实;匿名 404,登录态探测)。 */
    val listFavoritesUrl: String
        get() = "https://$domain/api/list_favorites/"

    /**
     * 竞赛详情端点(1.8.0 F2,REST GET,CN 站竞赛回顾)。2026-10-03 实测:
     * 竞赛不存在时 HTTP 仍 200 + `{"error":"竞赛不存在"}`(以 body error 判缺失)。
     */
    fun contestInfoUrl(contestSlug: String): String = "https://$domain/contest/api/info/$contestSlug/"
}
