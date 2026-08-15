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
