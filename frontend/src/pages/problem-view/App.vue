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
 * WP64(1.7.0 F3):header「加入复习」按钮——本地主档 id 为确定性
 * `<platform>:<remoteId>` 映射,直接调 add(幂等);已排期(today 三组里查
 * 同 id 行)则两态切换为「已排期至 yyyy-MM-dd」。
 * WP65(1.7.0 F4):header 题目计时角标——收到题面数据即重置起表(切题自动
 * 重置);订阅 `submission:updated`(payload WP65 起含 platformId/problemRemoteId),
 * 命中当前题且 AC → 停表定格 + toast「本题耗时 xx」。会话级,开关
 * localStorage `cq-problem-timer-enabled`(默认开);网页直接提交不停表,属预期。
 * WP73(1.9.0 F1/F2):header 收藏星标两态(questionData 协议无收藏态字段,
 * 中性空心 + localStorage 本地记忆,点击乐观切换)+ 题目提示折叠区(懒加载,
 * 点击才发请求,逐条 details 默认收起)。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import { TOPIC_AUTH_LOGIN, TOPIC_AUTH_LOGIN_RESULT, TOPIC_CHALLENGE_CREATE, TOPIC_CHALLENGE_CREATE_RESULT, TOPIC_NAV_OPEN_URL, TOPIC_SUBMISSION_UPDATED, TOPIC_WEBVIEW_ZOOM, cqBridge } from '../../bridge/cq-bridge'
import {
  ApiError,
  addReviewProblem,
  fetchCustomCss,
  fetchProblemDetail,
  fetchProblemHints,
  fetchProblemSolutions,
  fetchReviewToday,
  fetchSolutionArticleContent,
  toggleFavorite,
  type ProblemDetail,
  type SolutionArticle,
  type SolutionArticleContent,
} from '../../lib/api'
import { difficultyClass } from '../../lib/difficulty'
import { formatEpochDay } from '../../lib/epochDay'
import { createProblemTimer, formatDuration, isAcceptedForProblem, isTimerEnabled } from '../../lib/problemTimer'
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

/** WP64:加入复习(1.7.0 F3)。 */
/** 已排期时为首个别到期日(epoch 秒);null = 未排期。 */
const reviewScheduledFor = ref<number | null>(null)
const reviewAdding = ref(false)
const reviewError = ref('')

/** 查当前题排期:today 三组里找同 id 行(附属能力,失败按未排期处理)。 */
async function loadReviewState(): Promise<void> {
  if (!platform || !remoteId) return
  try {
    const result = await fetchReviewToday()
    const row = [...result.overdue, ...result.today, ...result.upcoming].find(
      (item) => item.problemId === reviewProblemId,
    )
    reviewScheduledFor.value = row?.nextDate ?? null
  } catch {
    reviewScheduledFor.value = null
  }
}

/** 加入复习(幂等):成功切「已排期至 首个计划日」;失败按钮旁提示后端消息。 */
async function handleAddReview(): Promise<void> {
  if (reviewAdding.value || reviewScheduledFor.value !== null) return
  reviewAdding.value = true
  reviewError.value = ''
  try {
    const result = await addReviewProblem(reviewProblemId)
    reviewScheduledFor.value = result.planDates[0] ?? null
  } catch (cause) {
    reviewError.value = describeError(cause)
  } finally {
    reviewAdding.value = false
  }
}

/** WP65:题目计时器(会话级,开关 localStorage `cq-problem-timer-enabled` 默认开)。 */
const timerEnabled = isTimerEnabled()
const problemTimer = createProblemTimer()
const timerText = ref('00:00')
/** AC 停表后的「本题耗时 xx」toast(短暂提示后自动消失)。 */
const timerNotice = ref('')
let timerNoticeTimeout: ReturnType<typeof setTimeout> | null = null
let timerTick: ReturnType<typeof setInterval> | null = null

function refreshTimerText(): void {
  timerText.value = formatDuration(problemTimer.elapsedMs())
}

/** 收到题面数据即重置起表(切题/刷新/登录重拉均按最新一次起表)。 */
function startTimer(): void {
  if (!timerEnabled) return
  problemTimer.start()
  refreshTimerText()
  if (timerTick === null) {
    timerTick = setInterval(refreshTimerText, 500)
  }
}

/** AC 命中当前题 → 停表定格 + toast;不匹配/非 AC 不动。 */
function handleSubmissionUpdated(payload: unknown): void {
  if (!problemTimer.isRunning()) return
  if (!isAcceptedForProblem(payload, platform, remoteId)) return
  if (!problemTimer.stop()) return
  if (timerTick !== null) {
    clearInterval(timerTick)
    timerTick = null
  }
  refreshTimerText()
  timerNotice.value = `本题耗时 ${timerText.value}`
  if (timerNoticeTimeout !== null) clearTimeout(timerNoticeTimeout)
  timerNoticeTimeout = setTimeout(() => {
    timerNotice.value = ''
    timerNoticeTimeout = null
  }, 4000)
}

function stopTimer(): void {
  if (timerTick !== null) {
    clearInterval(timerTick)
    timerTick = null
  }
  if (timerNoticeTimeout !== null) {
    clearTimeout(timerNoticeTimeout)
    timerNoticeTimeout = null
  }
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

/** WP64:本地 problem 主档 id(后端确定性映射 `<platform>:<remoteId>`,add/complete 均用它)。 */
const reviewProblemId = `${platform}:${remoteId}`

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

/** WP73:收藏星标(1.9.0 F1)——questionData 协议无收藏态字段(WP72 双站实测),
 * 初始态走中性空心 + localStorage 本地记忆(`cq-fav-<platform>-<remoteId>`),
 * 点击乐观切换,POST toggle 成功保持+写记忆,失败回滚+按钮旁提示。 */
const favStorageKey = `cq-fav-${platform}-${remoteId}`
const favorited = ref(localStorage.getItem(favStorageKey) === '1')
const favPending = ref(false)
const favError = ref('')
/** 星标仅在题面携带 questionId(LC 数字主档 id)时可用;缺失(无概念平台/旧缓存)中性隐藏。 */
const favoriteAvailable = computed(() => Boolean(detail.value?.questionId))

async function toggleFavoriteStar(): Promise<void> {
  const current = detail.value
  if (favPending.value || !current?.questionId) return
  const target = !favorited.value
  favPending.value = true
  favorited.value = target
  favError.value = ''
  try {
    await toggleFavorite(current.platform, current.questionId, target)
    if (target) {
      localStorage.setItem(favStorageKey, '1')
    } else {
      localStorage.removeItem(favStorageKey)
    }
  } catch (cause) {
    favorited.value = !target
    favError.value =
      cause instanceof ApiError && cause.code === 'auth-expired'
        ? '请先登录'
        : describeError(cause)
  } finally {
    favPending.value = false
  }
}

/** WP73:题目提示(1.9.0 F2,仅 LC 系平台有该能力)——懒加载,未点击零请求;
 * null = 未拉取过(首次点击拉取并展开,再点收起,再点展开不重拉)。 */
const supportsHints = LEETCODE_PLATFORM_IDS.has(platform)
const hintsOpen = ref(false)
const hints = ref<string[] | null>(null)
const hintsLoading = ref(false)

async function toggleHints(): Promise<void> {
  if (!hintsOpen.value && hints.value === null) {
    if (hintsLoading.value) return
    hintsLoading.value = true
    try {
      hints.value = await fetchProblemHints(platform, remoteId)
    } catch {
      hints.value = [] // 无能力平台(404)等附属失败按「无提示」兜底,不阻塞题面
    } finally {
      hintsLoading.value = false
    }
    hintsOpen.value = true
    return
  }
  hintsOpen.value = !hintsOpen.value
}

/** hints 原文可含 HTML 实体(`&lt;code&gt;` 等,站方转义形态),文本渲染前解码
 * 为字面文本(纯文本插值无 v-html,零注入面;双转义序列不二次解码,如实)。 */
const HTML_ENTITIES: Record<string, string> = {
  '&lt;': '<',
  '&gt;': '>',
  '&quot;': '"',
  '&#39;': "'",
  '&nbsp;': ' ',
  '&amp;': '&',
}

function decodeEntities(text: string): string {
  return text.replace(/&(?:lt|gt|quot|#39|nbsp|amp);/g, (match) => HTML_ENTITIES[match] ?? match)
}

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
    // WP65:收到题面数据即重置起表(失败态不起表,重试成功再起)
    startTimer()
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
  void loadReviewState()
  detachLoginResult = cqBridge().on(TOPIC_AUTH_LOGIN_RESULT, handleLoginResult)
  detachCreateResult = cqBridge().on(TOPIC_CHALLENGE_CREATE_RESULT, handleCreateResult)
  // WP65:计时器开着才订阅提交事件(关时不起表也不订阅,零开销)
  if (timerEnabled) {
    detachSubmissionUpdated = cqBridge().on(TOPIC_SUBMISSION_UPDATED, handleSubmissionUpdated)
  }
})

let detachLoginResult: (() => void) | null = null
let detachCreateResult: (() => void) | null = null
let detachSubmissionUpdated: (() => void) | null = null

onUnmounted(() => {
  detachLoginResult?.()
  detachLoginResult = null
  detachCreateResult?.()
  detachCreateResult = null
  detachSubmissionUpdated?.()
  detachSubmissionUpdated = null
  stopTimer()
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
          <!-- WP65:题目计时角标(会话级;AC 停表定格,切题重置) -->
          <span
            v-if="timerEnabled"
            class="statement-header__timer"
            :class="{ 'statement-header__timer--stopped': !problemTimer.isRunning() && timerText !== '00:00' }"
            title="本题用时(通过后定格)"
          >{{ timerText }}</span>
          <button
            type="button"
            class="statement-header__start"
            :disabled="startPending"
            @click="startCoding"
          >
            {{ startPending ? '准备中…' : '一键开刷' }}
          </button>
          <!-- WP64:加入复习两态(未排期可点;已排期显示首个到期日并禁用,add 幂等) -->
          <button
            type="button"
            class="statement-header__review"
            :disabled="reviewAdding || reviewScheduledFor !== null"
            :title="reviewScheduledFor !== null ? '已加入间隔复习计划' : '加入间隔复习计划(1,4,7,14,28,60 天)'"
            @click="handleAddReview"
          >
            {{
              reviewScheduledFor !== null
                ? `已排期至 ${formatEpochDay(reviewScheduledFor)}`
                : reviewAdding
                  ? '加入中…'
                  : '加入复习'
            }}
          </button>
          <!-- WP73:收藏星标两态(协议无收藏态字段,中性空心+本地记忆;乐观切换,失败回滚) -->
          <button
            v-if="favoriteAvailable"
            type="button"
            class="statement-header__fav"
            :class="{ 'statement-header__fav--on': favorited }"
            :disabled="favPending"
            :title="favorited ? '移出默认收藏夹' : '加入默认收藏夹'"
            :aria-label="favorited ? '移出默认收藏夹' : '加入默认收藏夹'"
            :aria-pressed="favorited"
            @click="toggleFavoriteStar"
          >{{ favorited ? '★' : '☆' }}</button>
          <span v-if="favError" class="statement-header__start-msg" role="alert">{{ favError }}</span>
          <!-- WP73:题目提示(懒加载,点击才发请求;再点收起整个区块) -->
          <button
            v-if="supportsHints"
            type="button"
            class="statement-header__hints"
            :class="{ 'statement-header__hints--open': hintsOpen }"
            title="查看题目提示(逐条展开)"
            @click="toggleHints"
          >提示</button>
          <span v-if="reviewError" class="statement-header__start-msg" role="alert">{{ reviewError }}</span>
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

      <!-- WP65:AC 停表 toast(本题耗时,4s 自动消失) -->
      <p v-if="timerNotice" class="timer-toast" role="status">{{ timerNotice }}</p>

      <!-- eslint-disable-next-line vue/no-v-html — 内容经 renderStatement 白名单清洗 -->
      <article class="statement" v-html="renderedHtml"></article>

      <!-- WP73:题目提示(懒加载折叠区,题面与题解之间;逐条 details 默认收起) -->
      <section v-if="hintsOpen" class="hints" aria-label="题目提示">
        <p v-if="hintsLoading" class="hints__state">正在加载提示…</p>
        <p v-else-if="!hints || hints.length === 0" class="hints__state">无提示</p>
        <details v-else v-for="(hint, index) in hints" :key="index" class="hints__item">
          <summary class="hints__summary">提示 {{ index + 1 }}</summary>
          <p class="hints__text">{{ decodeEntities(hint) }}</p>
        </details>
      </section>

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

/* WP65:题目计时角标(等宽数字,停表后绿色定格) */
.statement-header__timer {
  padding: 1px 8px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-variant-numeric: tabular-nums;
  font-size: 11px;
  color: var(--cq-muted);
}

.statement-header__timer--stopped {
  color: var(--cq-success);
  border-color: var(--cq-success);
}

/* WP65:AC 停表 toast(本题耗时,4s 自动消失) */
.timer-toast {
  margin: 0 0 8px;
  padding: 4px 12px;
  border: 1px solid var(--cq-success);
  border-radius: 8px;
  background: var(--cq-panel);
  font-size: 12px;
  color: var(--cq-success);
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

/* WP64:加入复习按钮(主题色描边,与绿色「一键开刷」并排) */
.statement-header__review {
  padding: 1px 12px;
  border: 1px solid var(--cq-accent);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-accent);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}

.statement-header__review:disabled {
  opacity: 0.75;
  cursor: default;
}

/* WP73:收藏星标两态(空心中性/实心 accent;协议无收藏态字段,本地记忆) */
.statement-header__fav {
  min-width: 26px;
  padding: 1px 6px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-muted);
  font-size: 13px;
  line-height: 1.5;
  cursor: pointer;
}

.statement-header__fav--on {
  color: var(--cq-accent);
  border-color: var(--cq-accent);
}

.statement-header__fav:disabled {
  opacity: 0.55;
  cursor: default;
}

/* WP73:题目提示按钮(与星标同区;展开态 accent 描边示开) */
.statement-header__hints {
  padding: 1px 12px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.statement-header__hints--open {
  color: var(--cq-accent);
  border-color: var(--cq-accent);
}

/* WP73:题目提示折叠区(题面与题解之间;逐条 details 默认收起) */
.hints {
  margin-top: 12px;
  padding: 8px 12px;
  border: 1px dashed var(--cq-border);
  border-radius: 8px;
}

.hints__state {
  margin: 0;
  font-size: 12px;
  color: var(--cq-muted);
}

.hints__item {
  padding: 2px 0;
  font-size: 13px;
}

.hints__summary {
  color: var(--cq-accent);
  cursor: pointer;
  user-select: none;
}

.hints__text {
  margin: 4px 0 6px;
  color: var(--cq-fg);
  overflow-wrap: anywhere;
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
