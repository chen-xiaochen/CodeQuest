/**
 * 1.7.0 WP63:题库评分列工具(五档着色/区间过滤/排序)。
 *
 * 阈值权威来源:docs/superpowers/specs/2026-10-02-lc-p0-four-features-design.md §1.4——
 * - 分差模式(有用户竞赛分):diff = rating − userRating,
 *   <-200 秒杀 / [-200,-50) 热身 / [-50,50) 普通 / [50,200) 吃力 / ≥200 劝退;
 * - 绝对退化(无用户分):<1400 / 1400-1799 / 1800-2199 / 2200-2599 / ≥2600 同五色;
 * - 无分题目 tone = null(不着色、不参与区间过滤,排序沉底)。
 *
 * 五色载体为评分列文字色(CSS 类 rating-num--<tone>,变量定义在 theme.css)。
 */

/** 着色模式:diff=分差(默认)/absolute=绝对/off=关闭。 */
export type RatingColorMode = 'diff' | 'absolute' | 'off'

/** 五档色调键(与 CSS 类 rating-num--<tone> 同名)。 */
export type RatingTone = 'instant' | 'warmup' | 'normal' | 'strain' | 'retreat'

/** 五档中文标签(行 title 提示)。 */
export const RATING_TONE_LABELS: Record<RatingTone, string> = {
  instant: '秒杀',
  warmup: '热身',
  normal: '普通',
  strain: '吃力',
  retreat: '劝退',
}

/** 分档表:上界左闭右开(值 < upper 即入档),末档 +∞ 兜底。 */
type RatingBand = ReadonlyArray<{ upper: number; tone: RatingTone }>

/** 分差五档(diff = rating − userRating)。 */
const DIFF_BANDS: RatingBand = [
  { upper: -200, tone: 'instant' },
  { upper: -50, tone: 'warmup' },
  { upper: 50, tone: 'normal' },
  { upper: 200, tone: 'strain' },
  { upper: Number.POSITIVE_INFINITY, tone: 'retreat' },
]

/** 绝对分五档(无用户竞赛分时退化)。 */
const ABS_BANDS: RatingBand = [
  { upper: 1400, tone: 'instant' },
  { upper: 1800, tone: 'warmup' },
  { upper: 2200, tone: 'normal' },
  { upper: 2600, tone: 'strain' },
  { upper: Number.POSITIVE_INFINITY, tone: 'retreat' },
]

/** 行评分字段形态(/api/problems 行 rating;无分 null/缺省)。 */
export interface RatingFields {
  rating?: number | null
}

/**
 * 评分 → 五档色调:无分 → null;有用户竞赛分走分差档,否则绝对档
 * (着色模式 off 由调用方前置判断,不在此函数内)。
 */
export function ratingTone(
  rating: number | null | undefined,
  userRating: number | null | undefined,
): RatingTone | null {
  if (rating == null) return null
  const score = userRating != null ? rating - userRating : rating
  const bands = userRating != null ? DIFF_BANDS : ABS_BANDS
  return (bands.find((band) => score < band.upper) ?? bands[bands.length - 1]).tone
}

/** 评分展示:无分 '-';有分四舍五入整数(竞赛分惯例整数口径)。 */
export function formatRating(rating: number | null | undefined): string {
  return rating == null ? '-' : String(Math.round(rating))
}

/** 区间过滤(本地,仅已加载项):任一边界激活即排除无分行;边界含等号;null 边界 = 不限。 */
export function filterByRating<T extends RatingFields>(
  items: T[],
  min: number | null,
  max: number | null,
): T[] {
  if (min === null && max === null) return items
  return items.filter(
    (item) =>
      item.rating != null &&
      (min === null || item.rating >= min) &&
      (max === null || item.rating <= max),
  )
}

/** 评分排序(本地):'' = 默认序(原样返回);无分题目升/降序均恒沉底。 */
export type RatingSortOrder = '' | 'rating-asc' | 'rating-desc'

export function sortByRating<T extends RatingFields>(items: T[], order: RatingSortOrder): T[] {
  if (order === '') return items
  const direction = order === 'rating-asc' ? 1 : -1
  return [...items].sort((a, b) => {
    if (a.rating == null || b.rating == null) {
      if (a.rating == null && b.rating == null) return 0
      return a.rating == null ? 1 : -1
    }
    return (a.rating - b.rating) * direction
  })
}
