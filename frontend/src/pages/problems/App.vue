<script setup lang="ts">
/**
 * 题目列表页(WP20 真实数据):平台下拉(/api/platforms)+ 关键字 + 难度筛选 +
 * 分页(加载更多);点击行经桥 nav:openProblem 打开题面工具窗口。
 * WP37:状态图标列(🔒 会员 / ✓ AC / ● 已尝试 / 空 未做)+ 状态下拉——本地过滤
 * 已加载项(端点不加参数;后页未加载项不参与,筛选激活时 UI 注明)。
 * WP38:头部账号条(原版 AccountActionGroup 对齐)——每平台徽标(/api/auth/status
 * 快照):已登录高亮,点击登出;未登录灰,点击触发 auth:login 登录对话框。
 * WP42:公司下拉(仅 /api/companies 有数据的 LC 系平台渲染)+ 期间下拉——选中后
 * 列表改走公司题单查询(company/period 参数);keyword 在公司模式下被后端忽略
 * (旧实现事实,激活时 UI 注明)。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import {
  TOPIC_AUTH_LOGIN,
  TOPIC_AUTH_LOGIN_RESULT,
  TOPIC_AUTH_LOGOUT,
  TOPIC_AUTH_LOGOUT_RESULT,
  TOPIC_NAV_OPEN_PROBLEM,
  cqBridge,
} from '../../bridge/cq-bridge'
import {
  ApiError,
  fetchAuthStatus,
  fetchCompanies,
  fetchPlatforms,
  fetchProblems,
  type AuthStatusItem,
  type CompanySummary,
  type PlatformSummary,
  type ProblemListItem,
} from '../../lib/api'
import { difficultyClass } from '../../lib/difficulty'
import {
  filterByStatus,
  statusIconOf,
  type ProblemStatusFields,
  type ProblemStatusFilter,
} from '../../lib/problemStatus'

const PAGE_SIZE = 50
const DIFFICULTY_OPTIONS = ['', 'easy', 'medium', 'hard'] as const
/** WP42:面试期间(旧 InterviewParameterAction 的 slug 逐字事实;默认 30 天同旧 UI)。 */
const DEFAULT_PERIOD = 'thirty-days'
const PERIOD_OPTIONS = [
  { value: 'thirty-days', label: 'Last 30 days' },
  { value: 'three-months', label: 'Last 3 months' },
  { value: 'six-months', label: 'Last 6 months' },
  { value: 'more-than-six-months', label: 'More than 6 months' },
  { value: 'all', label: 'All time' },
] as const

const bridge = cqBridge()

const platforms = ref<PlatformSummary[]>([])
const platform = ref('')
const keyword = ref('')
const difficulty = ref('')
/** WP37:状态下拉(本地过滤;'' = 全部)。 */
const statusFilter = ref<ProblemStatusFilter>('')
/** WP42:公司下拉('' = 全部题库);仅 CompanyQueryCapable 平台有选项。 */
const companies = ref<CompanySummary[]>([])
const company = ref('')
/** WP42:面试期间(仅公司模式生效)。 */
const period = ref<string>(DEFAULT_PERIOD)
const items = ref<(ProblemListItem & ProblemStatusFields)[]>([])
const offset = ref(0)
const hasMore = ref(false)
const loading = ref(false)
const error = ref('')
/** 后端错误码;auth-expired 时错误态附登录入口(1.0.x,如 leetcode.com 列表 401)。 */
const errorCode = ref('')

/** WP37:本地过滤后的可见行(仅作用于已加载项;不发请求、不影响分页游标)。 */
const visibleItems = computed(() => filterByStatus(items.value, statusFilter.value))

/** 登录请求进行中(结果回推前置灰按钮,防重复弹窗)。 */
const loginPending = ref(false)

// ---------------------------------------------------------------------
// WP38:账号条(每平台登录快照 + 登录/登出入口)
// ---------------------------------------------------------------------

const authItems = ref<AuthStatusItem[]>([])

/** 账号条进行中的平台 id(登录或登出;期间全部徽标置灰防重复触发)。 */
const authPending = ref('')

function refreshAuthStatus(): void {
  fetchAuthStatus()
    .then((items) => {
      authItems.value = items
    })
    .catch(() => {
      // 状态条非关键路径:拉取失败保持现状(如端点未装配/启动竞态)
    })
}

function displayNameOf(platformId: string): string {
  return platforms.value.find((entry) => entry.id === platformId)?.displayName ?? platformId
}

function badgeTitle(item: AuthStatusItem): string {
  if (item.loggedIn) {
    const who = item.userId ? ` as ${item.userId}` : ''
    return `${displayNameOf(item.platform)} — logged in${who}, click to log out`
  }
  return `${displayNameOf(item.platform)} — not logged in, click to log in`
}

function handleBadgeClick(item: AuthStatusItem): void {
  if (authPending.value) return
  authPending.value = item.platform
  bridge.send(item.loggedIn ? TOPIC_AUTH_LOGOUT : TOPIC_AUTH_LOGIN, { platform: item.platform })
}

/** 登出结果:回执平台清进行中标记并刷新快照。 */
function handleLogoutResult(payload: unknown): void {
  const result = payload as { platform?: string }
  if (!result || result.platform !== authPending.value) return
  authPending.value = ''
  refreshAuthStatus()
}

/** 浏览链路登录入口(1.0.x):Kotlin 弹平台登录对话框,结果经 auth:login-result 回推。 */
function requestLogin(): void {
  if (loginPending.value || !platform.value) return
  loginPending.value = true
  bridge.send(TOPIC_AUTH_LOGIN, { platform: platform.value })
}

/** 登录结果:徽标反馈(任意平台)+ 当前平台重载列表(登录态已注入 cookie)。 */
function handleLoginResult(payload: unknown): void {
  const result = payload as { ok?: boolean; platform?: string }
  if (!result) return
  if (result.platform === authPending.value) {
    authPending.value = ''
  }
  refreshAuthStatus()
  if (result.platform !== platform.value) return
  loginPending.value = false
  if (result.ok) {
    reload()
  }
}

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
  errorCode.value = ''
  try {
    const result = await fetchProblems({
      platform: platform.value,
      keyword: keyword.value,
      difficulty: difficulty.value || undefined,
      company: company.value || undefined,
      period: company.value !== '' ? period.value : undefined,
      limit: PAGE_SIZE,
      offset: offset.value,
    })
    items.value = append ? [...items.value, ...result.items] : result.items
    hasMore.value = result.hasMore
    offset.value += result.items.length
  } catch (cause) {
    error.value = describeError(cause)
    errorCode.value = cause instanceof ApiError ? cause.code : ''
  } finally {
    loading.value = false
  }
}

/**
 * WP42:按平台拉公司标签(能力探测式渲染)——/api/companies 404(平台无该能力)
 * 或失败时清空并隐藏下拉,不报错(公司下拉是附属能力,非关键路径)。
 */
async function refreshCompanies(): Promise<void> {
  if (!platform.value) {
    companies.value = []
    return
  }
  try {
    companies.value = await fetchCompanies(platform.value)
  } catch {
    companies.value = []
  }
}

/** 平台切换:公司选择与期间归位(公司集合随平台变),再刷新清单与列表。 */
async function handlePlatformChange(): Promise<void> {
  company.value = ''
  period.value = DEFAULT_PERIOD
  await refreshCompanies()
  reload()
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
  statusFilter.value = ''
  company.value = ''
  reload()
}

function openProblem(item: ProblemListItem): void {
  bridge.send(TOPIC_NAV_OPEN_PROBLEM, {
    platformId: platform.value,
    remoteId: item.remoteId,
  })
}

let detachLoginResult: (() => void) | null = null
let detachLogoutResult: (() => void) | null = null

onMounted(async () => {
  detachLoginResult = bridge.on(TOPIC_AUTH_LOGIN_RESULT, handleLoginResult)
  detachLogoutResult = bridge.on(TOPIC_AUTH_LOGOUT_RESULT, handleLogoutResult)
  refreshAuthStatus()
  try {
    platforms.value = await fetchPlatforms()
    platform.value = platforms.value[0]?.id ?? ''
    await refreshCompanies()
    reload()
  } catch (cause) {
    error.value = describeError(cause)
  }
})

onUnmounted(() => {
  detachLoginResult?.()
  detachLogoutResult?.()
  detachLoginResult = null
  detachLogoutResult = null
})
</script>

<template>
  <AppShell title="Problems" subtitle="Browse and open problems by platform, keyword and difficulty.">
    <!-- WP38:账号条(原版 AccountActionGroup 对齐)——已登录高亮点击登出,未登录灰点击登录 -->
    <div v-if="authItems.length > 0" class="accounts" aria-label="account status">
      <button
        v-for="item in authItems"
        :key="item.platform"
        type="button"
        class="accounts__badge"
        :class="{ 'accounts__badge--on': item.loggedIn }"
        :disabled="authPending !== ''"
        :title="badgeTitle(item)"
        @click="handleBadgeClick(item)"
      >
        <span class="accounts__platform">{{ displayNameOf(item.platform) }}</span>
        <span v-if="item.userId" class="accounts__user">{{ item.userId }}</span>
        <span v-if="authPending === item.platform" class="accounts__pending">…</span>
      </button>
    </div>

    <form class="filters" @submit.prevent="reload">
      <label class="filters__field">
        <span>Platform</span>
        <select v-model="platform" @change="handlePlatformChange">
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
      <!-- WP37:状态筛选为本地过滤(仅作用于已加载项),不触发请求 -->
      <label class="filters__field">
        <span>Status</span>
        <select v-model="statusFilter">
          <option value="">All statuses</option>
          <option value="AC">Solved</option>
          <option value="TRIED">Tried</option>
          <option value="UNSOLVED">Unsolved</option>
        </select>
      </label>
      <!-- WP42:公司下拉(仅 CompanyQueryCapable 平台渲染;选中后走公司题单查询) -->
      <label v-if="companies.length > 0" class="filters__field">
        <span>Company</span>
        <select v-model="company" @change="reload">
          <option value="">All companies</option>
          <option v-for="entry in companies" :key="entry.slug" :value="entry.slug">
            {{ entry.name }} ({{ entry.questionCount }})
          </option>
        </select>
      </label>
      <!-- WP42:面试期间(仅公司模式出现;默认 30 天,旧 UI 默认值) -->
      <label v-if="companies.length > 0 && company !== ''" class="filters__field">
        <span>Period</span>
        <select v-model="period" @change="reload">
          <option v-for="option in PERIOD_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>
      <div class="filters__actions">
        <button type="submit" :disabled="loading || !platform">Search</button>
        <button type="button" :disabled="loading" @click="resetFilters">Reset</button>
      </div>
    </form>

    <p v-if="statusFilter !== ''" class="filter-note">
      Status filter applies to loaded items only — load more to expand the scope.
    </p>

    <p v-if="company !== '' && keyword.trim() !== ''" class="filter-note">
      Keyword is ignored in company list mode (platform limitation).
    </p>

    <p v-if="error" class="state state--error" role="alert">
      {{ error }}
      <button
        v-if="errorCode === 'auth-expired'"
        type="button"
        class="state__login"
        :disabled="loginPending"
        @click="requestLogin"
      >
        {{ loginPending ? 'Logging in…' : 'Login' }}
      </button>
    </p>
    <p v-else-if="loading && items.length === 0" class="state">Loading problems…</p>
    <p v-else-if="!loading && visibleItems.length === 0 && statusFilter !== ''" class="state">
      No loaded items match the status filter. Load more or clear the filter.
    </p>
    <p v-else-if="!loading && items.length === 0" class="state">No problems matched. Adjust the filters and retry.</p>

    <ul v-if="visibleItems.length > 0" class="problems" aria-label="problem list">
      <li v-for="item in visibleItems" :key="item.remoteId">
        <button type="button" class="problems__row" @click="openProblem(item)">
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
            <template v-else-if="statusIconOf(item).kind === 'tried'">●</template>
          </span>
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
      <span class="pager__count">
        {{ statusFilter === '' ? items.length : `${visibleItems.length} of ${items.length}` }} loaded
      </span>
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
/* WP38:账号条(主题变量配色;已登录=平台名高亮,未登录=灰) */
.accounts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}

.accounts__badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border: 1px solid var(--cq-border);
  border-radius: 999px;
  background: var(--cq-panel);
  color: var(--cq-muted);
  font-size: 12px;
  cursor: pointer;
}

.accounts__badge--on {
  border-color: var(--cq-accent);
  background: var(--cq-panel-raised);
  color: var(--cq-accent);
}

.accounts__badge--on .accounts__platform {
  font-weight: 600;
}

.accounts__badge:disabled {
  opacity: 0.6;
  cursor: default;
}

.accounts__user {
  font-weight: 600;
}

.accounts__pending {
  color: var(--cq-muted);
}

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

/* 1.0.x:浏览链路登录入口(auth-expired 错误态) */
.state__login {
  margin-left: 10px;
  padding: 2px 10px;
  border: 1px solid var(--cq-danger);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-danger);
  cursor: pointer;
}

.state__login:disabled {
  opacity: 0.55;
  cursor: default;
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

.problems__row:hover {
  border-color: var(--cq-accent);
  background: var(--cq-panel-raised);
}

/* WP37:状态图标列(🔒 会员 / ✓ AC / ● 已尝试 / 空 未做;主题变量配色) */
.status-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 700;
  line-height: 1;
  user-select: none;
}

.status-icon--ac {
  color: var(--cq-diff-easy);
}

.status-icon--tried {
  color: var(--cq-accent);
  font-size: 10px;
}

.status-icon--locked,
.status-icon__lock {
  color: var(--cq-muted);
}

/* WP37:本地过滤范围说明(筛选激活时) */
.filter-note {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--cq-muted);
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
