package com.codequest.platform.leetcode

import com.codequest.core.model.OJException
import com.codequest.spi.CodeSnippet
import com.codequest.spi.OJTransport
import com.codequest.spi.ProblemFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * LeetCode API 封装(WP19):GraphQL / REST 端点到 Kotlin 数据的纯映射层。
 *
 * 端点与协议事实沿用旧 Scala 实现(LeetCodeApi.scala):
 * - 登录态:`globalData` 查询 → `data.userStatus.isSignedIn`;
 * - 题目列表:CN `problemsetQuestionList`(skip/limit/filters)、COM `problemsetQuestionListV2`
 *   (skip/limit/searchKeyword/filtersV2 结构 + sortBy);
 * - 题目详情:`questionData`(titleSlug → content,HTML);
 * - 提交:REST `POST /problems/{slug}/submit/`(`{lang, question_id, typed_code}`,
 *   snake_case 信封),响应 `{submission_id}`;
 * - 提交轮询:REST `GET /submissions/detail/{id}/check/`(`state` ∈ STARTED/PENDING/SUCCESS,
 *   SUCCESS 时 `status_msg` 给出判定);
 * - 题解列表:CN `questionTopicsList` → `data.questionSolutionArticles`、
 *   COM `ugcArticleSolutionArticles` → `data.ugcArticleSolutionArticles`;
 * - 题解正文(WP31):CN `discussTopic` → `data.solutionArticle`、COM
 *   `ugcArticleSolutionArticle` → `data.ugcArticleSolutionArticle`
 *   (content 为 Markdown 原文,COM 站点为二次转义形态);
 * - 公司题单(WP42):公司列表 `questionCompanyTagsV2`(CN 走 companyTagsV2.
 *   companyTagInfoNodes / COM 走 companyTags)、公司题单 `favoriteQuestionList`
 *   (favoriteSlug = 公司 slug[-期间后缀],filtersV2 完整骨架同 problemsetQuestionListV2)。
 *
 * 所有请求携带 `x-csrftoken` + `Referer`(csrftoken 取自 `/api/home/` 种下的 cookie,
 * 缺失时为空串,与旧实现一致)。非 2xx 已由 [OJTransport] 翻译为领域异常;
 * GraphQL `errors` 信封与结构缺失抛 [OJException]。
 */
class LeetCodeApi(
    private val variant: LeetCodeVariant,
    private val transport: OJTransport,
) {
    private val json = Json

    // -------------------------------------------------------------------
    // 公共请求底座
    // -------------------------------------------------------------------

    /** GET /api/home/ 触发 Set-Cookie csrftoken,返回该平台 Jar 中的 csrftoken(缺失为空串)。 */
    internal suspend fun fetchCsrfToken(): String {
        transport.get(variant.id, variant.csrfUrl)
        return transport.cookies(variant.id, variant.homeUrl)["csrftoken"].orEmpty()
    }

    /** 旧实现 commonHeaders:x-csrftoken + Referer。 */
    private suspend fun csrfHeaders(): Map<String, String> =
        mapOf(
            "x-csrftoken" to fetchCsrfToken(),
            "Referer" to variant.homeUrl,
        )

    /** GraphQL 收口:校验 errors 信封并取出 data 对象。 */
    private suspend fun graphql(
        operationName: String,
        query: String,
        variables: JsonObject,
    ): JsonObject {
        val raw =
            transport.postGraphQL(
                platformId = variant.id,
                url = variant.graphqlUrl,
                query = query,
                variables = variables.toString(),
                operationName = operationName,
                headers = csrfHeaders(),
            )
        val body = json.parseToJsonElement(raw).jsonObject
        body["errors"]?.let { errors ->
            if (errors is JsonObject || errors.jsonArray.isNotEmpty()) {
                throw OJException("LeetCode GraphQL '$operationName' 返回 errors: $errors")
            }
        }
        return body["data"] as? JsonObject
            ?: throw OJException("LeetCode GraphQL '$operationName' 响应缺少 data 字段(可能为 null)")
    }

    private fun JsonObject.child(name: String): JsonObject = this[name]?.jsonObject ?: throw OJException("LeetCode 响应缺少字段 '$name': $this")

    // -------------------------------------------------------------------
    // 登录态
    // -------------------------------------------------------------------

    /** 登录态查询结果。 */
    data class LeetCodeUserStatus(
        val isSignedIn: Boolean,
        val username: String?,
        val userSlug: String?,
    )

    /** `globalData` → data.userStatus(isSignedIn / username / userSlug)。 */
    suspend fun fetchUserStatus(): LeetCodeUserStatus {
        val data = graphql("globalData", LeetCodeGraphQL.GLOBAL_DATA, buildJsonObject { })
        val status = data.child("userStatus")
        return LeetCodeUserStatus(
            isSignedIn =
                status["isSignedIn"]?.jsonPrimitive?.content?.toBooleanStrict()
                    ?: throw OJException("LeetCode userStatus 缺少 isSignedIn"),
            username = status["username"]?.jsonPrimitive?.contentOrNullIfNull(),
            userSlug = status["userSlug"]?.jsonPrimitive?.contentOrNullIfNull(),
        )
    }

    // -------------------------------------------------------------------
    // 题目列表
    // -------------------------------------------------------------------

    /** 列表项(remoteId 即 titleSlug,与旧实现 titleSlug 语义一致)。 */
    data class LeetCodeQuestionListItem(
        val frontendQuestionId: String,
        val title: String,
        val titleSlug: String,
        val difficulty: String,
        val paidOnly: Boolean,
        val topicTags: List<String>,
        /** 站内做题状态原文("ac"/"AC"/"notac"/"NOT_AC"/null);1.2.1 起透传给 [com.codequest.spi.ProblemSummary.solvedOnPlatform]。 */
        val status: String? = null,
    )

    data class LeetCodeQuestionPage(
        val total: Int,
        val questions: List<LeetCodeQuestionListItem>,
    )

    /**
     * 题目列表分页查询。[skip]/[limit] 对应旧实现的 offset/limit;
     * [filter] 的 keyword → searchKeywords(CN)/searchKeyword(COM)、
     * difficulty → EASY/MEDIUM/HARD、tags → 标签 slug 列表(协议事实同旧实现)。
     */
    suspend fun fetchQuestionPage(
        filter: ProblemFilter,
        skip: Int,
        limit: Int,
    ): LeetCodeQuestionPage =
        if (variant.isCn) {
            fetchQuestionPageCn(filter, skip, limit)
        } else {
            fetchQuestionPageCom(filter, skip, limit)
        }

    /** LeetCode 搜索过滤的难度取值(旧实现 leetCodeDifficulty:EASY/MEDIUM/HARD)。 */
    private fun difficultyFilterValue(raw: String): String? =
        when (raw.lowercase()) {
            "easy" -> "EASY"
            "medium" -> "MEDIUM"
            "hard" -> "HARD"
            else -> null
        }

    private suspend fun fetchQuestionPageCn(
        filter: ProblemFilter,
        skip: Int,
        limit: Int,
    ): LeetCodeQuestionPage {
        val variables =
            buildJsonObject {
                put("skip", skip)
                put("limit", limit)
                put("categorySlug", "all-code-essentials")
                put(
                    "filters",
                    buildJsonObject {
                        filter.keyword?.let { put("searchKeywords", it) }
                        filter.difficulty?.let { difficultyFilterValue(it.value) }?.let { put("difficulty", it) }
                        if (filter.tags.isNotEmpty()) put("tags", stringArray(filter.tags))
                    },
                )
            }
        val data = graphql("problemsetQuestionList", LeetCodeGraphQL.PROBLEMSET_QUESTION_LIST_CN, variables)
        val list = data.child("problemsetQuestionList")
        return LeetCodeQuestionPage(
            total = list["total"]?.jsonPrimitive?.intOrNull ?: 0,
            questions =
                list["questions"]?.jsonArray.orEmpty().map { element ->
                    val q = element.jsonObject
                    LeetCodeQuestionListItem(
                        frontendQuestionId = q["frontendQuestionId"]?.jsonPrimitive?.content.orEmpty(),
                        // 旧实现事实(UI 三处消费):titleCn 非空优先(CN 中文),title 兜底
                        title =
                            q["titleCn"]?.jsonPrimitive?.contentOrNullIfNull()?.takeIf { it.isNotEmpty() }
                                ?: q["title"]?.jsonPrimitive?.content.orEmpty(),
                        titleSlug = q["titleSlug"]?.jsonPrimitive?.content.orEmpty(),
                        difficulty = q["difficulty"]?.jsonPrimitive?.content.orEmpty(),
                        paidOnly = q["paidOnly"]?.jsonPrimitive?.content == "true",
                        status = q["status"]?.jsonPrimitive?.contentOrNullIfNull(),
                        topicTags =
                            q["topicTags"]
                                ?.jsonArray
                                .orEmpty()
                                .map { it.jsonObject["name"]?.jsonPrimitive?.content }
                                .filterNotNull(),
                    )
                },
        )
    }

    private suspend fun fetchQuestionPageCom(
        filter: ProblemFilter,
        skip: Int,
        limit: Int,
    ): LeetCodeQuestionPage {
        val variables =
            buildJsonObject {
                put("skip", skip)
                put("limit", limit)
                put("categorySlug", "all-code-essentials")
                put("searchKeyword", filter.keyword.orEmpty())
                put(
                    "filters",
                    buildJsonObject {
                        // 空子过滤结构为旧实现逐字事实(服务端要求完整 filter 骨架)
                        put(
                            "companyFilter",
                            buildJsonObject {
                                put("companySlugs", stringArray(emptyList()))
                                put("operator", "IS")
                            },
                        )
                        put(
                            "positionFilter",
                            buildJsonObject {
                                put("positionSlugs", stringArray(emptyList()))
                                put("operator", "IS")
                            },
                        )
                        put("acceptanceFilter", buildJsonObject { })
                        put("frequencyFilter", buildJsonObject { })
                        put(
                            "languageFilter",
                            buildJsonObject {
                                put("languageSlugs", stringArray(emptyList()))
                                put("operator", "IS")
                            },
                        )
                        put(
                            "difficultyFilter",
                            buildJsonObject {
                                put(
                                    "difficulties",
                                    stringArray(listOfNotNull(filter.difficulty?.let { difficultyFilterValue(it.value) })),
                                )
                                put("operator", "IS")
                            },
                        )
                        put(
                            "premiumFilter",
                            buildJsonObject {
                                put("premiumStatus", stringArray(emptyList()))
                                put("operator", "IS")
                            },
                        )
                        put(
                            "statusFilter",
                            buildJsonObject {
                                put("questionStatuses", stringArray(emptyList()))
                                put("operator", "IS")
                            },
                        )
                        put(
                            "topicFilter",
                            buildJsonObject {
                                put("topicSlugs", stringArray(filter.tags))
                                put("operator", "IS")
                            },
                        )
                        put("filterCombineType", "ALL")
                    },
                )
                put(
                    "sortBy",
                    buildJsonObject {
                        put("sortField", "CUSTOM")
                        put("sortOrder", "ASCENDING")
                    },
                )
            }
        val data = graphql("problemsetQuestionListV2", LeetCodeGraphQL.PROBLEMSET_QUESTION_LIST_V2, variables)
        val list = data.child("problemsetQuestionListV2")
        return LeetCodeQuestionPage(
            total = list["totalLength"]?.jsonPrimitive?.intOrNull ?: 0,
            questions =
                list["questions"]?.jsonArray.orEmpty().map { element ->
                    val q = element.jsonObject
                    LeetCodeQuestionListItem(
                        frontendQuestionId = q["questionFrontendId"]?.jsonPrimitive?.content.orEmpty(),
                        title = q["title"]?.jsonPrimitive?.content.orEmpty(),
                        titleSlug = q["titleSlug"]?.jsonPrimitive?.content.orEmpty(),
                        difficulty = q["difficulty"]?.jsonPrimitive?.content.orEmpty(),
                        paidOnly = q["paidOnly"]?.jsonPrimitive?.content == "true",
                        status = q["status"]?.jsonPrimitive?.contentOrNullIfNull(),
                        topicTags = emptyList(), // V2 查询不含 topicTags(旧实现同款字段集)
                    )
                },
        )
    }

    // -------------------------------------------------------------------
    // 公司题单(WP42)
    // -------------------------------------------------------------------

    /** 公司标签(旧 LeetCodeQuestionCompanyTag:name/slug/questionCount;id 无消费方不透出)。 */
    data class LeetCodeCompanyTag(
        val name: String,
        val slug: String,
        val questionCount: Int,
    )

    /**
     * 公司列表(`questionCompanyTagsV2`,变量为空对象——旧 getQuestionCompanyTags 事实):
     * CN 响应 `data.companyTagsV2.companyTagInfoNodes`(questionCount 为 companyQuestionNum
     * 别名)、COM 响应 `data.companyTags`,查询文本与响应路径均按站点分支。
     */
    suspend fun fetchCompanyTags(): List<LeetCodeCompanyTag> {
        val data =
            graphql(
                "questionCompanyTagsV2",
                if (variant.isCn) LeetCodeGraphQL.QUESTION_COMPANY_TAGS_V2_CN else LeetCodeGraphQL.QUESTION_COMPANY_TAGS_V2,
                buildJsonObject { },
            )
        val nodes =
            if (variant.isCn) {
                data.child("companyTagsV2")["companyTagInfoNodes"]?.jsonArray
            } else {
                data["companyTags"]?.jsonArray
            }
        return nodes.orEmpty().map { element ->
            val node = element.jsonObject
            LeetCodeCompanyTag(
                name = node["name"]?.jsonPrimitive?.content.orEmpty(),
                slug = node["slug"]?.jsonPrimitive?.content.orEmpty(),
                questionCount = node["questionCount"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }

    /**
     * 公司题单分页查询(`favoriteQuestionList`,旧 searchCompanyChallenges 逐字事实):
     *
     * - `favoriteSlug` = [companySlugs] 首项 +(`-$interviewPeriodSlug` 后缀,非 null 时);
     * - 其余公司进 `filtersV2.companyFilter.companySlugs`;
     * - [filter] 的 difficulty → difficultyFilter、tags → topicFilter;keyword 无对应
     *   输入(旧实现恒发空串 `searchKeyword: ""`,公司查询面板无关键字入口);
     * - `sortBy` 恒 `{CUSTOM, ASCENDING}`(旧实现无排序输入时的兜底值);
     * - `filtersV2` 为完整骨架(空子过滤结构为服务端要求的旧实现逐字事实)。
     *
     * [companySlugs] 为空时直接返回空页、不发请求(旧 Presenter 同款守卫)。
     * 响应项 `translatedTitle` 非空优先(旧 LeetCodeChallengeListItem.titleCn 语义)。
     */
    suspend fun fetchCompanyQuestionPage(
        companySlugs: List<String>,
        interviewPeriodSlug: String?,
        filter: ProblemFilter,
        skip: Int,
        limit: Int,
    ): LeetCodeQuestionPage {
        if (companySlugs.isEmpty()) return LeetCodeQuestionPage(total = 0, questions = emptyList())
        val favorite = companySlugs.first()
        val favoriteSlug = interviewPeriodSlug?.let { "$favorite-$it" } ?: favorite
        val variables =
            buildJsonObject {
                put("skip", skip)
                put("limit", limit)
                put("favoriteSlug", favoriteSlug)
                put(
                    "filtersV2",
                    buildJsonObject {
                        put(
                            "companyFilter",
                            buildJsonObject {
                                put("companySlugs", stringArray(companySlugs.drop(1)))
                                put("operator", "IS")
                            },
                        )
                        put(
                            "positionFilter",
                            buildJsonObject {
                                put("positionSlugs", stringArray(emptyList()))
                                put("operator", "IS")
                            },
                        )
                        put("acceptanceFilter", buildJsonObject { })
                        put("frequencyFilter", buildJsonObject { })
                        put(
                            "languageFilter",
                            buildJsonObject {
                                put("languageSlugs", stringArray(emptyList()))
                                put("operator", "IS")
                            },
                        )
                        put(
                            "difficultyFilter",
                            buildJsonObject {
                                put(
                                    "difficulties",
                                    stringArray(listOfNotNull(filter.difficulty?.let { difficultyFilterValue(it.value) })),
                                )
                                put("operator", "IS")
                            },
                        )
                        put(
                            "premiumFilter",
                            buildJsonObject {
                                put("premiumStatus", stringArray(emptyList()))
                                put("operator", "IS")
                            },
                        )
                        put(
                            "statusFilter",
                            buildJsonObject {
                                put("questionStatuses", stringArray(emptyList()))
                                put("operator", "IS")
                            },
                        )
                        put(
                            "topicFilter",
                            buildJsonObject {
                                put("topicSlugs", stringArray(filter.tags))
                                put("operator", "IS")
                            },
                        )
                        put("filterCombineType", "ALL")
                    },
                )
                put(
                    "sortBy",
                    buildJsonObject {
                        put("sortField", "CUSTOM")
                        put("sortOrder", "ASCENDING")
                    },
                )
                put("searchKeyword", "")
            }
        val data =
            graphql(
                "favoriteQuestionList",
                if (variant.isCn) LeetCodeGraphQL.FAVORITE_QUESTION_LIST_CN else LeetCodeGraphQL.FAVORITE_QUESTION_LIST,
                variables,
            )
        val list = data.child("favoriteQuestionList")
        return LeetCodeQuestionPage(
            total = list["total"]?.jsonPrimitive?.intOrNull ?: 0,
            questions =
                list["questions"]?.jsonArray.orEmpty().map { element ->
                    val q = element.jsonObject
                    LeetCodeQuestionListItem(
                        // 字段名与普通列表不同:questionFrontendId / translatedTitle(旧模型
                        // 即以 frontendQuestionId/titleCn 别名承载)
                        frontendQuestionId = q["questionFrontendId"]?.jsonPrimitive?.content.orEmpty(),
                        title =
                            q["translatedTitle"]?.jsonPrimitive?.contentOrNullIfNull()?.takeIf { it.isNotEmpty() }
                                ?: q["title"]?.jsonPrimitive?.content.orEmpty(),
                        titleSlug = q["titleSlug"]?.jsonPrimitive?.content.orEmpty(),
                        difficulty = q["difficulty"]?.jsonPrimitive?.content.orEmpty(),
                        paidOnly = q["paidOnly"]?.jsonPrimitive?.content == "true",
                        status = q["status"]?.jsonPrimitive?.contentOrNullIfNull(),
                        topicTags =
                            q["topicTags"]
                                ?.jsonArray
                                .orEmpty()
                                .map { it.jsonObject["name"]?.jsonPrimitive?.content }
                                .filterNotNull(),
                    )
                },
        )
    }

    // -------------------------------------------------------------------
    // 题目详情
    // -------------------------------------------------------------------

    /** 题目详情(questionData 映射;content 为 HTML,CN 站点可回退 translatedContent)。 */
    data class LeetCodeQuestionDetail(
        val questionId: String,
        val titleSlug: String,
        val title: String,
        val difficulty: String,
        val isPaidOnly: Boolean,
        /** 英文题面(HTML);会员题或缺失时可能为 null。 */
        val content: String?,
        /** 中文题面(HTML,CN 站点);缺失为 null。 */
        val translatedContent: String?,
        val topicTags: List<String>,
        /** 各语言起始代码(1.2.4);langSlug 与 WorkspaceLanguage.id 同名约定。 */
        val codeSnippets: List<CodeSnippet> = emptyList(),
    )

    suspend fun fetchQuestionDetail(slug: String): LeetCodeQuestionDetail {
        val variables =
            buildJsonObject {
                put("titleSlug", slug)
            }
        val data = graphql("questionData", LeetCodeGraphQL.QUESTION_DATA, variables)
        val question =
            data["question"]?.jsonObject
                ?: throw OJException("LeetCode 题目不存在或不可见: $slug")
        return LeetCodeQuestionDetail(
            questionId = question["questionId"]?.jsonPrimitive?.content.orEmpty(),
            titleSlug = question["titleSlug"]?.jsonPrimitive?.content ?: slug,
            title = question["title"]?.jsonPrimitive?.content.orEmpty(),
            difficulty = question["difficulty"]?.jsonPrimitive?.content.orEmpty(),
            isPaidOnly = question["isPaidOnly"]?.jsonPrimitive?.content == "true",
            content = question["content"]?.jsonPrimitive?.contentOrNullIfNull(),
            translatedContent = question["translatedContent"]?.jsonPrimitive?.contentOrNullIfNull(),
            topicTags =
                question["topicTags"]
                    ?.jsonArray
                    .orEmpty()
                    .map { it.jsonObject["name"]?.jsonPrimitive?.content }
                    .filterNotNull(),
            codeSnippets =
                question["codeSnippets"]
                    ?.jsonArray
                    .orEmpty()
                    .mapNotNull { element ->
                        val snippet = element.jsonObject
                        val code = snippet["code"]?.jsonPrimitive?.contentOrNullIfNull() ?: return@mapNotNull null
                        CodeSnippet(
                            lang = snippet["lang"]?.jsonPrimitive?.content.orEmpty(),
                            langSlug = snippet["langSlug"]?.jsonPrimitive?.content.orEmpty(),
                            code = code,
                        )
                    },
        )
    }

    // -------------------------------------------------------------------
    // 题解列表
    // -------------------------------------------------------------------

    data class LeetCodeSolutionArticle(
        val uuid: String,
        val title: String,
        val slug: String,
    )

    /**
     * 官方题解列表(CN questionTopicsList / COM ugcArticleSolutionArticles)。
     * [orderBy] 为排序键(WP41,默认 HOT 同旧实现;映射见
     * [solutionOrderByGraphQLValue]);解析大小写不敏感,未知值回退 HOT。
     */
    suspend fun fetchSolutionArticles(
        questionSlug: String,
        orderBy: String? = null,
        limit: Int = DEFAULT_SOLUTION_LIMIT,
    ): List<LeetCodeSolutionArticle> {
        val operationName: String
        val query: String
        val dataField: String
        if (variant.isCn) {
            operationName = "questionTopicsList"
            query = LeetCodeGraphQL.SOLUTION_ARTICLES_CN
            dataField = "questionSolutionArticles"
        } else {
            operationName = "ugcArticleSolutionArticles"
            query = LeetCodeGraphQL.SOLUTION_ARTICLES_COM
            dataField = "ugcArticleSolutionArticles"
        }
        val variables =
            buildJsonObject {
                put("skip", 0)
                put("first", limit)
                put("questionSlug", questionSlug)
                // WP41:排序键映射;COM+oldest 无对应值,按旧实现发 JsonNull
                put(
                    "orderBy",
                    solutionOrderByGraphQLValue(variant, orderBy)?.let(::JsonPrimitive) ?: JsonNull,
                )
                put("userInput", "")
                put("tagSlugs", stringArray(emptyList()))
            }
        val data = graphql(operationName, query, variables)
        return data.child(dataField)["edges"]?.jsonArray.orEmpty().map { edge ->
            val node = edge.jsonObject.child("node")
            LeetCodeSolutionArticle(
                uuid = node["uuid"]?.jsonPrimitive?.content.orEmpty(),
                title = node["title"]?.jsonPrimitive?.content.orEmpty(),
                slug = node["slug"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }

    /**
     * 题解排序键(REST/桥 camelCase)→ 站点 GraphQL orderBy 变量(WP41;
     * 旧 LeetCodeQuestionSolutionArticlesOrderBy 逐字事实):
     *
     * | 键          | CN               | COM           |
     * |-------------|------------------|---------------|
     * | mostUpvote  | MOST_UPVOTE      | MOST_UPVOTE   |
     * | hot         | HOT              | HOT           |
     * | newest      | NEWEST_TO_OLDEST | MOST_RECENT   |
     * | oldest      | OLDEST_TO_NEWEST | (无,发 null) |
     *
     * null/空/未知键回退 HOT(旧实现默认;端点侧不校验,消费方为自家前端下拉)。
     * 解析大小写不敏感、忽略首尾空白。
     */
    internal fun solutionOrderByGraphQLValue(
        variant: LeetCodeVariant,
        orderBy: String?,
    ): String? {
        val key = orderBy?.trim()?.lowercase()
        return when (key) {
            "mostupvote" -> "MOST_UPVOTE"
            null, "", "hot" -> "HOT"
            "newest" -> if (variant.isCn) "NEWEST_TO_OLDEST" else "MOST_RECENT"
            "oldest" -> if (variant.isCn) "OLDEST_TO_NEWEST" else null
            else -> "HOT"
        }
    }

    /** 官方题解正文(WP31;content 为 Markdown 原文,COM 站点为二次转义形态)。 */
    data class LeetCodeSolutionArticleDetail(
        val title: String,
        val slug: String,
        val content: String,
    )

    /**
     * 官方题解正文(CN `discussTopic` → `data.solutionArticle` / COM
     * `ugcArticleSolutionArticle` → `data.ugcArticleSolutionArticle`,
     * 变量均为 `{slug}`——旧 getSolutionArticle 事实)。content 缺失
     * (文章不可见/会员限定)抛 [OJException];COM 站点的反转义由
     * [LeetCodeSolutions] 承担(与旧 ArticleDetailPresenter 同层)。
     */
    suspend fun fetchSolutionArticleDetail(articleSlug: String): LeetCodeSolutionArticleDetail {
        val operationName: String
        val query: String
        val dataField: String
        if (variant.isCn) {
            operationName = "discussTopic"
            query = LeetCodeGraphQL.SOLUTION_ARTICLE_CN
            dataField = "solutionArticle"
        } else {
            operationName = "ugcArticleSolutionArticle"
            query = LeetCodeGraphQL.SOLUTION_ARTICLE_COM
            dataField = "ugcArticleSolutionArticle"
        }
        val variables =
            buildJsonObject {
                put("slug", articleSlug)
            }
        val data = graphql(operationName, query, variables)
        val node =
            data[dataField] as? JsonObject
                ?: throw OJException("LeetCode 题解文章不存在或不可见: $articleSlug")
        val content =
            node["content"]?.jsonPrimitive?.contentOrNullIfNull()
                ?: throw OJException("LeetCode 题解文章正文不可见(可能需要登录或会员): $articleSlug")
        return LeetCodeSolutionArticleDetail(
            title = node["title"]?.jsonPrimitive?.content.orEmpty(),
            slug = node["slug"]?.jsonPrimitive?.content ?: articleSlug,
            content = content,
        )
    }

    // -------------------------------------------------------------------
    // 提交与轮询(REST,旧实现事实)
    // -------------------------------------------------------------------

    /** 提交代码,返回平台侧 submissionId。 */
    suspend fun submitCode(
        questionId: String,
        problemSlug: String,
        langSlug: String,
        code: String,
    ): String {
        val body =
            buildJsonObject {
                put("lang", langSlug)
                put("question_id", questionId)
                put("typed_code", code)
            }.toString()
        val raw = transport.postJson(variant.id, variant.submitUrl(problemSlug), body, csrfHeaders())
        val submissionId =
            json
                .parseToJsonElement(raw)
                .jsonObject["submission_id"]
                ?.jsonPrimitive
                ?.intOrNull
                ?: throw OJException("LeetCode 提交响应缺少 submission_id: $raw")
        return submissionId.toString()
    }

    /** 提交结果快照(/check/ 端点关键字段)。 */
    data class LeetCodeSubmissionCheck(
        /** STARTED / PENDING / SUCCESS。 */
        val state: String,
        /** SUCCESS 时的判定原文,如 "Accepted" / "Wrong Answer" / "Compile Error"。 */
        val statusMsg: String?,
        /** 内存(KB,平台原始 int)。 */
        val memory: Int?,
        /** 耗时原文,如 "4 ms"。 */
        val statusRuntime: String?,
    )

    suspend fun fetchSubmissionCheck(submissionId: String): LeetCodeSubmissionCheck {
        val raw = transport.get(variant.id, variant.submissionCheckUrl(submissionId), csrfHeaders())
        val check = json.parseToJsonElement(raw).jsonObject
        return LeetCodeSubmissionCheck(
            state = check["state"]?.jsonPrimitive?.content.orEmpty(),
            statusMsg = check["status_msg"]?.jsonPrimitive?.contentOrNullIfNull(),
            memory = check["memory"]?.jsonPrimitive?.intOrNull,
            statusRuntime = check["status_runtime"]?.jsonPrimitive?.contentOrNullIfNull(),
        )
    }

    /**
     * /check/ 响应的详情超集(WP36):同一端点、全量字段解析。字段名与语义 =
     * 旧 LeetCodeSubmitAnswerResult.Success 事实(snake_case);
     * 与 [LeetCodeSubmissionCheck] 分离,轮询热路径不解析用不到的字段。
     */
    data class LeetCodeSubmissionCheckDetail(
        val state: String,
        val statusMsg: String?,
        val memory: Int?,
        val statusRuntime: String?,
        val prettyLang: String?,
        val runtimePercentile: Double?,
        val memoryPercentile: Double?,
        val totalCorrect: Int?,
        val totalTestcases: Int?,
        val inputFormatted: String?,
        val input: String?,
        val codeOutput: String?,
        val expectedOutput: String?,
        val fullCompileError: String?,
        val compileError: String?,
        val fullRuntimeError: String?,
        val runtimeError: String?,
    )

    /** 详情拉取(按需低频,如 submissions 页展开;不落库)。 */
    suspend fun fetchSubmissionCheckDetail(submissionId: String): LeetCodeSubmissionCheckDetail {
        val raw = transport.get(variant.id, variant.submissionCheckUrl(submissionId), csrfHeaders())
        val check =
            json
                .parseToJsonElement(raw)
                .jsonObject
                .takeIf { it["state"] != null }
                ?: throw OJException("LeetCode 提交详情响应缺少 state 字段: ${raw.take(SNIPPET)}")
        return LeetCodeSubmissionCheckDetail(
            state = check["state"]?.jsonPrimitive?.content.orEmpty(),
            statusMsg = check["status_msg"]?.jsonPrimitive?.contentOrNullIfNull(),
            memory = check["memory"]?.jsonPrimitive?.intOrNull,
            statusRuntime = check["status_runtime"]?.jsonPrimitive?.contentOrNullIfNull(),
            prettyLang = check["pretty_lang"]?.jsonPrimitive?.contentOrNullIfNull(),
            runtimePercentile = check["runtime_percentile"]?.jsonPrimitive?.numberOrNullPercentile(),
            memoryPercentile = check["memory_percentile"]?.jsonPrimitive?.numberOrNullPercentile(),
            totalCorrect = check["total_correct"]?.jsonPrimitive?.flexibleIntOrNull(),
            totalTestcases = check["total_testcases"]?.jsonPrimitive?.flexibleIntOrNull(),
            inputFormatted = check["input_formatted"]?.jsonPrimitive?.contentOrNullIfNull(),
            input = check["input"]?.jsonPrimitive?.contentOrNullIfNull(),
            codeOutput = check["code_output"]?.jsonPrimitive?.contentOrNullIfNull(),
            expectedOutput = check["expected_output"]?.jsonPrimitive?.contentOrNullIfNull(),
            fullCompileError = check["full_compile_error"]?.jsonPrimitive?.contentOrNullIfNull(),
            compileError = check["compile_error"]?.jsonPrimitive?.contentOrNullIfNull(),
            fullRuntimeError = check["full_runtime_error"]?.jsonPrimitive?.contentOrNullIfNull(),
            runtimeError = check["runtime_error"]?.jsonPrimitive?.contentOrNullIfNull(),
        )
    }

    companion object {
        /** 单次题解列表拉取条数(旧实现列表页默认量级)。 */
        const val DEFAULT_SOLUTION_LIMIT: Int = 20

        /** LeetCode 单次题目列表请求上限(协议限制 100)。 */
        const val MAX_PAGE_SIZE: Int = 100

        /** 错误响应截断长度(WP36 详情解析失败信息)。 */
        private const val SNIPPET: Int = 200
    }
}

/** List<String> → JsonArray(builders 无 Iterable 重载,逐项 add)。 */
private fun stringArray(values: List<String>): kotlinx.serialization.json.JsonArray =
    kotlinx.serialization.json.buildJsonArray {
        values.forEach { add(it) }
    }

/** JSON null 字段 → Kotlin null(content 访问对 JsonNull 会抛异常,先判空)。 */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullIfNull(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content

/**
 * 数字字段兼容解析(WP36):LC /check/ 响应的百分位/计数有时以字符串承载
 * (旧 circe 模型即按 Option[Float]/Option[Int] 解码,两种形态都见过)。
 */
private fun kotlinx.serialization.json.JsonPrimitive.numberOrNullPercentile(): Double? = contentOrNullIfNull()?.toDoubleOrNull()

private fun kotlinx.serialization.json.JsonPrimitive.flexibleIntOrNull(): Int? = intOrNull ?: contentOrNullIfNull()?.toIntOrNull()
