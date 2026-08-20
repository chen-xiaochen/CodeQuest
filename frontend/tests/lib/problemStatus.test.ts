/**
 * WP37:刷题状态图标与本地筛选——status/paidOnly → 图标种类映射(🔒 优先)、
 * 缺省回退 UNSOLVED、已加载项本地过滤。
 */
import { describe, expect, it } from 'vitest'
import {
  filterByStatus,
  statusIconOf,
  statusOf,
  type ProblemStatusFields,
} from '../../src/lib/problemStatus'

describe('problemStatus', () => {
  describe('statusIconOf', () => {
    it('maps AC / TRIED / UNSOLVED to distinct icon kinds with titles', () => {
      expect(statusIconOf({ status: 'AC' })).toEqual({ kind: 'ac', title: 'Solved' })
      expect(statusIconOf({ status: 'TRIED' })).toEqual({ kind: 'tried', title: 'Tried' })
      expect(statusIconOf({ status: 'UNSOLVED' })).toEqual({ kind: 'unsolved', title: '' })
    })

    it('falls back to UNSOLVED when status is missing (old payloads)', () => {
      expect(statusIconOf({}).kind).toBe('unsolved')
      expect(statusIconOf({ status: undefined, paidOnly: false }).kind).toBe('unsolved')
    })

    it('prefers the lock icon for paidOnly regardless of solve status', () => {
      expect(statusIconOf({ status: 'AC', paidOnly: true })).toEqual({
        kind: 'locked',
        title: 'Premium problem (locked)',
      })
      expect(statusIconOf({ paidOnly: true }).kind).toBe('locked')
    })
  })

  describe('statusOf', () => {
    it('normalizes missing status to UNSOLVED', () => {
      expect(statusOf({ status: 'TRIED' })).toBe('TRIED')
      expect(statusOf({} satisfies ProblemStatusFields)).toBe('UNSOLVED')
    })
  })

  describe('filterByStatus', () => {
    const rows: Array<ProblemStatusFields & { id: string }> = [
      { id: 'a', status: 'AC' },
      { id: 'b', status: 'TRIED' },
      { id: 'c', status: 'UNSOLVED' },
      { id: 'd' },
    ]

    it('returns all rows for the empty filter', () => {
      expect(filterByStatus(rows, '')).toEqual(rows)
    })

    it('keeps only matching rows and treats missing status as UNSOLVED', () => {
      expect(filterByStatus(rows, 'AC').map((row) => row.id)).toEqual(['a'])
      expect(filterByStatus(rows, 'TRIED').map((row) => row.id)).toEqual(['b'])
      expect(filterByStatus(rows, 'UNSOLVED').map((row) => row.id)).toEqual(['c', 'd'])
    })

    it('returns an empty array when nothing matches', () => {
      expect(filterByStatus([], 'AC')).toEqual([])
    })
  })
})
