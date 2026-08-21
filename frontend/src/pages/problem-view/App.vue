<script setup lang="ts">
/**
 * 题面页(WP20 真实数据):URL query `?platform=&remoteId=`(Kotlin 侧
 * ProblemViewNavigator 构造)→ /api/problem 拉取 → 渲染管线(清洗+hljs/KaTeX)。
 * WP26:题解引用列表区块(SolutionViewCapable 平台附加拉取)。
 * WP31:题解列表→应用内正文阅读器(同区块内切换,不另开页面)——
 * 复用题面 MD/HTML 渲染管线,浏览器风格导航工具栏(后退/前进/刷新),
 * 历史栈页内维护(cursor -1 = 列表根视图,同浏览器语义);站外打开仍留作
 * 兜底出口(经 `nav:openUrl` 桥消息)。
 * WP39:题面自定义 CSS——加载后 fetch `/api/custom.css`(text/css,按请求现读
 * 设置),非空则注入 `<style data-cq-custom-css>`;settings apply 后下次页面
 * 加载生效;卸载时移除注入节点。
 * WP41:题解排序下拉(仅 LeetCode 系平台;值/文案=旧 ArticleOrderByAction
 * 事实,变更即重拉)与 webview 缩放工具栏(−/100%/+,经 `webview:zoom`
 * 桥消息;题面与阅读器同属一个浏览器实例,天然共用;不持久化)。
 * WP50:文案全面中文化(排序选项 value 不变,仅 label 中文)。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import { TOPIC_AUTH_LOGIN, TOPIC_AUTH_LOGIN_RESULT, TOPIC_CHALLENGE_CREATE, TOPIC_CHALLENGE_CREATE_RESULT, TOPIC_NAV_OPEN_URL, TOPIC_WEBVIEW_ZOOM, cqBridge } from '../../bridge/cq-bridge'
import {
  ApiError,
  fetchCustomCss,
  fetchProblemDetail,
  fetchProblemSolutions,
  fetchSolutionArticleContent,
  type ProblemDetail,
  type SolutionArticle,
  type SolutionArticleContent,
} from '../../lib/api'
import { difficultyClass } from '../../lib/difficulty'
import { renderStatement } from '../../lib/statement'

const detail = ref<ProblemDetail | null>(null)
const loading = ref(false)
const error = ref('')
/** 后端错误码(auth-expired 等);为 auth-expired 时错误态附登录入口(1.0.x)。 */
const errorCode = ref('')

const solutions = ref<SolutionArticle[]>([])
const solutionsLoading = ref(false)
/** 题解是附属区块:拉取失败只降级提示(如未登录),不影响题面主体。 */
const solutionsError = ref('')
/** 题解区块错误码;auth-expired 时附登录入口(1.0.x,如洛谷题解未登录)。 */
const solutionsErrorCode = ref('')

/** 登录请求进行中(结果回推前置灰按钮,防重复弹窗)。 */
const loginPending = ref(false)

/** 浏览链路登录入口(1.0.x):Kotlin 弹平台登录对话框,结果经 auth:login-result 回推。 */
function requestLogin(): void {
  if (loginPending.value || !platform) return
  loginPending.value = true
  cqBridge().send(TOPIC_AUTH_LOGIN, { platform })
}

/** 登录结果:仅本页平台;成功后重拉题面与题解(登录态已注入 cookie)。 */
function handleLoginResult(payload: unknown): void {
  const result = payload as { ok?: boolean; platform?: string }
  if (!result || result.platform !== platform) return
  loginPending.value = false
  if (result.ok) {
    void load()
    void loadSolutions()
  }
}

/** 一键开刷(1.0.x):题面数据全量自动带入,Kotlin 侧选语言→创建/复用工作区并打开编辑器。 */
const startPending = ref(false)
const startMsg = ref('')

function startCoding(): void {
  const current = detail.value
  if (startPending.value || !current) return
  startPending.value = true
  startMsg.value = ''
  cqBridge().send(TOPIC_CHALLENGE_CREATE, {
    platform: current.platform,
    remoteId: current.remoteId,
    title: current.title,
    difficulty: current.difficulty,
    url: current.url,
  })
}

/** 开刷结果:失败在按钮旁提示;成功无感(编辑器已打开)。 */
function handleCreateResult(payload: unknown): void {
  const result = payload as { ok?: boolean; error?: string }
  startPending.value = false
  startMsg.value = result?.ok ? '' : result?.error || '创建失败'
}

/** 阅读器历史栈:cursor 为 -1 表示列表根视图(浏览器语义,可前进回正文)。 */
const readerHistory = ref<SolutionArticle[]>([])
const readerCursor = ref(-1)
const content = ref<SolutionArticleContent | null>(null)
const contentLoading = ref(false)
const contentError = ref('')
/** 已读正文缓存(id → 响应):后退/前进即时切换;刷新按 id 失效。 */
const contentCache = new Map<string, SolutionArticleContent>()

const params = new URLSearchParams(window.location.search)
const platform = params.get('platform') ?? ''
const remoteId = params.get('remoteId') ?? ''

/**
 * WP41:题解排序——仅 LeetCode 系平台有排序能力(原版排序器即 LC 题解视图
 * 专属;洛谷等如实忽略参数)。选项值/文案 = 后端 SPI/旧 Show 实现事实;
 * 默认 hot 与后端缺省一致。
 */
const LEETCODE_PLATFORM_IDS = new Set(['leetcode', 'leetcodecn'])
const supportsSolutionOrder = LEETCODE_PLATFORM_IDS.has(platform)
const SOLUTION_ORDER_OPTIONS = [
  { value: 'mostUpvote', label: '最多点赞' },
  { value: 'hot', label: '最热' },
  { value: 'newest', label: '最新' },
  { value: 'oldest', label: '最早' },
]
const solutionOrder = ref('hot')

/** 排序变更即重拉列表(阅读器态无列表,重拉只更新底层数据,返回列表即见)。 */
function onOrderChange(event: Event): void {
  solutionOrder.value = (event.target as HTMLSelectElement).value
  void loadSolutions()
}

/** WP41:webview 缩放(delta 步进 / level 直设;Kotlin 侧钳制与生效)。 */
function zoomBy(delta: number): void {
  cqBridge().send(TOPIC_WEBVIEW_ZOOM, { delta })
}

function zoomReset(): void {
  cqBridge().send(TOPIC_WEBVIEW_ZOOM, { level: 0 })
}

const renderedHtml = computed(() => {
  if (!detail.value) return ''
  return renderStatement(detail.value.content, detail.value.contentFormat)
})

const inReader = computed(() => readerCursor.value >= 0)
const currentArticle = computed(() => readerHistory.value[readerCursor.value] ?? null)
const canBack = computed(() => readerCursor.value >= 0)
const canForward = computed(() => readerCursor.value < readerHistory.value.length - 1)
const readerTitle = computed(() => content.value?.title || currentArticle.value?.title || '')

/** 正文经同一渲染管线(白名单清洗 + hljs + KaTeX + 表格包装)。 */
const renderedContentHtml = computed(() => {
  if (!content.value) return ''
  return renderStatement(content.value.content, content.value.contentFormat)
})

function describeError(cause: unknown): string {
  if (cause instanceof ApiError) {
    return `[${cause.code}] ${cause.message}`
  }
  return cause instanceof Error ? cause.message : String(cause)
}

async function load(): Promise<void> {
  if (!platform || !remoteId) return
  loading.value = true
  error.value = ''
  errorCode.value = ''
  try {
    detail.value = await fetchProblemDetail(platform, remoteId)
  } catch (cause) {
    error.value = describeError(cause)
    errorCode.value = cause instanceof ApiError ? cause.code : ''
  } finally {
    loading.value = false
  }
}

async function loadSolutions(): Promise<void> {
  if (!platform || !remoteId) return
  solutionsLoading.value = true
  solutionsError.value = ''
  solutionsErrorCode.value = ''
  try {
    // 排序键仅对支持排序的平台随请求发出(后端对其余平台忽略参数)
    const order = supportsSolutionOrder ? solutionOrder.value : undefined
    // items 缺失(端点契约被破坏/被通用 stub 拦截)时降级为空列表,不影响题面
    solutions.value = (await fetchProblemSolutions(platform, remoteId, order)).items ?? []
  } catch (cause) {
    solutions.value = []
    solutionsError.value = describeError(cause)
    solutionsErrorCode.value = cause instanceof ApiError ? cause.code : ''
  } finally {
    solutionsLoading.value = false
  }
}

/** 列表点击 → 打开阅读器(截断前进分支后压栈,同浏览器新建历史条目)。 */
function openArticle(article: SolutionArticle): void {
  readerHistory.value = readerHistory.value.slice(0, readerCursor.value + 1)
  readerHistory.value.push(article)
  readerCursor.value = readerHistory.value.length - 1
  void showArticle(article)
}

async function showArticle(article: SolutionArticle): Promise<void> {
  const cached = contentCache.get(article.id)
  if (cached) {
    content.value = cached
    contentError.value = ''
    return
  }
  contentLoading.value = true
  contentError.value = ''
  try {
    const fetched = await fetchSolutionArticleContent(platform, remoteId, article.id)
    contentCache.set(article.id, fetched)
    // 竞态守卫:后退/刷新期间的迟到响应只落缓存,不覆盖当前展示
    if (currentArticle.value?.id === article.id) {
      content.value = fetched
    }
  } catch (cause) {
    if (currentArticle.value?.id === article.id) {
      contentError.value = describeError(cause)
    }
  } finally {
    contentLoading.value = false
  }
}

function goBack(): void {
  if (!canBack.value) return
  readerCursor.value -= 1
  const target = currentArticle.value
  if (target) {
    void showArticle(target)
  }
}

function goForward(): void {
  if (!canForward.value) return
  readerCursor.value += 1
  const target = currentArticle.value
  if (target) {
    void showArticle(target)
  }
}

/** 刷新:正文态重拉当前篇(清缓存);列表根视图重拉题解列表。 */
async function refresh(): Promise<void> {
  const article = currentArticle.value
  if (!article) {
    await loadSolutions()
    return
  }
  contentCache.delete(article.id)
  content.value = null
  await showArticle(article)
}

/** 外链出口:真实桥经 Kotlin 打开系统浏览器;dev/测试无桥回退 window.open。 */
function openSolution(url: string): void {
  cqBridge().send(TOPIC_NAV_OPEN_URL, { url })
  if (!cqBridge().isRealBridge()) {
    window.open(url, '_blank', 'noopener')
  }
}

/** WP39:注入的自定义 CSS 节点(卸载时移除,避免跨页面残留)。 */
let customStyleEl: HTMLStyleElement | null = null

/** WP39:题面自定义 CSS——非空才注入;失败静默(附属能力,不影响题面)。 */
async function injectCustomCss(): Promise<void> {
  try {
    const css = await fetchCustomCss()
    if (!css.trim()) return
    const el = document.createElement('style')
    el.setAttribute('data-cq-custom-css', '')
    el.textContent = css
    document.head.appendChild(el)
    customStyleEl = el
  } catch {
    // 端点未配置/网络抖动:跳过注入,题面照常渲染
  }
}

onMounted(() => {
  void load()
  void loadSolutions()
  void injectCustomCss()
  detachLoginResult = cqBridge().on(TOPIC_AUTH_LOGIN_RESULT, handleLoginResult)
  detachCreateResult = cqBridge().on(TOPIC_CHALLENGE_CREATE_RESULT, handleCreateResult)
})

let detachLoginResult: (() => void) | null = null
let detachCreateResult: (() => void) | null = null

onUnmounted(() => {
  detachLoginResult?.()
  detachLoginResult = null
  detachCreateResult?.()
  detachCreateResult = null
  customStyleEl?.remove()
  customStyleEl = null
})
</script>

<template>
  <AppShell title="题面" subtitle="题目描述渲染(净化后的 HTML / Markdown)。">
    <!-- WP41:整页缩放工具栏(题面与阅读器共用;不持久化,加载即 100%) -->
    <div v-if="platform && remoteId" class="zoombar" role="toolbar" aria-label="页面缩放">
      <button
        type="button"
        class="zoombar__btn"
        title="缩小"
        aria-label="缩小"
        @click="zoomBy(-1)"
      >
        −
      </button>
      <button
        type="button"
        class="zoombar__btn zoombar__btn--reset"
        title="恢复 100% 缩放"
        aria-label="恢复 100% 缩放"
        @click="zoomReset"
      >
        100%
      </button>
      <button
        type="button"
        class="zoombar__btn"
        title="放大"
        aria-label="放大"
        @click="zoomBy(1)"
      >
        +
      </button>
    </div>

    <p v-if="!platform || !remoteId" class="state">
      尚未选择题目,请从题库列表打开。
    </p>

    <p v-else-if="error" class="state state--error" role="alert">
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
      <button type="button" class="state__retry" @click="load">重试</button>
    </p>

    <p v-else-if="loading" class="state">正在加载题面…</p>

    <template v-else-if="detail">
      <header class="statement-header">
        <h2 class="statement-header__title">
          <span class="statement-header__remote">{{ detail.remoteId }}</span>
          {{ detail.title }}
        </h2>
        <p class="statement-header__meta">
          <span class="diff-badge" :class="difficultyClass(detail.difficulty)">{{ detail.difficulty }}</span>
          <span class="statement-header__source" :data-source="detail.source">{{ detail.source }}</span>
          <button
            type="button"
            class="statement-header__start"
            :disabled="startPending"
            @click="startCoding"
          >
            {{ startPending ? '准备中…' : '一键开刷' }}
          </button>
          <span v-if="startMsg" class="statement-header__start-msg" role="alert">{{ startMsg }}</span>
          <a
            v-if="detail.url"
            :href="detail.url"
            target="_blank"
            rel="noopener noreferrer"
          >
            站外打开
          </a>
        </p>
      </header>

      <!-- eslint-disable-next-line vue/no-v-html — 内容经 renderStatement 白名单清洗 -->
      <article class="statement" v-html="renderedHtml"></article>

      <!-- WP26/WP31:题解区块——列表 ↔ 应用内正文阅读器切换 + 浏览器风格导航工具栏 -->
      <section class="solutions" aria-label="题解">
        <div class="solutions__bar">
          <h3 class="solutions__title">题解</h3>
          <div class="solutions__tools">
            <!-- WP41:题解排序(仅 LeetCode 系平台;变更即重拉) -->
            <label v-if="supportsSolutionOrder" class="solutions__order">
              <span class="solutions__order-label">排序</span>
              <select
                class="solutions__order-select"
                aria-label="题解排序"
                @change="onOrderChange"
              >
                <option
                  v-for="option in SOLUTION_ORDER_OPTIONS"
                  :key="option.value"
                  :value="option.value"
                  :selected="option.value === solutionOrder"
                >
                  {{ option.label }}
                </option>
              </select>
            </label>
            <div class="solutions__nav" role="toolbar" aria-label="题解阅读器导航">
              <button
                type="button"
                class="solutions__nav-btn"
                :disabled="!canBack"
                title="返回列表 / 上一篇"
                aria-label="后退"
                @click="goBack"
              >
                ←
              </button>
              <button
                type="button"
                class="solutions__nav-btn"
                :disabled="!canForward"
                title="前进到下一篇"
                aria-label="前进"
                @click="goForward"
              >
                →
              </button>
              <button
                type="button"
                class="solutions__nav-btn"
                :disabled="contentLoading || solutionsLoading"
                title="刷新当前视图"
                aria-label="刷新"
                @click="refresh"
              >
                ⟳
              </button>
            </div>
          </div>
        </div>

        <!-- 列表根视图 -->
        <template v-if="!inReader">
          <p v-if="solutionsLoading" class="solutions__state">正在加载题解…</p>
          <p v-else-if="solutionsError" class="solutions__state solutions__state--error" role="alert">
            {{ solutionsError }}
            <button
              v-if="solutionsErrorCode === 'auth-expired'"
              type="button"
              class="state__login"
              :disabled="loginPending"
              @click="requestLogin"
            >
              {{ loginPending ? '登录中…' : '登录' }}
            </button>
          </p>
          <p v-else-if="solutions.length === 0" class="solutions__state">暂无题解。</p>
          <ul v-else class="solutions__list">
            <li v-for="article in solutions" :key="article.id" class="solutions__item">
              <button
                type="button"
                class="solutions__open"
                :title="article.title"
                @click="openArticle(article)"
              >
                <span class="solutions__item-title">{{ article.title }}</span>
                <span class="solutions__read">阅读 ›</span>
              </button>
            </li>
          </ul>
        </template>

        <!-- 正文阅读器 -->
        <article v-else class="reader">
          <header class="reader__header">
            <h4 class="reader__title">{{ readerTitle }}</h4>
            <button
              v-if="currentArticle"
              type="button"
              class="reader__external"
              :title="currentArticle.url"
              @click="openSolution(currentArticle.url)"
            >
              站外打开 ↗
            </button>
          </header>
          <p v-if="contentLoading" class="reader__state">正在加载正文…</p>
          <p v-else-if="contentError" class="reader__state reader__state--error" role="alert">
            {{ contentError }}
            <button
              v-if="currentArticle"
              type="button"
              class="state__retry"
              @click="showArticle(currentArticle)"
            >
              重试
            </button>
          </p>
          <!-- eslint-disable-next-line vue/no-v-html — 正文经 renderStatement 白名单清洗 -->
          <div v-else-if="content" class="reader__body statement" v-html="renderedContentHtml"></div>
        </article>
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

/* 1.0.x:浏览链路登录入口(auth-expired 错误态);视觉与 Retry 同级 */
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

/* WP41:整页缩放工具栏(−/100%/+;题面与阅读器共用一个浏览器级缩放) */
.zoombar {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-bottom: 8px;
}

.zoombar__btn {
  min-width: 26px;
  padding: 1px 6px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-size: 12px;
  line-height: 1.5;
  cursor: pointer;
}

.zoombar__btn--reset {
  min-width: 44px;
}

.statement-header {
  margin-bottom: 12px;
}

.statement-header__title {
  margin: 0 0 6px;
  font-size: 17px;
  line-height: 1.4;
}

.statement-header__remote {
  display: block;
  font-size: 11px;
  color: var(--cq-muted);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
}

.statement-header__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: 12px;
  color: var(--cq-muted);
}

.statement-header__source {
  padding: 1px 8px;
  border: 1px solid var(--cq-border);
  border-radius: 999px;
}

.statement-header__source[data-source='cache'] {
  color: var(--cq-success);
  border-color: var(--cq-success);
}

/* 1.0.x:一键开刷(题面数据自动带入,选语言即建工作区) */
.statement-header__start {
  padding: 1px 12px;
  border: 1px solid var(--cq-success);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-success);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.statement-header__start:disabled {
  opacity: 0.55;
  cursor: default;
}

.statement-header__start-msg {
  font-size: 11px;
  color: var(--cq-danger);
  overflow-wrap: anywhere;
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

.solutions {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px dashed var(--cq-border);
}

.solutions__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.solutions__title {
  margin: 0;
  font-size: 13px;
  color: var(--cq-muted);
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

/* WP41:排序下拉与阅读器导航并列(下拉仅 LeetCode 系平台渲染) */
.solutions__tools {
  display: flex;
  align-items: center;
  gap: 10px;
}

.solutions__order {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--cq-muted);
}

.solutions__order-select {
  padding: 1px 4px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-size: 12px;
  cursor: pointer;
}

.solutions__nav {
  display: flex;
  gap: 4px;
}

.solutions__nav-btn {
  min-width: 26px;
  padding: 1px 6px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-size: 12px;
  line-height: 1.5;
  cursor: pointer;
}

.solutions__nav-btn:disabled {
  opacity: 0.45;
  cursor: default;
}

.solutions__state {
  margin: 4px 0;
  font-size: 12px;
  color: var(--cq-muted);
}

.solutions__state--error {
  color: var(--cq-danger);
}

.solutions__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.solutions__item {
  padding: 2px 0;
  font-size: 13px;
}

.solutions__open {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 4px 8px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--cq-fg);
  text-align: left;
  cursor: pointer;
}

.solutions__open:hover {
  border-color: var(--cq-border);
  background: var(--cq-panel);
}

.solutions__item-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.solutions__read {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--cq-muted);
}

.reader {
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel);
  padding: 10px 12px;
}

.reader__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.reader__title {
  margin: 0;
  font-size: 14px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.reader__external {
  flex-shrink: 0;
  padding: 1px 10px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-size: 11px;
  white-space: nowrap;
  cursor: pointer;
}

.reader__state {
  margin: 4px 0;
  font-size: 12px;
  color: var(--cq-muted);
}

.reader__state--error {
  color: var(--cq-danger);
}

.reader__body {
  max-height: 60vh;
  overflow-y: auto;
  padding-right: 4px;
}
</style>
