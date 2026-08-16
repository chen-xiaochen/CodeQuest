<script setup lang="ts">
/**
 * 提交历史页(WP21 真实数据):`GET /api/submissions`(可带 URL query
 * `?challengeId=` 过滤单一工作区)→ 题目/语言/verdict 徽标/耗时/内存/时间列表;
 * Kotlin 侧提交落库后经桥 `submission:updated` 推送本页刷新(桥推送优先,
 * Refresh 按钮兜底)。
 */
import { onMounted, onUnmounted, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import { TOPIC_SUBMISSION_UPDATED, cqBridge } from '../../bridge/cq-bridge'
import { ApiError, fetchSubmissions, type SubmissionListItem } from '../../lib/api'
import { formatMemoryKb, formatMillis, formatTimestamp, verdictMeta } from '../../lib/verdict'

const PAGE_SIZE = 50

const bridge = cqBridge()

const items = ref<SubmissionListItem[]>([])
const offset = ref(0)
const hasMore = ref(false)
const loading = ref(false)
const error = ref('')

/** URL query 携带 challengeId 时过滤单一工作区(提交完成后的定向视图)。 */
const params = new URLSearchParams(window.location.search)
const challengeId = params.get('challengeId')?.trim() || ''

function describeError(cause: unknown): string {
  if (cause instanceof ApiError) {
    return `[${cause.code}] ${cause.message}`
  }
  return cause instanceof Error ? cause.message : String(cause)
}

async function loadPage(append: boolean): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await fetchSubmissions({
      challengeId: challengeId || undefined,
      limit: PAGE_SIZE,
      offset: offset.value,
    })
    items.value = append ? [...items.value, ...result.items] : result.items
    hasMore.value = result.hasMore
    offset.value += result.items.length
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    loading.value = false
  }
}

function reload(): void {
  items.value = []
  offset.value = 0
  hasMore.value = false
  void loadPage(false)
}

/** 桥推送:提交落库后 Kotlin 侧发 `submission:updated`,重拉第一页。 */
function onSubmissionUpdated(): void {
  reload()
}

let offBridge: (() => void) | null = null
onMounted(() => {
  void loadPage(false)
  offBridge = bridge.on(TOPIC_SUBMISSION_UPDATED, onSubmissionUpdated)
})

onUnmounted(() => {
  offBridge?.()
  offBridge = null
})
</script>

<template>
  <AppShell
    title="Submissions"
    :subtitle="challengeId
      ? `Submission history for the selected challenge workspace (${challengeId}).`
      : 'Submission history across all challenge workspaces.'"
  >
    <p v-if="challengeId" class="scope">
      Filtered by challenge <code>{{ challengeId }}</code>
    </p>

    <p v-if="error" class="state state--error" role="alert">
      {{ error }}
      <button type="button" class="state__retry" @click="reload">Retry</button>
    </p>
    <p v-else-if="loading && items.length === 0" class="state">Loading submissions…</p>
    <p v-else-if="!loading && items.length === 0" class="state">
      No submissions yet. Submit from the editor with "Submit to OJ".
    </p>

    <table v-if="items.length > 0" class="submissions" aria-label="submission list">
      <thead>
        <tr>
          <th>Problem</th>
          <th>Language</th>
          <th>Verdict</th>
          <th class="submissions__num">Time</th>
          <th class="submissions__num">Memory</th>
          <th>Submitted</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.id">
          <td class="submissions__problem">
            <span class="submissions__name">{{ item.challengeName ?? item.challengeId }}</span>
            <span class="submissions__meta">{{ item.platformId }} · #{{ item.remoteSubmissionId }}</span>
          </td>
          <td>{{ item.language }}</td>
          <td>
            <span class="verdict-badge" :class="`verdict-badge--${verdictMeta(item.verdict).tone}`">
              {{ verdictMeta(item.verdict).label }}
            </span>
          </td>
          <td class="submissions__num">{{ formatMillis(item.timeMs) }}</td>
          <td class="submissions__num">{{ formatMemoryKb(item.memoryKb) }}</td>
          <td class="submissions__time">{{ formatTimestamp(item.submittedAt) }}</td>
        </tr>
      </tbody>
    </table>

    <div v-if="items.length > 0" class="pager">
      <span class="pager__count">{{ items.length }} loaded</span>
      <button
        v-if="hasMore"
        type="button"
        class="pager__more"
        :disabled="loading"
        @click="loadPage(true)"
      >
        {{ loading ? 'Loading…' : 'Load more' }}
      </button>
      <span v-else class="pager__done">end of results</span>
    </div>
  </AppShell>
</template>

<style scoped>
.scope {
  margin: 12px 0;
  font-size: 12px;
  color: var(--cq-muted);
}

.scope code {
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  color: var(--cq-fg);
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

.submissions {
  width: 100%;
  margin-top: 12px;
  border-collapse: collapse;
  font-size: 13px;
}

.submissions th {
  text-align: left;
  padding: 6px 10px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--cq-muted);
  border-bottom: 1px solid var(--cq-border);
}

.submissions td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--cq-border);
  vertical-align: top;
}

.submissions__num {
  text-align: right;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.submissions__problem {
  max-width: 280px;
}

.submissions__name {
  display: block;
  color: var(--cq-fg);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.submissions__meta {
  display: block;
  font-size: 11px;
  color: var(--cq-muted);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
}

.submissions__time {
  white-space: nowrap;
  color: var(--cq-muted);
}

.verdict-badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  color: #ffffff;
  white-space: nowrap;
}

.verdict-badge--accepted {
  background: var(--cq-success);
}

.verdict-badge--rejected {
  background: var(--cq-danger);
}

.verdict-badge--limit {
  background: var(--cq-warning);
}

.verdict-badge--crash {
  background: var(--cq-accent);
}

.verdict-badge--partial {
  background: var(--cq-warning);
}

.verdict-badge--neutral {
  background: var(--cq-muted);
}

.verdict-badge--pending {
  background: transparent;
  color: var(--cq-muted);
  border: 1px dashed var(--cq-muted);
}

.pager {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  font-size: 12px;
  color: var(--cq-muted);
}

.pager__more {
  padding: 4px 14px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  cursor: pointer;
}

.pager__more:hover {
  border-color: var(--cq-accent);
}

.pager__more:disabled {
  opacity: 0.6;
  cursor: default;
}
</style>
