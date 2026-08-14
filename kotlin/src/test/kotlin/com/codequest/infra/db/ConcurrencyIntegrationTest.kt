package com.codequest.infra.db

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.model.SubmissionResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * [integration] 并发读写集成测试(WP14)。
 *
 * 多协程并发写 + 并发读同一 SQLite 文件库:经单写协程(Mutex)串行化后,
 * 不损坏数据库、不丢数据;读始终看到一致快照。
 */
class ConcurrencyIntegrationTest {
    @Test
    fun `concurrent writers do not lose data under single writer mutex`() {
        integrationTest { db ->
            val repo = ExposedSubmissionRepository(db)
            coroutineScope {
                val writerJobs = (0 until 8).map { writerId ->
                    async(Dispatchers.IO) {
                        repeat(25) { i ->
                            val id = "s-$writerId-$i"
                            repo.save(submissionOf(id))
                        }
                    }
                }
                val readerJobs = (0 until 4).map {
                    async(Dispatchers.IO) {
                        repeat(50) { repo.findByChallenge(ChallengeId("c-1")) }
                    }
                }
                writerJobs.awaitAll() + readerJobs.awaitAll()
            }

            assertEquals(200, repo.findByChallenge(ChallengeId("c-1")).size)
            assertEquals(200, db.queryLong("SELECT COUNT(*) FROM submission").toInt())
            // 每一条都能按 id 读回
            (0 until 8).forEach { writerId ->
                (0 until 25).forEach { i ->
                    check(repo.findById(SubmissionId("s-$writerId-$i")) != null) { "丢失 s-$writerId-$i" }
                }
            }
        }
    }

    @Test
    fun `database file stays consistent after concurrent load`() {
        integrationTest { db ->
            val repo = ExposedProblemRepository(db)
            coroutineScope {
                (0 until 4).map { writerId ->
                    async(Dispatchers.IO) {
                        repeat(20) { i ->
                            repo.save(problemOf("p-$writerId-$i", "并发题 $writerId-$i"))
                        }
                    }
                }.awaitAll()
            }
            // integrity check 全绿
            assertEquals("ok", db.queryString("PRAGMA integrity_check").lowercase())
            assertEquals(80, db.queryLong("SELECT COUNT(*) FROM problem").toInt())
        }
    }

    private fun submissionOf(id: String) = Submission(
        id = SubmissionId(id),
        challengeId = ChallengeId("c-1"),
        platformId = PlatformId("codeforces"),
        remoteSubmissionId = "remote-$id",
        language = "Kotlin 2.1",
        codeSnapshot = "fun main() {}",
        verdict = SubmissionResult.Accepted,
        timeMs = 10,
        memoryKb = 1024,
        submittedAt = Instant.parse("2026-08-14T12:00:00Z").plusSeconds(id.hashCode().toLong() % 1000),
    )

    private fun problemOf(id: String, title: String) = com.codequest.core.model.Problem(
        id = com.codequest.core.model.ProblemId(id),
        platformId = PlatformId("codeforces"),
        remoteId = "R-$id",
        title = title,
        difficulty = com.codequest.core.model.Difficulty("easy"),
        url = "u",
        tags = emptyList(),
        fetchedAt = Instant.EPOCH,
    )
}
