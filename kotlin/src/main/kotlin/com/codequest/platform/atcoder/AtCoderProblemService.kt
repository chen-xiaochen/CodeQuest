package com.codequest.platform.atcoder

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.core.model.OJException
import com.codequest.spi.ProblemFilter
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.ProblemStatement
import com.codequest.spi.ProblemSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * AtCoder 题目查询(WP25):ProblemQueryCapable 实现。
 *
 * - **列表**:kenkoooo merged-problems 无服务端过滤/分页参数(旧实现事实,一次
 *   全量数千条),冷流内拉题库 + 难度表两份资源后**内存过滤**:
 *   - keyword → 题名/完整标题/remoteId 忽略大小写包含;
 *   - difficulty → 精确展示难度数字串;或旧 AtCoderDifficulty 色档词
 *     (grey/brown/green/cyan/blue/yellow/orange/red/bronze/silver/gold)
 *     按旧枚举区间映射;无难度数据的题目仅在无难度过滤时出现;
 *   - tags → AtCoder 无标签体系(SPI 允许实现忽略不支持的条件),**恒不过滤**;
 *   - offset/limit 窗口在过滤后应用;
 * - **详情**:remoteId("abc390_g")拆 contestId+problemId → 题面页 HTML,提取
 *   `#main-container div.row` 第二个直接子 div 作题面(含题干/IO 说明/样例,
 *   交前端 HTML 渲染管线;旧实现拆分题干与样例建 challenge,新管线整块给
 *   [ProblemStatement])。
 */
class AtCoderProblemService(
    private val api: AtCoderApi,
) : ProblemQueryCapable {
    override fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary> =
        flow {
            val difficulties = api.fetchProblemDifficulties()
            api
                .fetchProblems()
                .asSequence()
                .filter { problem -> problem.matchesKeyword(filter.keyword) }
                .filter { problem -> problem.matchesDifficulty(filter.difficulty, difficulties[problem.id]) }
                .drop(filter.offset)
                .take(filter.limit)
                .forEach { problem ->
                    emit(problem.toSummary(difficulties[problem.id]))
                }
        }

    override suspend fun fetchProblemDetail(remoteId: String): ProblemStatement {
        val (contestId, problemId) = parseProblemRemoteId(remoteId)
        val html = api.fetchTaskPageHtml(contestId, problemId)
        val statement =
            AtCoderHtml.extractStatement(html)
                ?: throw OJException(
                    "AtCoder 题面不可获取(页面无 main-container/row 结构,可能未开始或被反爬拦截): $remoteId",
                )
        return ProblemStatement(remoteId = remoteId, content = statement, contentFormat = ContentFormat.HTML)
    }

    private fun AtCoderApi.AtProblem.matchesKeyword(keyword: String?): Boolean {
        if (keyword.isNullOrEmpty()) return true
        return name.contains(keyword, ignoreCase = true) ||
            title.contains(keyword, ignoreCase = true) ||
            id.startsWith(keyword, ignoreCase = true)
    }

    private fun AtCoderApi.AtProblem.matchesDifficulty(
        difficulty: Difficulty?,
        displayDifficulty: Int?,
    ): Boolean {
        if (difficulty == null) return true
        val wanted = difficulty.value.trim()
        // 精确数字:比对展示难度
        wanted.toIntOrNull()?.let { return displayDifficulty == it }
        // 旧 AtCoderDifficulty 色档词 → 展示难度区间(旧枚举边界事实,区间右闭)
        val band = difficultyBandOf(wanted) ?: return true
        val value = displayDifficulty ?: return false
        return value >= band.first && value <= band.second
    }

    private fun AtCoderApi.AtProblem.toSummary(displayDifficulty: Int?): ProblemSummary =
        ProblemSummary(
            remoteId = id,
            title = title,
            difficulty = displayDifficulty?.let { Difficulty(it.toString()) } ?: Difficulty(UNRATED),
            url = atCoderTaskUrl(contestId, id),
            tags = emptyList(),
        )

    companion object {
        /** 无难度数据题目的难度原文(展示侧按需映射文案)。 */
        const val UNRATED: String = "-"

        // 旧 AtCoderDifficulty 枚举边界(展示难度区间,右闭;grey 为下界开区间)
        private const val BROWN_MIN = 400
        private const val GREEN_MIN = 800
        private const val CYAN_MIN = 1_200
        private const val BLUE_MIN = 1_600
        private const val YELLOW_MIN = 2_000
        private const val ORANGE_MIN = 2_400
        private const val RED_MIN = 2_800
        private const val BRONZE_MIN = 3_200
        private const val SILVER_MIN = 3_600
        private const val GOLD_MIN = 4_000

        /** 旧色档词 → 展示难度区间;非色档词返回 null(不过滤)。 */
        fun difficultyBandOf(word: String): Pair<Int, Int>? =
            when (word.lowercase()) {
                "grey" -> Int.MIN_VALUE to BROWN_MIN - 1
                "brown" -> BROWN_MIN to GREEN_MIN - 1
                "green" -> GREEN_MIN to CYAN_MIN - 1
                "cyan" -> CYAN_MIN to BLUE_MIN - 1
                "blue" -> BLUE_MIN to YELLOW_MIN - 1
                "yellow" -> YELLOW_MIN to ORANGE_MIN - 1
                "orange" -> ORANGE_MIN to RED_MIN - 1
                "red" -> RED_MIN to BRONZE_MIN - 1
                "bronze" -> BRONZE_MIN to SILVER_MIN - 1
                "silver" -> SILVER_MIN to GOLD_MIN - 1
                "gold" -> GOLD_MIN to Int.MAX_VALUE
                else -> null
            }
    }
}

/**
 * 解析 AtCoder 题目标识:`"abc390_g"` → (contestId="abc390", problemId="abc390_g")。
 * 约定:remoteId 即 AtCoder problem id(TaskScreenName),自身结构为
 * `<contestId>_<problemIndex>`,contestId 取**最后一个** `_` 之前段(竞赛 id 本身
 * 不含下划线,problemIndex 可为多字符如 "cp");不含 `_` 的形态非法。
 */
internal fun parseProblemRemoteId(remoteId: String): Pair<String, String> {
    val contestId = remoteId.substringBeforeLast('_', "")
    val problemIndex = remoteId.substringAfterLast('_', "")
    if (contestId.isEmpty() || problemIndex.isEmpty()) {
        throw OJException("非法 AtCoder 题目标识: '$remoteId'(约定形如 abc390_g,即 contestId_problemIndex)")
    }
    return contestId to remoteId
}
