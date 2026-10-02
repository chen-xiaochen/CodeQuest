/**
 * WP29:stats 页本地 API 客户端。
 *
 * 页面与 ResourceHttpServer 同源,`/api/stats` 根绝对路径请求;
 * 错误统一抛 [ApiError](复用 lib/api 的错误类型,保持各页一致)。
 * stats 页目前是唯一消费方,故类型与客户端收在本页目录,不进公共 lib/api.ts。
 */
import { ApiError } from '../../lib/api'

export interface StatsSummary {
  totalSubmissions: number
  acceptedSubmissions: number
  /** AC 率,0..1 小数。 */
  acceptanceRate: number
  currentStreakDays: number
  longestStreakDays: number
}

export interface DailyCount {
  /** 本地日期 ISO 字符串(yyyy-MM-dd)。 */
  date: string
  count: number
}

export interface StatsResult {
  /** 过滤的平台 id;缺省 = 全部平台。 */
  platform?: string | null
  /** 热力图窗口天数(响应实际采用值,缺省 365)。 */
  days: number
  summary: StatsSummary
  /** 今天往前 days 天(含今天),零提交日占位,升序。 */
  heatmap: DailyCount[]
  /** 今天往前 min(days, 30) 天,升序。 */
  trend: DailyCount[]
  /**
   * 1.7.0 F2(WP61 起):站内提交历史已同步条数(remote_submission 计数,
   * 两表合并统计;旧版端点无此字段按 0)。
   */
  remoteSyncedCount?: number | null
}

export interface StatsQuery {
  platform?: string
  days?: number
}

/** WP29:刷题统计(summary + 热力图 + 趋势,来自本地库聚合)。 */
export async function fetchStats(query: StatsQuery = {}): Promise<StatsResult> {
  const params = new URLSearchParams()
  if (query.platform && query.platform.trim().length > 0) {
    params.set('platform', query.platform.trim())
  }
  if (query.days !== undefined) {
    params.set('days', String(query.days))
  }
  const suffix = Array.from(params.keys()).length > 0 ? `?${params.toString()}` : ''
  const response = await fetch(`/api/stats${suffix}`, { headers: { Accept: 'application/json' } })
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
  return (await response.json()) as StatsResult
}
