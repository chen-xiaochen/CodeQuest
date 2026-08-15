/**
 * 难度展示工具(WP20):平台原始难度串 → 徽标样式类与展示文案。
 * LC/力扣 Easy/Medium/Hard(含中文「简单/中等/困难」);其余平台原样展示为 other 档。
 */

const EASY = ['easy', '简单']
const MEDIUM = ['medium', '中等']
const HARD = ['hard', '困难']

export type DifficultyTone = 'easy' | 'medium' | 'hard' | 'other'

export function difficultyTone(difficulty: string): DifficultyTone {
  const value = difficulty.trim().toLowerCase()
  if (EASY.includes(value)) return 'easy'
  if (MEDIUM.includes(value)) return 'medium'
  if (HARD.includes(value)) return 'hard'
  return 'other'
}

export function difficultyClass(difficulty: string): string {
  return `diff-badge--${difficultyTone(difficulty)}`
}
