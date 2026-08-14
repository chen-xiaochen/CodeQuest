package com.codequest.infra.db

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.model.SubmissionResult
import com.codequest.core.repository.SubmissionRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [unit] DAO 与内存假实现行为对照(WP14)。
 *
 * 同一输入序列分别喂给 Exposed 实现与内存假实现,断言输出一致:
 * 覆盖 save/upsert、findById(含缺失)、findByChallenge 时间倒序(含同秒 tie-break)。
 * 内存假实现是 WP16 用例服务单测的基准,两者行为漂移会在此暴露。
 */
class SubmissionRepositoryComparisonTest {
    private val baseTime = Instant.parse("2026-08-14T12:00:00Z")

    private val scenario = listOf(
        submission("a", offsetSeconds = 300, verdict = SubmissionResult.Accepted),
        submission("b", offsetSeconds = 100, verdict = SubmissionResult.WrongAnswer),
        submission("c", offsetSeconds = 200, verdict = SubmissionResult.PartialScore(0.5)),
        submission("d", challengeId = "c-2", offsetSeconds = 50, verdict = SubmissionResult.Pending),
        // upsert:同 id 覆盖 verdict
        submission("b", offsetSeconds = 100, verdict = SubmissionResult.CompileError),
        // 同一秒提交两条,时间倒序需稳定 tie-break(按 id 升序)
        submission("e", offsetSeconds = 200, verdict = SubmissionResult.TimeLimitExceeded),
    )

    @Test
    fun `exposed dao matches in-memory fake behavior`() {
        val fake = InMemorySubmissionRepository()
        integrationTest { db ->
            val exposed = ExposedSubmissionRepository(db)
            scenario.forEach { fake.save(it) }
            for (submission in scenario) exposed.save(submission)

            for (id in listOf("a", "b", "c", "e", "missing")) {
                assertEquals(fake.findById(SubmissionId(id)), exposed.findById(SubmissionId(id)), "findById($id) 不一致")
            }
            for (challenge in listOf("c-1", "c-2", "c-3")) {
                assertEquals(
                    fake.findByChallenge(ChallengeId(challenge)),
                    exposed.findByChallenge(ChallengeId(challenge)),
                    "findByChallenge($challenge) 不一致",
                )
            }
        }
    }

    @Test
    fun `in-memory fake ordering is newest first with id tie-break`() {
        kotlinx.coroutines.runBlocking {
            val fake = InMemorySubmissionRepository()
            scenario.forEach { fake.save(it) }
            assertEquals(
                listOf("a", "c", "e", "b"),
                fake.findByChallenge(ChallengeId("c-1")).map { it.id.value },
            )
            assertNull(fake.findById(SubmissionId("missing")))
        }
    }

    private fun submission(
        id: String,
        challengeId: String = "c-1",
        offsetSeconds: Long,
        verdict: SubmissionResult,
    ) = Submission(
        id = SubmissionId(id),
        challengeId = ChallengeId(challengeId),
        platformId = PlatformId("codeforces"),
        remoteSubmissionId = "remote-$id",
        language = "Kotlin 2.1",
        codeSnapshot = "fun main() { /* $id */ }",
        verdict = verdict,
        timeMs = 42,
        memoryKb = 4096,
        submittedAt = baseTime.plusSeconds(offsetSeconds),
    )
}

/** 内存假实现:WP16 用例服务单测的替换基准;排序契约与 Exposed 实现一致。 */
class InMemorySubmissionRepository : SubmissionRepository {
    private val store = linkedMapOf<SubmissionId, Submission>()

    override suspend fun save(submission: Submission) {
        store[submission.id] = submission
    }

    override suspend fun findById(id: SubmissionId): Submission? = store[id]

    override suspend fun findByChallenge(challengeId: ChallengeId): List<Submission> = store.values
        .asSequence()
        .filter { it.challengeId == challengeId }
        .sortedWith(compareByDescending<Submission> { it.submittedAt }.thenBy { it.id.value })
        .toList()
}
