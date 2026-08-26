<script setup lang="ts">
/**
 * 本地搜索面板(WP49 从 search/App.vue 抽出,题库页「本地搜索」子视图与
 * /search/ 独立路由页共用):关键字(FTS5 三源:标题/题面/题解笔记)+ 标签多选
 * (AND)+ 平台过滤(`/api/search`);标签面板数据来自 `/api/tags`。
 * 结果行带命中来源 chips(标题/题面/题解),点击经桥 nav:openProblem 打开
 * 题面工具窗口;分页走加载更多(offset 累加)。
 * WP37:状态徽标列(🔒 会员 / ✓ AC / ○ 已尝试 / 空 未做)+ 状态下拉——本地过滤
 * 已加载项(端点不加参数;后页未加载项不参与,筛选激活时 UI 注明)。
 * WP49:文案中文化。
 */
import { computed, onMounted, ref } from 'vue'
import { TOPIC_NAV_OPEN_PROBLEM, cqBridge } from '../../bridge/cq-bridge'
import {
  ApiError,
  fetchPlatforms,
  fetchSearch,
  fetchTags,
  type PlatformSummary,
  type ProblemTag,
  type SearchItem,
  type SearchMatchSource,
  type TagSummary,
} from '../../lib/api'
import { difficultyClass } from '../../lib/difficulty'
import {
  filterByStatus,
  statusIconOf,
  type ProblemStatusFields,
  type ProblemStatusFilter,
} from '../../lib/problemStatus'

const PAGE_SIZE = 50

/** 命中来源 → 展示文案。 */
const SOURCE_LABELS: Record<SearchMatchSource, string> = {
  title: '标题',
  content: '题面',
  note: '题解',
}

const bridge = cqBridge()

const platforms = ref<PlatformSummary[]>([])
const platform = ref('')
const keyword = ref('')
const selectedTags = ref<string[]>([])
const availableTags = ref<TagSummary[]>([])
/** WP37:状态下拉(本地过滤;'' = 全部)。 */
const statusFilter = ref<ProblemStatusFilter>('')
const items = ref<(SearchItem & ProblemStatusFields)[]>([])
const total = ref(0)
const offset = ref(0)
const hasMore = ref(false)
const loading = ref(false)
const error = ref('')
/** 是否已执行过至少一次检索(区分首屏提示与结果空态)。 */
const searched = ref(false)

const hasFilters = computed(
  () => keyword.value.trim().length > 0 || selectedTags.value.length > 0 || platform.value !== '',
)

/** WP37:本地过滤后的可见行(仅作用于已加载项;不发请求、不影响分页游标)。 */
const visibleItems = computed(() => filterByStatus(items.value, statusFilter.value))

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
    const result = await fetchSearch({
      keyword: keyword.value.trim() || undefined,
      tags: selectedTags.value,
      platform: platform.value || undefined,
      limit: PAGE_SIZE,
      offset: offset.value,
    })
    items.value = append ? [...items.value, ...result.items] : result.items
    total.value = result.total
    hasMore.value = result.hasMore
    offset.value += result.items.length
    searched.value = true
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    loading.value = false
  }
}

function reload(): void {
  items.value = []
  offset.value = 0
  total.value = 0
  hasMore.value = false
  void loadPage(false)
}

function toggleTag(name: string): void {
  const index = selectedTags.value.indexOf(name)
  if (index >= 0) {
    selectedTags.value.splice(index, 1)
  } else {
    selectedTags.value.push(name)
  }
  reload()
}

function resetFilters(): void {
  keyword.value = ''
  selectedTags.value = []
  platform.value = ''
  statusFilter.value = ''
  reload()
}

function openProblem(item: SearchItem): void {
  bridge.send(TOPIC_NAV_OPEN_PROBLEM, {
    platformId: item.platform,
    remoteId: item.remoteId,
  })
}

/** 1.6.0:标签展示名——中文优先,英文兜底(tag 行经 CN 数据 enrich 后带中文名)。 */
function tagLabel(tag: ProblemTag): string {
  return tag.nameCn || tag.name
}

onMounted(async () => {
  // 浏览模式首屏(全部近期题目)+ 标签面板;平台下拉失败不阻塞检索
  reload()
  try {
    platforms.value = await fetchPlatforms()
  } catch (cause) {
    error.value = describeError(cause)
  }
  try {
    availableTags.value = await fetchTags()
  } catch {
    // 标签面板失败不阻塞关键字检索(reload 的错误态已覆盖关键路径)
  }
})
</script>

<template>
  <div class="search-panel">
    <form class="filters" @submit.prevent="reload">
      <label class="filters__field">
        <span>平台</span>
        <select v-model="platform" @change="reload">
          <option value="">全部平台</option>
          <option v-for="entry in platforms" :key="entry.id" :value="entry.id">
            {{ entry.displayName }}
          </option>
        </select>
      </label>
      <label class="filters__field filters__field--grow">
        <span>关键字</span>
        <input
          v-model="keyword"
          type="search"
          placeholder="检索标题、题面与题解笔记…"
        />
      </label>
      <!-- WP37:状态筛选为本地过滤(仅作用于已加载项),不触发请求 -->
      <label class="filters__field">
        <span>状态</span>
        <select v-model="statusFilter">
          <option value="">全部状态</option>
          <option value="AC">已解决</option>
          <option value="TRIED">已尝试</option>
          <option value="UNSOLVED">未解决</option>
        </select>
      </label>
      <div class="filters__actions">
        <button type="submit" :disabled="loading">搜索</button>
        <button type="button" :disabled="loading" @click="resetFilters">重置</button>
      </div>
    </form>

    <p v-if="statusFilter !== ''" class="filter-note">
      状态筛选仅作用于已加载条目——加载更多可扩大范围。
    </p>

    <fieldset v-if="availableTags.length > 0" class="tags" aria-label="标签筛选">
      <legend class="tags__legend">标签(多选,全部满足)</legend>
      <button
        v-for="tag in availableTags"
        :key="tag.name"
        type="button"
        class="tags__chip"
        :class="{ 'tags__chip--on': selectedTags.includes(tag.name) }"
        :aria-pressed="selectedTags.includes(tag.name)"
        :title="tag.nameCn ? tag.name : undefined"
        @click="toggleTag(tag.name)"
      >
        {{ tag.nameCn || tag.name }} <span class="tags__count">{{ tag.count }}</span>
      </button>
    </fieldset>

    <p v-if="error" class="state state--error" role="alert">{{ error }}</p>
    <p v-else-if="loading && items.length === 0" class="state">正在检索…</p>
    <p v-else-if="searched && !loading && visibleItems.length === 0 && statusFilter !== ''" class="state">
      没有已加载条目匹配状态筛选,请加载更多或清除筛选。
    </p>
    <p v-else-if="searched && !loading && items.length === 0 && hasFilters" class="state">
      没有匹配结果,请调整关键字、标签或平台后重试。
    </p>
    <p v-else-if="searched && !loading && items.length === 0" class="state">
      本地库还没有已缓存的题目,先到题库页浏览以缓存题面。
    </p>

    <ul v-if="visibleItems.length > 0" class="results" aria-label="搜索结果">
      <li v-for="item in visibleItems" :key="`${item.platform}:${item.remoteId}`">
        <!-- 修复反馈:单行行布局(状态徽标 | 平台+题号 | 标题 | 命中来源/标签/难度),各行等高、标题严格对齐 -->
        <button type="button" class="results__row" @click="openProblem(item)">
          <span
            class="status-icon"
            :class="`status-icon--${statusIconOf(item).kind}`"
            :title="statusIconOf(item).title"
            :aria-label="statusIconOf(item).title || undefined"
          >
            <svg
              v-if="statusIconOf(item).kind === 'locked'"
              class="status-icon__lock"
              viewBox="0 0 16 16"
              width="12"
              height="12"
              aria-hidden="true"
            >
              <path
                d="M4.5 7V5a3.5 3.5 0 1 1 7 0v2h1v7h-9V7h1zm2 0h3V5a1.5 1.5 0 1 0-3 0v2z"
                fill="currentColor"
              />
            </svg>
            <template v-else-if="statusIconOf(item).kind === 'ac'">✓</template>
          </span>
          <span class="results__ident">
            <span class="platform-badge">{{ item.platform }}</span>
            <span class="results__id">{{ item.remoteId }}</span>
          </span>
          <span class="results__title">
            {{ item.title }}
            <!-- 1.6.0:副标题(标题另一语言形态;缺失侧不渲染) -->
            <span v-if="item.titleSecondary" class="results__title-alt">{{ item.titleSecondary }}</span>
          </span>
          <span class="results__meta">
            <span class="results__sources">
              <span
                v-for="source in item.matchedSources"
                :key="source"
                class="source-chip"
                :class="`source-chip--${source}`"
              >
                {{ SOURCE_LABELS[source] }}
              </span>
            </span>
            <span v-if="item.tags.length > 0" class="results__tags">{{ item.tags.map(tagLabel).join(' · ') }}</span>
            <span class="diff-badge" :class="difficultyClass(item.difficulty)">{{ item.difficulty }}</span>
          </span>
        </button>
      </li>
    </ul>

    <div v-if="items.length > 0" class="pager">
      <span class="pager__count">
        已加载 {{ statusFilter === '' ? items.length : `${visibleItems.length}/${items.length}` }} / 共 {{ total }} 条
      </span>
      <button
        v-if="hasMore"
        type="button"
        class="pager__more"
        :disabled="loading"
        @click="loadPage(true)"
      >
        {{ loading ? '正在加载…' : '加载更多' }}
      </button>
      <span v-else class="pager__done">已到底部</span>
    </div>
  </div>
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
  min-width: 200px;
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

.tags {
  margin: 10px 0 0;
  padding: 8px 10px;
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel);
}

.tags__legend {
  font-size: 11px;
  color: var(--cq-muted);
  padding: 0 4px;
}

.tags__chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin: 2px 4px 2px 0;
  padding: 2px 10px;
  border: 1px solid var(--cq-border);
  border-radius: 999px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-size: 12px;
  cursor: pointer;
}

.tags__chip:hover {
  border-color: var(--cq-accent);
}

.tags__chip--on {
  background: var(--cq-accent);
  border-color: var(--cq-accent);
  color: var(--cq-accent-fg);
}

.tags__chip--on .tags__count {
  color: var(--cq-accent-fg);
  opacity: 0.8;
}

.tags__count {
  font-size: 10px;
  color: var(--cq-muted);
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

.results {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.results__row {
  display: flex;
  justify-content: space-between;
  align-items: center;
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

.results__row:hover {
  border-color: var(--cq-accent);
  background: var(--cq-panel-raised);
}

/* WP37:状态徽标列——修复反馈后改为实心/空心圆徽标,一眼可辨:
   AC = 绿底白✓(实心圆) / TRIED = 主题色空心圆 / 🔒 会员 / 空 未做 */
.status-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 16px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  line-height: 1;
  user-select: none;
}

.status-icon--ac {
  background: var(--cq-diff-easy);
  color: #ffffff;
  font-size: 11px;
  font-weight: 800;
}

.status-icon--tried {
  border: 2px solid var(--cq-accent);
}

/* 1.2.1:未解决 = 灰色细空心圆(与 problems 页同步) */
.status-icon--unsolved {
  border: 1px solid var(--cq-border);
}

.status-icon--locked {
  border-radius: 0;
  color: var(--cq-muted);
}

.status-icon__lock {
  color: var(--cq-muted);
}

/* WP37:本地过滤范围说明(筛选激活时) */
.filter-note {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--cq-muted);
}

/* 修复反馈:平台+题号/标题/右侧元信息全部单行化,各行等高、标题基线对齐 */
.results__ident {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 0 1 200px;
  min-width: 110px;
  font-size: 11px;
  color: var(--cq-muted);
  white-space: nowrap;
  overflow: hidden;
}

.results__title {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 1.6.0:副标题(标题另一语言形态) */
.results__title-alt {
  margin-left: 8px;
  font-size: 11px;
  color: var(--cq-muted);
}

.results__id {
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
}

.platform-badge {
  display: inline-block;
  padding: 0 6px;
  border: 1px solid var(--cq-border);
  border-radius: 4px;
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--cq-muted);
}

.results__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 1 auto;
  min-width: 0;
}

.results__sources {
  display: flex;
  gap: 4px;
}

.source-chip {
  display: inline-block;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 600;
  border: 1px solid transparent;
}

.source-chip--title {
  color: var(--cq-accent);
  border-color: var(--cq-accent);
}

.source-chip--content {
  color: var(--cq-muted);
  border-color: var(--cq-border);
}

.source-chip--note {
  color: var(--cq-diff-easy, #2ea043);
  border-color: var(--cq-diff-easy, #2ea043);
}

.results__tags {
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
