<script setup lang="ts">
/**
 * 刷题统计页(WP29 真实数据;WP50 中文化):`GET /api/stats` → 汇总卡片(总提交/
 * 通过数/AC 率/当前连续)+ GitHub 风格日历热力图(纯 CSS grid,亮/暗主题变量)+
 * 近 30 天趋势条形;空库友好占位。提交落库后经桥 `submission:updated` 推送本页刷新。
 * WP64(1.7.0 F2):站内历史区——已同步条数(remoteSyncedCount)+「同步历史」
 * 按钮(POST /api/submissions/sync 所选平台;成功/失败 toast,进行中防重入,
 * 完成后重拉统计)。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import { TOPIC_SUBMISSION_UPDATED, cqBridge } from '../../bridge/cq-bridge'
import { ApiError, fetchPlatforms, syncSubmissions, type PlatformSummary } from '../../lib/api'
import { fetchStats, type StatsResult } from './stats-api'

const bridge = cqBridge()

const stats = ref<StatsResult | null>(null)
const loading = ref(false)
const error = ref('')

// ---------------------------------------------------------------------------
// WP64:站内历史同步(remoteSyncedCount 呈现 + 同步按钮)
// ---------------------------------------------------------------------------

const platforms = ref<PlatformSummary[]>([])
/** 同步目标平台(默认 leetcode,无则首项;站内历史目前仅 LC 系支持)。 */
const syncPlatform = ref('')
const syncing = ref(false)
const syncNotice = ref('')
const syncNoticeKind = ref<'ok' | 'error'>('ok')
let syncToastTimer: ReturnType<typeof setTimeout> | null = null

/** toast 语义:短暂提示后自动消失(同键新提示覆盖旧计时,照题库页 rating toast)。 */
function showSyncNotice(kind: 'ok' | 'error', text: string): void {
  syncNoticeKind.value = kind
  syncNotice.value = text
  if (syncToastTimer !== null) clearTimeout(syncToastTimer)
  syncToastTimer = setTimeout(() => {
    syncNotice.value = ''
    syncToastTimer = null
  }, 4000)
}

/** 平台清单为附属能力:拉取失败留空(无选项时同步按钮禁用)。 */
async function loadPlatforms(): Promise<void> {
  try {
    platforms.value = await fetchPlatforms()
    syncPlatform.value =
      platforms.value.find((entry) => entry.id === 'leetcode')?.id ?? platforms.value[0]?.id ?? ''
  } catch {
    platforms.value = []
  }
}

/** 同步所选平台站内历史:成功 toast 条数 + 重拉统计;失败 toast 后端消息;防重入。 */
async function handleSync(): Promise<void> {
  if (syncing.value || !syncPlatform.value) return
  syncing.value = true
  try {
    const result = await syncSubmissions(syncPlatform.value)
    showSyncNotice('ok', `同步完成:新入库 ${result.synced} 条,站内共 ${result.total} 条`)
    await load()
  } catch (cause) {
    showSyncNotice('error', `同步失败:${describeError(cause)}`)
  } finally {
    syncing.value = false
  }
}

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
    stats.value = await fetchStats()
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    loading.value = false
  }
}

function reload(): void {
  void load()
}

/** 桥推送:提交落库后 Kotlin 侧发 `submission:updated`,重拉统计。 */
function onSubmissionUpdated(): void {
  reload()
}

let offBridge: (() => void) | null = null
onMounted(() => {
  void load()
  void loadPlatforms()
  offBridge = bridge.on(TOPIC_SUBMISSION_UPDATED, onSubmissionUpdated)
})

onUnmounted(() => {
  offBridge?.()
  offBridge = null
  if (syncToastTimer !== null) {
    clearTimeout(syncToastTimer)
    syncToastTimer = null
  }
})

const isEmpty = computed(() => (stats.value?.summary.totalSubmissions ?? 0) === 0)
const summary = computed(() => stats.value?.summary)
const acceptancePercent = computed(() =>
  summary.value ? `${(summary.value.acceptanceRate * 100).toFixed(1)}%` : '—',
)

// ---------------------------------------------------------------------------
// 热力图(GitHub 风格:列=周,行=周一..周日;纯 CSS grid,无图表库)
// ---------------------------------------------------------------------------

/** 计数 → 色阶(0..4;阈值与 GitHub 量级一致)。 */
function heatLevel(count: number): number {
  if (count <= 0) return 0
  if (count <= 2) return 1
  if (count <= 5) return 2
  if (count <= 9) return 3
  return 4
}

interface HeatCell {
  date: string
  count: number
  level: number
  title: string
}

/** 首列对齐周一所需的前置空白格数(UTC 解析,布局不随运行时区漂移)。 */
function mondayPadding(firstDate: string): number {
  const utc = new Date(`${firstDate}T00:00:00Z`)
  return (utc.getUTCDay() + 6) % 7
}

/** 月份标签:所在周(列)的月份相对上一标注变化时打标,列号对齐日格。 */
interface MonthLabel {
  column: number
  label: string
}

const MONTHS = ['1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月', '9月', '10月', '11月', '12月']

const heatCells = computed<HeatCell[]>(() =>
  (stats.value?.heatmap ?? []).map((day) => ({
    date: day.date,
    count: day.count,
    level: heatLevel(day.count),
    title: `${day.date}：${day.count} 次提交`,
  })),
)

const heatPads = computed(() =>
  heatCells.value.length > 0 ? mondayPadding(heatCells.value[0].date) : 0,
)

const monthLabels = computed<MonthLabel[]>(() => {
  const cells = heatCells.value
  if (cells.length === 0) return []
  const labels: MonthLabel[] = []
  let previous = -1
  const totalColumns = Math.ceil((heatPads.value + cells.length) / 7)
  for (let week = 0; week < totalColumns; week++) {
    const cell = cells[week * 7 - heatPads.value]
    if (!cell) continue
    const month = new Date(`${cell.date}T00:00:00Z`).getUTCMonth()
    if (month !== previous) {
      labels.push({ column: week + 1, label: MONTHS[month] })
      previous = month
    }
  }
  return labels
})

const heatRange = computed(() => {
  const cells = heatCells.value
  if (cells.length === 0) return ''
  return `${cells[0].date} – ${cells[cells.length - 1].date}`
})

// ---------------------------------------------------------------------------
// 趋势(近 30 天条形;高度 = count / max)
// ---------------------------------------------------------------------------

const trendBars = computed(() => stats.value?.trend ?? [])
const trendMax = computed(() => Math.max(1, ...trendBars.value.map((day) => day.count)))
const trendHeight = (count: number): string => `${Math.round((count / trendMax.value) * 100)}%`
</script>

<template>
  <AppShell title="统计" subtitle="本地提交历史的刷题统计、热力图与趋势。">
    <!-- WP64:站内历史同步区(统计加载后常驻,空库态也可同步;自含 v-if 不入下方状态链) -->
    <section v-if="stats" class="remote-sync" aria-label="站内提交历史">
      <span class="remote-sync__count">
        站内历史:已同步 {{ stats.remoteSyncedCount ?? 0 }} 条
      </span>
      <select v-if="platforms.length > 0" v-model="syncPlatform" aria-label="同步平台">
        <option v-for="entry in platforms" :key="entry.id" :value="entry.id">
          {{ entry.displayName }}
        </option>
      </select>
      <button
        type="button"
        class="remote-sync__btn"
        :disabled="syncing || !syncPlatform"
        @click="handleSync"
      >
        {{ syncing ? '同步中…' : '同步历史' }}
      </button>
    </section>
    <p
      v-if="syncNotice"
      class="remote-sync__toast"
      :class="`remote-sync__toast--${syncNoticeKind}`"
      role="status"
    >
      {{ syncNotice }}
    </p>

    <p v-if="error" class="state state--error" role="alert">
      {{ error }}
      <button type="button" class="state__retry" @click="reload">重试</button>
    </p>
    <p v-else-if="loading && !stats" class="state">正在加载统计…</p>

    <section v-else-if="stats && isEmpty" class="placeholder" aria-label="空统计">
      <p class="placeholder__mark">还没有提交记录</p>
      <p>
        在编辑器中使用「提交到 OJ」后,通过数、连续天数与热力图会随本地提交历史在此累积。
      </p>
    </section>

    <template v-else-if="stats && summary">
      <!-- 汇总卡片 -->
      <section class="cards" aria-label="汇总卡片">
        <div class="card">
          <span class="card__label">总提交</span>
          <span class="card__value">{{ summary.totalSubmissions }}</span>
        </div>
        <div class="card">
          <span class="card__label">通过数</span>
          <span class="card__value">{{ summary.acceptedSubmissions }}</span>
        </div>
        <div class="card">
          <span class="card__label">AC 率</span>
          <span class="card__value">{{ acceptancePercent }}</span>
        </div>
        <div class="card">
          <span class="card__label">当前连续</span>
          <span class="card__value">{{ summary.currentStreakDays }} 天</span>
          <span class="card__hint">最长连续 {{ summary.longestStreakDays }} 天</span>
        </div>
      </section>

      <!-- 日历热力图 -->
      <section class="block" aria-label="提交热力图">
        <div class="block__head">
          <h2 class="block__title">提交热力图</h2>
          <span class="block__meta">{{ heatRange }}</span>
        </div>
        <div class="heatmap__scroll">
          <div class="heatmap" :aria-label="`提交热力图 ${heatRange}`" role="img">
            <div class="heatmap__weekdays" aria-hidden="true">
              <span>一</span>
              <span></span>
              <span>三</span>
              <span></span>
              <span>五</span>
              <span></span>
              <span></span>
            </div>
            <div class="heatmap__right">
              <div class="heatmap__months" aria-hidden="true">
                <span
                  v-for="label in monthLabels"
                  :key="`month-${label.column}`"
                  class="heatmap__month"
                  :style="{ gridColumnStart: label.column }"
                >
                  {{ label.label }}
                </span>
              </div>
              <div class="heatmap__days">
                <span v-for="(_, index) in heatPads" :key="`pad-${index}`" class="heat-pad" />
                <span
                  v-for="cell in heatCells"
                  :key="cell.date"
                  class="heat-cell"
                  :class="`heat-cell--l${cell.level}`"
                  :title="cell.title"
                />
              </div>
            </div>
          </div>
        </div>
        <div class="heatmap__legend heat-palette">
          <span class="heatmap__legend-label">少</span>
          <span class="heat-cell heat-cell--l0" />
          <span class="heat-cell heat-cell--l1" />
          <span class="heat-cell heat-cell--l2" />
          <span class="heat-cell heat-cell--l3" />
          <span class="heat-cell heat-cell--l4" />
          <span class="heatmap__legend-label">多</span>
        </div>
      </section>

      <!-- 近 30 天趋势 -->
      <section class="block" aria-label="近期趋势">
        <div class="block__head">
          <h2 class="block__title">近 {{ trendBars.length }} 天</h2>
          <span class="block__meta">单日最高 {{ trendMax }}</span>
        </div>
        <div class="trend__bars">
          <span
            v-for="day in trendBars"
            :key="day.date"
            class="trend__bar"
            :style="{ height: trendHeight(day.count) }"
            :title="`${day.date}：${day.count} 次`"
          />
        </div>
        <div class="trend__axis">
          <span>{{ trendBars[0]?.date }}</span>
          <span>{{ trendBars[trendBars.length - 1]?.date }}</span>
        </div>
      </section>
    </template>
  </AppShell>
</template>

<style scoped>
/* WP64:站内历史同步区(计数 + 平台下拉 + 同步按钮) */
.remote-sync {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  padding: 8px 12px;
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel);
}

.remote-sync__count {
  font-size: 12px;
  color: var(--cq-muted);
  font-variant-numeric: tabular-nums;
}

.remote-sync select {
  padding: 3px 8px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-size: 12px;
}

.remote-sync__btn {
  padding: 3px 14px;
  border: 1px solid var(--cq-accent);
  border-radius: 6px;
  background: var(--cq-accent);
  color: var(--cq-accent-fg);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.remote-sync__btn:disabled {
  opacity: 0.6;
  cursor: default;
}

.remote-sync__toast {
  margin: 0 0 12px;
  padding: 6px 12px;
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel);
  font-size: 12px;
  color: var(--cq-fg);
}

.remote-sync__toast--ok {
  border-color: var(--cq-success);
  color: var(--cq-success);
}

.remote-sync__toast--error {
  border-color: var(--cq-danger);
  color: var(--cq-danger);
}

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

.placeholder {
  color: var(--cq-muted);
  border: 1px dashed var(--cq-border);
  border-radius: 8px;
  padding: 24px;
  background: var(--cq-panel);
}

.placeholder__mark {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 650;
  color: var(--cq-fg);
}

/* ---- 汇总卡片 ---- */

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px 14px;
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel);
}

.card__label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--cq-muted);
}

.card__value {
  font-size: 22px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}

.card__hint {
  font-size: 11px;
  color: var(--cq-muted);
}

/* ---- 区块通用 ---- */

.block {
  margin-top: 18px;
}

.block__head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 8px;
}

.block__title {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
}

.block__meta {
  font-size: 11px;
  color: var(--cq-muted);
  font-variant-numeric: tabular-nums;
}

/* ---- 热力图色阶(亮色 = GitHub 绿阶;暗色经 [data-theme='dark'] 覆盖) ---- */

.heat-palette {
  --cq-heat-0: #ebedf0;
  --cq-heat-1: #9be9a8;
  --cq-heat-2: #40c463;
  --cq-heat-3: #30a14e;
  --cq-heat-4: #216e39;
}

:global([data-theme='dark']) .heat-palette {
  --cq-heat-0: #2b2d30;
  --cq-heat-1: #0e4429;
  --cq-heat-2: #006d32;
  --cq-heat-3: #26a641;
  --cq-heat-4: #39d353;
}

.heat-cell--l0 {
  background: var(--cq-heat-0);
}

.heat-cell--l1 {
  background: var(--cq-heat-1);
}

.heat-cell--l2 {
  background: var(--cq-heat-2);
}

.heat-cell--l3 {
  background: var(--cq-heat-3);
}

.heat-cell--l4 {
  background: var(--cq-heat-4);
}

.heat-cell {
  display: inline-block;
  width: 13px;
  height: 13px;
  border-radius: 2px;
}

/* ---- 热力图布局:左侧星期标签 + 右侧(月份条 / 日格网格) ---- */

.heatmap__scroll {
  overflow-x: auto;
  padding-bottom: 4px;
}

.heatmap {
  display: flex;
  gap: 4px;
  width: max-content;
}

.heatmap__weekdays {
  display: grid;
  grid-template-rows: repeat(7, 13px);
  gap: 3px;
  padding-top: 17px; /* 月份行 14px + 间距 3px */
  font-size: 9px;
  color: var(--cq-muted);
}

.heatmap__right {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.heatmap__months {
  display: grid;
  grid-auto-flow: column;
  grid-template-rows: 14px;
  grid-auto-columns: 13px;
  gap: 3px;
}

.heatmap__month {
  font-size: 10px;
  color: var(--cq-muted);
  white-space: nowrap;
}

.heatmap__days {
  display: grid;
  grid-auto-flow: column;
  grid-template-rows: repeat(7, 13px);
  grid-auto-columns: 13px;
  gap: 3px;
}

.heatmap__legend {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
}

.heatmap__legend-label {
  font-size: 10px;
  color: var(--cq-muted);
  margin: 0 2px;
}

/* ---- 趋势条形 ---- */

.trend__bars {
  display: flex;
  align-items: flex-end;
  gap: 3px;
  height: 120px;
  padding: 4px 2px 0;
  border-bottom: 1px solid var(--cq-border);
}

.trend__bar {
  flex: 1;
  min-height: 2px;
  border-radius: 2px 2px 0 0;
  background: var(--cq-accent);
  opacity: 0.85;
}

.trend__bar:hover {
  opacity: 1;
}

.trend__axis {
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 10px;
  color: var(--cq-muted);
  font-variant-numeric: tabular-nums;
}
</style>
