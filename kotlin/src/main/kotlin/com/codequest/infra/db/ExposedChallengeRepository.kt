package com.codequest.infra.db

import com.codequest.core.model.Challenge
import com.codequest.core.model.ChallengeId
import com.codequest.core.model.ChallengeLanguage
import com.codequest.core.model.ChallengeLanguageId
import com.codequest.core.model.ProblemId
import com.codequest.core.repository.ChallengeRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/** ChallengeRepository 的 Exposed/SQLite 实现(WP14)。 */
class ExposedChallengeRepository(
    private val db: CodeQuestDatabase,
) : ChallengeRepository {
    override suspend fun save(challenge: Challenge) =
        db.write {
            // upsert = 全量替换
            Challenges.deleteWhere { Challenges.id eq challenge.id.value }
            Challenges.insert {
                it[id] = challenge.id.value
                it[problemId] = challenge.problemId.value
                it[name] = challenge.name
                it[createdAt] = DbCodecs.formatInstant(challenge.createdAt)
            }
            Unit
        }

    override suspend fun findById(id: ChallengeId): Challenge? =
        db.read {
            Challenges
                .selectAll()
                .where { Challenges.id eq id.value }
                .firstOrNull()
                ?.toChallenge()
        }

    override suspend fun findByProblem(problemId: ProblemId): List<Challenge> =
        db.read {
            Challenges
                .selectAll()
                .where { Challenges.problemId eq problemId.value }
                .orderBy(Challenges.createdAt to SortOrder.ASC)
                .map { it.toChallenge() }
        }

    override suspend fun delete(id: ChallengeId): Boolean =
        db.write {
            // 级联删除该工作区的全部语言配置(实现决定的级联策略)
            ChallengeLanguages.deleteWhere { ChallengeLanguages.challengeId eq id.value }
            Challenges.deleteWhere { Challenges.id eq id.value } > 0
        }

    override suspend fun saveLanguage(language: ChallengeLanguage) =
        db.write {
            ChallengeLanguages.deleteWhere { ChallengeLanguages.id eq language.id.value }
            ChallengeLanguages.insert {
                it[id] = language.id.value
                it[challengeId] = language.challengeId.value
                it[ChallengeLanguages.language] = language.language
                it[languageVersion] = language.languageVersion
                it[template] = language.template
                it[entryFilePath] = language.entryFilePath
            }
            Unit
        }

    override suspend fun findLanguages(challengeId: ChallengeId): List<ChallengeLanguage> =
        db.read {
            ChallengeLanguages
                .selectAll()
                .where { ChallengeLanguages.challengeId eq challengeId.value }
                .map { it.toChallengeLanguage() }
        }

    override suspend fun deleteLanguage(id: ChallengeLanguageId): Boolean =
        db.write {
            ChallengeLanguages.deleteWhere { ChallengeLanguages.id eq id.value } > 0
        }
}

private fun ResultRow.toChallenge() =
    Challenge(
        id = ChallengeId(this[Challenges.id]),
        problemId = ProblemId(this[Challenges.problemId]),
        name = this[Challenges.name],
        createdAt = DbCodecs.parseInstant(this[Challenges.createdAt]),
    )

private fun ResultRow.toChallengeLanguage() =
    ChallengeLanguage(
        id = ChallengeLanguageId(this[ChallengeLanguages.id]),
        challengeId = ChallengeId(this[ChallengeLanguages.challengeId]),
        language = this[ChallengeLanguages.language],
        languageVersion = this[ChallengeLanguages.languageVersion],
        template = this[ChallengeLanguages.template],
        entryFilePath = this[ChallengeLanguages.entryFilePath],
    )
