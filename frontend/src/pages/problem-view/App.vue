<script setup lang="ts">
/**
 * 题面页(WP20 真实数据):URL query `?platform=&remoteId=`(Kotlin 侧
 * ProblemViewNavigator 构造)→ /api/problem 拉取 → 渲染管线(清洗+hljs/KaTeX)。
 * WP26:题解引用列表区块(SolutionViewCapable 平台附加拉取)。
 * WP31:题解列表→应用内正文阅读器(同区块内切换,不另开页面)——
 * 复用题面 MD/HTML 渲染管线,浏览器风格导航工具栏(后退/前进/刷新),
 * 历史栈页内维护(cursor -1 = 列表根视图,同浏览器语义);站外打开仍留作
 * 兜底出口(经 `nav:openUrl` 桥消息)。
 */
import { computed, onMounted, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import { cqBridge, TOPIC_NAV_OPEN_URL } from '../../bridge/cq-bridge'
import {
  ApiError,
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

const solutions = ref<SolutionArticle[]>([])
const solutionsLoading = ref(false)
/** 题解是附属区块:拉取失败只降级提示(如未登录),不影响题面主体。 */
const solutionsError = ref('')

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
  try {
    detail.value = await fetchProblemDetail(platform, remoteId)
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    loading.value = false
  }
}

async function loadSolutions(): Promise<void> {
  if (!platform || !remoteId) return
  solutionsLoading.value = true
  solutionsError.value = ''
  try {
    // items 缺失(端点契约被破坏/被通用 stub 拦截)时降级为空列表,不影响题面
    solutions.value = (await fetchProblemSolutions(platform, remoteId)).items ?? []
  } catch (cause) {
    solutions.value = []
    solutionsError.value = describeError(cause)
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

onMounted(() => {
  void load()
  void loadSolutions()
})
</script>

<template>
  <AppShell title="Problem View" subtitle="Problem statement with sanitized HTML / markdown rendering.">
    <p v-if="!platform || !remoteId" class="state">
      No problem selected. Open one from the Problems list.
    </p>

    <p v-else-if="error" class="state state--error" role="alert">
      {{ error }}
      <button type="button" class="state__retry" @click="load">Retry</button>
    </p>

    <p v-else-if="loading" class="state">Loading problem statement…</p>

    <template v-else-if="detail">
      <header class="statement-header">
        <h2 class="statement-header__title">
          <span class="statement-header__remote">{{ detail.remoteId }}</span>
          {{ detail.title }}
        </h2>
        <p class="statement-header__meta">
          <span class="diff-badge" :class="difficultyClass(detail.difficulty)">{{ detail.difficulty }}</span>
          <span class="statement-header__source" :data-source="detail.source">{{ detail.source }}</span>
          <a
            v-if="detail.url"
            :href="detail.url"
            target="_blank"
            rel="noopener noreferrer"
          >
            open on site
          </a>
        </p>
      </header>

      <!-- eslint-disable-next-line vue/no-v-html — 内容经 renderStatement 白名单清洗 -->
      <article class="statement" v-html="renderedHtml"></article>

      <!-- WP26/WP31:题解区块——列表 ↔ 应用内正文阅读器切换 + 浏览器风格导航工具栏 -->
      <section class="solutions" aria-label="Solutions">
        <div class="solutions__bar">
          <h3 class="solutions__title">Solutions</h3>
          <div class="solutions__nav" role="toolbar" aria-label="Solution reader navigation">
            <button
              type="button"
              class="solutions__nav-btn"
              :disabled="!canBack"
              title="Back to list / previous article"
              aria-label="Back"
              @click="goBack"
            >
              ←
            </button>
            <button
              type="button"
              class="solutions__nav-btn"
              :disabled="!canForward"
              title="Forward to next article"
              aria-label="Forward"
              @click="goForward"
            >
              →
            </button>
            <button
              type="button"
              class="solutions__nav-btn"
              :disabled="contentLoading || solutionsLoading"
              title="Refresh current view"
              aria-label="Refresh"
              @click="refresh"
            >
              ⟳
            </button>
          </div>
        </div>

        <!-- 列表根视图 -->
        <template v-if="!inReader">
          <p v-if="solutionsLoading" class="solutions__state">Loading solutions…</p>
          <p v-else-if="solutionsError" class="solutions__state solutions__state--error" role="alert">
            {{ solutionsError }}
          </p>
          <p v-else-if="solutions.length === 0" class="solutions__state">No solutions available.</p>
          <ul v-else class="solutions__list">
            <li v-for="article in solutions" :key="article.id" class="solutions__item">
              <button
                type="button"
                class="solutions__open"
                :title="article.title"
                @click="openArticle(article)"
              >
                <span class="solutions__item-title">{{ article.title }}</span>
                <span class="solutions__read">read ›</span>
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
              open on site ↗
            </button>
          </header>
          <p v-if="contentLoading" class="reader__state">Loading article…</p>
          <p v-else-if="contentError" class="reader__state reader__state--error" role="alert">
            {{ contentError }}
            <button
              v-if="currentArticle"
              type="button"
              class="state__retry"
              @click="showArticle(currentArticle)"
            >
              Retry
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
