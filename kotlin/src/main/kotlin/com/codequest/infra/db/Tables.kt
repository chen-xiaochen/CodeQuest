package com.codequest.infra.db

import org.jetbrains.exposed.v1.core.Table

// 列长度(SQLite TEXT 不强制长度,长度仅作文档化上限与一致性约束)
private const val ID_LEN = 128
private const val ENUM_LEN = 16
private const val NAME_LEN = 256
private const val TITLE_LEN = 1024
private const val URL_LEN = 2048
private const val INSTANT_LEN = 48

/**
 * 全量物理表定义(spec §3 schema 蓝本,列名沿用蓝本命名如 dojo_problem_id)。
 *
 * - id 一律 TEXT(core 层 value class 的字符串形态)
 * - 时间列 TEXT,存固定宽度 ISO-8601(见 [DbCodecs.formatInstant]),字典序即时序
 * - submission.verdict TEXT,存 SubmissionResult 的 JSON(classDiscriminator = type)
 * - problem_tag 带 position 列,保证题目标签往返顺序稳定
 */
internal object Problems : Table("problem") {
    val id = varchar("id", ID_LEN)
    val platformId = varchar("platform_id", NAME_LEN)
    val remoteId = varchar("dojo_problem_id", NAME_LEN)
    val title = varchar("title", TITLE_LEN)

    /** 1.6.0:副标题(标题另一语言形态);v2 迁移加列,旧行为 null。 */
    val secondaryTitle = varchar("secondary_title", TITLE_LEN).nullable()
    val difficulty = varchar("difficulty", NAME_LEN)
    val url = varchar("url", URL_LEN)
    val fetchedAt = varchar("fetched_at", INSTANT_LEN)

    override val primaryKey = PrimaryKey(id)
}

internal object ProblemContents : Table("problem_content") {
    val problemId = varchar("problem_id", ID_LEN)
    val contentMd = text("content_md")
    val contentFormat = varchar("content_format", ENUM_LEN)

    override val primaryKey = PrimaryKey(problemId)
}

internal object Challenges : Table("challenge") {
    val id = varchar("id", ID_LEN)
    val problemId = varchar("problem_id", ID_LEN)
    val name = varchar("name", TITLE_LEN)
    val createdAt = varchar("created_at", INSTANT_LEN)

    override val primaryKey = PrimaryKey(id)
}

internal object ChallengeLanguages : Table("challenge_language") {
    val id = varchar("id", ID_LEN)
    val challengeId = varchar("challenge_id", ID_LEN)
    val language = varchar("language", NAME_LEN)
    val languageVersion = varchar("language_version", NAME_LEN)
    val template = text("template")
    val entryFilePath = varchar("entry_file_path", TITLE_LEN)

    override val primaryKey = PrimaryKey(id)
}

internal object Solutions : Table("solution") {
    val id = varchar("id", ID_LEN)
    val challengeId = varchar("challenge_id", ID_LEN)
    val name = varchar("name", TITLE_LEN)
    val noteMd = text("note_md")
    val filePath = varchar("file_path", TITLE_LEN)
    val createdAt = varchar("created_at", INSTANT_LEN)

    override val primaryKey = PrimaryKey(id)
}

internal object TestCases : Table("test_case") {
    val id = varchar("id", ID_LEN)
    val challengeId = varchar("challenge_id", ID_LEN)
    val input = text("input")
    val expected = text("expected")
    val origin = varchar("source", ENUM_LEN)

    override val primaryKey = PrimaryKey(id)
}

internal object Submissions : Table("submission") {
    val id = varchar("id", ID_LEN)
    val challengeId = varchar("challenge_id", ID_LEN)
    val platformId = varchar("platform_id", NAME_LEN)
    val remoteSubmissionId = varchar("remote_submission_id", NAME_LEN)
    val language = varchar("language", NAME_LEN)
    val codeSnapshot = text("code_snapshot")
    val verdict = text("verdict")
    val timeMs = integer("time_ms").nullable()
    val memoryKb = integer("memory_kb").nullable()
    val submittedAt = varchar("submitted_at", INSTANT_LEN)

    override val primaryKey = PrimaryKey(id)
}

internal object Tags : Table("tag") {
    val id = varchar("id", ID_LEN)
    val name = varchar("name", NAME_LEN)

    /** 1.6.0:标签 slug(平台过滤用);v2 迁移加列,旧行为 null。 */
    val slug = varchar("slug", NAME_LEN).nullable()

    /** 1.6.0:中文标签名(展示用);v2 迁移加列,旧行为 null。 */
    val nameCn = varchar("name_cn", NAME_LEN).nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object ProblemTags : Table("problem_tag") {
    val problemId = varchar("problem_id", ID_LEN)
    val tagId = varchar("tag_id", ID_LEN)
    val position = integer("position")

    override val primaryKey = PrimaryKey(problemId, tagId)
}

internal object ContestReminders : Table("contest_reminder") {
    val contestId = varchar("contest_id", ID_LEN)
    val remindAt = varchar("remind_at", INSTANT_LEN)
    val notified = bool("notified")

    override val primaryKey = PrimaryKey(contestId)
}

internal object SettingsKv : Table("settings_kv") {
    val key = varchar("key", NAME_LEN)
    val value = text("value")

    override val primaryKey = PrimaryKey(key)
}

/**
 * 1.7.0:zerotrac 难度评分(F1),对应表 `problem_rating`,按 LC 题号主键;
 * 在线刷新 upsert 全量覆盖(schema v3 raw DDL 建表,不走 SchemaUtils.create)。
 */
internal object ProblemRatings : Table("problem_rating") {
    val frontendId = varchar("frontend_id", NAME_LEN)
    val rating = double("rating")
    val updatedAt = varchar("updated_at", INSTANT_LEN)

    override val primaryKey = PrimaryKey(frontendId)
}

/**
 * 1.7.0:站内提交历史(F2),对应表 `remote_submission`,与本地 `submission` 表独立
 * (站内历史无本地 challenge)。`UNIQUE(platform_id, remote_id)` 支撑增量同步停止
 * 判定(INSERT OR IGNORE 冲突跳过)。id 自增主键由 v3 raw DDL 的
 * `INTEGER PRIMARY KEY AUTOINCREMENT` 保证(不赌 Exposed 方言生成,故本表不进
 * SchemaUtils.create 列表)。
 */
internal object RemoteSubmissions : Table("remote_submission") {
    val id = integer("id").autoIncrement()
    val platformId = varchar("platform_id", NAME_LEN)
    val remoteId = varchar("remote_id", NAME_LEN)
    val titleSlug = varchar("title_slug", NAME_LEN)
    val title = varchar("title", TITLE_LEN)
    val statusDisplay = varchar("status_display", NAME_LEN)
    val lang = varchar("lang", NAME_LEN)
    val runtimeMs = integer("runtime_ms").nullable()
    val memoryKb = integer("memory_kb").nullable()
    val submittedAt = varchar("submitted_at", INSTANT_LEN)

    override val primaryKey = PrimaryKey(id)
}

/**
 * 1.7.0:间隔重复复习排期(F3),对应表 `review_schedule`;plan/history 两列均为
 * epoch 秒数组 JSON(见 [DbCodecs.encodeEpochSecondsList]),秒值对齐当天零点。
 */
internal object ReviewSchedules : Table("review_schedule") {
    val problemId = varchar("problem_id", ID_LEN)
    val planDates = text("plan_dates")
    val historyDates = text("history_dates")
    val updatedAt = varchar("updated_at", INSTANT_LEN)

    override val primaryKey = PrimaryKey(problemId)
}

internal object SchemaVersionTable : Table("schema_version") {
    val version = integer("version")

    override val primaryKey = PrimaryKey(version)
}
