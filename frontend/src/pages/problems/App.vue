<script setup lang="ts">
/**
 * 题目列表页(WP20 真实数据):平台下拉(/api/platforms)+ 关键字 + 难度筛选 +
 * 分页(加载更多);点击行经桥 nav:openProblem 打开题面工具窗口。
 */
import { onMounted, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import { TOPIC_NAV_OPEN_PROBLEM, cqBridge } from '../../bridge/cq-bridge'
import {
  ApiError,
  fetchPlatforms,
  fetchProblems,
  type PlatformSummary,
  type ProblemListItem,
} from '../../lib/api'
import { difficultyClass } from '../../lib/difficulty'

const PAGE_SIZE = 50
const DIFFICULTY_OPTIONS = ['', 'easy', 'medium', 'hard'] as const

const bridge = cqBridge()

const platforms = ref<PlatformSummary[]>([])
const platform = ref('')
const keyword = ref('')
const difficulty = ref('')
const items = ref<ProblemListItem[]>([])
const offset = ref(0)
const hasMore = ref(false)
const loading = ref(false)
const error = ref('')

const DIFFICULTY_LABELS: Record<string, string> = {
  '': 'All difficulties',
  easy: 'Easy',
  medium: 'Medium',
  hard: 'Hard',
}

function describeError(cause: unknown): string {
  if (cause instanceof ApiError) {
    return `[${cause.code}] ${cause.message}`
  }
  return cause instanceof Error ? cause.message : String(cause)
}

async function loadPage(append: boolean): Promise<void> {
  if (!platform.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await fetchProblems({
      platform: platform.value,
      keyword: keyword.value,
      difficulty: difficulty.value || undefined,
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

function resetFilters(): void {
  keyword.value = ''
  difficulty.value = ''
  reload()
}

function openProblem(item: ProblemListItem): void {
  bridge.send(TOPIC_NAV_OPEN_PROBLEM, {
    platformId: platform.value,
    remoteId: item.remoteId,
  })
}

onMounted(async () => {
  try {
    platforms.value = await fetchPlatforms()
    platform.value = platforms.value[0]?.id ?? ''
    reload()
  } catch (cause) {
    error.value = describeError(cause)
  }
})
</script>

<template>
  <AppShell title="Problems" subtitle="Browse and open problems by platform, keyword and difficulty.">
    <form class="filters" @submit.prevent="reload">
      <label class="filters__field">
        <span>Platform</span>
        <select v-model="platform" @change="reload">
          <option v-for="entry in platforms" :key="entry.id" :value="entry.id">
            {{ entry.displayName }}
          </option>
        </select>
      </label>
      <label class="filters__field filters__field--grow">
        <span>Keyword</span>
        <input
          v-model="keyword"
          type="search"
          placeholder="Search by title or id…"
        />
      </label>
      <label class="filters__field">
        <span>Difficulty</span>
        <select v-model="difficulty" @change="reload">
          <option v-for="option in DIFFICULTY_OPTIONS" :key="option" :value="option">
            {{ DIFFICULTY_LABELS[option] }}
          </option>
        </select>
      </label>
      <div class="filters__actions">
        <button type="submit" :disabled="loading || !platform">Search</button>
        <button type="button" :disabled="loading" @click="resetFilters">Reset</button>
      </div>
    </form>

    <p v-if="error" class="state state--error" role="alert">{{ error }}</p>
    <p v-else-if="loading && items.length === 0" class="state">Loading problems…</p>
    <p v-else-if="!loading && items.length === 0" class="state">No problems matched. Adjust the filters and retry.</p>

    <ul v-if="items.length > 0" class="problems" aria-label="problem list">
      <li v-for="item in items" :key="item.remoteId">
        <button type="button" class="problems__row" @click="openProblem(item)">
          <span class="problems__title">
            <span class="problems__remote">{{ item.remoteId }}</span>
            {{ item.title }}
          </span>
          <span class="problems__meta">
            <span class="diff-badge" :class="difficultyClass(item.difficulty)">{{ item.difficulty }}</span>
            <span v-if="item.tags.length > 0" class="problems__tags">{{ item.tags.join(' · ') }}</span>
          </span>
        </button>
      </li>
    </ul>

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
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: flex-end;
  padding: 10px;
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel);
}

.filters__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--cq-muted);
}

.filters__field--grow {
  flex: 1;
  min-width: 180px;
}

.filters select,
.filters input {
  padding: 4px 8px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-size: 13px;
}

.filters__actions {
  display: flex;
  gap: 6px;
}

.filters__actions button {
  padding: 5px 14px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  cursor: pointer;
}

.filters__actions button[type='submit'] {
  background: var(--cq-accent);
  border-color: var(--cq-accent);
  color: var(--cq-accent-fg);
}

.filters__actions button:disabled {
  opacity: 0.6;
  cursor: default;
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

.problems {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.problems__row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  text-align: left;
  padding: 8px 12px;
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel);
  color: var(--cq-fg);
  cursor: pointer;
}

.problems__row:hover {
  border-color: var(--cq-accent);
  background: var(--cq-panel-raised);
}

.problems__title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.problems__remote {
  font-size: 11px;
  color: var(--cq-muted);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
}

.problems__meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  flex-shrink: 0;
}

.problems__tags {
  font-size: 11px;
  color: var(--cq-muted);
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  color: #ffffff;
}

.diff-badge--hard {
  background: var(--cq-diff-hard);
}

.diff-badge--other {
  background: var(--cq-diff-other);
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
