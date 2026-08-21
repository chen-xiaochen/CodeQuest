/**
 * WP49:题库页平台门控纯函数(独立于组件,可单测)。
 *
 * - [filterPlatformsByAuth]:fetchPlatforms ⋈ fetchAuthStatus,仅保留 loggedIn=true 的平台;
 * - [pickDefaultPlatform]:过滤结果非空取第一项;全未登录回落力扣中国。
 */

import type { AuthStatusItem, PlatformSummary } from '../../lib/api'

/**
 * 全未登录时的兜底平台 id(力扣中国)。取 /api/platforms 实际返回值
 * (LeetCodeVariant.kt: LEETCODE_CN(PlatformId("leetcodecn"))),勿写成 leetcode-cn。
 */
export const FALLBACK_PLATFORM_ID = 'leetcodecn'

/** 已登录平台内连接:authItems 中 loggedIn=true 的平台才保留(保持原顺序)。 */
export function filterPlatformsByAuth(
  platforms: PlatformSummary[],
  authItems: AuthStatusItem[],
): PlatformSummary[] {
  const loggedIn = new Set(
    authItems.filter((item) => item.loggedIn).map((item) => item.platform),
  )
  return platforms.filter((platform) => loggedIn.has(platform.id))
}

/** 默认选中平台:已登录过滤结果第一项;全空回落力扣中国兜底。 */
export function pickDefaultPlatform(filtered: PlatformSummary[]): string {
  return filtered[0]?.id ?? FALLBACK_PLATFORM_ID
}
