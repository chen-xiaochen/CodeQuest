/**
 * `platform` 层:各 OJ 平台适配器,每平台一个子包(leetcode / leetcodecn / codeforces / atcoder / luogu / hackerrank)。
 *
 * 仅依赖 `spi` 与 `core`,包内统一拆分:
 * - XxxApi:纯 HTTP 映射
 * - XxxAuth:登录态(AuthCapable 实现)
 * - XxxSubmissionService:提交与轮询(SubmissionCapable 实现,复用 spi 的 BaseSubmissionFlow)
 * - contest/:竞赛能力(CodeForces 专属)
 *
 * 约束:platform 各 OJ 子包之间禁止互相 import;不自带 UI 代码,不直接弹通知。
 */
