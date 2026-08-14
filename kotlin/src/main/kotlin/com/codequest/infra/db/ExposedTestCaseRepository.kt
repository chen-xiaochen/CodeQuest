package com.codequest.infra.db

import com.codequest.core.model.ChallengeId
import com.codequest.core.model.TestCase
import com.codequest.core.model.TestCaseId
import com.codequest.core.model.TestCaseSource
import com.codequest.core.repository.TestCaseRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/** TestCaseRepository 的 Exposed/SQLite 实现(WP14)。 */
class ExposedTestCaseRepository(
    private val db: CodeQuestDatabase,
) : TestCaseRepository {
    override suspend fun save(testCase: TestCase) =
        db.write {
            TestCases.deleteWhere { TestCases.id eq testCase.id.value }
            TestCases.insert {
                it[id] = testCase.id.value
                it[challengeId] = testCase.challengeId.value
                it[input] = testCase.input
                it[expected] = testCase.expected
                it[origin] = testCase.source.name
            }
            Unit
        }

    override suspend fun findById(id: TestCaseId): TestCase? =
        db.read {
            TestCases
                .selectAll()
                .where { TestCases.id eq id.value }
                .firstOrNull()
                ?.toTestCase()
        }

    override suspend fun findByChallenge(challengeId: ChallengeId): List<TestCase> =
        db.read {
            TestCases
                .selectAll()
                .where { TestCases.challengeId eq challengeId.value }
                .orderBy(TestCases.id to SortOrder.ASC)
                .map { it.toTestCase() }
        }

    override suspend fun delete(id: TestCaseId): Boolean =
        db.write {
            TestCases.deleteWhere { TestCases.id eq id.value } > 0
        }
}

private fun ResultRow.toTestCase() =
    TestCase(
        id = TestCaseId(this[TestCases.id]),
        challengeId = ChallengeId(this[TestCases.challengeId]),
        input = this[TestCases.input],
        expected = this[TestCases.expected],
        source = TestCaseSource.valueOf(this[TestCases.origin]),
    )
