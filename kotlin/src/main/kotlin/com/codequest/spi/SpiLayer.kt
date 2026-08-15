package com.codequest.spi

/**
 * `spi` 层:OJ 平台能力接口 + 注册机制。
 *
 * 仅依赖 `core` 领域模型,定义能力型接口(按需组合,suspend + Flow 风格):
 * - OJPlatform:平台身份(id / domain / PlatformInfo)
 * - AuthCapable / ProblemQueryCapable / SubmissionCapable / SolutionViewCapable
 *   (ContestCapable 留待 M4 随 CodeForces 接入再加)
 * - BaseSubmissionFlow:通用「提交→轮询→终态」状态机(指数退避 + 超时),各平台只实现 fetchRemoteStatus() 钩子
 * - 领域异常体系(OJException / AuthExpiredException / AntiBotChallengeException /
 *   RateLimitedException / SubmissionTimeoutException)落位 core/model:语义平台无关,
 *   且 core 用例服务需要 catch/翻译(放 spi 会造成 core→spi 反向依赖,见该文件 KDoc)
 *
 * 每平台通过 OJPlatformProvider(IntelliJ Extension Point)注册,新增 OJ = 新包 + plugin.xml 一行声明,
 * 不存在任何枚举 match 分发。
 */
object SpiLayer
