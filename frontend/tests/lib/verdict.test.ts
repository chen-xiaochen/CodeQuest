/**
 * WP21:verdict 展示元数据与格式化——tone 映射/PartialScore/Unknown 回退/
 * 耗时/内存/时间戳格式化。
 */
import { describe, expect, it } from 'vitest'
import { formatMemoryKb, formatMillis, formatTimestamp, verdictMeta } from '../../src/lib/verdict'

describe('verdictMeta', () => {
  it('maps known verdict types to label and tone', () => {
    expect(verdictMeta({ type: 'Accepted' })).toEqual({ label: 'Accepted', tone: 'accepted' })
    expect(verdictMeta({ type: 'WrongAnswer' })).toEqual({ label: 'Wrong Answer', tone: 'rejected' })
    expect(verdictMeta({ type: 'TimeLimitExceeded' })).toEqual({ label: 'TLE', tone: 'limit' })
    expect(verdictMeta({ type: 'CompileError' })).toEqual({ label: 'Compile Error', tone: 'crash' })
    expect(verdictMeta({ type: 'Judging' }).tone).toBe('pending')
  })

  it('renders PartialScore with percent', () => {
    expect(verdictMeta({ type: 'PartialScore', score: 0.456 })).toEqual({
      label: 'Partial 46%',
      tone: 'partial',
    })
  })

  it('keeps Unknown raw text with neutral tone', () => {
    expect(verdictMeta({ type: 'Unknown', raw: 'Time Limit' })).toEqual({
      label: 'Time Limit',
      tone: 'neutral',
    })
  })

  it('falls back to the type name for unknown types', () => {
    expect(verdictMeta({ type: 'Frozen' })).toEqual({ label: 'Frozen', tone: 'neutral' })
  })
})

describe('formatters', () => {
  it('formats millis as ms below one second and s above', () => {
    expect(formatMillis(12)).toBe('12 ms')
    expect(formatMillis(1400)).toBe('1.4 s')
    expect(formatMillis(null)).toBe('—')
    expect(formatMillis(undefined)).toBe('—')
  })

  it('formats memory as KB below one MB and MB above', () => {
    expect(formatMemoryKb(512)).toBe('512 KB')
    expect(formatMemoryKb(16384)).toBe('16.0 MB')
    expect(formatMemoryKb(null)).toBe('—')
  })

  it('formats ISO timestamps as locale strings and falls back on garbage', () => {
    expect(typeof formatTimestamp('2026-08-15T10:00:00Z')).toBe('string')
    expect(formatTimestamp('not-a-date')).toBe('not-a-date')
  })
})
