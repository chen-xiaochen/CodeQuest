/**
 * WP65(1.7.0 F4):题目计时器纯逻辑——起表/停表/耗时/格式化 + AC 匹配判定。
 *
 * 会话级,不持久化(spec §4):题面页收到题面数据即重置起表(切题自动重置);
 * `submission:updated` 桥事件命中当前题且 AC 时停表定格。计时源可注入,
 * 单测用假时钟确定性断言,线上用 Date.now()。
 */

/** 耗时格式化:<1h 为 `mm:ss`,≥1h 为 `h:mm:ss`(59:59 → 1:00:00)。 */
export function formatDuration(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  const mm = String(minutes).padStart(2, '0')
  const ss = String(seconds).padStart(2, '0')
  return hours >= 1 ? `${hours}:${mm}:${ss}` : `${mm}:${ss}`
}

export interface ProblemTimer {
  /** 重置并起表(收到题面数据即调;重复调用按最新一次起表)。 */
  start(): void
  /** 停表定格;未起表或已停返回 false(真正停表才返回 true)。 */
  stop(): boolean
  /** 停表后返回定格耗时;起表中返回实时耗时;未起表为 0。 */
  elapsedMs(): number
  isRunning(): boolean
}

export function createProblemTimer(now: () => number = Date.now): ProblemTimer {
  let startedAt: number | null = null
  let stoppedAt: number | null = null

  return {
    start() {
      startedAt = now()
      stoppedAt = null
    },
    stop() {
      if (startedAt === null || stoppedAt !== null) return false
      stoppedAt = now()
      return true
    },
    elapsedMs() {
      if (startedAt === null) return 0
      return (stoppedAt ?? now()) - startedAt
    },
    isRunning() {
      return startedAt !== null && stoppedAt === null
    },
  }
}

/** `submission:updated` payload(Kotlin 侧 WP65 起含 platformId/problemRemoteId)。 */
export interface SubmissionUpdatedPayload {
  challengeId?: unknown
  platformId?: unknown
  problemRemoteId?: unknown
  verdict?: { type?: unknown }
}

/** 停表条件:事件命中当前题(platformId + problemRemoteId)且判定为 Accepted。 */
export function isAcceptedForProblem(
  payload: unknown,
  platformId: string,
  problemRemoteId: string,
): boolean {
  if (!payload || typeof payload !== 'object') return false
  const record = payload as SubmissionUpdatedPayload
  return (
    record.platformId === platformId &&
    record.problemRemoteId === problemRemoteId &&
    record.verdict?.type === 'Accepted'
  )
}

/** 计时开关:localStorage `cq-problem-timer-enabled`,默认开('0' 表示关)。 */
export const TIMER_ENABLED_KEY = 'cq-problem-timer-enabled'

export function isTimerEnabled(storage: Storage = window.localStorage): boolean {
  return storage.getItem(TIMER_ENABLED_KEY) !== '0'
}
