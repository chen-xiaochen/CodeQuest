package com.codequest.ui.submission

import com.codequest.core.model.AuthExpiredException
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.PlatformId
import com.codequest.core.model.Submission
import com.codequest.core.model.SubmissionId
import com.codequest.core.model.SubmissionResult
import com.codequest.core.model.SubmissionTimeoutException
import com.codequest.core.repository.SubmissionRepository
import com.codequest.core.workspace.TemplateStyle
import com.codequest.spi.OJPlatform
import com.codequest.spi.PlatformInfo
import com.codequest.spi.SubmissionCapable
import com.codequest.spi.SubmissionHandle
import com.codequest.spi.SubmitRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [integration-ish unit] WP21:提交流程编排(假平台,不上网络)——
 * submit→轮询 Pending/Judging/Accepted→落库;AuthExpired→重登→重试一次。
 */
class SubmissionFlowTest {
    private val fixedInstant = Instant.parse("2026-08-15T08:00:00Z")

    private class FakeSubmissionPlatform : OJPlatform, SubmissionCapable {
        var submitCalls = 0
        var authFailuresRemaining = 0
        var states: List<SubmissionResult> = emptyList()
        var statesError: Throwable? = null
        var lastRequest: SubmitRequest? = null

        override val id = PlatformId("fake")
        override val domain = "fake.test"
        override val info = PlatformInfo(displayName = "Fake", templateStyle = TemplateStyle.ACM)

        override suspend fun submit(request: SubmitRequest): SubmissionHandle {
            submitCalls++
            lastRequest = request
            if (authFailuresRemaining > 0) {
                authFailuresRemaining--
                throw AuthExpiredException("session expired (call #$submitCalls)")
            }
            return SubmissionHandle(id, "remote-$submitCalls")
        }

        override fun watchSubmission(handle: SubmissionHandle): Flow<SubmissionResult> =
            statesError?.let { error ->
                flow {
                    emit(SubmissionResult.Pending)
                    throw error
                }
            } ?: states.asFlow()
    }

    private class RecordingRepository : SubmissionRepository {
        val saved = mutableListOf<Submission>()

        override suspend fun save(submission: Submission) {
            saved += submission
        }

        override suspend fun findById(id: SubmissionId): Submission? = saved.firstOrNull { it.id == id }

        override suspend fun findByChallenge(challengeId: ChallengeId): List<Submission> =
            saved.filter { it.challengeId == challengeId }
    }

    private class RecordingHooks(var reloginResult: Boolean = false) : SubmissionFlow.Hooks {
        val states = mutableListOf<SubmissionResult>()
        val saved = mutableListOf<Submission>()
        var reloginCalls = 0

        override fun onState(result: SubmissionResult) {
            states += result
        }

        override suspend fun relogin(): Boolean {
            reloginCalls++
            return reloginResult
        }

        override fun onSaved(submission: Submission) {
            saved += submission
        }
    }

    private fun flow(repo: SubmissionRepository) =
        SubmissionFlow(
            submissionRepository = repo,
            ids = { "sub-id-1" },
            clock = { fixedInstant },
        )

    private fun request(code: String = "class Solution") = SubmitRequest("two-sum", "kotlin", code)

    @Test
    fun `submits code polls to terminal state and persists submission`() =
        runBlocking {
            val platform =
                FakeSubmissionPlatform().apply {
                    states = listOf(SubmissionResult.Pending, SubmissionResult.Judging, SubmissionResult.Accepted)
                }
            val repo = RecordingRepository()
            val hooks = RecordingHooks()
            val challengeId = ChallengeId("c-1")

            val submission =
                flow(repo).submit(
                    platform = platform,
                    capable = platform,
                    request = request(code = "fun answer() = 42"),
                    challengeId = challengeId,
                    languageLabel = "Kotlin",
                    hooks = hooks,
                )

            assertEquals(1, platform.submitCalls)
            assertEquals("two-sum", platform.lastRequest?.problemRemoteId)
            assertEquals("kotlin", platform.lastRequest?.languageId)
            assertEquals("fun answer() = 42", platform.lastRequest?.code)

            assertEquals(listOf(SubmissionResult.Pending, SubmissionResult.Judging, SubmissionResult.Accepted), hooks.states)

            assertEquals(1, repo.saved.size)
            val stored = repo.saved.single()
            assertEquals(SubmissionId("sub-id-1"), stored.id)
            assertEquals(challengeId, stored.challengeId)
            assertEquals(PlatformId("fake"), stored.platformId)
            assertEquals("remote-1", stored.remoteSubmissionId)
            assertEquals("Kotlin", stored.language)
            assertEquals("fun answer() = 42", stored.codeSnapshot)
            assertEquals(SubmissionResult.Accepted, stored.verdict)
            assertEquals(fixedInstant, stored.submittedAt)
            assertEquals(listOf(stored), hooks.saved)
        }

    @Test
    fun `auth expiry retries once after successful relogin`() =
        runBlocking {
            val platform =
                FakeSubmissionPlatform().apply {
                    authFailuresRemaining = 1
                    states = listOf(SubmissionResult.Judging, SubmissionResult.Accepted)
                }
            val repo = RecordingRepository()
            val hooks = RecordingHooks(reloginResult = true)

            flow(repo).submit(platform, platform, request(), ChallengeId("c-1"), "Kotlin", hooks)

            assertEquals(2, platform.submitCalls)
            assertEquals(1, hooks.reloginCalls)
            assertEquals(1, repo.saved.size)
            assertEquals("remote-2", repo.saved.single().remoteSubmissionId)
        }

    @Test
    fun `auth expiry without relogin propagates and nothing is persisted`() =
        runBlocking {
            val platform =
                FakeSubmissionPlatform().apply {
                    authFailuresRemaining = 1
                    states = listOf(SubmissionResult.Accepted)
                }
            val repo = RecordingRepository()
            val hooks = RecordingHooks(reloginResult = false)

            val error =
                assertFailsWith<AuthExpiredException> {
                    flow(repo).submit(platform, platform, request(), ChallengeId("c-1"), "Kotlin", hooks)
                }

            assertEquals(1, platform.submitCalls)
            assertEquals(1, hooks.reloginCalls)
            assertTrue("session expired" in (error.message ?: ""))
            assertTrue(repo.saved.isEmpty())
        }

    @Test
    fun `second auth failure after relogin propagates without further retry`() =
        runBlocking {
            val platform =
                FakeSubmissionPlatform().apply {
                    authFailuresRemaining = 2
                    states = listOf(SubmissionResult.Accepted)
                }
            val repo = RecordingRepository()
            val hooks = RecordingHooks(reloginResult = true)

            assertFailsWith<AuthExpiredException> {
                flow(repo).submit(platform, platform, request(), ChallengeId("c-1"), "Kotlin", hooks)
            }

            assertEquals(2, platform.submitCalls)
            assertEquals(1, hooks.reloginCalls)
            assertTrue(repo.saved.isEmpty())
        }

    @Test
    fun `poll error propagates and nothing is persisted`() =
        runBlocking {
            val platform =
                FakeSubmissionPlatform().apply {
                    states = listOf(SubmissionResult.Pending)
                    statesError = SubmissionTimeoutException("polling timed out")
                }
            val repo = RecordingRepository()

            assertFailsWith<SubmissionTimeoutException> {
                flow(repo).submit(platform, platform, request(), ChallengeId("c-1"), "Kotlin", RecordingHooks())
            }

            assertTrue(repo.saved.isEmpty())
        }
}
