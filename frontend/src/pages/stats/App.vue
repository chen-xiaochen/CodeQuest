<script setup lang="ts">
/**
 * 刷题统计页(WP29 真实数据):`GET /api/stats` → 汇总卡片(总提交/AC/AC 率/
 * 当前 streak)+ GitHub 风格日历热力图(纯 CSS grid,亮/暗主题变量)+ 近 30 天
 * 趋势条形;空库友好占位。提交落库后经桥 `submission:updated` 推送本页刷新。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import { TOPIC_SUBMISSION_UPDATED, cqBridge } from '../../bridge/cq-bridge'
import { ApiError } from '../../lib/api'
import { fetchStats, type StatsResult } from './stats-api'

const bridge = cqBridge()

const stats = ref<StatsResult | null>(null)
const loading = ref(false)
const error = ref('')

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
  offBridge = bridge.on(TOPIC_SUBMISSION_UPDATED, onSubmissionUpdated)
})

onUnmounted(() => {
  offBridge?.()
  offBridge = null
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

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

const heatCells = computed<HeatCell[]>(() =>
  (stats.value?.heatmap ?? []).map((day) => ({
    date: day.date,
    count: day.count,
    level: heatLevel(day.count),
    title: `${day.date}: ${day.count} submission${day.count === 1 ? '' : 's'}`,
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
  <AppShell title="Statistics" subtitle="Solving statistics with heatmap and trends.">
    <p v-if="error" class="state state--error" role="alert">
      {{ error }}
      <button type="button" class="state__retry" @click="reload">Retry</button>
    </p>
    <p v-else-if="loading && !stats" class="state">Loading statistics…</p>

    <section v-else-if="stats && isEmpty" class="placeholder" aria-label="empty statistics">
      <p class="placeholder__mark">No submissions yet</p>
      <p>
        Submit from the editor with "Submit to OJ"; solved counts, streaks and the
        heatmap will build up here from your local submission history.
      </p>
    </section>

    <template v-else-if="stats && summary">
      <!-- 汇总卡片 -->
      <section class="cards" aria-label="summary cards">
        <div class="card">
          <span class="card__label">Total submissions</span>
          <span class="card__value">{{ summary.totalSubmissions }}</span>
        </div>
        <div class="card">
          <span class="card__label">Accepted</span>
          <span class="card__value">{{ summary.acceptedSubmissions }}</span>
        </div>
        <div class="card">
          <span class="card__label">Acceptance rate</span>
          <span class="card__value">{{ acceptancePercent }}</span>
        </div>
        <div class="card">
          <span class="card__label">Current streak</span>
          <span class="card__value">
            {{ summary.currentStreakDays }} day{{ summary.currentStreakDays === 1 ? '' : 's' }}
          </span>
          <span class="card__hint">
            longest {{ summary.longestStreakDays }} day{{ summary.longestStreakDays === 1 ? '' : 's' }}
          </span>
        </div>
      </section>

      <!-- 日历热力图 -->
      <section class="block" aria-label="submission heatmap">
        <div class="block__head">
          <h2 class="block__title">Submissions heatmap</h2>
          <span class="block__meta">{{ heatRange }}</span>
        </div>
        <div class="heatmap__scroll">
          <div class="heatmap" :aria-label="`Submission heatmap from ${heatRange}`" role="img">
            <div class="heatmap__weekdays" aria-hidden="true">
              <span>Mon</span>
              <span></span>
              <span>Wed</span>
              <span></span>
              <span>Fri</span>
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
          <span class="heatmap__legend-label">Less</span>
          <span class="heat-cell heat-cell--l0" />
          <span class="heat-cell heat-cell--l1" />
          <span class="heat-cell heat-cell--l2" />
          <span class="heat-cell heat-cell--l3" />
          <span class="heat-cell heat-cell--l4" />
          <span class="heatmap__legend-label">More</span>
        </div>
      </section>

      <!-- 近 30 天趋势 -->
      <section class="block" aria-label="recent trend">
        <div class="block__head">
          <h2 class="block__title">Last {{ trendBars.length }} days</h2>
          <span class="block__meta">max {{ trendMax }} / day</span>
        </div>
        <div class="trend__bars">
          <span
            v-for="day in trendBars"
            :key="day.date"
            class="trend__bar"
            :style="{ height: trendHeight(day.count) }"
            :title="`${day.date}: ${day.count}`"
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
