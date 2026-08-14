package com.codequest.ui

/**
 * `ui` 层:最上层,只做界面壳与交互转发,可依赖 platform/spi/core/infra。
 *
 * - toolwindow:左侧 CodeQuest 主侧栏(Problems / Contest / Statistics / Search 可切换面板,
 *   Kotlin UI DSL 壳 + JCEF 内容区,无 GUI Designer .form)
 * - jcef:CQBridge 桥(postMessage/on,消息仅承载命令与事件)
 * - actions:编辑器与工具栏动作
 * - settings:Kotlin UI DSL 设置面板
 * - auth:登录页公共组件(表单型/内嵌浏览器型/cookie 粘贴型)
 *
 * 异常到通知的统一映射在本层完成,平台适配器不直接弹通知。
 */
object UiLayer
