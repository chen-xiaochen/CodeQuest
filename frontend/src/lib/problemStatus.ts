/**
 * WP37:题目刷题状态(列表状态列与本地筛选,problems/search 两页共用)。
 *
 * - status 由本地 REST 端点聚合(WP37 起 /api/problems、/api/search 每项恒带:
 *   该题全部工作区提交任一通过 → AC;有提交未通过 → TRIED;无提交 → UNSOLVED);
 * - paidOnly 为平台列表数据的会员题标记(SPI 未透传阶段恒 false/缺省),
 *   不与会员登录态联动(YAGNI);
 * - 图标语义对齐原版 KeywordQueryPresenter 状态列:
 *   🔒 会员题 > ✓ AC > ● 已尝试 > 空 未做。
 */

/** 刷题状态三值(后端 SolveStatus 枚举名的线协议形态)。 */
export type ProblemStatus = 'AC' | 'TRIED' | 'UNSOLVED'

/** 端点响应项的状态附加字段(WP37;旧响应缺省时按 UNSOLVED 兜底)。 */
export interface ProblemStatusFields {
  status?: ProblemStatus
  paidOnly?: boolean | null
}

/** 状态筛选值;'' = 不过滤(All)。本地过滤语义:仅作用于已加载项。 */
export type ProblemStatusFilter = '' | ProblemStatus

export type StatusIconKind = 'locked' | 'ac' | 'tried' | 'unsolved'

export interface StatusIcon {
  kind: StatusIconKind
  /** title 提示文案;unsolved 为空串(无内容无可提示)。 */
  title: string
}

/** 规范化状态:缺省回退 UNSOLVED(兼容旧响应)。 */
export function statusOf(item: ProblemStatusFields): ProblemStatus {
  return item.status ?? 'UNSOLVED'
}

/**
 * 状态图标:🔒 优先于刷题状态——原版语义即「非会员视角锁即锁」,
 * 本工具不感知会员态,只按列表数据(YAGNI)。
 */
export function statusIconOf(item: ProblemStatusFields): StatusIcon {
  // WP49:提示文案中文化(仅 problems/search 两页消费)
  if (item.paidOnly) {
    return { kind: 'locked', title: '会员题(已锁定)' }
  }
  switch (statusOf(item)) {
    case 'AC':
      return { kind: 'ac', title: '已解决' }
    case 'TRIED':
      return { kind: 'tried', title: '已尝试' }
    default:
      return { kind: 'unsolved', title: '' }
  }
}

/** 本地筛选:仅保留已加载项中匹配状态的行('' = 全部)。不发请求、不改分页状态。 */
export function filterByStatus<T extends ProblemStatusFields>(
  items: T[],
  filter: ProblemStatusFilter,
): T[] {
  if (filter === '') return items
  return items.filter((item) => statusOf(item) === filter)
}
