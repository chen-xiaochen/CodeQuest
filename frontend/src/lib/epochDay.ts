/**
 * WP64(1.7.0 F3):epoch 秒 → 本地日期 `yyyy-MM-dd`(复习排期/到期日展示)。
 *
 * 后端日期按本地时区零点对齐成 epoch 秒,故取本地字段而非 toISOString()
 * (UTC 切片在东八区会落到前一天)。
 */
export function formatEpochDay(epochSeconds: number): string {
  const date = new Date(epochSeconds * 1000)
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}
