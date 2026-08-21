/**
 * 提交判定展示元数据(WP21;WP50 中文):verdict 多态 JSON → 徽标文案/色调,
 * 以及耗时/内存/时间戳格式化(submissions 页与通知文案共用)。
 * 判定文案与 Kotlin PluginBundle_zh 的 submission.verdict.* 逐条对齐。
 */

import type { SubmissionVerdict } from './api'

/** 徽标色调(对应 theme 变量)。 */
export type VerdictTone = 'accepted' | 'rejected' | 'limit' | 'crash' | 'partial' | 'neutral' | 'pending'

export interface VerdictMeta {
  label: string
  tone: VerdictTone
}

const META_BY_TYPE: Record<string, VerdictMeta> = {
  Accepted: { label: '通过', tone: 'accepted' },
  WrongAnswer: { label: '答案错误', tone: 'rejected' },
  TimeLimitExceeded: { label: '超出时间限制', tone: 'limit' },
  MemoryLimitExceeded: { label: '超出内存限制', tone: 'limit' },
  OutputLimitExceeded: { label: '超出输出限制', tone: 'limit' },
  RuntimeError: { label: '运行时错误', tone: 'crash' },
  CompileError: { label: '编译错误', tone: 'crash' },
  Pending: { label: '等待中', tone: 'pending' },
  Judging: { label: '评测中', tone: 'pending' },
}

/** verdict → 展示元数据;PartialScore/Unknown 保留平台数据,未知 type 回退中性。 */
export function verdictMeta(verdict: SubmissionVerdict): VerdictMeta {
  if (verdict.type === 'PartialScore') {
    const percent = Math.round((verdict.score ?? 0) * 100)
    return { label: `部分通过 ${percent}%`, tone: 'partial' }
  }
  if (verdict.type === 'Unknown') {
    return { label: verdict.raw?.trim() || '未知', tone: 'neutral' }
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
