/**
 * WP49:题库页平台门控纯函数测试——已登录过滤/全空回落力扣中国/
 * 登录后重新过滤出现/快照含未知平台不误伤。
 */
import { describe, expect, it } from 'vitest'
import {
  FALLBACK_PLATFORM_ID,
  filterPlatformsByAuth,
  pickDefaultPlatform,
} from '../../src/pages/problems/platformGate'
import type { AuthStatusItem, PlatformSummary } from '../../src/lib/api'

const PLATFORMS: PlatformSummary[] = [
  { id: 'leetcode', displayName: 'LeetCode' },
  { id: 'leetcodecn', displayName: '力扣' },
  { id: 'codeforces', displayName: 'Codeforces' },
  { id: 'atcoder', displayName: 'AtCoder' },
]

function auth(platform: string, loggedIn: boolean, userId: string | null = null): AuthStatusItem {
  return { platform, loggedIn, userId }
}

describe('platformGate', () => {
  it('filterPlatformsByAuth 仅保留 loggedIn=true 的平台并保持原顺序', () => {
    const filtered = filterPlatformsByAuth(PLATFORMS, [
      auth('codeforces', true, 'cf_user'),
      auth('leetcode', false),
      auth('atcoder', true),
    ])
    expect(filtered.map((entry) => entry.id)).toEqual(['codeforces', 'atcoder'])
  })

  it('filterPlatformsByAuth 全未登录返回空数组', () => {
    expect(filterPlatformsByAuth(PLATFORMS, [auth('leetcode', false)])).toEqual([])
    expect(filterPlatformsByAuth(PLATFORMS, [])).toEqual([])
  })

  it('filterPlatformsByAuth 忽略快照中平台清单不存在的条目', () => {
    const filtered = filterPlatformsByAuth(PLATFORMS, [
      auth('luogu', true, 'lg'),
      auth('leetcodecn', true),
    ])
    expect(filtered.map((entry) => entry.id)).toEqual(['leetcodecn'])
  })

  it('pickDefaultPlatform 非空取第一项', () => {
    expect(pickDefaultPlatform(PLATFORMS)).toBe('leetcode')
    expect(pickDefaultPlatform([PLATFORMS[2]])).toBe('codeforces')
  })

  it('pickDefaultPlatform 全空回落力扣中国(leetcodecn)', () => {
    expect(pickDefaultPlatform([])).toBe(FALLBACK_PLATFORM_ID)
    expect(FALLBACK_PLATFORM_ID).toBe('leetcodecn')
  })

  it('登录后重新过滤:同一输入随快照变化出现新平台', () => {
    // 首次:仅 codeforces 登录 → 过滤结果 [codeforces]
    const before = filterPlatformsByAuth(PLATFORMS, [auth('leetcode', false), auth('codeforces', true)])
    expect(pickDefaultPlatform(before)).toBe('codeforces')
    // 登录 leetcode 后重跑:leetcode 出现且居首(按平台清单顺序)
    const after = filterPlatformsByAuth(PLATFORMS, [auth('leetcode', true), auth('codeforces', true)])
    expect(pickDefaultPlatform(after)).toBe('leetcode')
  })
})
