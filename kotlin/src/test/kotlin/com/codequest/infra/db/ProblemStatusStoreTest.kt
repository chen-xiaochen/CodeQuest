package com.codequest.infra.db

import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.PlatformId
import com.codequest.core.model.ProblemId
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.model.SubmissionResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [integration-ish unit] WP37:题目刷题状态聚合——AC(任一提交 isSuccessful)/
 * TRIED(有提交无 AC,非终态 Pending 也算有提交)/未做(不在返回表)/
 * 多 challenge 取最高(AC > TRIED)/跨平台隔离(problem.id 含平台前缀)/
 * 空请求短路。数据经既有 Exposed 仓库写入,读路径走被测 store。
 */
class ProblemStatusStoreTest {
    @Test
    fun `accepted submission yields AC`() {
        integrationTest { db ->
            seedSubmission(db, problem = "leetcode:two-sum", challenge = "c-1", verdict = SubmissionResult.Accepted)

            val statuses = ExposedProblemStatusStore(db).statuses(listOf(ProblemId("leetcode:two-sum")))

            assertEquals(mapOf(ProblemId("leetcode:two-sum") to SolveStatus.AC), statuses)
        }
    }

    @Test
    fun `failed or pending submissions yield TRIED`() {
        integrationTest { db ->
            seedSubmission(db, problem = "leetcode:coin-change", challenge = "c-1", verdict = SubmissionResult.WrongAnswer)
            // 非终态也算「有提交」:判定未出仍是已尝试
            seedSubmission(db, problem = "leetcode:pending-one", challenge = "c-2", verdict = SubmissionResult.Pending)

            val store = ExposedProblemStatusStore(db)
            val statuses =
                store.statuses(listOf(ProblemId("leetcode:coin-change"), ProblemId("leetcode:pending-one")))

            assertEquals(SolveStatus.TRIED, statuses[ProblemId("leetcode:coin-change")])
            assertEquals(SolveStatus.TRIED, statuses[ProblemId("leetcode:pending-one")])
        }
    }

    @Test
    fun `problem without submissions is absent meaning UNSOLVED`() {
        integrationTest { db ->
            seedSubmission(db, problem = "leetcode:solved", challenge = "c-1", verdict = SubmissionResult.Accepted)

            val statuses =
                ExposedProblemStatusStore(db).statuses(
                    listOf(ProblemId("leetcode:solved"), ProblemId("leetcode:never-touched")),
                )

            assertTrue(ProblemId("leetcode:never-touched") !in statuses)
            assertEquals(setOf(ProblemId("leetcode:solved")), statuses.keys)
        }
    }

    @Test
    fun `multiple challenges roll up to highest status`() {
        integrationTest { db ->
            // 工作区一全 WA、工作区二 AC → AC
            seedSubmission(db, problem = "leetcode:multi", challenge = "c-1", verdict = SubmissionResult.WrongAnswer)
            seedSubmission(db, problem = "leetcode:multi", challenge = "c-2", verdict = SubmissionResult.Accepted)
            // 另一题:两工作区均未通过(PartialScore 不算完全通过)→ TRIED
            seedSubmission(db, problem = "leetcode:partial", challenge = "c-3", verdict = SubmissionResult.PartialScore(0.9))
            seedSubmission(db, problem = "leetcode:partial", challenge = "c-4", verdict = SubmissionResult.TimeLimitExceeded)

            val statuses =
                ExposedProblemStatusStore(db).statuses(listOf(ProblemId("leetcode:multi"), ProblemId("leetcode:partial")))

            assertEquals(SolveStatus.AC, statuses[ProblemId("leetcode:multi")])
            assertEquals(SolveStatus.TRIED, statuses[ProblemId("leetcode:partial")])
        }
    }

    @Test
    fun `same remoteId on different platforms stays isolated`() {
        integrationTest { db ->
            // 两平台同 remoteId:problem.id 前缀不同,提交只影响所属平台
            seedSubmission(db, problem = "leetcode:1927A", challenge = "c-1", verdict = SubmissionResult.Accepted)

            val statuses =
                ExposedProblemStatusStore(db).statuses(
                    listOf(ProblemId("leetcode:1927A"), ProblemId("codeforces:1927A")),
                )

            assertEquals(SolveStatus.AC, statuses[ProblemId("leetcode:1927A")])
            assertFalse(ProblemId("codeforces:1927A") in statuses)
        }
    }

    @Test
    fun `challenge without submissions does not leak into other problems`() {
        integrationTest { db ->
            // 有 challenge 无提交 → 仍是未做(状态只看 submission,不看 challenge 存在性)
            ExposedChallengeRepository(db)
                .save(Challenge(ChallengeId("c-empty"), ProblemId("leetcode:challenge-only"), "ws", Instant.EPOCH))

            val statuses = ExposedProblemStatusStore(db).statuses(listOf(ProblemId("leetcode:challenge-only")))

            assertTrue(statuses.isEmpty())
        }
    }

    @Test
    fun `empty id collection short circuits to empty map`() {
        integrationTest { db ->
            assertEquals(emptyMap(), ExposedProblemStatusStore(db).statuses(emptyList()))
        }
    }

    // ------------------------------------------------------------------
    // 夹具:challenge + submission 落库(经既有 Exposed 仓库)
    // ------------------------------------------------------------------

    private suspend fun seedSubmission(
        db: CodeQuestDatabase,
        problem: String,
        challenge: String,
        verdict: SubmissionResult,
    ) {
        val platform = problem.substringBefore(':')
        ExposedChallengeRepository(db)
            .save(Challenge(ChallengeId(challenge), ProblemId(problem), "ws-$challenge", Instant.EPOCH))
        ExposedSubmissionRepository(db)
            .save(
                Submission(
                    id = SubmissionId("s-$challenge"),
                    challengeId = ChallengeId(challenge),
                    platformId = PlatformId(platform),
                    remoteSubmissionId = "remote-$challenge",
                    language = "Kotlin",
                    codeSnapshot = "class Solution",
                    verdict = verdict,
                    timeMs = null,
                    memoryKb = null,
                    submittedAt = Instant.EPOCH,
                ),
            )
    }
}
