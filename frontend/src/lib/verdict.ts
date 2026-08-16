/**
 * 提交判定展示元数据(WP21):verdict 多态 JSON → 徽标文案/色调,
 * 以及耗时/内存/时间戳格式化(submissions 页与通知文案共用)。
 */

import type { SubmissionVerdict } from './api'

/** 徽标色调(对应 theme 变量)。 */
export type VerdictTone = 'accepted' | 'rejected' | 'limit' | 'crash' | 'partial' | 'neutral' | 'pending'

export interface VerdictMeta {
  label: string
  tone: VerdictTone
}

const META_BY_TYPE: Record<string, VerdictMeta> = {
  Accepted: { label: 'Accepted', tone: 'accepted' },
  WrongAnswer: { label: 'Wrong Answer', tone: 'rejected' },
  TimeLimitExceeded: { label: 'TLE', tone: 'limit' },
  MemoryLimitExceeded: { label: 'MLE', tone: 'limit' },
  OutputLimitExceeded: { label: 'OLE', tone: 'limit' },
  RuntimeError: { label: 'Runtime Error', tone: 'crash' },
  CompileError: { label: 'Compile Error', tone: 'crash' },
  Pending: { label: 'Pending', tone: 'pending' },
  Judging: { label: 'Judging', tone: 'pending' },
}

/** verdict → 展示元数据;PartialScore/Unknown 保留平台数据,未知 type 回退中性。 */
export function verdictMeta(verdict: SubmissionVerdict): VerdictMeta {
  if (verdict.type === 'PartialScore') {
    const percent = Math.round((verdict.score ?? 0) * 100)
    return { label: `Partial ${percent}%`, tone: 'partial' }
  }
  if (verdict.type === 'Unknown') {
    return { label: verdict.raw?.trim() || 'Unknown', tone: 'neutral' }
  }
  return META_BY_TYPE[verdict.type] ?? { label: verdict.type, tone: 'neutral' }
}

/** 毫秒 → "12 ms" / "1.4 s";null/undefined → "—"。 */
export function formatMillis(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(1)} s`
}

/** KB → "16 KB" / "9.8 MB";null/undefined → "—"。 */
export function formatMemoryKb(kb: number | null | undefined): string {
  if (kb === null || kb === undefined) return '—'
  if (kb < 1024) return `${kb} KB`
  return `${(kb / 1024).toFixed(1)} MB`
}

/** ISO 时间 → 本地化短时间串;解析失败回退原文。 */
export function formatTimestamp(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return date.toLocaleString()
}
