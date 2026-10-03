<script setup lang="ts">
/**
 * 竞赛页(WP23 真实数据):平台标签(/api/contests 无参)+ 竞赛列表(开始时间/
 * 时长/阶段徽标/参加外链)+ 提醒开关(订阅 = 24h 前提醒,落 contest_reminder 表,
 * IntelliJ 侧调度器派发通知)。
 *
 * 如实选型(M4 前置):「参加」为竞赛页外链直开——CF 参加需登录 POST,
 * 平台内交互留 WP24;届时可换桥消息。
 * WP50:平台标签门控(join /api/auth/status,照 WP49 题库页模式)——仅展示
 * 已登录平台;status 失败不门控(防线);全未登录给引导文案,不回落力扣。
 * 文案全面中文化。
 * WP69(1.8.0):行内「题目」详情展开(GET /api/contest/{remoteId}?platform=,
 * CF/LC 共用;题目行点击经桥 nav:openProblem 开题面——payload platformId =
 * 当前平台 tab,LC remoteId = titleSlug 与题库同源)。平台标签数据源为能力
 * 探测(/api/contests 无参),力扣 CN 接入后自动出现,无需前端改动。
 */
import { computed, onMounted, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import { TOPIC_NAV_OPEN_PROBLEM, cqBridge } from '../../bridge/cq-bridge'
import {
  ApiError,
  fetchAuthStatus,
  fetchContestDetail,
  fetchContestPlatforms,
  fetchContestReminders,
  fetchContests,
  subscribeContestReminder,
  unsubscribeContestReminder,
  type AuthStatusItem,
  type ContestDetailResult,
  type ContestPhase,
  type ContestSummary,
  type PlatformSummary,
} from '../../lib/api'
import { filterPlatformsByAuth } from '../problems/platformGate'

const bridge = cqBridge()

/** 默认提醒提前量(分钟),与后端 ContestReminderService.DEFAULT_LEAD_TIME_MINUTES 一致。 */
const DEFAULT_LEAD_TIME_MINUTES = 1440

const PHASE_LABELS: Record<ContestPhase, string> = {
  BEFORE: '未开始',
  CODING: '进行中',
  PENDING_SYSTEM_TEST: '等待系统测试',
  SYSTEM_TEST: '系统测试中',
  FINISHED: '已结束',
}

const platforms = ref<PlatformSummary[]>([])
const platform = ref('')
const items = ref<ContestSummary[]>([])
const subscribedIds = ref<Set<string>>(new Set())
const loading = ref(false)
const error = ref('')

// WP69:行内详情展开(单槽——同槽再点收起;换行/换平台自动关闭)
const detailRemoteId = ref('')
const detail = ref<ContestDetailResult | null>(null)
const detailLoading = ref(false)
const detailError = ref('')

// WP50:登录快照门控(照题库页模式;auth 端点失败不门控)
const authItems = ref<AuthStatusItem[]>([])
/** GET /api/auth/status 是否成功过——失败/未装配时展示全部平台(不门控防线)。 */
const authKnown = ref(false)

/** 门控后的可见平台:已登录内连接;authKnown=false 时原样全量。 */
const visiblePlatforms = computed(() =>
  authKnown.value ? filterPlatformsByAuth(platforms.value, authItems.value) : platforms.value,
)

/** 全未登录(快照可用且过滤后为空)——竞赛页不回落力扣(力扣无竞赛能力)。 */
const noneLoggedIn = computed(
  () => authKnown.value && visiblePlatforms.value.length === 0,
)

const hasPlatform = computed(() => visiblePlatforms.value.length > 0)

function describeError(cause: unknown): string {
  if (cause instanceof ApiError) {
    return `[${cause.code}] ${cause.message}`
  }
  return cause instanceof Error ? cause.message : String(cause)
}

function formatStartTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatDuration(minutes: number): string {
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (hours === 0) return `${rest} 分钟`
  if (rest === 0) return `${hours} 小时`
  return `${hours} 小时 ${rest} 分钟`
}

async function loadPlatform(): Promise<void> {
  if (!platform.value) return
  closeDetail()
  loading.value = true
  error.value = ''
  try {
    const [contests, reminders] = await Promise.all([
      fetchContests(platform.value),
      fetchContestReminders(platform.value),
    ])
    items.value = contests
    subscribedIds.value = new Set(reminders.map((reminder) => reminder.remoteId))
  } catch (cause) {
    items.value = []
    subscribedIds.value = new Set()
    error.value = describeError(cause)
  } finally {
    loading.value = false
  }
}

/** WP69:关闭详情展开(平台切换/重载时收起)。 */
function closeDetail(): void {
  detailRemoteId.value = ''
  detail.value = null
  detailError.value = ''
}

/** WP69:展开/收起竞赛题目详情(同槽再点收起;CF/LC 共用端点与渲染)。 */
async function toggleDetail(contest: ContestSummary): Promise<void> {
  if (detailRemoteId.value === contest.remoteId) {
    closeDetail()
    return
  }
  detailRemoteId.value = contest.remoteId
  detail.value = null
  detailError.value = ''
  detailLoading.value = true
  try {
    detail.value = await fetchContestDetail(platform.value, contest.remoteId)
  } catch (cause) {
    detailError.value = describeError(cause)
  } finally {
    detailLoading.value = false
  }
}

/**
 * WP69:详情题目行点击开题面——platformId 用当前平台 tab(CF=codeforces,
 * LC=leetcodecn),LC remoteId 为 titleSlug,与题库行同源同款桥调用。
 */
function openContestProblem(remoteId: string): void {
  bridge.send(TOPIC_NAV_OPEN_PROBLEM, { platformId: platform.value, remoteId })
}

function isSubscribed(remoteId: string): boolean {
  return subscribedIds.value.has(remoteId)
}

async function toggleReminder(contest: ContestSummary, enabled: boolean | string | number): Promise<void> {
  const on = Boolean(enabled)
  try {
    if (on) {
      await subscribeContestReminder({
        platform: platform.value,
        remoteId: contest.remoteId,
        startTime: contest.startTime,
        leadTimeMinutes: DEFAULT_LEAD_TIME_MINUTES,
      })
      subscribedIds.value = new Set([...subscribedIds.value, contest.remoteId])
    } else {
      await unsubscribeContestReminder(platform.value, contest.remoteId)
      const next = new Set(subscribedIds.value)
      next.delete(contest.remoteId)
      subscribedIds.value = next
    }
  } catch (cause) {
    // 开关失败提示并整页重载,恢复服务端真值(开关视觉状态随之复位)
    error.value = describeError(cause)
    await loadPlatform()
  }
}

onMounted(async () => {
  // WP50:平台清单 ⋈ 登录快照并行拉取——快照失败不门控(authKnown=false),
  // 平台清单失败进错误态;默认选中门控后第一项(全未登录为空,展示引导)
  const [contestsResult, authResult] = await Promise.allSettled([
    fetchContestPlatforms(),
    fetchAuthStatus(),
  ])
  if (contestsResult.status === 'rejected') {
    error.value = describeError(contestsResult.reason)
    return
  }
  platforms.value = contestsResult.value
  if (authResult.status === 'fulfilled') {
    authItems.value = authResult.value
    authKnown.value = true
  }
  platform.value = visiblePlatforms.value[0]?.id ?? ''
  await loadPlatform()
})
</script>

<template>
  <AppShell title="竞赛" subtitle="竞赛开始时间、阶段与提醒订阅;平台需登录后可选。">
    <!-- WP50:全未登录引导(不回落力扣——力扣无竞赛能力) -->
    <p v-if="!error && noneLoggedIn" class="state">
      当前无已登录平台,请在题库页账号区登录;登录 CodeForces 后,竞赛会出现在这里。
    </p>

    <p v-else-if="!loading && !hasPlatform && !error" class="state">
      尚未接入支持竞赛的平台。CodeForces 集成就绪后,竞赛会自动出现在这里。
    </p>

    <div v-if="hasPlatform" class="tabs" role="tablist" aria-label="竞赛平台">
      <button
        v-for="entry in visiblePlatforms"
        :key="entry.id"
        type="button"
        role="tab"
        class="tabs__item"
        :class="{ 'tabs__item--active': entry.id === platform }"
        :aria-selected="entry.id === platform"
        @click="platform = entry.id; loadPlatform()"
      >
        {{ entry.displayName }}
      </button>
    </div>

    <p v-if="error" class="state state--error" role="alert">{{ error }}</p>
    <p v-else-if="loading" class="state">正在加载竞赛…</p>
    <p v-else-if="hasPlatform && items.length === 0" class="state">该平台暂无即将开始的竞赛。</p>

    <ul v-if="items.length > 0" class="contests" aria-label="竞赛列表">
      <li v-for="contest in items" :key="contest.remoteId" class="contests__row">
        <div class="contests__line">
          <div class="contests__main">
            <span class="contests__title">
              <span class="contests__remote">{{ contest.remoteId }}</span>
              {{ contest.title }}
            </span>
            <span class="contests__meta">
              <span class="contests__time">{{ formatStartTime(contest.startTime) }}</span>
              <span class="contests__duration">{{ formatDuration(contest.durationMinutes) }}</span>
              <span class="phase-badge" :class="`phase-badge--${contest.phase}`">
                {{ PHASE_LABELS[contest.phase] ?? contest.phase }}
              </span>
            </span>
          </div>
          <div class="contests__actions">
            <a
              class="contests__join"
              :href="contest.url"
              target="_blank"
              rel="noopener noreferrer"
            >打开</a>
            <button
              type="button"
              class="contests__detail-toggle"
              :aria-expanded="detailRemoteId === contest.remoteId"
              @click="toggleDetail(contest)"
            >
              {{ detailRemoteId === contest.remoteId ? '收起题目' : '题目' }}
            </button>
            <label class="contests__remind-label">
              <input
                class="contests__remind"
                type="checkbox"
                :checked="isSubscribed(contest.remoteId)"
                :data-remote="contest.remoteId"
                @change="toggleReminder(contest, ($event.target as HTMLInputElement).checked)"
              />
              <span>提前 24 小时提醒</span>
            </label>
          </div>
        </div>
        <!-- WP69:题目详情展开(CF/LC 共用;题目行点击开题面) -->
        <div v-if="detailRemoteId === contest.remoteId" class="contests__detail">
          <p v-if="detailLoading" class="contests__detail-state">正在加载题目…</p>
          <p
            v-else-if="detailError"
            class="contests__detail-state contests__detail-state--error"
            role="alert"
          >{{ detailError }}</p>
          <template v-else-if="detail">
            <button
              v-for="(problem, problemIndex) in detail.problems"
              :key="problem.remoteId"
              type="button"
              class="contests__problem"
              :data-remote="problem.remoteId"
              @click="openContestProblem(problem.remoteId)"
            >
              <span class="contests__problem-index">Q{{ problem.index ?? problemIndex + 1 }}</span>
              <span class="contests__problem-title">{{ problem.title }}</span>
            </button>
            <p v-if="detail.problems.length === 0" class="contests__detail-state">
              该竞赛暂无题目信息。
            </p>
          </template>
        </div>
      </li>
    </ul>
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

.tabs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.tabs__item {
  padding: 5px 14px;
  border: 1px solid var(--cq-border);
  border-radius: 999px;
  background: var(--cq-panel);
  color: var(--cq-fg);
  font-size: 12px;
  cursor: pointer;
}

.tabs__item--active {
  background: var(--cq-accent-soft);
  border-color: var(--cq-accent);
  color: var(--cq-accent);
}

.contests {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.contests__row {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel);
}

/* WP69:竞赛行首行(主信息 + 操作),原 row 布局下沉到这一层 */
.contests__line {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.contests__main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.contests__title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: var(--cq-fg);
}

.contests__remote {
  font-size: 11px;
  color: var(--cq-muted);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
}

.contests__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--cq-muted);
}

.phase-badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  border: 1px solid transparent;
}

.phase-badge--BEFORE {
  color: var(--cq-accent);
  border-color: var(--cq-accent);
  background: var(--cq-accent-soft);
}

.phase-badge--CODING {
  color: var(--cq-success);
  border-color: var(--cq-success);
  background: var(--cq-panel-raised);
}

.phase-badge--PENDING_SYSTEM_TEST,
.phase-badge--SYSTEM_TEST {
  color: var(--cq-warning);
  border-color: var(--cq-warning);
  background: var(--cq-panel-raised);
}

.phase-badge--FINISHED {
  color: var(--cq-muted);
  border-color: var(--cq-border);
  background: var(--cq-panel-raised);
}

.contests__actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  flex-shrink: 0;
}

/* WP69:「题目」展开按钮(次级操作,弱于「打开」主按钮) */
.contests__detail-toggle {
  padding: 3px 12px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-size: 12px;
  cursor: pointer;
}

.contests__detail-toggle:hover {
  border-color: var(--cq-accent);
}

/* WP69:详情展开区(题目行列表;行点击开题面) */
.contests__detail {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-top: 6px;
  border-top: 1px dashed var(--cq-border);
}

.contests__detail-state {
  margin: 0;
  font-size: 12px;
  color: var(--cq-muted);
}

.contests__detail-state--error {
  color: var(--cq-danger);
}

.contests__problem {
  display: flex;
  align-items: center;
  gap: 8px;
  text-align: left;
  padding: 4px 8px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--cq-fg);
  font-size: 12px;
  cursor: pointer;
}

.contests__problem:hover {
  border-color: var(--cq-accent);
  background: var(--cq-panel-raised);
}

.contests__problem-index {
  flex: none;
  min-width: 28px;
  font-size: 11px;
  font-weight: 600;
  color: var(--cq-accent);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
}

.contests__problem-title {
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.contests__join {
  padding: 3px 12px;
  border: 1px solid var(--cq-accent);
  border-radius: 6px;
  background: var(--cq-accent);
  color: var(--cq-accent-fg);
  font-size: 12px;
  text-decoration: none;
}

.contests__remind-label {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--cq-muted);
  cursor: pointer;
}
</style>
