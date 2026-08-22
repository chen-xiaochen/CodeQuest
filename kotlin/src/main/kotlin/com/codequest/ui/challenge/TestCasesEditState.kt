package com.codequest.ui.challenge

import com.codequest.core.model.TestCase
import com.codequest.core.model.TestCaseId

/** 表格行模型(WP54):[id] 非空 = 已有用例,null = 新增行。 */
data class TestCaseRow(
    val id: TestCaseId? = null,
    var input: String = "",
    var expected: String = "",
)

/** diff 产物:upserts 含改与增([TestCaseRow.id] 即 existingId),deletes = 缺席行 id。 */
data class TestCaseEditDiff(
    val upserts: List<TestCaseRow>,
    val deletes: List<TestCaseId>,
)

/**
 * 测试用例编辑对话框的逻辑核(WP54)——列表↔表格行转换与增/删/改 diff。
 *
 * 纯函数、不碰 Swing:[TestCasesDialog] 只做组件接线;单测见
 * `TestCasesEditStateTest`。
 *
 * 语义约定:
 * - 行携带**原用例 id** = 编辑已有行(内容有变才 upsert,existingId 走更新路径);
 * - 行 id 为 null = 新增行(**全空白视为噪音丢弃**,不落库);
 * - 原列表中 id 未出现在行集 = 该行被删;
 * - 纯调序不改内容 → 零写入(按 id 匹配,与行序无关)。
 */
object TestCasesEditState {
    /** DB 用例列表 → 可编辑表格行(保序,id 透传)。 */
    fun toRows(cases: List<TestCase>): List<TestCaseRow> = cases.map { TestCaseRow(it.id, it.input, it.expected) }

    /** 求编辑 diff:按 id 匹配原列表,内容有变/新增进 upserts,缺席 id 进 deletes。 */
    fun diff(
        original: List<TestCase>,
        rows: List<TestCaseRow>,
    ): TestCaseEditDiff {
        val byId = original.associateBy { it.id }
        val present = HashSet<TestCaseId>()
        val upserts = mutableListOf<TestCaseRow>()
        for (row in rows) {
            val id = row.id
            if (id == null) {
                // 新增行全空白 = 噪音(误触增行未填),丢弃
                if (row.input.isNotBlank() || row.expected.isNotBlank()) upserts += row
                continue
            }
            present += id
            val before = byId[id]
            if (before == null || before.input != row.input || before.expected != row.expected) upserts += row
        }
        val deletes = original.filter { it.id !in present }.map { it.id }
        return TestCaseEditDiff(upserts, deletes)
    }
}
