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
}
