package com.codequest.spi

import com.codequest.core.model.PlatformId
import com.codequest.core.workspace.TemplateStyle
import kotlinx.serialization.Serializable

/**
 * OJ 平台身份接口(WP17):平台适配器的根契约。
 *
 * 能力(Auth/题目/提交/题解)不放进本接口,由平台按能力矩阵**按需组合实现**
 * `com.codequest.spi` 下的能力接口;调用方经 [OJPlatformRegistry.allWith] 按能力过滤,
 * 不存在枚举 match 分发。
 *
 * 线程/协程约定(适用于整个 spi 包):
 * - 接口方法可在任意线程调用;suspend 实现必须 main-safe(IO 内部切 Dispatchers.IO);
 * - Flow 返回值均为冷流,每次收集独立执行,不支持多收集者共享状态;
 * - 实现只抛 core 领域异常体系(OJException 及子类,见 `core/model/OJExceptions.kt`)。
 */
interface OJPlatform {
    /** 平台标识,与 [com.codequest.core.model.Problem.platformId] 同源,如 "leetcode" / "codeforces"。 */
    val id: PlatformId

    /** 平台主域名(不带协议),如 "leetcode.com"。 */
    val domain: String

    /** 展示信息:显示名/图标/支持的提交语言。 */
    val info: PlatformInfo
}

/** 平台展示信息(WP17,YAGNI 最小集;WP44 增补模版风格声明)。 */
@Serializable
data class PlatformInfo(
    val displayName: String,
    /** 插件内图标资源路径(如 "icons/leetcode.svg");无专属图标时为 null。 */
    val iconPath: String? = null,
    /** 该平台接受的提交语言列表(供提交对话框选择)。 */
    val supportedLanguages: List<LanguageInfo> = emptyList(),
    /** 入口文件默认模版风格(WP44):LEETCODE=函数版、ACM=stdin/stdout 竞赛版。 */
    val templateStyle: TemplateStyle,
)

/**
 * 平台侧提交语言描述(WP17)。
 *
 * [id] 是提交时传给平台的语言标识(如 LC "kotlin"、CF "GNU G++20");
 * [workspaceLanguageId] 指向 core 的 WorkspaceLanguage.id(如 "kotlin"/"cpp"),
 * 用于 challenge 工作区语言 → 平台提交语言的映射;平台无对应工作区语言时为 null。
 */
@Serializable
data class LanguageInfo(
    val id: String,
    val displayName: String,
    val workspaceLanguageId: String? = null,
)
