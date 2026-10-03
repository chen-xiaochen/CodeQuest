package com.codequest.platform.leetcode

/**
 * LeetCode GraphQL 查询文本(WP19)。
 *
 * **协议事实,逐字沿用旧 Scala 实现的 GraphQL 资源文件**
 * (src/main/resources/leetcode/graphql/ 目录下的同名 .graphql):
 * 端点、操作名、字段名、分页参数名不得改动;`query`/`questionData` 与 CN 题解列表为两站点
 * 同构,题目列表与题解列表按站点取不同查询。Kotlin 原始字符串中 `$` 需转义为 `${'$'}`。
 */
internal object LeetCodeGraphQL {
    /** 登录态查询(globalData.graphql,两站点同构)。 */
    val GLOBAL_DATA: String =
        """
        query globalData {
          userStatus {
            isSignedIn
            isPremium
            username
            realName
            avatar
            userSlug
          }
        }
        """.trimIndent()

    /**
     * 用户竞赛分(userContestRankingInfo,1.7.0 F1;两站点同构,变量名均 userSlug)。
     * 仅取评分着色所需两字段;未参赛/未登录时 `userContestRanking` 字段为 null。
     */
    val USER_CONTEST_RANKING_INFO: String =
        """
        query userContestRankingInfo(${'$'}userSlug: String!) {
          userContestRanking(userSlug: ${'$'}userSlug) {
            attendedContestsCount
            rating
          }
        }
        """.trimIndent()

    /**
     * 站内提交历史(submissionList,1.7.0 F2;两站点同构)。
     * 用户全局倒序列表(不带 questionSlug);分页纯 offset/limit——不传 lastKey
     * 参数(null 字面量是非法 GraphQL 语法,真实站会拒绝),响应 `lastKey`
     * 为 null 即无更多(hasMore 判定)。
     * runtime/memory 为字符串原文("64 ms"/"66.3 MB"),timestamp 为秒级 epoch 字符串。
     */
    val SUBMISSION_LIST: String =
        """
        query submissionList(${'$'}offset: Int!, ${'$'}limit: Int!) {
          submissionList(offset: ${'$'}offset, limit: ${'$'}limit) {
            submissions {
              id
              title
              titleSlug
              statusDisplay
              runtime
              memory
              lang
              timestamp
            }
            lastKey
          }
        }
        """.trimIndent()

    /** 题目详情(questionData.graphql,两站点同构;content 为 HTML)。 */
    val QUESTION_DATA: String =
        """
        query questionData(${'$'}titleSlug: String!) {
            question(titleSlug: ${'$'}titleSlug) {
                questionId
                frontendQuestionId: questionFrontendId
                title
                titleSlug
                content
                translatedTitle
                translatedContent
                isPaidOnly
                difficulty
                topicTags {
                    name
                    slug
                    translatedName
                }
                status
                exampleTestcaseList
                codeSnippets {
                    lang
                    langSlug
                    code
                }
            }
        }
        """.trimIndent()

    /** 题目列表(problemsetQuestionList_cn.graphql,CN 站点)。 */
    val PROBLEMSET_QUESTION_LIST_CN: String =
        """
        query problemsetQuestionList(${'$'}categorySlug: String, ${'$'}limit: Int, ${'$'}skip: Int, ${'$'}filters: QuestionListFilterInput) {
            problemsetQuestionList(
                categorySlug: ${'$'}categorySlug
                limit: ${'$'}limit
                skip: ${'$'}skip
                filters: ${'$'}filters
            ) {
                hasMore
                total
                questions {
                    acRate
                    difficulty
                    freqBar
                    frontendQuestionId
                    paidOnly
                    solutionNum
                    status
                    title
                    titleCn
                    titleSlug
                    topicTags {
                        name
                        nameTranslated
                        id
                        slug
                    }
                }
            }
        }
        """.trimIndent()

    /** 题目列表(problemsetQuestionListV2.graphql,COM 站点)。 */
    val PROBLEMSET_QUESTION_LIST_V2: String =
        """
        query problemsetQuestionListV2(${'$'}filters: QuestionFilterInput, ${'$'}limit: Int, ${'$'}searchKeyword: String, ${'$'}skip: Int, ${'$'}sortBy: QuestionSortByInput, ${'$'}categorySlug: String) {
          problemsetQuestionListV2(
            filters: ${'$'}filters
            limit: ${'$'}limit
            searchKeyword: ${'$'}searchKeyword
            skip: ${'$'}skip
            sortBy: ${'$'}sortBy
            categorySlug: ${'$'}categorySlug
          ) {
            totalLength
            questions {
              acRate
              difficulty
              questionFrontendId
              titleSlug
              title
              paidOnly
              status
              frequency
              topicTags {
                name
                slug
              }
            }
          }
        }
        """.trimIndent()

    /** 官方题解列表(ugcArticleSolutionArticles.graphql,COM 站点;仅取引用列表所需字段)。 */
    val SOLUTION_ARTICLES_COM: String =
        """
        query ugcArticleSolutionArticles(${'$'}questionSlug: String!, ${'$'}orderBy: ArticleOrderByEnum, ${'$'}userInput: String, ${'$'}tagSlugs: [String!], ${'$'}skip: Int, ${'$'}first: Int) {
          ugcArticleSolutionArticles(
            questionSlug: ${'$'}questionSlug
            orderBy: ${'$'}orderBy
            userInput: ${'$'}userInput
            tagSlugs: ${'$'}tagSlugs
            skip: ${'$'}skip
            first: ${'$'}first
          ) {
            totalNum
            edges {
              node {
                uuid
                title
                slug
              }
            }
          }
        }
        """.trimIndent()

    /** 官方题解列表(questionTopicsList_cn.graphql,CN 站点;仅取引用列表所需字段)。 */
    val SOLUTION_ARTICLES_CN: String =
        """
        query questionTopicsList(${'$'}questionSlug: String!, ${'$'}skip: Int, ${'$'}first: Int, ${'$'}orderBy: SolutionArticleOrderBy, ${'$'}userInput: String, ${'$'}tagSlugs: [String!]) {
          questionSolutionArticles(
            questionSlug: ${'$'}questionSlug
            skip: ${'$'}skip
            first: ${'$'}first
            orderBy: ${'$'}orderBy
            userInput: ${'$'}userInput
            tagSlugs: ${'$'}tagSlugs
          ) {
            totalNum
            edges {
              node {
                uuid
                title
                slug
              }
            }
          }
        }
        """.trimIndent()

    /**
     * 官方题解正文(discussTopic_cn.graphql,CN 站点;WP31,仅取正文所需字段)。
     * 旧资源文件还请求 next/prev(文章间跳转导航),SPI 无此契约,不取。
     */
    val SOLUTION_ARTICLE_CN: String =
        """
        query discussTopic(${'$'}slug: String) {
          solutionArticle(slug: ${'$'}slug, orderBy: DEFAULT) {
            uuid
            title
            content
            slug
          }
        }
        """.trimIndent()

    /** 官方题解正文(ugcArticleSolutionArticle.graphql,COM 站点;WP31,仅取正文所需字段)。 */
    val SOLUTION_ARTICLE_COM: String =
        """
        query ugcArticleSolutionArticle(${'$'}articleId: ID, ${'$'}topicId: ID, ${'$'}slug: String) {
          ugcArticleSolutionArticle(articleId: ${'$'}articleId, topicId: ${'$'}topicId, slug: ${'$'}slug) {
            uuid
            title
            content
            slug
          }
        }
        """.trimIndent()

    // ------------------------------------------------------------------
    // 公司题单(WP42):公司列表 + 公司题单查询,逐字沿用旧资源文件
    // ------------------------------------------------------------------

    /** 公司列表(questionCompanyTagsV2.graphql,COM 站点;变量为空对象)。 */
    val QUESTION_COMPANY_TAGS_V2: String =
        """
        query questionCompanyTagsV2{
          companyTags {
              name
              slug
              id
              questionCount
          }
        }
        """.trimIndent()

    /** 公司列表(questionCompanyTagsV2_cn.graphql,CN 站点;响应走 companyTagsV2.companyTagInfoNodes)。 */
    val QUESTION_COMPANY_TAGS_V2_CN: String =
        """
        query questionCompanyTagsV2 {
          companyTagsV2 {
            companyTagInfoNodes {
              name
              slug
              id
              questionCount: companyQuestionNum
            }
          }
        }
        """.trimIndent()

    /** 公司题单(favoriteQuestionList.graphql,COM 站点;total 为 totalLength 别名)。 */
    val FAVORITE_QUESTION_LIST: String =
        """
        query favoriteQuestionList(${'$'}favoriteSlug: String!, ${'$'}filter: FavoriteQuestionFilterInput, ${'$'}searchKeyword: String, ${'$'}filtersV2: QuestionFilterInput, ${'$'}sortBy: QuestionSortByInput, ${'$'}limit: Int, ${'$'}skip: Int, ${'$'}version: String = "v2") {
          favoriteQuestionList(
            favoriteSlug: ${'$'}favoriteSlug
            filter: ${'$'}filter
            filtersV2: ${'$'}filtersV2
            searchKeyword: ${'$'}searchKeyword
            sortBy: ${'$'}sortBy
            limit: ${'$'}limit
            skip: ${'$'}skip
            version: ${'$'}version
          ) {
            questions {
              difficulty
              id
              paidOnly
              # can't be used as frontendQuestionId
              questionFrontendId
              status
              title
              titleSlug
              translatedTitle
              isInMyFavorites
              frequency
              acRate
              topicTags {
                name
                slug
              }
            }
            total: totalLength
          }
        }
        """.trimIndent()

    // ------------------------------------------------------------------
    // 题单/收藏夹(WP56):我的收藏夹清单,查询名与字段为匿名 schema 探测实证
    // ------------------------------------------------------------------

    /**
     * 收藏夹清单(favoritesLists,COM 站点)。spec 预估的 `favoriteFolders` 在
     * 两站点 schema 均不存在(2026-08-22 匿名探测「Cannot query field」);
     * 真实查询 `favoritesLists.allFavorites`,COM FavoriteNode 直出
     * slug/questionCount。匿名请求合法返回空数组(登录态才有数据)。
     */
    val FAVORITES_LISTS: String =
        """
        query favoritesLists {
          favoritesLists {
            allFavorites {
              id
              name
              slug
              questionCount
            }
          }
        }
        """.trimIndent()

    /** 收藏夹清单(favoritesLists,CN 站点;FavoriteNode 无 slug/questionCount,题数按 questions 长度计、slug 回退 id)。 */
    val FAVORITES_LISTS_CN: String =
        """
        query favoritesLists {
          favoritesLists {
            allFavorites {
              id
              name
              questions {
                titleSlug
              }
            }
          }
        }
        """.trimIndent()

    /** 公司题单(favoriteQuestionList_cn.graphql,CN 站点;topicTags 多 nameTranslated、多 hasMore)。 */
    val FAVORITE_QUESTION_LIST_CN: String =
        """
        query favoriteQuestionList(${'$'}favoriteSlug: String!, ${'$'}filter: FavoriteQuestionFilterInput, ${'$'}searchKeyword: String, ${'$'}filtersV2: QuestionFilterInput, ${'$'}sortBy: QuestionSortByInput, ${'$'}limit: Int, ${'$'}skip: Int, ${'$'}version: String = "v2") {
          favoriteQuestionList(
            favoriteSlug: ${'$'}favoriteSlug
            filter: ${'$'}filter
            filtersV2: ${'$'}filtersV2
            searchKeyword: ${'$'}searchKeyword
            sortBy: ${'$'}sortBy
            limit: ${'$'}limit
            skip: ${'$'}skip
            version: ${'$'}version
          ) {
            questions {
              difficulty
              id
              paidOnly
              # can't be used as frontendQuestionId
              questionFrontendId
              status
              title
              titleSlug
              translatedTitle
              isInMyFavorites
              frequency
              acRate
              topicTags {
                name
                nameTranslated
                slug
              }
            }
            total: totalLength
            hasMore
          }
        }
        """.trimIndent()

    // ------------------------------------------------------------------
    // 每日一题 / 竞赛回顾(1.8.0 F1/F2,CN 站点)
    // ------------------------------------------------------------------

    /**
     * 每日一题(questionOfToday,CN 站点;1.8.0 F1)。
     * 2026-10-03 实测(https://leetcode.cn/graphql/,需 Referer/Origin 头):
     * `todayRecord` 为**数组**(单元素),date 为 yyyy-MM-dd,question 内
     * translatedTitle 为中文标题(优先作 title);userStatus 字段无消费方不取。
     */
    val QUESTION_OF_TODAY: String =
        """
        query questionOfToday {
          todayRecord {
            date
            question {
              questionFrontendId
              title
              translatedTitle
              titleSlug
              difficulty
              topicTags {
                name
                slug
              }
            }
          }
        }
        """.trimIndent()

    /**
     * 竞赛列表(contestHistory,CN 站点;1.8.0 F2)。
     * 2026-10-03 实测:startTime/duration 均**秒级**(如 1790476200/5400),
     * 按开始时间倒序、仅含已结束场次的近期回顾(匿名可查,不含未来赛)。
     */
    val CONTEST_HISTORY: String =
        """
        query contestHistory(${'$'}pageNum: Int!, ${'$'}pageSize: Int!) {
          contestHistory(pageNum: ${'$'}pageNum, pageSize: ${'$'}pageSize) {
            contests {
              title
              titleSlug
              startTime
              duration
            }
          }
        }
        """.trimIndent()

    /**
     * 学习计划详情(studyPlanV2Detail,1.6.0;两站点同构,匿名可用)。
     *
     * 2026-08-26 双站实测:CN 19 个内置 slug 中 15 个匿名返回全量题目(双语标题 +
     * 中文标签),3 个会员计划 premiumOnly=true 且题目空;COM 6 个 CN 专属 slug
     * 返回 null。**学习计划专用**——favoriteQuestionList 对学习计划 slug 基本返回
     * 空集(仅公司题单/我的题单适用),是旧版「学习计划为空」的根因。
     * 全量单次返回(无分页参数),分页由服务层内存切片。
     */
    val STUDY_PLAN_DETAIL: String =
        """
        query studyPlanV2Detail(${'$'}planSlug: String!) {
          studyPlanV2Detail(planSlug: ${'$'}planSlug) {
            slug
            name
            premiumOnly
            planSubGroups {
              name
              questionNum
              questions {
                title
                titleSlug
                translatedTitle
                questionFrontendId
                difficulty
                paidOnly
                topicTags {
                  name
                  nameTranslated
                  slug
                }
              }
            }
          }
        }
        """.trimIndent()
}
