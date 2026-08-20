/**
 * 本地 REST 端点客户端(WP20)。
 *
 * 页面与 ResourceHttpServer 同源(http://127.0.0.1:<port>/<page>/),
 * 一律用根绝对路径 `/api/...` 发起请求;错误统一抛 [ApiError](带 HTTP 状态与
 * 后端错误码,如 auth-expired / rate-limited),页面据此渲染提示。
 */

export interface PlatformSummary {
  id: string
  displayName: string
}

/** WP38:平台登录快照(/api/auth/status);userId 恒 null(SPI 无只读 session 契约)。 */
export interface AuthStatusItem {
  platform: string
  loggedIn: boolean
  userId: string | null
}

export interface ProblemListItem {
  remoteId: string
  title: string
  difficulty: string
  url: string
  tags: string[]
}

export interface ProblemListResult {
  platform: string
  offset: number
  limit: number
  items: ProblemListItem[]
  hasMore: boolean
}

export interface ProblemDetail {
  platform: string
  remoteId: string
  title: string
  difficulty: string
  url: string
  content: string
  contentFormat: 'HTML' | 'MARKDOWN'
  source: 'cache' | 'remote'
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(
    status: number,
    code: string,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: { Accept: 'application/json' } })
  return unwrap<T>(response)
}

/** WP23:POST/DELETE 请求(getJson 同款错误解包)。 */
async function sendJson<T>(
  path: string,
  method: 'POST' | 'DELETE',
  body?: string,
): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: {
      Accept: 'application/json',
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body,
  })
  return unwrap<T>(response)
}

async function unwrap<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let code = 'http-error'
    let message = `HTTP ${response.status}`
    try {
      const body = (await response.json()) as { error?: string; message?: string }
      code = body.error ?? code
      message = body.message ?? message
    } catch {
      // 非 JSON 错误体:保留 HTTP 状态文案
    }
    throw new ApiError(response.status, code, message)
  }
  return (await response.json()) as T
}

export function fetchPlatforms(): Promise<PlatformSummary[]> {
  return getJson<{ platforms: PlatformSummary[] }>('/api/platforms').then((body) => body.platforms)
}

/** WP38:全部平台登录快照(同步,不触发登录流程)。 */
export function fetchAuthStatus(): Promise<AuthStatusItem[]> {
  return getJson<{ items: AuthStatusItem[] }>('/api/auth/status').then((body) => body.items)
}

export interface ProblemListQuery {
  platform: string
  keyword?: string
  difficulty?: string
  tags?: string[]
  limit?: number
  offset?: number
}

export function fetchProblems(query: ProblemListQuery): Promise<ProblemListResult> {
  const params = new URLSearchParams()
  params.set('platform', query.platform)
  if (query.keyword && query.keyword.trim().length > 0) {
    params.set('keyword', query.keyword.trim())
  }
  if (query.difficulty) {
    params.set('difficulty', query.difficulty)
  }
  if (query.tags && query.tags.length > 0) {
    params.set('tags', query.tags.join(','))
  }
  if (query.limit !== undefined) {
    params.set('limit', String(query.limit))
  }
  if (query.offset !== undefined && query.offset > 0) {
    params.set('offset', String(query.offset))
  }
  return getJson<ProblemListResult>(`/api/problems?${params.toString()}`)
}

export function fetchProblemDetail(
  platform: string,
  remoteId: string,
): Promise<ProblemDetail> {
  const params = new URLSearchParams({ platform })
  return getJson<ProblemDetail>(
    `/api/problem/${encodeURIComponent(remoteId)}?${params.toString()}`,
  )
}

/** WP26:题解引用(WP31 起点击在应用内展开正文;url 为站外浏览器兜底出口)。 */
export interface SolutionArticle {
  id: string
  title: string
  url: string
}

export interface SolutionListResult {
  platform: string
  remoteId: string
  items: SolutionArticle[]
}

/** WP31:题解文章正文(contentFormat 决定 MD/HTML 渲染管线)。 */
export interface SolutionArticleContent {
  platform: string
  remoteId: string
  id: string
  title: string
  content: string
  contentFormat: 'HTML' | 'MARKDOWN'
}

/** WP26:题解引用列表(SolutionViewCapable 平台,如洛谷;可能要求登录态)。 */
export function fetchProblemSolutions(
  platform: string,
  remoteId: string,
): Promise<SolutionListResult> {
  const params = new URLSearchParams({ platform })
  return getJson<SolutionListResult>(
    `/api/problem/${encodeURIComponent(remoteId)}/solutions?${params.toString()}`,
  )
}

/** WP31:单篇题解正文(SPI fetchSolutionContent 纯透传,不落库)。 */
export function fetchSolutionArticleContent(
  platform: string,
  remoteId: string,
  solutionId: string,
): Promise<SolutionArticleContent> {
  const params = new URLSearchParams({ platform })
  return getJson<SolutionArticleContent>(
    `/api/problem/${encodeURIComponent(remoteId)}/solutions/${encodeURIComponent(solutionId)}?${params.toString()}`,
  )
}

/** 提交判定(Kotlin 侧 SubmissionResult 多态 JSON,discriminator `type`)。 */
export interface SubmissionVerdict {
  type: string
  /** PartialScore 得分比例 (0, 1)。 */
  score?: number
  /** Unknown 平台原文。 */
  raw?: string
}

export interface SubmissionListItem {
  id: string
  challengeId: string
  /** challenge 主档被删除时缺省,前端回退显示 challengeId。 */
  challengeName?: string
  platformId: string
  remoteSubmissionId: string
  language: string
  verdict: SubmissionVerdict
  timeMs?: number | null
  memoryKb?: number | null
  /** ISO-8601 即时字符串。 */
  submittedAt: string
}

export interface SubmissionListResult {
  /** 过滤的 challenge id;缺省 = 全部工作区。 */
  challengeId?: string | null
  offset: number
  limit: number
  items: SubmissionListItem[]
  hasMore: boolean
}

export interface SubmissionListQuery {
  challengeId?: string
  limit?: number
  offset?: number
}

/** WP21:提交历史列表(按提交时间倒序,来自本地库)。 */
export function fetchSubmissions(query: SubmissionListQuery): Promise<SubmissionListResult> {
  const params = new URLSearchParams()
  if (query.challengeId && query.challengeId.trim().length > 0) {
    params.set('challengeId', query.challengeId.trim())
  }
  if (query.limit !== undefined) {
    params.set('limit', String(query.limit))
  }
  if (query.offset !== undefined && query.offset > 0) {
    params.set('offset', String(query.offset))
  }
  return getJson<SubmissionListResult>(`/api/submissions?${params.toString()}`)
}

/** WP36:错误用例三件套(平台侧,如 LeetCode WA 的 input/期望/实际)。 */
export interface SubmissionErrorCase {
  input?: string | null
  expectedOutput?: string | null
  actualOutput?: string | null
}

/**
 * WP36:单次提交详情(本地快照 + 平台详情合并)。
 * verdict/耗时/内存/语言为「平台侧最新值 ?: 本地快照」;message/三件套/extras/
 * viewUrl 仅平台给出时存在;code 为本地代码快照(WP40 代码回看复用)。
 */
export interface SubmissionDetailResult {
  id: string
  challengeId: string
  /** challenge 主档被删除时缺省。 */
  challengeName?: string | null
  platformId: string
  remoteSubmissionId: string
  language: string
  verdict: SubmissionVerdict
  timeMs?: number | null
  memoryKb?: number | null
  /** ISO-8601 即时字符串。 */
  submittedAt: string
  code: string
  message?: string | null
  errorCase?: SubmissionErrorCase | null
  /** 平台特有键值对(如 testcases "12/14"、runtimePercentile "95.20%")。 */
  extras?: Record<string, string> | null
  /** 平台网站提交详情页(浏览器兜底出口)。 */
  viewUrl?: string | null
}

/** WP36:提交详情(平台重拉,不落库;平台错误按既有错误码抛 ApiError)。 */
export function fetchSubmissionDetail(id: string): Promise<SubmissionDetailResult> {
  return getJson<SubmissionDetailResult>(`/api/submission/${encodeURIComponent(id)}/detail`)
}

// ---------------------------------------------------------------------------
// WP23:竞赛(contest 页数据源;ContestCapable 平台经 /api/contests)
// ---------------------------------------------------------------------------

/** 竞赛阶段(Kotlin ContestPhase 枚举名)。 */
export type ContestPhase =
  | 'BEFORE'
  | 'CODING'
  | 'PENDING_SYSTEM_TEST'
  | 'SYSTEM_TEST'
  | 'FINISHED'

export interface ContestSummary {
  remoteId: string
  title: string
  /** ISO-8601 即时字符串。 */
  startTime: string
  durationMinutes: number
  phase: ContestPhase
  url: string
}

export interface ContestProblemRef {
  remoteId: string
  index?: string | null
  title: string
}

export interface ContestDetailResult extends ContestSummary {
  platform: string
  problems: ContestProblemRef[]
  joinable: boolean
}

export interface ContestReminderItem {
  platform: string
  remoteId: string
  /** ISO-8601 即时字符串。 */
  remindAt: string
  notified: boolean
}

export interface ContestSubscribeRequest {
  platform: string
  remoteId: string
  /** ISO-8601 即时字符串(来自列表项 startTime)。 */
  startTime: string
  /** 提前量(分钟);缺省后端 24h。 */
  leadTimeMinutes?: number
}

/** 支持(实现 ContestCapable)竞赛查询的平台。 */
export function fetchContestPlatforms(): Promise<PlatformSummary[]> {
  return getJson<{ platforms: PlatformSummary[] }>('/api/contests').then((body) => body.platforms)
}

export function fetchContests(platform: string): Promise<ContestSummary[]> {
  const params = new URLSearchParams({ platform })
  return getJson<{ platform: string; items: ContestSummary[] }>(`/api/contests?${params}`).then(
    (body) => body.items,
  )
}

export function fetchContestReminders(platform: string): Promise<ContestReminderItem[]> {
  const params = new URLSearchParams({ platform })
  return getJson<{ platform: string; items: ContestReminderItem[] }>(
    `/api/contests/reminders?${params}`,
  ).then((body) => body.items)
}

export function subscribeContestReminder(request: ContestSubscribeRequest): Promise<ContestReminderItem> {
  return sendJson<ContestReminderItem>('/api/contests/reminders', 'POST', JSON.stringify(request))
}

export function unsubscribeContestReminder(
  platform: string,
  remoteId: string,
): Promise<{ deleted: boolean }> {
  const params = new URLSearchParams({ platform, remoteId })
  return sendJson(`/api/contests/reminders?${params}`, 'DELETE')
}

// ---------------------------------------------------------------------------
// WP30:跨题全文检索(search 页数据源;/api/search + /api/tags)
// ---------------------------------------------------------------------------

/** 关键字命中来源:标题/remoteId、题面、题解笔记;浏览模式为空数组。 */
export type SearchMatchSource = 'title' | 'content' | 'note'

export interface SearchItem {
  platform: string
  remoteId: string
  title: string
  difficulty: string
  url: string
  tags: string[]
  matchedSources: SearchMatchSource[]
}

export interface SearchResult {
  /** 本次检索关键字;缺省 = 浏览模式。 */
  keyword?: string | null
  /** 过滤的平台 id;缺省 = 全部平台。 */
  platform?: string | null
  /** 本次过滤的标签(AND 语义)。 */
  tags: string[]
  offset: number
  limit: number
  /** 过滤后命中总数(分页前)。 */
  total: number
  items: SearchItem[]
  hasMore: boolean
}

export interface SearchQuery {
  keyword?: string
  tags?: string[]
  platform?: string
  limit?: number
  offset?: number
}

/** WP30:三源聚合检索(标题/题面/题解笔记 + 标签 AND + 平台过滤)。 */
export function fetchSearch(query: SearchQuery): Promise<SearchResult> {
  const params = new URLSearchParams()
  if (query.keyword && query.keyword.trim().length > 0) {
    params.set('keyword', query.keyword.trim())
  }
  if (query.tags && query.tags.length > 0) {
    params.set('tags', query.tags.join(','))
  }
  if (query.platform) {
    params.set('platform', query.platform)
  }
  if (query.limit !== undefined) {
    params.set('limit', String(query.limit))
  }
  if (query.offset !== undefined && query.offset > 0) {
    params.set('offset', String(query.offset))
  }
  return getJson<SearchResult>(`/api/search?${params.toString()}`)
}

export interface TagSummary {
  name: string
  /** 关联题目数。 */
  count: number
}

/** WP30:标签清单(名称 + 关联题数,题数降序);platform 过滤可选。 */
export function fetchTags(platform?: string): Promise<TagSummary[]> {
  const params = new URLSearchParams()
  if (platform) {
    params.set('platform', platform)
  }
  const qs = params.toString()
  return getJson<{ tags: TagSummary[] }>(`/api/tags${qs.length > 0 ? `?${qs}` : ''}`).then(
    (body) => body.tags,
  )
}

// ---------------------------------------------------------------------------
// WP39:题面自定义 CSS(/api/custom.css,text/css 透传,未设置空体)
// ---------------------------------------------------------------------------

/**
 * WP39:拉取题面自定义 CSS。非 JSON 端点,失败/非 2xx 一律回空串
 * (自定义 CSS 是附属能力,页面侧静默跳过注入,不抛 ApiError)。
 */
export async function fetchCustomCss(): Promise<string> {
  const response = await fetch('/api/custom.css')
  if (!response.ok) return ''
  return response.text()
}
