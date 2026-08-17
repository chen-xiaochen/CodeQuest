package com.codequest.platform.atcoder

import com.codequest.core.model.Difficulty
import com.codequest.core.model.OJException
import com.codequest.platform.leetcode.FakeOJTransport
import com.codequest.spi.ProblemFilter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [unit] WP25:AtCoderProblemService——kenkoooo 两份资源拉取后的内存过滤
 * (关键字/难度精确与色档区间/offset-limit)、难度换算落 Difficulty、
 * 题面提取与 remoteId 解析,经 FakeOJTransport,不上网络。
 */
class AtCoderProblemServiceTest {
    private val problemsJson =
        """
        [
          {"id":"abc100_a","contest_id":"abc100","problem_index":"A","name":"Happy Birthday!","title":"AtCoder Beginner Contest 100 A - Happy Birthday!"},
          {"id":"abc100_b","contest_id":"abc100","problem_index":"B","name":"Ringo's Favorite Numbers","title":"AtCoder Beginner Contest 100 B - Ringo's Favorite Numbers"},
          {"id":"practice_1","contest_id":"practice","problem_index":"A","name":"Welcome to AtCoder","title":"practice A - Welcome to AtCoder"},
          {"id":"abc099_z","contest_id":"abc099","problem_index":"Z","name":"No Model Entry","title":"AtCoder Beginner Contest 99 Z - No Model Entry"}
        ]
        """.trimIndent()

    private val modelsJson =
        """
        {"abc100_a": {"difficulty": 990.0}, "abc100_b": {"difficulty": -123.45}, "practice_1": {"slope": 1.0}}
        """.trimIndent()

    private val taskPageHtml =
        """
        <div id="main-container"><div class="row">
          <div class="col-sm-2">nav</div>
          <div class="col-sm-10"><p>You are given an integer.</p></div>
        </div></div>
        """.trimIndent()

    private fun service(): Pair<AtCoderProblemService, FakeOJTransport> {
        val transport = FakeOJTransport()
        transport.handler = { request ->
            when {
                request.url.contains("merged-problems.json") -> problemsJson
                request.url.contains("problem-models.json") -> modelsJson
                request.url.endsWith("/contests/abc100/tasks/abc100_a") -> taskPageHtml
                else -> error("unexpected ${request.url}")
            }
        }
        return AtCoderProblemService(AtCoderApi(transport)) to transport
    }

    @Test
    fun `lists problems with converted difficulty and unrated marker`() = runBlocking {
        val (svc, _) = service()

        val summaries = svc.queryProblems(ProblemFilter(limit = 50)).toList()

        assertEquals(listOf("abc100_a", "abc100_b", "practice_1", "abc099_z"), summaries.map { it.remoteId })
        // 990 原样;负 IRT 换算 108;难度表有条目但缺 difficulty 字段按 0 → 147(旧 getOrElse(0) 事实);
        // 完全不在难度表 → "-"(展示侧约定)
        assertEquals("990", summaries[0].difficulty.value)
        assertEquals("108", summaries[1].difficulty.value)
        assertEquals("147", summaries[2].difficulty.value)
        assertEquals("-", summaries[3].difficulty.value)
        assertEquals("AtCoder Beginner Contest 100 A - Happy Birthday!", summaries[0].title)
        assertEquals("https://atcoder.jp/contests/abc100/tasks/abc100_a", summaries[0].url)
        assertTrue(summaries.all { it.tags.isEmpty() }, "AtCoder 无标签体系")
    }

    @Test
    fun `keyword matches name title or remote id case-insensitively`() = runBlocking {
        val (svc, _) = service()

        assertEquals(
            listOf("abc100_a"),
            svc.queryProblems(ProblemFilter(keyword = "happy", limit = 50)).toList().map { it.remoteId },
        )
        assertEquals(
            listOf("practice_1"),
            svc.queryProblems(ProblemFilter(keyword = "PRACTICE", limit = 50)).toList().map { it.remoteId },
        )
        assertEquals(
            listOf("abc100_b"),
            svc.queryProblems(ProblemFilter(keyword = "abc100_b", limit = 50)).toList().map { it.remoteId },
        )
    }

    @Test
    fun `difficulty filter supports exact number and legacy color bands`() = runBlocking {
        val (svc, _) = service()

        assertEquals(
            listOf("abc100_a"),
            svc.queryProblems(ProblemFilter(difficulty = Difficulty("990"), limit = 50)).toList().map { it.remoteId },
        )
        // grey 档(≤399):换算后的 108 与「有模型条目但缺 difficulty」的 147 命中;
        // 完全无难度数据的题目不参与色档过滤
        assertEquals(
            listOf("abc100_b", "practice_1"),
            svc.queryProblems(ProblemFilter(difficulty = Difficulty("grey"), limit = 50)).toList().map { it.remoteId },
        )
        // 非色档词不过滤(SPI 允许忽略不支持的条件)
        assertEquals(
            4,
            svc.queryProblems(ProblemFilter(difficulty = Difficulty("weird"), limit = 50)).toList().size,
        )
    }

    @Test
    fun `offset and limit applied after filtering`() = runBlocking {
        val (svc, _) = service()

        val page = svc.queryProblems(ProblemFilter(limit = 2, offset = 1)).toList()

        assertEquals(listOf("abc100_b", "practice_1"), page.map { it.remoteId })
    }


    @Test
    fun `problem detail extracts second row child as statement html`() = runBlocking {
        val (svc, transport) = service()

        val statement = svc.fetchProblemDetail("abc100_a")

        assertEquals("abc100_a", statement.remoteId)
        assertTrue(statement.content.contains("You are given an integer."))
        assertTrue(
            transport.recorded.single().url.endsWith("/contests/abc100/tasks/abc100_a"),
            transport.recorded.single().url,
        )
    }

    @Test
    fun `problem detail rejects malformed remote id`() {
        val (svc, _) = service()
        assertFailsWith<OJException> {
            runBlocking { svc.fetchProblemDetail("no-underscore") }
        }
    }
}
