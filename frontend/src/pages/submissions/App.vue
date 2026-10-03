<script setup lang="ts">
/**
 * 提交历史页(WP21 真实数据;WP36 详情面板;WP40 代码回看):`GET /api/submissions`
 * (可带 URL query `?challengeId=` 过滤单一工作区)→ 题目/语言/verdict 徽标/耗时/
 * 内存/时间列表;行点击展开详情面板(`GET /api/submission/{id}/detail`,本地快照+
 * 平台详情合并:verdict/耗时/内存/语言 + 错误用例三件套 input/expected/actual 对比
 * + extras 键值 + 平台站外链;展开互斥,再点收起,按 id 缓存不再重复拉取);
 * 面板内 Details/Code 双 tab(按行独立,收起重开保持)——Code 态渲染本地 codeSnapshot
 * (hljs 按需高亮,未知语言纯文本降级,空快照提示文案,附 Copy 按钮);
 * Kotlin 侧提交落库后经桥 `submission:updated` 推送本页刷新(桥推送优先,
 * Refresh 按钮兜底)。
 * WP50:文案全面中文化(判定文案在 lib/verdict.ts,与 PluginBundle_zh 对齐)。
 */
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import AppShell from '../../components/AppShell.vue'
import { TOPIC_SUBMISSION_UPDATED, cqBridge } from '../../bridge/cq-bridge'
import {
  ApiError,
  fetchRemoteSubmissions,
  fetchSubmissionDetail,
  fetchSubmissions,
  type RemoteSubmissionItem,
  type SubmissionDetailResult,
  type SubmissionListItem,
} from '../../lib/api'
import { highlightCode } from '../../lib/highlight'
import { formatMemoryKb, formatMillis, formatTimestamp, verdictMeta } from '../../lib/verdict'

const PAGE_SIZE = 50

const bridge = cqBridge()

const items = ref<SubmissionListItem[]>([])
const offset = ref(0)
const hasMore = ref(false)
const loading = ref(false)
const error = ref('')

// 站内提交历史(WP61 同步落库的 remote_submission):混合展示的附属数据源。
const remoteItems = ref<RemoteSubmissionItem[]>([])
const remoteHasMore = ref(false)
let remoteOffset = 0

/** 当前展开详情面板的行(单展开互斥;再点同一行收起)。 */
const expandedId = ref<string | null>(null)

/** 详情面板按 id 缓存(loading/ready/error 三态;重开不重复拉取)。 */
type PanelState =
  | { status: 'loading' }
  | { status: 'ready'; data: SubmissionDetailResult }
  | { status: 'error'; message: string }
const panels = reactive<Record<string, PanelState>>({})

/** URL query 携带 challengeId 时过滤单一工作区(提交完成后的定向视图)。 */
const params = new URLSearchParams(window.location.search)
const challengeId = params.get('challengeId')?.trim() || ''

// challengeId 定向视图不掺站内历史(其无 challenge 概念,保持纯本地现状)。
const remoteEnabled = !challengeId

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
    await loadRemotePage()
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    loading.value = false
  }
}

/** 站内历史分页(与本地页同步推进);附属数据:失败静默降级纯本地视图。 */
async function loadRemotePage(): Promise<void> {
  if (!remoteEnabled) return
  try {
    const result = await fetchRemoteSubmissions({ limit: PAGE_SIZE, offset: remoteOffset })
    // 防御:缺 remoteId 的畸形条目丢弃(旧 mock/协议漂移不产生幻影行)
    const page = result.items.filter((r) => r != null && r.remoteId != null)
    remoteItems.value = [...remoteItems.value, ...page]
    remoteOffset += page.length
    remoteHasMore.value = remoteOffset < (result.total ?? 0)
  } catch {
    remoteHasMore.value = false
  }
}

function reload(): void {
  items.value = []
  offset.value = 0
  hasMore.value = false
  remoteItems.value = []
  remoteOffset = 0
  remoteHasMore.value = false
  void loadPage(false)
}

// ---------------------------------------------------------------------------
// 混合展示:本地 + 站内历史合并行(提交时间倒序;同 (platform, remoteId) 本地优先)
// ---------------------------------------------------------------------------

type MergedRow =
  | { kind: 'local'; key: string; item: SubmissionListItem }
  | { kind: 'remote'; key: string; item: RemoteSubmissionItem }

const rows = computed<MergedRow[]>(() => {
  const byKey = new Map<string, MergedRow>()
  for (const remote of remoteItems.value) {
    byKey.set(`${remote.platformId}:${remote.remoteId}`, {
      kind: 'remote',
      key: `${remote.platformId}:${remote.remoteId}`,
      item: remote,
    })
  }
  // 本地条目后写覆盖同键站内行(本地含代码快照与完整 verdict)
  for (const local of items.value) {
    byKey.set(`${local.platformId}:${local.remoteSubmissionId}`, {
      kind: 'local',
      key: `${local.platformId}:${local.remoteSubmissionId}`,
      item: local,
    })
  }
  return [...byKey.values()].sort(
    (a, b) => Date.parse(b.item.submittedAt) - Date.parse(a.item.submittedAt),
  )
})

/** 「加载更多」两源任一未尽即可续。 */
const canLoadMore = computed(() => hasMore.value || (remoteEnabled && remoteHasMore.value))

/** 站内判定文案 → 徽标(AC 绿;常见失败档映射既有色;未知中性)。 */
function remoteStatusMeta(statusDisplay: string): { label: string; tone: string } {
  const norm = statusDisplay.trim()
  if (norm === 'AC') return { label: '通过', tone: 'accepted' }
  const known: Record<string, { label: string; tone: string }> = {
    'Wrong Answer': { label: '答案错误', tone: 'rejected' },
    'Time Limit Exceeded': { label: '超出时间限制', tone: 'limit' },
    'Memory Limit Exceeded': { label: '超出内存限制', tone: 'limit' },
    'Runtime Error': { label: '运行时错误', tone: 'crash' },
    'Compile Error': { label: '编译错误', tone: 'crash' },
  }
  return known[norm] ?? { label: norm || '未知', tone: 'neutral' }
}

/** 站内提交详情外链(仅力扣两变体有同步数据)。 */
function remoteViewUrl(item: RemoteSubmissionItem): string {
  const domain = item.platformId === 'leetcodecn' ? 'leetcode.cn' : 'leetcode.com'
  return `https://${domain}/submissions/detail/${item.remoteId}/`
}

// ---------------------------------------------------------------------------
// WP36:详情面板(展开互斥;按 id 缓存)
// ---------------------------------------------------------------------------

function toggleDetail(id: string): void {
  if (expandedId.value === id) {
    expandedId.value = null
    return
  }
  expandedId.value = id
  if (panels[id] === undefined) {
    void loadDetail(id)
  }
}

/** 站内行展开:轻量内联信息(无详情拉取、无 Code 态——无本地代码快照)。 */
function toggleRemote(key: string): void {
  expandedId.value = expandedId.value === key ? null : key
}

async function loadDetail(id: string): Promise<void> {
  panels[id] = { status: 'loading' }
  try {
    panels[id] = { status: 'ready', data: await fetchSubmissionDetail(id) }
  } catch (cause) {
    panels[id] = { status: 'error', message: describeError(cause) }
  }
}

/**
 * ready 态详情的模板取值口(vue-tsc 无法跨指令窄化 Record 索引访问的判别联合,
 * 模板内统一经本函数取值)。**仅在 `v-else-if="panels[item.id]?.status === 'ready'"`
 * 守卫的分支内调用**——非 ready 态渲染该分支的先决条件不成立,不会走到。
 */
function detailOf(id: string): SubmissionDetailResult {
  const state = panels[id]
  return state != null && state.status === 'ready'
    ? state.data
    : (undefined as unknown as SubmissionDetailResult)
}

/** 错误态文案(同理,仅错误分支渲染时取值)。 */
function errorOf(id: string): string {
  const state = panels[id]
  return state != null && state.status === 'error' ? state.message : ''
}

/** 错误用例三件套的可展示条目(null 值渲染为 —,保持三段结构可对照)。 */
function caseEntries(
  errorCase: SubmissionDetailResult['errorCase'],
): Array<{ label: string; value: string; missing: boolean }> {
  const case_ = errorCase ?? {}
  return [
    { label: '输入', value: case_.input ?? '', missing: case_.input == null },
    { label: '期望输出', value: case_.expectedOutput ?? '', missing: case_.expectedOutput == null },
    { label: '实际输出', value: case_.actualOutput ?? '', missing: case_.actualOutput == null },
  ]
}

function extrasEntries(detail: SubmissionDetailResult): Array<[string, string]> {
  return Object.entries(detail.extras ?? {})
}

// ---------------------------------------------------------------------------
// WP40:详情面板 Details/Code 切换(本地 codeSnapshot 回看)
// ---------------------------------------------------------------------------

/** 面板内视图 tab;按行独立记录,收起再展开保持上次选择。 */
type DetailTab = 'details' | 'code'
const detailTabs = reactive<Record<string, DetailTab>>({})

function tabOf(id: string): DetailTab {
  return detailTabs[id] ?? 'details'
}

function setDetailTab(id: string, tab: DetailTab): void {
  detailTabs[id] = tab
}

/** 有无代码快照(空串/纯空白视为无;同理仅 ready 分支调用)。 */
function hasCode(id: string): boolean {
  const code = detailOf(id).code
  return code != null && code.trim() !== ''
}

/** Code 态高亮 HTML(hljs/escapeHtml 输出均已转义,v-html 无注入面)。 */
function highlightedCode(id: string): string {
  const detail = detailOf(id)
  return highlightCode(detail.code, detail.language)
}

/** 复制按钮回显(1.2s 后复位;剪贴板不可用时静默,手动选中复制兜底)。 */
const copiedId = ref<string | null>(null)
let copyTimer: ReturnType<typeof setTimeout> | null = null

async function copyCode(id: string): Promise<void> {
  try {
    await navigator.clipboard?.writeText(detailOf(id).code)
    copiedId.value = id
    if (copyTimer) clearTimeout(copyTimer)
    copyTimer = setTimeout(() => {
      copiedId.value = null
    }, 1200)
  } catch {
    // 忽略:非安全上下文/权限拒绝
  }
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
  if (copyTimer) clearTimeout(copyTimer)
  copyTimer = null
})
</script>

<template>
  <AppShell
    title="提交记录"
    :subtitle="challengeId
      ? `当前挑战工作区(${challengeId})的提交历史。`
      : '全部挑战工作区的提交历史。'"
  >
    <p v-if="challengeId" class="scope">
      已按挑战 <code>{{ challengeId }}</code> 过滤
    </p>

    <p v-if="error" class="state state--error" role="alert">
      {{ error }}
      <button type="button" class="state__retry" @click="reload">重试</button>
    </p>
    <p v-else-if="loading && rows.length === 0" class="state">正在加载提交记录…</p>
    <p v-else-if="!loading && rows.length === 0" class="state">
      还没有提交记录。在编辑器中使用「提交到 OJ」后,结果会出现在这里。
      <template v-if="remoteEnabled"><br />提示:统计页「同步历史」可拉取站内提交,拉取后在此混合展示。</template>
    </p>

    <table v-if="rows.length > 0" class="submissions" aria-label="提交记录列表">
      <thead>
        <tr>
          <th>题目</th>
          <th>语言</th>
          <th>判定</th>
          <th class="submissions__num">耗时</th>
          <th class="submissions__num">内存</th>
          <th>提交时间</th>
        </tr>
      </thead>
      <tbody>
        <template v-for="row in rows" :key="row.key">
          <template v-if="row.kind === 'local'">
            <template v-for="item in [row.item]" :key="item.id">
          <tr
            class="submissions__row"
            :class="{ 'submissions__row--expanded': expandedId === item.id }"
            :aria-expanded="expandedId === item.id"
            @click="toggleDetail(item.id)"
          >
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
          <tr v-if="expandedId === item.id" class="detail-row">
            <td colspan="6" class="detail-row__cell" @click.stop>
              <div v-if="panels[item.id]?.status === 'loading'" class="detail-state">正在加载提交详情…</div>
              <div
                v-else-if="panels[item.id]?.status === 'error'"
                class="detail-state detail-state--error"
                role="alert"
              >
                {{ errorOf(item.id) }}
                <button type="button" class="state__retry" @click="loadDetail(item.id)">重试</button>
              </div>
              <div v-else-if="panels[item.id]?.status === 'ready'" class="detail">
                <div class="detail__tabs" role="tablist" aria-label="提交详情视图">
                  <button
                    type="button"
                    role="tab"
                    class="detail__tab"
                    :class="{ 'detail__tab--active': tabOf(item.id) === 'details' }"
                    :aria-selected="tabOf(item.id) === 'details'"
                    @click="setDetailTab(item.id, 'details')"
                  >
                    详情
                  </button>
                  <button
                    type="button"
                    role="tab"
                    class="detail__tab"
                    :class="{ 'detail__tab--active': tabOf(item.id) === 'code' }"
                    :aria-selected="tabOf(item.id) === 'code'"
                    @click="setDetailTab(item.id, 'code')"
                  >
                    代码
                  </button>
                  <button
                    v-if="tabOf(item.id) === 'code' && hasCode(item.id)"
                    type="button"
                    class="detail__copy"
                    @click="copyCode(item.id)"
                  >
                    {{ copiedId === item.id ? '已复制' : '复制' }}
                  </button>
                </div>

                <template v-if="tabOf(item.id) === 'code'">
                  <p v-if="!hasCode(item.id)" class="detail__code-empty">
                    该提交没有保存代码快照。
                  </p>
                  <pre v-else class="detail__code">
                    <!-- eslint-disable-next-line vue/no-v-html — highlight.js 输出已转义,无用户可控标签 -->
                    <code class="hljs" v-html="highlightedCode(item.id)"></code>
                  </pre>
                </template>

                <template v-else>
                  <dl class="detail__meta">
                    <div class="detail__meta-item">
                      <dt>判定</dt>
                      <dd>
                        <span
                          class="verdict-badge"
                          :class="`verdict-badge--${verdictMeta(detailOf(item.id).verdict).tone}`"
                        >
                          {{ verdictMeta(detailOf(item.id).verdict).label }}
                        </span>
                      </dd>
                    </div>
                    <div class="detail__meta-item">
                      <dt>耗时</dt>
                      <dd>{{ formatMillis(detailOf(item.id).timeMs) }}</dd>
                    </div>
                    <div class="detail__meta-item">
                      <dt>内存</dt>
                      <dd>{{ formatMemoryKb(detailOf(item.id).memoryKb) }}</dd>
                    </div>
                    <div class="detail__meta-item">
                      <dt>语言</dt>
                      <dd>{{ detailOf(item.id).language }}</dd>
                    </div>
                    <div class="detail__meta-item">
                      <dt>提交时间</dt>
                      <dd>{{ formatTimestamp(detailOf(item.id).submittedAt) }}</dd>
                    </div>
                  </dl>

                  <p v-if="detailOf(item.id).message" class="detail__message">
                    {{ detailOf(item.id).message }}
                  </p>

                  <div v-if="detailOf(item.id).errorCase" class="detail__cases">
                    <section
                      v-for="entry in caseEntries(detailOf(item.id).errorCase)"
                      :key="entry.label"
                      class="detail__case"
                      :class="{ 'detail__case--missing': entry.missing }"
                    >
                      <h4 class="detail__case-label">{{ entry.label }}</h4>
                      <pre class="detail__case-value">{{ entry.missing ? '—' : entry.value }}</pre>
                    </section>
                  </div>

                  <dl v-if="extrasEntries(detailOf(item.id)).length > 0" class="detail__extras">
                    <div v-for="[key, value] in extrasEntries(detailOf(item.id))" :key="key" class="detail__extra">
                      <dt>{{ key }}</dt>
                      <dd>{{ value }}</dd>
                    </div>
                  </dl>

                  <a
                    v-if="detailOf(item.id).viewUrl"
                    class="detail__view-url"
                    :href="detailOf(item.id).viewUrl ?? undefined"
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    在 {{ detailOf(item.id).platformId }} 查看 ↗
                  </a>
                </template>
              </div>
            </td>
          </tr>
            </template>
          </template>
          <template v-else>
            <tr
              class="submissions__row submissions__row--remote"
              :class="{ 'submissions__row--expanded': expandedId === row.key }"
              :aria-expanded="expandedId === row.key"
              @click="toggleRemote(row.key)"
            >
              <td class="submissions__problem">
                <span class="submissions__name">{{ row.item.title }}</span>
                <span class="submissions__meta">
                  {{ row.item.platformId }} · #{{ row.item.remoteId }}
                  <span class="submissions__remote-badge">站内</span>
                </span>
              </td>
              <td>{{ row.item.lang }}</td>
              <td>
                <span
                  class="verdict-badge"
                  :class="`verdict-badge--${remoteStatusMeta(row.item.statusDisplay).tone}`"
                >
                  {{ remoteStatusMeta(row.item.statusDisplay).label }}
                </span>
              </td>
              <td class="submissions__num">{{ formatMillis(row.item.runtimeMs) }}</td>
              <td class="submissions__num">{{ formatMemoryKb(row.item.memoryKb) }}</td>
              <td class="submissions__time">{{ formatTimestamp(row.item.submittedAt) }}</td>
            </tr>
            <tr v-if="expandedId === row.key" class="detail-row">
              <td colspan="6" class="detail-row__cell" @click.stop>
                <div class="detail detail--remote">
                  <dl class="detail__meta">
                    <div class="detail__meta-item">
                      <dt>题目</dt>
                      <dd>{{ row.item.title }}({{ row.item.titleSlug }})</dd>
                    </div>
                    <div class="detail__meta-item">
                      <dt>判定</dt>
                      <dd>
                        <span
                          class="verdict-badge"
                          :class="`verdict-badge--${remoteStatusMeta(row.item.statusDisplay).tone}`"
                        >
                          {{ remoteStatusMeta(row.item.statusDisplay).label }}
                        </span>
                      </dd>
                    </div>
                    <div class="detail__meta-item">
                      <dt>耗时</dt>
                      <dd>{{ formatMillis(row.item.runtimeMs) }}</dd>
                    </div>
                    <div class="detail__meta-item">
                      <dt>内存</dt>
                      <dd>{{ formatMemoryKb(row.item.memoryKb) }}</dd>
                    </div>
                    <div class="detail__meta-item">
                      <dt>语言</dt>
                      <dd>{{ row.item.lang }}</dd>
                    </div>
                    <div class="detail__meta-item">
                      <dt>提交时间</dt>
                      <dd>{{ formatTimestamp(row.item.submittedAt) }}</dd>
                    </div>
                  </dl>
                  <a
                    class="detail__view-url"
                    :href="remoteViewUrl(row.item)"
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    在 {{ row.item.platformId }} 查看 ↗
                  </a>
                </div>
              </td>
            </tr>
          </template>
        </template>
      </tbody>
    </table>

    <div v-if="rows.length > 0" class="pager">
      <span class="pager__count">已加载 {{ rows.length }} 条</span>
      <button
        v-if="canLoadMore"
        type="button"
        class="pager__more"
        :disabled="loading"
        @click="loadPage(true)"
      >
        {{ loading ? '正在加载…' : '加载更多' }}
      </button>
      <span v-else class="pager__done">已到底部</span>
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

.submissions__row {
  cursor: pointer;
}

.submissions__row:hover {
  background: var(--cq-panel-raised);
}

.submissions__row--expanded {
  background: var(--cq-panel-raised);
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

/* -------------------------------------------------------------------------
 * WP36:详情面板(行下方展开;等宽三件套对照 + extras 键值 + 站外链)
 * ------------------------------------------------------------------------- */

.detail-row__cell {
  background: var(--cq-panel);
  padding: 12px 14px;
}

.detail-state {
  font-size: 12px;
  color: var(--cq-muted);
}

.detail-state--error {
  color: var(--cq-danger);
}

.detail__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 28px;
  margin: 0;
}

.detail__meta-item dt {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--cq-muted);
  margin-bottom: 2px;
}

.detail__meta-item dd {
  margin: 0;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.detail__message {
  margin: 12px 0 0;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid var(--cq-danger);
  color: var(--cq-danger);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 220px;
  overflow: auto;
}

.detail__cases {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
  margin-top: 12px;
}

.detail__case-label {
  margin: 0 0 4px;
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--cq-muted);
}

.detail__case-value {
  margin: 0;
  padding: 8px 10px;
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow: auto;
  user-select: text;
}

.detail__case--missing .detail__case-value {
  color: var(--cq-muted);
}

.detail__extras {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 22px;
  margin: 12px 0 0;
}

.detail__extra dt {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--cq-muted);
}

.detail__extra dd {
  margin: 0;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.detail__view-url {
  display: inline-block;
  margin-top: 12px;
  font-size: 12px;
  color: var(--cq-accent);
}

/* -------------------------------------------------------------------------
 * WP40:Details/Code 切换(Code 态 = 本地 codeSnapshot 高亮回看)
 * ------------------------------------------------------------------------- */

.detail__tabs {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
}

.detail__tab {
  padding: 2px 12px;
  border: 1px solid var(--cq-border);
  border-radius: 999px;
  background: transparent;
  color: var(--cq-muted);
  font-size: 11px;
  cursor: pointer;
}

.detail__tab:hover {
  color: var(--cq-fg);
}

.detail__tab--active {
  background: var(--cq-panel-raised);
  border-color: var(--cq-accent);
  color: var(--cq-fg);
}

.detail__copy {
  margin-left: auto;
  padding: 2px 10px;
  border: 1px solid var(--cq-border);
  border-radius: 6px;
  background: var(--cq-panel-raised);
  color: var(--cq-fg);
  font-size: 11px;
  cursor: pointer;
}

.detail__copy:hover {
  border-color: var(--cq-accent);
}

.detail__code {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--cq-border);
  border-radius: 8px;
  background: var(--cq-panel-raised);
  max-height: 420px;
  overflow: auto;
}

.detail__code code {
  display: block;
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  /* 不折行,靠 pre 横向滚动 */
  white-space: pre;
}

.detail__code-empty {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--cq-muted);
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
/* 站内历史行:来源徽标 + 轻微底色区分本地行(亮暗走 CSS 变量)。 */
.submissions__remote-badge {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 18px;
  color: var(--cq-muted);
  background: var(--cq-panel);
  border: 1px solid var(--cq-border);
}

.submissions__row--remote .submissions__name {
  color: var(--cq-muted);
}
</style>
