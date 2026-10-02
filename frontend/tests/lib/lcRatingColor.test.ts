/**
 * WP63:评分五档着色/区间过滤/排序映射(阈值权威来源 spec §1.4)——
 * 分差与绝对两套边界值(左闭右开)、无分退化不着色、区间空值与闭边界、排序无分沉底。
 */
import { describe, expect, it } from 'vitest'
import {
  RATING_TONE_LABELS,
  filterByRating,
  formatRating,
  ratingTone,
  sortByRating,
} from '../../src/lib/lcRatingColor'

describe('lcRatingColor', () => {
  describe('ratingTone(分差模式)', () => {
    const user = 1500

    it('按 rating − userRating 落五档(档界左闭右开)', () => {
      expect(ratingTone(1299, user)).toBe('instant') // diff -201
      expect(ratingTone(1300, user)).toBe('warmup') // diff -200(闭,入热身)
      expect(ratingTone(1449, user)).toBe('warmup') // diff -51
      expect(ratingTone(1450, user)).toBe('normal') // diff -50(闭,入普通)
      expect(ratingTone(1549, user)).toBe('normal') // diff +49
      expect(ratingTone(1550, user)).toBe('strain') // diff +50(闭,入吃力)
      expect(ratingTone(1699, user)).toBe('strain') // diff +199
      expect(ratingTone(1700, user)).toBe('retreat') // diff +200(闭,入劝退)
    })

    it('支持小数评分(zerotrac/竞赛分 REAL 口径)', () => {
      expect(ratingTone(1300.5, user)).toBe('warmup')
      expect(ratingTone(1699.5, user)).toBe('strain')
      expect(ratingTone(1299.9, user)).toBe('instant')
    })
  })

  describe('ratingTone(绝对退化)', () => {
    it('无用户竞赛分时按绝对分五档', () => {
      expect(ratingTone(1399, null)).toBe('instant')
      expect(ratingTone(1400, null)).toBe('warmup')
      expect(ratingTone(1799, null)).toBe('warmup')
      expect(ratingTone(1800, null)).toBe('normal')
      expect(ratingTone(2199, null)).toBe('normal')
      expect(ratingTone(2200, null)).toBe('strain')
      expect(ratingTone(2599, null)).toBe('strain')
      expect(ratingTone(2600, null)).toBe('retreat')
    })

    it('题目无分(null/undefined)恒 null,不着色', () => {
      expect(ratingTone(null, 1500)).toBeNull()
      expect(ratingTone(undefined, null)).toBeNull()
    })
  })

  it('RATING_TONE_LABELS 五档中文标签齐全', () => {
    expect(RATING_TONE_LABELS).toEqual({
      instant: '秒杀',
      warmup: '热身',
      normal: '普通',
      strain: '吃力',
      retreat: '劝退',
    })
  })

  describe('formatRating', () => {
    it('无分显示 -;有分四舍五入整数', () => {
      expect(formatRating(null)).toBe('-')
      expect(formatRating(undefined)).toBe('-')
      expect(formatRating(1533)).toBe('1533')
      expect(formatRating(1533.4)).toBe('1533')
      expect(formatRating(1533.5)).toBe('1534')
    })
  })

  describe('filterByRating', () => {
    const rows = [
      { id: 'low', rating: 1200 },
      { id: 'mid', rating: 1800 },
      { id: 'high', rating: 2600 },
      { id: 'none', rating: null },
      { id: 'missing' },
    ]

    it('双边为空返回全部(含无分行,清空恢复语义)', () => {
      expect(filterByRating(rows, null, null)).toEqual(rows)
    })

    it('仅 min:保留 ≥ min,排除无分行', () => {
      expect(filterByRating(rows, 1800, null).map((row) => row.id)).toEqual(['mid', 'high'])
    })

    it('仅 max:保留 ≤ max,排除无分行', () => {
      expect(filterByRating(rows, null, 1799).map((row) => row.id)).toEqual(['low'])
    })

    it('双边闭区间(边界值本身命中)', () => {
      expect(filterByRating(rows, 1200, 1800).map((row) => row.id)).toEqual(['low', 'mid'])
    })

    it('空数组安全', () => {
      expect(filterByRating([], 0, 4000)).toEqual([])
    })
  })

  describe('sortByRating', () => {
    const rows = [
      { id: 'mid', rating: 1800 },
      { id: 'none', rating: null },
      { id: 'high', rating: 2600 },
      { id: 'low', rating: 1200 },
    ]

    it("'' 返回原数组引用(默认序零开销)", () => {
      expect(sortByRating(rows, '')).toBe(rows)
    })

    it('升序,无分恒沉底', () => {
      expect(sortByRating(rows, 'rating-asc').map((row) => row.id)).toEqual([
        'low',
        'mid',
        'high',
        'none',
      ])
    })

    it('降序,无分仍沉底', () => {
      expect(sortByRating(rows, 'rating-desc').map((row) => row.id)).toEqual([
        'high',
        'mid',
        'low',
        'none',
      ])
    })

    it('拷贝排序,不改变原数组', () => {
      const before = [...rows]
      sortByRating(rows, 'rating-asc')
      expect(rows).toEqual(before)
    })
  })
})
