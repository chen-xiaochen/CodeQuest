/**
 * WP65(1.7.0 F4):题目计时器纯逻辑——格式化边界(<1h / ≥1h / 59:59→1:00:00)、
 * 起停语义(注入时钟:题面加载重置、AC 停表定格、重复停表)、AC 匹配判定与开关。
 */
import { describe, expect, it } from 'vitest'
import {
  TIMER_ENABLED_KEY,
  createProblemTimer,
  formatDuration,
  isAcceptedForProblem,
  isTimerEnabled,
} from '../../src/lib/problemTimer'

describe('formatDuration', () => {
  it('formats below one hour as mm:ss', () => {
    expect(formatDuration(0)).toBe('00:00')
    expect(formatDuration(500)).toBe('00:00')
    expect(formatDuration(59_900)).toBe('00:59')
    expect(formatDuration(60_000)).toBe('01:00')
    expect(formatDuration(125_000)).toBe('02:05')
  })

  it('keeps mm:ss up to 59:59 then rolls to h:mm:ss', () => {
    expect(formatDuration(3_599_000)).toBe('59:59')
    expect(formatDuration(3_599_999)).toBe('59:59')
    expect(formatDuration(3_600_000)).toBe('1:00:00')
    expect(formatDuration(3_661_000)).toBe('1:01:01')
    expect(formatDuration(90_061_000)).toBe('25:01:01')
  })

  it('clamps negative durations to zero', () => {
    expect(formatDuration(-1)).toBe('00:00')
    expect(formatDuration(-61_000)).toBe('00:00')
  })
})

describe('createProblemTimer', () => {
  /** 假时钟:测试手动推进,毫秒。 */
  function fakeClock() {
    let now = 1_000_000
    return {
      advance(ms: number): void {
        now += ms
      },
      tick: () => now,
    }
  }

  it('starts at zero elapsed and not running before start', () => {
    const clock = fakeClock()
    const timer = createProblemTimer(clock.tick)
    expect(timer.isRunning()).toBe(false)
    expect(timer.elapsedMs()).toBe(0)
    expect(timer.stop()).toBe(false)
  })

  it('tracks elapsed while running', () => {
    const clock = fakeClock()
    const timer = createProblemTimer(clock.tick)
    timer.start()
    expect(timer.isRunning()).toBe(true)

    clock.advance(5_000)
    expect(timer.elapsedMs()).toBe(5_000)
    clock.advance(250)
    expect(timer.elapsedMs()).toBe(5_250)
  })

  it('start resets the clock (problem data reload)', () => {
    const clock = fakeClock()
    const timer = createProblemTimer(clock.tick)
    timer.start()
    clock.advance(120_000)
    expect(timer.elapsedMs()).toBe(120_000)

    // 题面数据再次到达 → 重置起表,旧耗时清零
    timer.start()
    expect(timer.isRunning()).toBe(true)
    expect(timer.elapsedMs()).toBe(0)
    clock.advance(1_000)
    expect(timer.elapsedMs()).toBe(1_000)
  })

  it('stop freezes elapsed and reports idempotent stop', () => {
    const clock = fakeClock()
    const timer = createProblemTimer(clock.tick)
    timer.start()
    clock.advance(65_000)

    expect(timer.stop()).toBe(true)
    expect(timer.isRunning()).toBe(false)
    expect(timer.elapsedMs()).toBe(65_000)

    // 停表后再走时钟,耗时定格不变;重复停表返回 false
    clock.advance(30_000)
    expect(timer.elapsedMs()).toBe(65_000)
    expect(timer.stop()).toBe(false)
  })

  it('restarts after stop via start', () => {
    const clock = fakeClock()
    const timer = createProblemTimer(clock.tick)
    timer.start()
    clock.advance(10_000)
    timer.stop()

    timer.start()
    expect(timer.isRunning()).toBe(true)
    clock.advance(2_000)
    expect(timer.elapsedMs()).toBe(2_000)
  })
})

describe('isAcceptedForProblem', () => {
  const payload = {
    challengeId: 'ch-1',
    submissionId: 'sub-1',
    challengeName: '两数之和',
    platformId: 'leetcode',
    problemRemoteId: 'two-sum',
    verdict: { type: 'Accepted' },
  }

  it('matches current problem with Accepted verdict', () => {
    expect(isAcceptedForProblem(payload, 'leetcode', 'two-sum')).toBe(true)
  })

  it('rejects other platform or other problem', () => {
    expect(isAcceptedForProblem(payload, 'codeforces', 'two-sum')).toBe(false)
    expect(isAcceptedForProblem(payload, 'leetcode', 'add-two-numbers')).toBe(false)
    expect(isAcceptedForProblem(payload, 'leetcode', '')).toBe(false)
  })

  it('rejects non-accepted verdicts and missing fields', () => {
    expect(isAcceptedForProblem({ ...payload, verdict: { type: 'WrongAnswer' } }, 'leetcode', 'two-sum')).toBe(false)
    expect(isAcceptedForProblem({ ...payload, verdict: { type: 'Pending' } }, 'leetcode', 'two-sum')).toBe(false)
    expect(isAcceptedForProblem({ ...payload, platformId: undefined }, 'leetcode', 'two-sum')).toBe(false)
    expect(isAcceptedForProblem({ ...payload, problemRemoteId: undefined }, 'leetcode', 'two-sum')).toBe(false)
  })

  it('rejects malformed payloads', () => {
    expect(isAcceptedForProblem(null, 'leetcode', 'two-sum')).toBe(false)
    expect(isAcceptedForProblem(undefined, 'leetcode', 'two-sum')).toBe(false)
    expect(isAcceptedForProblem('accepted', 'leetcode', 'two-sum')).toBe(false)
    expect(isAcceptedForProblem({}, 'leetcode', 'two-sum')).toBe(false)
  })
})

describe('isTimerEnabled', () => {
  function storageWith(value: string | null): Storage {
    return {
      getItem: (key: string) => (key === TIMER_ENABLED_KEY ? value : null),
    } as Storage
  }

  it('defaults to enabled when unset', () => {
    expect(isTimerEnabled(storageWith(null))).toBe(true)
  })

  it('is disabled only for explicit "0"', () => {
    expect(isTimerEnabled(storageWith('0'))).toBe(false)
    expect(isTimerEnabled(storageWith('1'))).toBe(true)
    expect(isTimerEnabled(storageWith('false'))).toBe(true)
  })
})
