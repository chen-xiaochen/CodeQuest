package com.codequest.platform.leetcode

import com.codequest.core.model.OJException
import com.codequest.spi.CodeSnippet
import com.codequest.spi.OJTransport
import com.codequest.spi.ProblemFilter
import com.codequest.spi.TagInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 *   (favoriteSlug = 公司 slug[-期间后缀],filtersV2 完整骨架同 problemsetQuestionListV2);
 * - 题单/收藏夹(WP56):题单分页泛化为任意 favoriteSlug(公司/学习计划/收藏夹
 *   共用 [fetchFavoriteQuestionPage]);我的收藏夹清单 GraphQL `favoritesLists`
 *   (spec 预估的 favoriteFolders 两站点 schema 均不存在,实测改用真实查询)+
 *   REST `GET /api/list_favorites/` 备选;
 * - 云端测试(WP53):REST `POST /problems/{slug}/interpret_solution/`
 *   (`{lang, data_input, question_id, typed_code}`)→ `{interpret_id}`,轮询同族
 *   `GET /submissions/detail/{interpretId}/check/` 到 state == SUCCESS
 *   (字段全集 = 旧 LeetCodeRunResult.Success)。
 *
 * 所有请求携带 `x-csrftoken` + `Referer`(csrftoken 取自 `/api/home/` 种下的 cookie,
 * 缺失时为空串,与旧实现一致)。非 2xx 已由 [OJTransport] 翻译为领域异常;
 * GraphQL `errors` 信封与结构缺失抛 [OJException]。
 *
 * LargeClass 豁免:目录式端点聚合(WP19 起逐 WP 追加、共享 graphql/csrf 收口,
 * 无状态),拆分只会复制请求底座——同 config/detekt.yml 对 TooManyFunctions
 * 的豁免口径,交由 review 把关。
 */
@Suppress("LargeClass")
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
        /** 1.6.0:副标题(标题另一语言形态);单语数据源为 null。 */
        val titleSecondary: String? = null,
        val titleSlug: String,
        val difficulty: String,
        val paidOnly: Boolean,
        val topicTags: List<TagInfo>,
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
                    val enTitle = q["title"]?.jsonPrimitive?.content.orEmpty()
                    // 旧实现事实(UI 三处消费):titleCn 非空优先(CN 中文),title 兜底;
                    // 1.6.0:另一侧标题不再丢弃,存 titleSecondary(中文为主英文为辅)
                    val cnTitle = q["titleCn"]?.jsonPrimitive?.contentOrNullIfNull().orEmpty()
                    val (primary, secondary) =
                        if (cnTitle.isNotEmpty()) cnTitle to enTitle else enTitle to null
                    LeetCodeQuestionListItem(
                        frontendQuestionId = q["frontendQuestionId"]?.jsonPrimitive?.content.orEmpty(),
                        title = primary,
                        titleSecondary = secondary?.takeIf { it.isNotEmpty() },
                        titleSlug = q["titleSlug"]?.jsonPrimitive?.content.orEmpty(),
                        difficulty = q["difficulty"]?.jsonPrimitive?.content.orEmpty(),
                        paidOnly = q["paidOnly"]?.jsonPrimitive?.content == "true",
                        status = q["status"]?.jsonPrimitive?.contentOrNullIfNull(),
                        topicTags = parseTopicTags(q),
                    )
                },
        )
    }

    /**
     * topicTags 数组 → [TagInfo](1.6.0):name 原文、slug、nameTranslated→中文名;
     * 三字段按站点/查询差异缺失时为 null(CN 列表/题单带 nameTranslated,COM 仅
     * name+slug,studyPlanV2Detail 双站同构带全三字段)。
     */
    private fun parseTopicTags(question: JsonObject): List<TagInfo> =
        question["topicTags"]
            ?.jsonArray
            .orEmpty()
            .mapNotNull { element ->
                val tag = element.jsonObject
                val name = tag["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                TagInfo(
                    name = name,
                    slug = tag["slug"]?.jsonPrimitive?.contentOrNullIfNull()?.takeIf { it.isNotEmpty() },
                    nameCn = tag["nameTranslated"]?.jsonPrimitive?.contentOrNullIfNull()?.takeIf { it.isNotEmpty() },
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
                        // 1.6.0:V2 查询补 topicTags(name+slug;COM 无 nameTranslated,
                        // 中文名由 tag 表已 enrich 的行回退);列表无中文字段(V2 实测
                        // translatedTitle 请求不返回),titleSecondary 为 null
                        topicTags = parseTopicTags(q),
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
     * 公司题单分页查询(`favoriteQuestionList`,旧 searchCompanyChallenges 逐字事实;
     * WP56 起委托泛化查询 [fetchFavoriteQuestionPage],线协议不变):
     *
     * - `favoriteSlug` = [companySlugs] 首项 +(`-$interviewPeriodSlug` 后缀,非 null 时);
     * - 其余公司进 `filtersV2.companyFilter.companySlugs`;
     * - [companySlugs] 为空时直接返回空页、不发请求(旧 Presenter 同款守卫)。
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
        return fetchFavoriteQuestionPage(favoriteSlug, companySlugs.drop(1), filter, skip, limit)
    }

    /**
     * 题单分页查询泛化(WP56,`favoriteQuestionList`):任意收藏夹/学习计划 slug 的
     * 单页查询,公司题单([fetchCompanyQuestionPage])与其他题单共用。
     *
     * - [favoriteSlug] 原样进查询变量(公司题单 = 首公司 + 期间后缀);
     * - [extraCompanySlugs] 进 `filtersV2.companyFilter.companySlugs`(公司题单多公司
     *   交集;纯收藏夹/学习计划查询传空);
     * - [filter] 的 difficulty → difficultyFilter、tags → topicFilter;keyword 无对应
     *   输入(旧实现恒发空串 `searchKeyword: ""`,题单查询面板无关键字入口);
     * - `sortBy` 恒 `{CUSTOM, ASCENDING}`(旧实现无排序输入时的兜底值);
     * - `filtersV2` 为完整骨架(空子过滤结构为服务端要求的旧实现逐字事实)。
     *
     * 响应项 `translatedTitle` 非空优先(旧 LeetCodeChallengeListItem.titleCn 语义)。
     */
    suspend fun fetchFavoriteQuestionPage(
        favoriteSlug: String,
        extraCompanySlugs: List<String>,
        filter: ProblemFilter,
        skip: Int,
        limit: Int,
    ): LeetCodeQuestionPage {
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
                                put("companySlugs", stringArray(extraCompanySlugs))
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
                    val enTitle = q["title"]?.jsonPrimitive?.content.orEmpty()
                    // 字段名与普通列表不同:questionFrontendId / translatedTitle(旧模型
                    // 即以 frontendQuestionId/titleCn 别名承载);1.6.0 双语同 CN 列表规则
                    val translated = q["translatedTitle"]?.jsonPrimitive?.contentOrNullIfNull().orEmpty()
                    val (primary, secondary) =
                        if (translated.isNotEmpty()) translated to enTitle else enTitle to null
                    LeetCodeQuestionListItem(
                        frontendQuestionId = q["questionFrontendId"]?.jsonPrimitive?.content.orEmpty(),
                        title = primary,
                        titleSecondary = secondary?.takeIf { it.isNotEmpty() },
                        titleSlug = q["titleSlug"]?.jsonPrimitive?.content.orEmpty(),
                        difficulty = q["difficulty"]?.jsonPrimitive?.content.orEmpty(),
                        paidOnly = q["paidOnly"]?.jsonPrimitive?.content == "true",
                        status = q["status"]?.jsonPrimitive?.contentOrNullIfNull(),
                        topicTags = parseTopicTags(q),
                    )
                },
        )
    }

    // -------------------------------------------------------------------
    // 学习计划(1.6.0)
    // -------------------------------------------------------------------

    /**
     * 学习计划详情(`studyPlanV2Detail`,全量单次返回):拍平 planSubGroups.questions,
     * total = 题目总数。会员计划未订阅时 questions 为空(premiumOnly=true),
     * slug 不存在时 data 字段为 null → 抛 [OJException](与 GraphQL 缺字段口径一致)。
     */
    suspend fun fetchStudyPlanDetail(planSlug: String): LeetCodeQuestionPage {
        val variables =
            buildJsonObject {
                put("planSlug", planSlug)
            }
        val data = graphql("studyPlanV2Detail", LeetCodeGraphQL.STUDY_PLAN_DETAIL, variables)
        val plan = data.child("studyPlanV2Detail")
        val questions =
            plan["planSubGroups"]
                ?.jsonArray
                .orEmpty()
                .flatMap { group ->
                    group.jsonObject["questions"]?.jsonArray.orEmpty().map { element ->
                        val q = element.jsonObject
                        val enTitle = q["title"]?.jsonPrimitive?.content.orEmpty()
                        val translated = q["translatedTitle"]?.jsonPrimitive?.contentOrNullIfNull().orEmpty()
                        val (primary, secondary) =
                            if (translated.isNotEmpty()) translated to enTitle else enTitle to null
                        LeetCodeQuestionListItem(
                            frontendQuestionId = q["questionFrontendId"]?.jsonPrimitive?.content.orEmpty(),
                            title = primary,
                            titleSecondary = secondary?.takeIf { it.isNotEmpty() },
                            titleSlug = q["titleSlug"]?.jsonPrimitive?.content.orEmpty(),
                            difficulty = q["difficulty"]?.jsonPrimitive?.content.orEmpty(),
                            paidOnly = q["paidOnly"]?.jsonPrimitive?.content == "true",
                            topicTags = parseTopicTags(q),
                        )
                    }
                }
        return LeetCodeQuestionPage(total = questions.size, questions = questions)
    }

    // -------------------------------------------------------------------
    // 我的收藏题单(WP56)
    // -------------------------------------------------------------------

    /** 收藏夹条目(slug 即 [fetchFavoriteQuestionPage] 的 favoriteSlug 输入)。 */
    data class LeetCodeFavorite(
        val slug: String,
        val name: String,
        val questionCount: Int,
    )

    /**
     * 我的收藏夹清单——GraphQL `favoritesLists`(WP56 实测探测):spec 预估的
     * `favoriteFolders` 在两站点 schema 均不存在,真实查询为
     * `favoritesLists.allFavorites`;COM FavoriteNode 直出 slug/questionCount,
     * CN 无此二字段(slug 回退 id、题数按 questions 长度计,字段宽容解析)。
     * 匿名请求合法返回空数组;异常交由调用方降级 REST。
     */
    suspend fun fetchFavoriteFolders(): List<LeetCodeFavorite> {
        val data =
            graphql(
                "favoritesLists",
                if (variant.isCn) LeetCodeGraphQL.FAVORITES_LISTS_CN else LeetCodeGraphQL.FAVORITES_LISTS,
                buildJsonObject { },
            )
        val favorites = data.child("favoritesLists")["allFavorites"]?.jsonArray
        return favorites.orEmpty().mapNotNull { element ->
            val node = element.jsonObject
            val name = node.pickString("name") ?: return@mapNotNull null
            val slug = node.pickString("slug", "id") ?: return@mapNotNull null
            LeetCodeFavorite(
                slug = slug,
                name = name,
                questionCount =
                    node.pickInt("questionCount")
                        ?: node["questions"]?.jsonArray?.size ?: 0,
            )
        }
    }

    /**
     * 我的收藏夹清单备选——REST `GET /api/list_favorites/`(旧插件端点事实,
     * WP56):顶层 `favorites` 数组(裸数组亦可),条目字段宽容解析
     * (key/slug/id → slug,questions_count/questionCount → 题数)。
     */
    suspend fun fetchFavoritesViaRest(): List<LeetCodeFavorite> {
        val raw = transport.get(variant.id, variant.listFavoritesUrl, csrfHeaders())
        val favorites =
            json.parseToJsonElement(raw).let { element ->
                (element as? JsonArray) ?: element.jsonObject["favorites"] as? JsonArray
            } ?: return emptyList()
        return favorites.mapNotNull { element ->
            val node = element.jsonObject
            val name = node.pickString("name") ?: return@mapNotNull null
            val slug = node.pickString("key", "slug", "id") ?: return@mapNotNull null
            LeetCodeFavorite(
                slug = slug,
                name = name,
                questionCount = node.pickInt("questions_count", "questionCount", "questionsCount") ?: 0,
            )
        }
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
        /** 题面示例用例输入(1.3.2,原版 LeetCodeChallengeData.exampleTestcaseList 事实):平台已按用例切好,每项为完整输入(多行用例内含换行)。 */
        val exampleTestcaseList: List<String> = emptyList(),
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
            exampleTestcaseList =
                question["exampleTestcaseList"]
                    ?.jsonArray
                    .orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNullIfNull()?.takeIf { tc -> tc.isNotBlank() } },
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

    // -------------------------------------------------------------------
    // 云端测试(interpret,WP53;旧 runAnswer/getRunCodeResult 事实)
    // -------------------------------------------------------------------

    /**
     * 发起云端测试(POST /problems/{slug}/interpret_solution/,snake_case 信封
     * `{lang, data_input, question_id, typed_code}` + CSRF),返回 interpret_id
     * 供 [fetchInterpretCheck] 轮询(响应字段全集 = 旧 LeetCodeRunResponse,
     * 其余字段无消费方不解析)。
     */
    suspend fun interpretSolution(
        questionId: String,
        problemSlug: String,
        langSlug: String,
        code: String,
        dataInput: String,
    ): String {
        val body =
            buildJsonObject {
                put("lang", langSlug)
                put("data_input", dataInput)
                put("question_id", questionId)
                put("typed_code", code)
            }.toString()
        val raw = transport.postJson(variant.id, variant.interpretSolutionUrl(problemSlug), body, csrfHeaders())
        val interpretId =
            json
                .parseToJsonElement(raw)
                .jsonObject["interpret_id"]
                ?.jsonPrimitive
                ?.contentOrNullIfNull()
        return interpretId ?: throw OJException("LeetCode interpret_solution 响应缺少 interpret_id: $raw")
    }

    /** interpret /check/ 快照(字段名 = 旧 LeetCodeRunResult.Success 事实,snake_case)。 */
    data class LeetCodeInterpretCheck(
        /** STARTED / PENDING / SUCCESS(SUCCESS = 终态)。 */
        val state: String,
        val statusMsg: String?,
        val codeAnswer: List<String>,
        val expectedCodeAnswer: List<String>,
        val compareResult: String?,
        val totalCorrect: Int?,
        val totalTestcases: Int?,
        val fullCompileError: String?,
        val compileError: String?,
        val fullRuntimeError: String?,
        val runtimeError: String?,
        val statusRuntime: String?,
        val statusMemory: String?,
    )

    /**
     * 轮询 interpret 结果(GET /submissions/detail/{interpretId}/check/,与提交
     * check 同族端点)。非终态(PENDING/STARTED)响应仅有 state,其余字段宽容为
     * null/空;缺 state 视为协议异常快速失败。
     */
    suspend fun fetchInterpretCheck(interpretId: String): LeetCodeInterpretCheck {
        val raw = transport.get(variant.id, variant.submissionCheckUrl(interpretId), csrfHeaders())
        val check =
            json
                .parseToJsonElement(raw)
                .jsonObject
                .takeIf { it["state"] != null }
                ?: throw OJException("LeetCode interpret check 响应缺少 state 字段: ${raw.take(SNIPPET)}")
        return LeetCodeInterpretCheck(
            state = check["state"]?.jsonPrimitive?.content.orEmpty(),
            statusMsg = check["status_msg"]?.jsonPrimitive?.contentOrNullIfNull(),
            codeAnswer = check["code_answer"].stringListOrEmpty(),
            expectedCodeAnswer = check["expected_code_answer"].stringListOrEmpty(),
            compareResult = check["compare_result"]?.jsonPrimitive?.contentOrNullIfNull(),
            totalCorrect = check["total_correct"]?.jsonPrimitive?.flexibleIntOrNull(),
            totalTestcases = check["total_testcases"]?.jsonPrimitive?.flexibleIntOrNull(),
            fullCompileError = check["full_compile_error"]?.jsonPrimitive?.contentOrNullIfNull(),
            compileError = check["compile_error"]?.jsonPrimitive?.contentOrNullIfNull(),
            fullRuntimeError = check["full_runtime_error"]?.jsonPrimitive?.contentOrNullIfNull(),
            runtimeError = check["runtime_error"]?.jsonPrimitive?.contentOrNullIfNull(),
            statusRuntime = check["status_runtime"]?.jsonPrimitive?.contentOrNullIfNull(),
            statusMemory = check["status_memory"]?.jsonPrimitive?.contentOrNullIfNull(),
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

/** 多候选字段名宽容取首个非空字符串值(WP56 收藏夹条目:站点/接口形态不一)。 */
private fun kotlinx.serialization.json.JsonObject.pickString(vararg names: String): String? =
    names.firstNotNullOfOrNull { name ->
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.content
            ?.takeIf { it.isNotEmpty() }
    }

/** 多候选字段名宽容取首个整数值(数字/字符串两种承载形态)。 */
private fun kotlinx.serialization.json.JsonObject.pickInt(vararg names: String): Int? =
    names.firstNotNullOfOrNull { name ->
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.let { it.intOrNull ?: it.content.toIntOrNull() }
    }

/**
 * 数字字段兼容解析(WP36):LC /check/ 响应的百分位/计数有时以字符串承载
 * (旧 circe 模型即按 Option[Float]/Option[Int] 解码,两种形态都见过)。
 */
private fun kotlinx.serialization.json.JsonPrimitive.numberOrNullPercentile(): Double? = contentOrNullIfNull()?.toDoubleOrNull()

private fun kotlinx.serialization.json.JsonPrimitive.flexibleIntOrNull(): Int? = intOrNull ?: contentOrNullIfNull()?.toIntOrNull()

/** 字符串数组字段宽容解析(WP53):null/JsonNull/非数组/含 null 元素一律降级为空或跳过。 */
private fun kotlinx.serialization.json.JsonElement?.stringListOrEmpty(): List<String> =
    (
        (this as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { el ->
                (el as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                    ?.content
            }
    ).orEmpty()
