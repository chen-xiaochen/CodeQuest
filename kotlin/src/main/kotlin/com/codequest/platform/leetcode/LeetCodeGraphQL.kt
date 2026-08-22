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
}
