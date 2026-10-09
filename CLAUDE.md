> 本项目属 devtools worktree 体系。新增工具/并行开发/worktree 运维 → Read `reference/worktree-architecture.md`。


<!-- tackle-harness -->

## Harness Plan Mode 优先级规则

当以下 skill 被触发时，必须直接调用 `EnterPlanMode` 进入 Plan 模式，不得先执行 brainstorming 或其他分析工作：

- `skill-task-creator`
- `skill-batch-task-creator`
- `skill-split-work-package`
- `skill-agentic-loop`
- `skill-tackle-plan`

这些 skill 的 skill.md 中已标注 `plan_mode_required: true`，优先级高于 superpowers:brainstorming。
收到匹配上述 skill 的请求时，跳过 brainstorming 检查，立即进入 Plan 模式。

<!-- tackle-harness -->

<!-- tackle-fusion-start -->
## Tackle × Superpowers 分工

- 本项目以 **Tackle Harness** 为宏观调度层，**Superpowers v6.0.3** 为方法论纪律层
- **选主**：多 WP/目标驱动 → Tackle 主导（模式 A/C）；单 plan/探索性 → SP 主导（模式 B）；默认 Tackle 主导，SP 作质量层
- **总指挥唯一**：Tackle orchestrator/loop 主导流程时，SP executing-plans/SDD 仅作单 WP 内部参考
- **进度统一出口**：走 Tackle progress-tracker，禁止 SP SDD ledger 双写
- **方法论强制**：Tackle 分发的 Teamee 内部必须遵循 TDD + verification + systematic-debugging（已内置进 Teamee prompt 模板）
- **门控归 Tackle**：P1 human-checkpoint、P3 checklist，SP 不绕过
- **P0 走 spec → plan**：先 SP brainstorming 产 spec（`docs/superpowers/specs/`），再 tackle-plan 消费 spec 产 plan-reader 契约（`docs/plan/`）。基于 vault 设计文档时，spec frontmatter 记 `source_design_doc: <vault 路径>`，供归档时 skill-design-doc-retrospect 自动回溯

> 📖 详细规则（分工声明全文、P0 spec 接入协议、溯源字段、重叠裁决、Teamee 纪律）见 `~/.claude/reference/tackle-superpowers-rules.md`——遇到 Tackle 操作时 Read 获取完整规则。
<!-- tackle-fusion-end -->
