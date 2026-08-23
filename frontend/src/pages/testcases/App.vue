<script setup lang="ts">
/**
 * 测试用例页(1.5.0,右窗「题面」工具窗三 tab 之一;底部挑战工具窗删除后承接
 * 用例管理):URL query `?challengeId=`(Kotlin 侧 ProblemViewTabNavigator 联动)
 * → `GET /api/testcases` 列表(输入/期望/来源徽标:平台样例|手动)→ 行内编辑
 * (点行展开输入/期望 textarea + 保存/删除)+「新增用例」(POST /api/testcases)。
 *
 * 底部「云端测试结果」区:Kotlin 侧 CloudTestCoordinator 经桥 `testrun:result`
 * 推送(X/Y 汇总 + 每用例输入/期望/实际/通过标记;CE/RE 以全文区替代表格);
 * 页面加载后经 `testrun:latest` 主动取回(桥就绪前的推送不丢)。未选挑战时
 * 空态提示「先在题面页开刷」。
 */
import { onMounted, onUnmounted, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import { TOPIC_TESTRUN_LATEST, TOPIC_TESTRUN_RESULT, cqBridge } from '../../bridge/cq-bridge'
import {
  ApiError,
  createTestCase,
  deleteTestCase,
  fetchTestCases,
  updateTestCase,
  type TestCaseItem,
} from '../../lib/api'

const bridge = cqBridge()

const initialId = new URLSearchParams(window.location.search).get('challengeId')?.trim() || ''
const challengeId = ref(initialId)
const challengeName = ref('')
const items = ref<TestCaseItem[]>([])
const loading = ref(false)
const error = ref('')

// ---------------------------------------------------------------------------
// 列表
// ---------------------------------------------------------------------------

function describeError(cause: unknown): string {
  if (cause instanceof ApiError) {
    return `[${cause.code}] ${cause.message}`
  }
  return cause instanceof Error ? cause.message : String(cause)
}

async function load(): Promise<void> {
  if (!challengeId.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await fetchTestCases(challengeId.value)
    challengeName.value = result.challengeName ?? ''
    items.value = result.items
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    loading.value = false
  }
}

/** 桥推送携带的 challengeId 优先(页面 URL 未带/已过期时采纳并重拉)。 */
function adoptChallengeId(id: string | undefined): void {
  if (id && id !== challengeId.value) {
    challengeId.value = id
    void load()
  }
}

// ---------------------------------------------------------------------------
// 行内编辑(点行展开;新增用例 = 无 id 的草稿行)
// ---------------------------------------------------------------------------

/** 正在编辑的条目:null=无;'new'=新增草稿;其余=该用例 id。 */
const editing = ref<string | null>(null)
const draftInput = ref('')
const draftExpected = ref('')
const saving = ref(false)
const editError = ref('')

function startEdit(item: TestCaseItem): void {
  if (saving.value) return
  editing.value = editing.value === item.id ? null : item.id
  draftInput.value = item.input
  draftExpected.value = item.expected
  editError.value = ''
}

function startCreate(): void {
  if (!challengeId.value || saving.value) return
  editing.value = 'new'
  draftInput.value = ''
  draftExpected.value = ''
  editError.value = ''
}

function cancelEdit(): void {
  if (saving.value) return
  editing.value = null
  editError.value = ''
}

async function saveEdit(): Promise<void> {
  if (editing.value == null || saving.value) return
  saving.value = true
  editError.value = ''
  try {
    if (editing.value === 'new') {
      await createTestCase({
        challengeId: challengeId.value,
        input: draftInput.value,
        expected: draftExpected.value,
      })
    } else {
      await updateTestCase({
        id: editing.value,
        input: draftInput.value,
        expected: draftExpected.value,
      })
    }
    editing.value = null
    await load()
  } catch (cause) {
    editError.value = describeError(cause)
  } finally {
    saving.value = false
  }
}

async function removeCase(id: string): Promise<void> {
  if (saving.value) return
  saving.value = true
  editError.value = ''
  try {
    await deleteTestCase(id)
    editing.value = null
    await load()
  } catch (cause) {
    editError.value = describeError(cause)
  } finally {
    saving.value = false
  }
}

/** 行摘要(多行输入取首行,空输入显示占位)。 */
function firstLine(text: string): string {
  const line = text.split('\n')[0]?.trim() ?? ''
  return line === '' ? '(空)' : line
}

function sourceLabel(source: TestCaseItem['source']): string {
  return source === 'PLATFORM' ? '平台样例' : '手动'
}

// ---------------------------------------------------------------------------
// 云端测试结果区(桥 testrun:result 推送)
// ---------------------------------------------------------------------------

interface TestRunRow {
  input: string | null
  expected: string | null
  actual: string | null
  passed: boolean | null
}

interface TestRunPayload {
  challengeId: string
  passed: number
  total: number
  rows: TestRunRow[]
  errorKind?: 'compile' | 'runtime'
  errorText?: string
}

const result = ref<TestRunPayload | null>(null)

function handleTestRunResult(payload: unknown): void {
  const data = payload as TestRunPayload | null
  if (!data || typeof data.challengeId !== 'string') return
  result.value = data
  adoptChallengeId(data.challengeId)
}

/** 桥就绪探测:页面 JS 先于 Kotlin 注入 cqSend 时轮询重试(最多约 5s)。 */
function sendWhenReady(topic: string, payload: unknown, tries = 20): void {
  if (typeof window.cqSend === 'function') {
    bridge.send(topic, payload)
    return
  }
  if (tries > 0) {
    setTimeout(() => sendWhenReady(topic, payload, tries - 1), 250)
  }
}

let offResult: (() => void) | null = null
onMounted(() => {
  if (challengeId.value) {
    void load()
    // 取最近结果:补桥就绪前错过的推送(tab 首建/页面刚重载)
    sendWhenReady(TOPIC_TESTRUN_LATEST, { challengeId: challengeId.value })
  }
  offResult = bridge.on(TOPIC_TESTRUN_RESULT, handleTestRunResult)
})

onUnmounted(() => {
  offResult?.()
  offResult = null
})
</script>

<template>
  <AppShell
    title="测试用例"
    :subtitle="challengeId
      ? (challengeName ? `${challengeName} 的测试用例(编辑后即可「提交测试」)。` : `挑战工作区(${challengeId})的测试用例。`)
      : '当前挑战工作区的测试用例与云端测试结果。'"
  >
    <p v-if="!challengeId" class="state">
      还没有选中的挑战——先在「题面」页打开题目并点击「一键开刷」,再回到本页管理测试用例。
    </p>

    <template v-else>
      <div class="toolbar">
        <button type="button" class="toolbar__add" :disabled="saving" @click="startCreate">
          新增用例
        </button>
        <span class="toolbar__count">{{ items.length }} 条</span>
      </div>

      <p v-if="error" class="state state--error" role="alert">
        {{ error }}
        <button type="button" class="state__retry" @click="load">重试</button>
      </p>
      <p v-else-if="loading && items.length === 0" class="state">正在加载测试用例…</p>
      <p v-else-if="!loading && items.length === 0 && editing !== 'new'" class="state">
        还没有测试用例。点「新增用例」,或开刷时平台样例会自动带入。
      </p>

      <ul v-if="items.length > 0 || editing === 'new'" class="cases" aria-label="测试用例列表">
        <li v-if="editing === 'new'" class="case case--editing">
          <div class="case__editor">
            <label class="case__field">输入<textarea v-model="draftInput" rows="4" /></label>
            <label class="case__field">期望输出<textarea v-model="draftExpected" rows="3" /></label>
            <p v-if="editError" class="case__error" role="alert">{{ editError }}</p>
            <div class="case__actions">
              <button type="button" class="btn btn--primary" :disabled="saving" @click="saveEdit">
                {{ saving ? '保存中…' : '保存' }}
              </button>
              <button type="button" class="btn" :disabled="saving" @click="cancelEdit">取消</button>
            </div>
          </div>
        </li>
        <li
          v-for="item in items"
          :key="item.id"
          class="case"
          :class="{ 'case--editing': editing === item.id }"
        >
          <button
            v-if="editing !== item.id"
            type="button"
            class="case__summary"
            @click="startEdit(item)"
          >
            <span class="case__input">{{ firstLine(item.input) }}</span>
            <span class="case__expected">{{ firstLine(item.expected) }}</span>
            <span class="badge" :class="item.source === 'PLATFORM' ? 'badge--platform' : 'badge--user'">
              {{ sourceLabel(item.source) }}
            </span>
          </button>
          <div v-else class="case__editor">
            <label class="case__field">输入<textarea v-model="draftInput" rows="4" /></label>
            <label class="case__field">期望输出<textarea v-model="draftExpected" rows="3" /></label>
            <p v-if="editError" class="case__error" role="alert">{{ editError }}</p>
            <div class="case__actions">
              <button type="button" class="btn btn--primary" :disabled="saving" @click="saveEdit">
                {{ saving ? '保存中…' : '保存' }}
              </button>
              <button type="button" class="btn btn--danger" :disabled="saving" @click="removeCase(item.id)">
                删除
              </button>
              <button type="button" class="btn" :disabled="saving" @click="cancelEdit">取消</button>
            </div>
          </div>
        </li>
      </ul>
    </template>

    <!-- 云端测试结果(桥 testrun:result 推送;编辑器「提交测试」触发) -->
    <section class="results" aria-label="云端测试结果">
      <h2 class="results__title">云端测试结果</h2>

      <p v-if="!result" class="state">编辑器里点「提交测试」,云端测试结果会显示在这里。</p>

      <template v-else-if="result.errorText">
        <p class="results__summary">
          <strong>{{ result.errorKind === 'compile' ? '编译错误' : '运行错误' }}</strong>
        </p>
        <pre class="results__error">{{ result.errorText }}</pre>
      </template>

      <template v-else>
        <p class="results__summary">
          <strong>{{ result.passed }}/{{ result.total }}</strong> 用例通过
        </p>
        <table v-if="result.rows.length > 0" class="results__table">
          <thead>
            <tr>
              <th>输入</th>
              <th>期望</th>
              <th>实际</th>
              <th>通过</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in result.rows" :key="index">
              <td class="cell-mono">{{ row.input ?? '—' }}</td>
              <td class="cell-mono">{{ row.expected ?? '—' }}</td>
              <td class="cell-mono">{{ row.actual ?? '—' }}</td>
              <td>
                <span
                  class="mark"
                  :class="row.passed === true ? 'mark--pass' : row.passed === false ? 'mark--fail' : ''"
                >
                  {{ row.passed === true ? '通过' : row.passed === false ? '失败' : '—' }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
    </section>
  </AppShell>
</template>

<style scoped>
.state {
  margin: 16px 0;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--cq-muted);
  border: 1px dashed var(--cq-border);
}

.state--error {
  color: var(--cq-danger);
  border: 1px solid var(--cq-danger);
  background: var(--cq-panel);
}

.state__retry {
  margin-left: 10px;
  padding: 2px 10px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  cursor: pointer;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 4px 0 10px;
}

.toolbar__add {
  padding: 4px 14px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-size: 12px;
  cursor: pointer;
}

.toolbar__add:hover:not(:disabled) {
  border-color: var(--cq-accent);
}

.toolbar__add:disabled {
  opacity: 0.6;
  cursor: default;
}

.toolbar__count {
  font-size: 12px;
  color: var(--cq-muted);
}

.cases {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.case {
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel);
}

.case--editing {
  border-color: var(--cq-accent);
}

.case__summary {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 12px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--cq-fg);
  text-align: left;
  cursor: pointer;
  font-size: 13px;
}

.case__summary:hover {
  background: var(--cq-panel-raised);
}

.case__input {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
}

.case__expected {
  max-width: 35%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--cq-muted);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
}

.badge {
  flex: none;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  white-space: nowrap;
}

.badge--platform {
  background: var(--cq-accent);
  color: #fff;
}

.badge--user {
  background: var(--cq-panel-raised);
  color: var(--cq-muted);
  border: 1px solid var(--cq-border);
}

.case__editor {
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.case__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 11px;
  color: var(--cq-muted);
}

.case__field textarea {
  resize: vertical;
  padding: 6px 8px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
}

.case__error {
  margin: 0;
  font-size: 12px;
  color: var(--cq-danger);
}

.case__actions {
  display: flex;
  gap: 8px;
}

.btn {
  padding: 3px 14px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-size: 12px;
  cursor: pointer;
}

.btn:disabled {
  opacity: 0.6;
  cursor: default;
}

.btn--primary {
  border-color: var(--cq-accent);
}

.btn--danger {
  color: var(--cq-danger);
  border-color: var(--cq-danger);
}

/* -------------------------------------------------------------------------
 * 云端测试结果区
 * ------------------------------------------------------------------------- */

.results {
  margin-top: 20px;
  padding-top: 12px;
  border-top: 1px solid var(--cq-border);
}

.results__title {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 650;
}

.results__title + .state {
  margin-top: 0;
}

.results__summary {
  margin: 0 0 8px;
  font-size: 13px;
}

.results__error {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--cq-danger);
  border-radius: 8px;
  color: var(--cq-danger);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow: auto;
  user-select: text;
}

.results__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.results__table th {
  text-align: left;
  padding: 5px 10px;
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--cq-muted);
  border-bottom: 1px solid var(--cq-border);
}

.results__table td {
  padding: 6px 10px;
  border-bottom: 1px solid var(--cq-border);
  vertical-align: top;
}

.cell-mono {
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  white-space: pre-wrap;
  word-break: break-word;
  max-width: 260px;
  user-select: text;
}

.mark {
  display: inline-block;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
}

.mark--pass {
  background: var(--cq-success);
  color: #fff;
}

.mark--fail {
  background: var(--cq-danger);
  color: #fff;
}
</style>
