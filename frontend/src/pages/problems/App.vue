<script setup lang="ts">
/**
 * 题库页(WP20 真实数据):平台下拉(/api/platforms)+ 关键字 + 难度筛选 +
 * 分页(加载更多);点击行经桥 nav:openProblem 打开题面工具窗口。
 * WP37:状态徽标列(🔒 会员 / ✓ AC / ○ 已尝试 / 空 未做)+ 状态下拉——本地过滤
 * 已加载项(端点不加参数;后页未加载项不参与,筛选激活时 UI 注明)。
 * WP42:公司下拉(仅 /api/companies 有数据的 LC 系平台渲染)+ 期间下拉——选中后
 * 列表改走公司题单查询(company/period 参数);keyword 在公司模式下被后端忽略
 * (旧实现事实,激活时 UI 注明)。
 * WP57:题单下拉(力扣系平台;/api/favorites 学习计划+我的题单两组 optgroup;
 * 与公司下拉互斥——选题单清公司+期间,选公司清题单)——选中后列表改走
 * favorite 题单查询(favorite 优先于 company);keyword 同被忽略(UI 注明)。
 * WP49:①账号区重设计——全部平台常驻,已登录=平台名+用户名+显式「退出」按钮,
 * 未登录=「登录」按钮(替代点击整行切换);②平台下拉门控——仅保留已登录平台,
 * 全未登录回落力扣中国并提示;③加载时序——先 GET status 瞬时渲染,再异步 POST
 * refresh 静默恢复重启前登录态;④「平台题库｜本地搜索」子视图切换(本地搜索
 * 复用 SearchPanel,状态本地保持);⑤文案中文化。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import SearchPanel from '../search/SearchPanel.vue'
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
  fetchFavorites,
  fetchPlatforms,
  fetchProblems,
  refreshAuthStatus as requestAuthRefresh,
  type AuthStatusItem,
  type CompanySummary,
  type FavoritesResult,
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
import { FALLBACK_PLATFORM_ID, filterPlatformsByAuth, pickDefaultPlatform } from './platformGate'

const PAGE_SIZE = 50
const DIFFICULTY_OPTIONS = ['', 'easy', 'medium', 'hard'] as const
/** WP42:面试期间(旧 InterviewParameterAction 的 slug 逐字事实;默认 30 天同旧 UI)。 */
const DEFAULT_PERIOD = 'thirty-days'
const PERIOD_OPTIONS = [
  { value: 'thirty-days', label: '近 30 天' },
  { value: 'three-months', label: '近 3 个月' },
  { value: 'six-months', label: '近 6 个月' },
  { value: 'more-than-six-months', label: '半年以上' },
  { value: 'all', label: '全部时间' },
] as const

const DIFFICULTY_LABELS: Record<string, string> = {
  '': '全部难度',
  easy: '简单',
  medium: '中等',
  hard: '困难',
}

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
/** WP57:题单下拉('' = 全部题库);null = 平台无该能力(/api/favorites 404/失败),不渲染。 */
const favorites = ref<FavoritesResult | null>(null)
/** WP57:选中题单 slug(与公司下拉互斥)。 */
const favorite = ref('')
const items = ref<(ProblemListItem & ProblemStatusFields)[]>([])
const offset = ref(0)
const hasMore = ref(false)
const loading = ref(false)
const error = ref('')
/** 后端错误码;auth-expired 时错误态附登录入口(1.0.x,如 leetcode.com 列表 401)。 */
const errorCode = ref('')

/** WP37:本地过滤后的可见行(仅作用于已加载项;不发请求、不影响分页游标)。 */
const visibleItems = computed(() => filterByStatus(items.value, statusFilter.value))

/** 浏览链路登录请求进行中(结果回推前置灰按钮,防重复弹窗)。 */
const loginPending = ref(false)

// ---------------------------------------------------------------------
// WP49:账号区(全部平台常驻 + 显式登录/退出按钮)与平台门控
// ---------------------------------------------------------------------

const authItems = ref<AuthStatusItem[]>([])

/** GET /api/auth/status 是否成功过——失败/未装配时不门控(展示全部平台)。 */
const authKnown = ref(false)

/** 账号区进行中的平台 id(登录或登出;期间全部按钮置灰防重复触发)。 */
const authPending = ref('')

/** 账号区行:platforms(全量,常驻)⋈ authItems(登录态;缺失按未登录)。 */
const accountRows = computed(() =>
  platforms.value.map((entry) => {
    const auth = authItems.value.find((item) => item.platform === entry.id)
    return {
      id: entry.id,
      displayName: entry.displayName,
      loggedIn: auth?.loggedIn ?? false,
      userId: auth?.userId ?? null,
    }
  }),
)

/** 门控后的已登录平台(纯函数,可单测)。 */
const filteredPlatforms = computed(() => filterPlatformsByAuth(platforms.value, authItems.value))

/**
 * 平台下拉可见项:已登录平台;全未登录回落力扣中国(FALLBACK_PLATFORM_ID);
 * 登录快照不可用(端点失败/未装配)时不门控,展示全部平台。
 */
const visiblePlatforms = computed(() => {
  if (!authKnown.value) return platforms.value
  if (filteredPlatforms.value.length > 0) return filteredPlatforms.value
  return platforms.value.filter((entry) => entry.id === FALLBACK_PLATFORM_ID)
})

/** 全未登录回落提示条。 */
const showFallbackHint = computed(
  () => authKnown.value && filteredPlatforms.value.length === 0,
)

/** 显式登录/退出按钮(WP49 替代点击整行切换):发既有 TOPIC_AUTH_LOGIN/LOGOUT。 */
function handleAuthAction(row: { id: string; loggedIn: boolean }): void {
  if (authPending.value) return
  authPending.value = row.id
  bridge.send(row.loggedIn ? TOPIC_AUTH_LOGOUT : TOPIC_AUTH_LOGIN, { platform: row.id })
}

/**
 * 登录态变化后重校验平台选择:当前平台仍在可见清单则不动;否则切到
 * 门控默认项(已登录第一项/力扣中国兜底)并刷新公司与列表。
 */
function revalidatePlatformSelection(): void {
  const visible = visiblePlatforms.value
  if (visible.length === 0) {
    if (platform.value === '') return
    platform.value = ''
    company.value = ''
    period.value = DEFAULT_PERIOD
    companies.value = []
    favorites.value = null
    favorite.value = ''
    reload()
    return
  }
  if (visible.some((entry) => entry.id === platform.value)) return
  platform.value = pickDefaultPlatform(visible)
  void handlePlatformChange()
}

/** 重拉平台清单 + 登录快照(登录/登出结果事件后)。 */
async function reloadAuthAndPlatforms(): Promise<void> {
  const [platformsResult, authResult] = await Promise.allSettled([
    fetchPlatforms(),
    fetchAuthStatus(),
  ])
  if (platformsResult.status === 'fulfilled') platforms.value = platformsResult.value
  if (authResult.status === 'fulfilled') {
    authItems.value = authResult.value
    authKnown.value = true
  }
  revalidatePlatformSelection()
}

/** WP48:静默恢复重启前登录态(POST /api/auth/refresh,不弹窗),成功后刷新门控。 */
async function silentRestore(): Promise<void> {
  try {
    authItems.value = await requestAuthRefresh()
    authKnown.value = true
    revalidatePlatformSelection()
  } catch {
    // 静默恢复失败(网络/端点未装配)保持 GET status 快照,不打扰用户
  }
}

/** 登出结果:回执平台清进行中标记并重拉两接口。 */
function handleLogoutResult(payload: unknown): void {
  const result = payload as { platform?: string }
  if (!result || result.platform !== authPending.value) return
  authPending.value = ''
  void reloadAuthAndPlatforms()
}

/** 浏览链路登录入口(1.0.x):Kotlin 弹平台登录对话框,结果经 auth:login-result 回推。 */
function requestLogin(): void {
  if (loginPending.value || !platform.value) return
  loginPending.value = true
  bridge.send(TOPIC_AUTH_LOGIN, { platform: platform.value })
}

/** 登录结果:重拉两接口 + 当前平台重载列表(登录态已注入 cookie)。 */
function handleLoginResult(payload: unknown): void {
  const result = payload as { ok?: boolean; platform?: string }
  if (!result) return
  if (result.platform === authPending.value) {
    authPending.value = ''
  }
  void reloadAuthAndPlatforms()
  if (result.platform !== platform.value) return
  loginPending.value = false
  if (result.ok) {
    reload()
  }
}

// ---------------------------------------------------------------------
// WP49:子视图切换(平台题库｜本地搜索;状态本地保持)
// ---------------------------------------------------------------------

type Subview = 'platform' | 'search'
const subview = ref<Subview>('platform')
/** 本地搜索子视图惰性挂载(首次切换才建),之后 v-show 保持组件状态。 */
const searchMounted = ref(false)

function switchSubview(next: Subview): void {
  subview.value = next
  if (next === 'search') searchMounted.value = true
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
      favorite: favorite.value || undefined,
      company: favorite.value === '' ? company.value || undefined : undefined,
      period: favorite.value === '' && company.value !== '' ? period.value : undefined,
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

/**
 * WP57:按平台拉题单清单(能力探测式渲染)——/api/favorites 404(力扣系以外)
 * 或失败时置 null 隐藏下拉,静默降级不打扰(同公司下拉,附属能力非关键路径);
 * 我的题单组后端已降级为空数组(未登录/拉取失败),前端空组不渲染 optgroup。
 */
async function refreshFavorites(): Promise<void> {
  if (!platform.value) {
    favorites.value = null
    return
  }
  try {
    favorites.value = await fetchFavorites(platform.value)
  } catch {
    favorites.value = null
  }
}

/** 平台切换:公司/题单选择与期间归位(两组集合随平台变),再刷新清单与列表。 */
async function handlePlatformChange(): Promise<void> {
  company.value = ''
  period.value = DEFAULT_PERIOD
  favorite.value = ''
  await Promise.all([refreshCompanies(), refreshFavorites()])
  reload()
}

/** WP57:选题单 → 清公司与期间(互斥),favorite 优先于 company。 */
function handleFavoriteChange(): void {
  company.value = ''
  period.value = DEFAULT_PERIOD
  reload()
}

/** WP57:选公司 → 清题单(互斥双向)。 */
function handleCompanyChange(): void {
  favorite.value = ''
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
  favorite.value = ''
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

  // WP49 加载时序:先 GET status + 平台清单(瞬时渲染账号区与下拉)——
  // status 失败不门控(authKnown=false);平台清单失败进错误态
  const [platformsResult, authResult] = await Promise.allSettled([
    fetchPlatforms(),
    fetchAuthStatus(),
  ])
  if (platformsResult.status === 'fulfilled') platforms.value = platformsResult.value
  else error.value = describeError(platformsResult.reason)
  if (authResult.status === 'fulfilled') {
    authItems.value = authResult.value
    authKnown.value = true
  }
  if (visiblePlatforms.value.length > 0) {
    platform.value = pickDefaultPlatform(visiblePlatforms.value)
    await Promise.all([refreshCompanies(), refreshFavorites()])
    reload()
  }

  // 异步静默恢复(POST /api/auth/refresh):不阻塞首屏,结果刷新账号区与门控
  void silentRestore()
})

onUnmounted(() => {
  detachLoginResult?.()
  detachLogoutResult?.()
  detachLoginResult = null
  detachLogoutResult = null
})
</script>

<template>
  <AppShell title="题库" subtitle="按平台浏览与筛选题目;平台需登录后可选,本地数据可在「本地搜索」跨平台检索。">
    <!-- WP49:账号区——全部平台常驻;已登录=平台名+用户名+「退出」,未登录=「登录」 -->
    <div v-if="accountRows.length > 0" class="accounts" aria-label="账号状态">
      <div
        v-for="row in accountRows"
        :key="row.id"
        class="accounts__item"
        :class="{ 'accounts__item--on': row.loggedIn }"
      >
        <span class="accounts__platform">{{ row.displayName }}</span>
        <span v-if="row.loggedIn && row.userId" class="accounts__user">{{ row.userId }}</span>
        <button
          type="button"
          class="accounts__action"
          :disabled="authPending !== ''"
          @click="handleAuthAction(row)"
        >
          {{ authPending === row.id ? '处理中…' : row.loggedIn ? '退出' : '登录' }}
        </button>
      </div>
    </div>

    <!-- WP49:子视图切换(平台题库｜本地搜索,互斥显示、状态本地保持) -->
    <div class="subviews" role="tablist" aria-label="子视图切换">
      <button
        type="button"
        class="subviews__tab"
        :class="{ 'subviews__tab--on': subview === 'platform' }"
        role="tab"
        :aria-selected="subview === 'platform'"
        @click="switchSubview('platform')"
      >
        平台题库
      </button>
      <button
        type="button"
        class="subviews__tab"
        :class="{ 'subviews__tab--on': subview === 'search' }"
        role="tab"
        :aria-selected="subview === 'search'"
        @click="switchSubview('search')"
      >
        本地搜索
      </button>
    </div>

    <!-- 平台题库子视图 -->
    <div v-show="subview === 'platform'" class="platform-view">
      <!-- WP49:全未登录回落提示条 -->
      <p v-if="showFallbackHint" class="fallback-hint">
        当前无已登录平台,默认展示力扣中国;在上方账号区登录后即可切换其他平台。
      </p>

      <form class="filters" @submit.prevent="reload">
        <label class="filters__field">
          <span>平台</span>
          <select v-model="platform" @change="handlePlatformChange">
            <option v-for="entry in visiblePlatforms" :key="entry.id" :value="entry.id">
              {{ entry.displayName }}
            </option>
          </select>
        </label>
        <label class="filters__field filters__field--grow">
          <span>关键字</span>
          <input
            v-model="keyword"
            type="search"
            placeholder="按题号或标题搜索…"
          />
        </label>
        <label class="filters__field">
          <span>难度</span>
          <select v-model="difficulty" @change="reload">
            <option v-for="option in DIFFICULTY_OPTIONS" :key="option" :value="option">
              {{ DIFFICULTY_LABELS[option] }}
            </option>
          </select>
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
        <!-- WP57:题单下拉(力扣系平台渲染;学习计划+我的题单两组;与公司下拉互斥) -->
        <label
          v-if="favorites !== null && (favorites.studyPlans.length > 0 || favorites.myFavorites.length > 0)"
          class="filters__field filters__field--favorite"
        >
          <span>题单</span>
          <select v-model="favorite" @change="handleFavoriteChange">
            <option value="">全部题库</option>
            <optgroup label="学习计划">
              <option v-for="entry in favorites.studyPlans" :key="entry.slug" :value="entry.slug">
                {{ entry.name }}
              </option>
            </optgroup>
            <!-- 我的题单:未登录/拉取失败后端降级为空数组,空组不渲染 -->
            <optgroup v-if="favorites.myFavorites.length > 0" label="我的题单">
              <option v-for="entry in favorites.myFavorites" :key="entry.slug" :value="entry.slug">
                {{ entry.name }} ({{ entry.questionCount }})
              </option>
            </optgroup>
          </select>
        </label>
        <!-- WP42:公司下拉(仅 CompanyQueryCapable 平台渲染;选中后走公司题单查询) -->
        <label v-if="companies.length > 0" class="filters__field filters__field--company">
          <span>公司</span>
          <select v-model="company" @change="handleCompanyChange">
            <option value="">全部公司</option>
            <option v-for="entry in companies" :key="entry.slug" :value="entry.slug">
              {{ entry.name }} ({{ entry.questionCount }})
            </option>
          </select>
        </label>
        <!-- WP42:面试期间(仅公司模式出现;默认 30 天,旧 UI 默认值) -->
        <label v-if="companies.length > 0 && company !== ''" class="filters__field">
          <span>期间</span>
          <select v-model="period" @change="reload">
            <option v-for="option in PERIOD_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <div class="filters__actions">
          <button type="submit" :disabled="loading || !platform">搜索</button>
          <button type="button" :disabled="loading" @click="resetFilters">重置</button>
        </div>
      </form>

      <p v-if="statusFilter !== ''" class="filter-note">
        状态筛选仅作用于已加载条目——加载更多可扩大范围。
      </p>

      <p v-if="company !== '' && keyword.trim() !== ''" class="filter-note">
        公司题单模式下关键字被忽略(平台限制)。
      </p>

      <p v-if="favorite !== '' && keyword.trim() !== ''" class="filter-note">
        题单模式下关键字被忽略(平台限制)。
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
          {{ loginPending ? '登录中…' : '登录' }}
        </button>
      </p>
      <p v-else-if="loading && items.length === 0" class="state">正在加载题目…</p>
      <p v-else-if="!loading && visibleItems.length === 0 && statusFilter !== ''" class="state">
        没有已加载条目匹配状态筛选,请加载更多或清除筛选。
      </p>
      <p v-else-if="!loading && items.length === 0" class="state">没有匹配的题目,请调整筛选后重试。</p>

      <ul v-if="visibleItems.length > 0" class="problems" aria-label="题目列表">
        <li v-for="item in visibleItems" :key="item.remoteId">
          <!-- 修复反馈:单行行布局(状态徽标 | 题号 | 标题 | 标签 | 难度),各行等高、标题严格对齐 -->
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
            </span>
            <span class="problems__remote">{{ item.remoteId }}</span>
            <span class="problems__title">{{ item.title }}</span>
            <span class="problems__meta">
              <span v-if="item.tags.length > 0" class="problems__tags">{{ item.tags.join(' · ') }}</span>
              <span class="diff-badge" :class="difficultyClass(item.difficulty)">{{ item.difficulty }}</span>
            </span>
          </button>
        </li>
      </ul>

      <div v-if="items.length > 0" class="pager">
        <span class="pager__count">
          已加载 {{ statusFilter === '' ? items.length : `${visibleItems.length}/${items.length}` }} 条
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

    <!-- 本地搜索子视图(WP49 复用 SearchPanel;/search/ 独立路由同款) -->
    <SearchPanel v-if="searchMounted" v-show="subview === 'search'" />
  </AppShell>
</template>

<style scoped>
/* WP49:账号区(全部平台常驻;已登录=高亮,未登录=灰) */
.accounts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}

.accounts__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border: 1px solid var(--cq-border);
  border-radius: 999px;
  background: var(--cq-panel);
  color: var(--cq-muted);
  font-size: 12px;
}

.accounts__item--on {
  border-color: var(--cq-accent);
  background: var(--cq-panel-raised);
  color: var(--cq-accent);
}

.accounts__item--on .accounts__platform {
  font-weight: 600;
}

/* WP49:显式登录/退出按钮(替代点击整行切换) */
.accounts__action {
  padding: 1px 10px;
  border: 1px solid var(--cq-border);
  border-radius: 999px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-size: 12px;
  cursor: pointer;
}

.accounts__action:hover {
  border-color: var(--cq-accent);
}

.accounts__action:disabled {
  opacity: 0.6;
  cursor: default;
}

.accounts__user {
  font-weight: 600;
}

/* WP49:子视图切换 tabs */
.subviews {
  display: flex;
  gap: 4px;
  margin-bottom: 10px;
  border-bottom: 1px solid var(--cq-border);
}

.subviews__tab {
  padding: 4px 14px;
  border: 1px solid transparent;
  border-bottom: none;
  border-radius: 6px 6px 0 0;
  background: transparent;
  color: var(--cq-muted);
  font-size: 13px;
  cursor: pointer;
}

.subviews__tab--on {
  border-color: var(--cq-border);
  background: var(--cq-panel-raised);
  color: var(--cq-accent);
  font-weight: 600;
}

.subviews__tab:not(.subviews__tab--on):hover {
  color: var(--cq-fg);
}

/* WP49:全未登录回落提示条 */
.fallback-hint {
  margin: 0 0 10px;
  padding: 8px 12px;
  border: 1px solid var(--cq-accent);
  border-radius: 8px;
  background: var(--cq-panel);
  color: var(--cq-accent);
  font-size: 12px;
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

/* WP37:状态徽标列——修复反馈后改为实心/空心圆徽标,一眼可辨:
   AC = 绿底白✓(实心圆) / TRIED = 主题色空心圆 / 🔒 会员 / 1.2.1:UNSOLVED = 灰色细空心圆 */
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

/* 1.2.1:未解决 = 灰色细空心圆(此前为空,整列表无视觉区分) */
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

/* 修复反馈:题号/标题/标签全部单行化,各行等高、标题基线对齐 */
.problems__remote {
  flex: 0 1 96px;
  min-width: 48px;
  font-size: 11px;
  color: var(--cq-muted);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.problems__title {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.problems__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 1 auto;
  min-width: 0;
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
