<script setup lang="ts">
/**
 * WP64(1.7.0 F3):题库页「复习」子视图面板。
 *
 * `GET /api/review/today` 三分组渲染(已过期 → 今日 → 未来;过期组红色系,
 * 行带「已过期」标记);行点击经 `nav:openProblem` 开右窗题面(payload 需
 * platformId+remoteId,联查缺失的行禁点);今日/过期行「标记完成」→ POST
 * complete → 整面板刷新;间隔配置逗号分隔输入 → PUT /api/review/config
 * (非法由后端 400 判定,消息原样提示)。today 组条数经 `count-change` 上抛,
 * 题库页 tab 徽标同步。
 */
import { computed, onMounted, ref } from 'vue'
import { TOPIC_NAV_OPEN_PROBLEM, cqBridge } from '../../bridge/cq-bridge'
import {
  ApiError,
  completeReviewProblem,
  fetchReviewConfig,
  fetchReviewToday,
  updateReviewConfig,
  type ReviewRow,
} from '../../lib/api'
import { difficultyClass } from '../../lib/difficulty'
import { formatEpochDay } from '../../lib/epochDay'

const emit = defineEmits<{ (e: 'count-change', count: number): void }>()

const overdue = ref<ReviewRow[]>([])
const today = ref<ReviewRow[]>([])
const upcoming = ref<ReviewRow[]>([])
const totalTracked = ref(0)
const loading = ref(false)
const error = ref('')

const completingId = ref('')
const completeError = ref('')

const intervalsText = ref('')
const configSaving = ref(false)
const configNotice = ref('')
const configError = ref('')

/** 三分组(渲染顺序即到期优先级:过期最急)。 */
const groups = computed(() => [
  { key: 'overdue', label: '已过期', rows: overdue.value },
  { key: 'today', label: '今日', rows: today.value },
  { key: 'upcoming', label: '未来', rows: upcoming.value },
])

function describeError(cause: unknown): string {
  if (cause instanceof ApiError) {
    return `[${cause.code}] ${cause.message}`
  }
  return cause instanceof Error ? cause.message : String(cause)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await fetchReviewToday()
    overdue.value = result.overdue
    today.value = result.today
    upcoming.value = result.upcoming
    totalTracked.value = result.totalTracked
    emit('count-change', result.today.length)
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    loading.value = false
  }
}

/** 间隔配置为附属能力:拉取失败保留空输入,保存时报后端错误。 */
async function loadConfig(): Promise<void> {
  try {
    intervalsText.value = (await fetchReviewConfig()).intervals.join(',')
  } catch {
    // 静默降级
  }
}

/** 标记完成当日复习 → 刷新列表与徽标计数(失败仅提示,不清列表)。 */
async function handleComplete(row: ReviewRow): Promise<void> {
  if (completingId.value) return
  completingId.value = row.problemId
  completeError.value = ''
  try {
    await completeReviewProblem(row.problemId)
    await load()
  } catch (cause) {
    completeError.value = describeError(cause)
  } finally {
    completingId.value = ''
  }
}

/** 保存间隔:逗号分隔解析为数字数组,合法性(正整数严格升序)由后端 400 判定。 */
async function handleSaveConfig(): Promise<void> {
  if (configSaving.value) return
  configSaving.value = true
  configNotice.value = ''
  configError.value = ''
  const intervals = intervalsText.value
    .split(',')
    .map((part) => Number(part.trim()))
    .filter((value) => Number.isFinite(value))
  try {
    const result = await updateReviewConfig(intervals)
    intervalsText.value = result.intervals.join(',')
    configNotice.value = `间隔已保存:${result.intervals.join(', ')} 天`
  } catch (cause) {
    configError.value = describeError(cause)
  } finally {
    configSaving.value = false
  }
}

/** 联查缺失(无 platformId/remoteId)的行无法定位题面,禁点。 */
function canOpen(row: ReviewRow): boolean {
  return Boolean(row.platformId && row.remoteId)
}

function openProblem(row: ReviewRow): void {
  if (!canOpen(row)) return
  cqBridge().send(TOPIC_NAV_OPEN_PROBLEM, {
    platformId: row.platformId,
    remoteId: row.remoteId,
  })
}

onMounted(() => {
  void load()
  void loadConfig()
})
</script>

<template>
  <div class="review-panel">
    <p v-if="error" class="state state--error" role="alert">
      {{ error }}
      <button type="button" class="state__retry" @click="load">重试</button>
    </p>
    <p v-else-if="loading && totalTracked === 0" class="state">正在加载复习计划…</p>
    <p v-else-if="totalTracked === 0" class="state">
      暂无复习计划——在题面页点「加入复习」,或提交通过后自动排期。
    </p>

    <p v-if="completeError" class="state state--error" role="alert">{{ completeError }}</p>

    <!-- 三分组:已过期(红)→ 今日 → 未来 -->
    <template v-if="totalTracked > 0">
      <section
        v-for="group in groups"
        :key="group.key"
        class="review-group"
        :class="{ 'review-group--overdue': group.key === 'overdue' }"
        :aria-label="`${group.label}复习`"
      >
        <h3 class="review-group__title">
          {{ group.label }}
          <span class="review-group__count">{{ group.rows.length }}</span>
        </h3>
        <p v-if="group.rows.length === 0" class="review-group__empty">无</p>
        <ul v-else class="review-rows">
          <li
            v-for="row in group.rows"
            :key="row.problemId"
            class="review-row"
            :class="{ 'review-row--overdue': group.key === 'overdue' }"
          >
            <button
              type="button"
              class="review-row__main"
              :disabled="!canOpen(row)"
              :title="canOpen(row) ? '打开题面' : '本地无该题主档,无法打开题面'"
              @click="openProblem(row)"
            >
              <span class="review-row__title">{{ row.title }}</span>
              <span class="review-row__meta">
                <span v-if="group.key === 'overdue'" class="review-row__flag">已过期</span>
                <span
                  v-if="row.difficulty"
                  class="diff-badge"
                  :class="difficultyClass(row.difficulty)"
                >
                  {{ row.difficulty }}
                </span>
                <span v-if="row.platformId" class="review-row__platform">{{ row.platformId }}</span>
                <span class="review-row__due">{{ formatEpochDay(row.nextDate) }}</span>
              </span>
            </button>
            <button
              v-if="group.key !== 'upcoming'"
              type="button"
              class="review-row__done"
              :disabled="completingId !== ''"
              @click="handleComplete(row)"
            >
              {{ completingId === row.problemId ? '完成中…' : '标记完成' }}
            </button>
          </li>
        </ul>
      </section>
    </template>

    <!-- 间隔配置(写 settings_kv,经 REST) -->
    <section class="review-config" aria-label="复习间隔配置">
      <div class="review-config__row">
        <label class="review-config__label" for="review-intervals">
          间隔(天,逗号分隔,正整数升序)
        </label>
        <input
          id="review-intervals"
          v-model="intervalsText"
          type="text"
          placeholder="如 1,4,7,14,28,60"
        />
        <button type="button" :disabled="configSaving" @click="handleSaveConfig">
          {{ configSaving ? '保存中…' : '保存间隔' }}
        </button>
      </div>
      <p v-if="configNotice" class="review-config__msg review-config__msg--ok" role="status">
        {{ configNotice }}
      </p>
      <p v-if="configError" class="review-config__msg review-config__msg--error" role="alert">
        {{ configError }}
      </p>
    </section>
  </div>
</template>

<style scoped>
.state {
  margin: 12px 0;
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

/* 三分组区块(过期组整体红色系:标题 + 行左边框) */
.review-group {
  margin: 12px 0;
}

.review-group__title {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 650;
  color: var(--cq-fg);
}

.review-group--overdue .review-group__title {
  color: var(--cq-danger);
}

.review-group__count {
  margin-left: 6px;
  padding: 0 8px;
  border-radius: 999px;
  background: var(--cq-panel-raised);
  border: 1px solid var(--cq-border);
  font-size: 11px;
  font-weight: 600;
  color: var(--cq-muted);
}

.review-group--overdue .review-group__count {
  border-color: var(--cq-danger);
  color: var(--cq-danger);
}

.review-group__empty {
  margin: 0;
  font-size: 12px;
  color: var(--cq-muted);
}

.review-rows {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.review-row {
  display: flex;
  align-items: stretch;
  gap: 6px;
}

.review-row__main {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
  text-align: left;
  padding: 8px 12px;
  border: 1px solid var(--cq-border);
  border-left: 3px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel);
  color: var(--cq-fg);
  cursor: pointer;
}

.review-row__main:hover:not(:disabled) {
  border-color: var(--cq-accent);
  background: var(--cq-panel-raised);
}

.review-row__main:disabled {
  cursor: default;
  opacity: 0.75;
}

/* 过期行:红色系左边框 + 标题着色 */
.review-row--overdue .review-row__main {
  border-left-color: var(--cq-danger);
}

.review-row--overdue .review-row__title {
  color: var(--cq-danger);
}

.review-row__title {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.review-row__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 1 auto;
  font-size: 11px;
  color: var(--cq-muted);
}

.review-row__flag {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--cq-danger);
  color: #ffffff;
  font-weight: 600;
  white-space: nowrap;
}

.review-row__platform {
  white-space: nowrap;
}

.review-row__due {
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  white-space: nowrap;
}

/* 标记完成(今日/过期行) */
.review-row__done {
  flex: none;
  padding: 0 12px;
  border: 1px solid var(--cq-success);
  border-radius: 8px;
  background: var(--cq-panel-raised);
  color: var(--cq-success);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.review-row__done:hover:not(:disabled) {
  background: var(--cq-success);
  color: #ffffff;
}

.review-row__done:disabled {
  opacity: 0.55;
  cursor: default;
}

.diff-badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  color: #ffffff;
}

.diff-badge--easy {
  background: var(--cq-diff-easy);
}

.diff-badge--medium {
  background: var(--cq-diff-medium);
}

.diff-badge--hard {
  background: var(--cq-diff-hard);
}

.diff-badge--other {
  background: var(--cq-diff-other);
}

/* 间隔配置 */
.review-config {
  margin-top: 16px;
  padding: 10px 12px;
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel);
}

.review-config__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.review-config__label {
  font-size: 12px;
  color: var(--cq-muted);
}

.review-config__row input {
  flex: 1;
  min-width: 160px;
  padding: 4px 8px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-size: 13px;
}

.review-config__row button {
  padding: 5px 14px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-accent);
  border-color: var(--cq-accent);
  color: var(--cq-accent-fg);
  cursor: pointer;
}

.review-config__row button:disabled {
  opacity: 0.6;
  cursor: default;
}

.review-config__msg {
  margin: 8px 0 0;
  font-size: 12px;
}

.review-config__msg--ok {
  color: var(--cq-success);
}

.review-config__msg--error {
  color: var(--cq-danger);
}
</style>
