package com.codequest.platform.luogu

import com.codequest.core.model.ContentFormat
import com.codequest.core.model.Difficulty
import com.codequest.spi.ProblemFilter
import com.codequest.spi.ProblemQueryCapable
import com.codequest.spi.ProblemStatement
import com.codequest.spi.ProblemSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 洛谷题目查询(WP26):ProblemQueryCapable 实现。
 *
 * - 列表:`/problem/list` 单页 perPage=50(响应给出),顺序翻页 + 全局计数消化
 *   [ProblemFilter.offset]/[limit] 语义(调用方 offset 上限 100,至多 3 页请求);
 *   keyword → `keyword` 参数(URL 编码)、difficulty → 洛谷档位数字(中文名/
 *   数字串均接受);**tags 过滤忽略**——洛谷 tag 参数要求标签数字 id,名字→id
 *   需要标签表端点,旧实现经 UI 静态资源携带,不属于本 WP 事实(SPI 契约允许
 *   平台自行忽略不支持的条件);
 * - 详情:`data.problem` 各字段为 Markdown 原文,经 [buildStatementMarkdown] 拼装
 *   (旧 makeDescriptionMarkdown 逐字),contentFormat = MARKDOWN(前端管线已支持);
 * - 列表项难度 → 中文档名(旧 PluginBundle_zh 的 luogu.difficulty.* 文案);
 *   tags 为数字 id 数组(无名字映射,输出空列表)。
 */
class LuoguProblemService(
    private val api: LuoguApi,
) : ProblemQueryCapable {
    override fun queryProblems(filter: ProblemFilter): Flow<ProblemSummary> =
        flow {
            var scanned = 0
            var emitted = 0
            var page = FIRST_PAGE
            while (emitted < filter.limit) {
                val result = api.fetchProblemPage(page = page, difficulty = difficultyCode(filter.difficulty), keyword = filter.keyword)
                if (result.items.isEmpty()) return@flow
                for (item in result.items) {
                    if (scanned >= filter.offset && emitted < filter.limit) {
                        emit(item.toSummary())
                        emitted++
                    }
                    scanned++
                }
                if (scanned >= result.count) return@flow
                page++
            }
        }

    override suspend fun fetchProblemDetail(remoteId: String): ProblemStatement {
        val data = api.fetchProblemData(remoteId)
        return ProblemStatement(remoteId = remoteId, content = data.markdown, contentFormat = ContentFormat.MARKDOWN)
    }

    /** 难度档位数字(0-7);[Difficulty] 值为中文名或数字串,不认识返回 null(不过滤)。 */
    internal fun difficultyCode(difficulty: Difficulty?): Int? {
        if (difficulty == null) return null
        val value = difficulty.value.trim()
        value.toIntOrNull()?.let { code -> return code.takeIf { it in DIFFICULTY_LABELS.indices } }
        return DIFFICULTY_LABELS.indexOfFirst { it == value }.takeIf { it >= 0 }
    }

    private fun LuoguApi.LuoguProblemItem.toSummary() =
        ProblemSummary(
            remoteId = pid,
            title = name,
            difficulty = Difficulty(DIFFICULTY_LABELS[difficulty.coerceIn(DIFFICULTY_LABELS.indices)]),
            url = luoguProblemUrl(pid),
            tags = emptyList(),
        )

    companion object {
        /** 列表页页号从 1 开始(旧实现事实)。 */
        internal const val FIRST_PAGE = 1

        /** 难度档位中文名(旧 PluginBundle_zh luogu.difficulty.* 文案,下标即档位)。 */
        internal val DIFFICULTY_LABELS =
            listOf(
                "暂无评定",
                "入门",
                "普及-",
                "普及/提高-",
                "普及+/提高",
                "提高+/省选-",
                "省选/NOI-",
                "NOI/NOI+/CTSC",
            )
    }
}
