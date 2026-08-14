/**
 * `core` 层:领域模型 + 用例服务 + 仓库接口。
 *
 * 全插件最底层,不依赖 ui/platform/infra/spi 中任何代码:
 * - 领域模型:Problem / ProblemDetail / Challenge / Solution / TestCase / Submission / Contest 等
 *   (value class id + kotlinx.serialization)
 * - 仓库接口:ProblemRepository / SubmissionRepository / ChallengeRepository 等(实现归 infra)
 * - 用例服务:面向 UI 的业务编排,只依赖仓库接口与 SPI,单测用内存假实现替换
 */
