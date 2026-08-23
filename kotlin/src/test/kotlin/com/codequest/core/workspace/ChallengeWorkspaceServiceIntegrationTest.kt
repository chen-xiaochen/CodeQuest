package com.codequest.core.workspace

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.Difficulty
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Problem
import com.codequest.core.model.ProblemId
import com.codequest.core.model.TestCaseSource
import com.codequest.infra.db.ExposedChallengeRepository
import com.codequest.infra.db.ExposedProblemRepository
import com.codequest.infra.db.ExposedTestCaseRepository
import com.codequest.infra.db.newTempDatabase
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [integration] 数据链路(WP16 acceptance 自动化部分):
 * 创建 challenge → 测试用例 → 落库 → 读回一致(1.5.0 多解法随底部挑战窗删除)。
 *
 * 服务在 core(纯 Kotlin),仓库实现来自 infra(Exposed/SQLite),测试代码可跨层组装。
 */
class ChallengeWorkspaceServiceIntegrationTest {
    private val fixedInstant = Instant.parse("2026-08-15T08:00:00Z")

    private fun problem() =
        Problem(
            id = ProblemId("p-1"),
            platformId = PlatformId("leetcode"),
            remoteId = "two-sum",
            title = "两数之和",
            difficulty = Difficulty("easy"),
            url = "https://leetcode.cn/problems/two-sum/",
            tags = emptyList(),
            fetchedAt = fixedInstant,
        )

    @Test
    fun `create challenge writes entry file and persists`() {
        val (db, dir) = newTempDatabase()
        db.use {
            val challenges = ExposedChallengeRepository(db)
            val service =
                ChallengeWorkspaceService(
                    challengeRepository = challenges,
                    testCaseRepository = ExposedTestCaseRepository(db),
                    workspaceRoot = { dir.resolveSibling("ws").resolve(it.id.value) },
                    ids = { "id-${(itmark++).toString().padStart(2, '0')}" },
                    clock = { fixedInstant },
                )
            val created = kotlinx.coroutines.runBlocking { service.createChallenge(problem(), "kotlin") }

            // 入口文件落盘且内容按模板渲染
            val entry = created.entryFile
            assertTrue(Files.isRegularFile(entry), entry.toString())
            val content = Files.readString(entry)
            assertTrue(content.contains("fun main()"), content)
            assertTrue(content.contains("class Solution"), content)
            assertTrue(content.endsWith("\n"), content)

            // challenge + 语言配置落库并可读回
            val reloaded = kotlinx.coroutines.runBlocking {
                challenges.findById(created.challenge.id)
            }
            assertEquals("两数之和", reloaded?.name)
            val languages = kotlinx.coroutines.runBlocking {
                challenges.findLanguages(created.challenge.id)
            }
            assertEquals(1, languages.size)
            assertEquals("kotlin", languages[0].language)
            assertEquals(entry.fileName.toString(), languages[0].entryFilePath)

            // 工作区目录位于注入的根之下
            assertTrue(entry.startsWith(dir.resolveSibling("ws")))
        }
    }

    @Test
    fun `test case save update delete roundtrip`() {
        val (db, dir) = newTempDatabase()
        db.use {
            val challenges = ExposedChallengeRepository(db)
            val testCases = ExposedTestCaseRepository(db)
            val service =
                ChallengeWorkspaceService(
                    challengeRepository = challenges,
                    testCaseRepository = testCases,
                    workspaceRoot = { dir.resolveSibling("ws").resolve(it.id.value) },
                    ids = { "tc-%03d".format(itmark++) },
                    clock = { fixedInstant },
                )
            kotlinx.coroutines.runBlocking {
                val created = service.createChallenge(problem(), "kotlin")
                val challengeId = created.challenge.id

                val case = service.saveTestCase(challengeId, "1\n2", "3")
                val case2 = service.saveTestCase(challengeId, "4\n5", "9")
                assertEquals(2, testCases.findByChallenge(challengeId).size)

                service.saveTestCase(challengeId, "1\n2", "3", existingId = case.id)
                val updated = service.saveTestCase(challengeId, "1\n2", "999", existingId = case.id)
                assertEquals("999", testCases.findById(updated.id)?.expected)
                // 更新不新增行
                assertEquals(2, testCases.findByChallenge(challengeId).size)

                assertTrue(service.deleteTestCase(case2.id))
                assertFalse(service.deleteTestCase(case2.id))
                assertEquals(1, testCases.findByChallenge(challengeId).size)
                assertEquals(TestCaseSource.USER, testCases.findByChallenge(challengeId).first().source)
            }
        }
    }

    @Test
    fun `slug fallback uses title when remoteId is numeric`() {
        val (db, dir) = newTempDatabase()
        db.use {
            val challenges = ExposedChallengeRepository(db)
            val service =
                ChallengeWorkspaceService(
                    challengeRepository = challenges,
                    testCaseRepository = ExposedTestCaseRepository(db),
                    workspaceRoot = { dir.resolveSibling("ws").resolve(it.id.value) },
                    ids = { "id-${itmark++}" },
                    clock = { fixedInstant },
                )
            val created =
                kotlinx.coroutines.runBlocking {
                    service.createChallenge(problem().copy(remoteId = "1927A", title = "Make It White"), "kotlin")
                }
            // 数字开头 remoteId 不能直接当类名,回退到 slugify(title) 再转 PascalCase
            assertEquals("MakeItWhite.kt", created.entryFile.fileName.toString())
            val reloaded = kotlinx.coroutines.runBlocking { challenges.findById(created.challenge.id) }
            assertEquals("Make It White", reloaded?.name)
        }
    }

    private companion object {
        var itmark = 0
    }
}
