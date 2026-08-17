<script setup lang="ts">
/**
 * 竞赛页(WP23 真实数据):平台标签(/api/contests 无参)+ 竞赛列表(开始时间/
 * 时长/阶段徽标/参加外链)+ 提醒开关(订阅 = 24h 前提醒,落 contest_reminder 表,
 * IntelliJ 侧调度器派发通知)。
 *
 * 如实选型(M4 前置):「参加」为竞赛页外链直开——CF 参加需登录 POST,
 * 平台内交互留 WP24;届时可换桥消息。
 */
import { computed, onMounted, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import {
  ApiError,
  fetchContestPlatforms,
  fetchContestReminders,
  fetchContests,
  subscribeContestReminder,
  unsubscribeContestReminder,
  type ContestPhase,
  type ContestSummary,
  type PlatformSummary,
} from '../../lib/api'

/** 默认提醒提前量(分钟),与后端 ContestReminderService.DEFAULT_LEAD_TIME_MINUTES 一致。 */
const DEFAULT_LEAD_TIME_MINUTES = 1440

const PHASE_LABELS: Record<ContestPhase, string> = {
  BEFORE: 'Before',
  CODING: 'Coding',
  PENDING_SYSTEM_TEST: 'Pending system test',
  SYSTEM_TEST: 'System test',
  FINISHED: 'Finished',
}

const platforms = ref<PlatformSummary[]>([])
const platform = ref('')
const items = ref<ContestSummary[]>([])
const subscribedIds = ref<Set<string>>(new Set())
const loading = ref(false)
const error = ref('')

const hasPlatform = computed(() => platforms.value.length > 0)

function describeError(cause: unknown): string {
  if (cause instanceof ApiError) {
    return `[${cause.code}] ${cause.message}`
  }
  return cause instanceof Error ? cause.message : String(cause)
}

function formatStartTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
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
  if (hours === 0) return `${rest}m`
  if (rest === 0) return `${hours}h`
  return `${hours}h ${rest}m`
}

async function loadPlatform(): Promise<void> {
  if (!platform.value) return
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
  try {
    platforms.value = await fetchContestPlatforms()
    platform.value = platforms.value[0]?.id ?? ''
    await loadPlatform()
  } catch (cause) {
    error.value = describeError(cause)
  }
})
</script>

<template>
  <AppShell title="Contest" subtitle="Contests with start times, phases and reminders.">
    <p v-if="!loading && !hasPlatform && !error" class="state">
      No contest-capable platform is registered yet. CodeForces arrives with the next
      platform integration; contests for it will appear here automatically.
    </p>

    <div v-if="hasPlatform" class="tabs" role="tablist" aria-label="contest platforms">
      <button
        v-for="entry in platforms"
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
    <p v-else-if="loading" class="state">Loading contests…</p>
    <p v-else-if="hasPlatform && items.length === 0" class="state">No upcoming contests on this platform.</p>

    <ul v-if="items.length > 0" class="contests" aria-label="contest list">
      <li v-for="contest in items" :key="contest.remoteId" class="contests__row">
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
          >Open</a>
          <label class="contests__remind-label">
            <input
              class="contests__remind"
              type="checkbox"
              :checked="isSubscribed(contest.remoteId)"
              :data-remote="contest.remoteId"
              @change="toggleReminder(contest, ($event.target as HTMLInputElement).checked)"
            />
            <span>Remind 24h before</span>
          </label>
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
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel);
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
