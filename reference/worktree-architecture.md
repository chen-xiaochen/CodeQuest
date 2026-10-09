# Git Worktree 多工具集架构 · 落地参考

> devtools 仓库 worktree 架构的详细落地参考，**按需加载**（不进 CLAUDE.md 主文件）。
> 触发场景：新增工具、删除/移动 worktree、公共文档更新、台账维护 → Read 本文件。

## 1. 架构模型

**纯净 master 主干 + worktree 工具集群 + 治理逻辑按需传递**。

- **master**（主仓库 `E:\Desktop\devtools`）：唯一基线，恒停 master，仅公共文档（README 台账 + CLAUDE 指针 + reference 治理文档 + .gitignore），不存业务代码，作为 worktree 挂载点
- **worktree 集群**（`E:\Desktop\devtoolsManager\devtools-<name>`）：每工具一条分支 + 一个 worktree，所有 worktree 共享主仓库 `.git`，物理隔离、可并行
- **治理传递**：治理逻辑在本文件，各 worktree 的 CLAUDE.md 顶部一行指针引用；CLAUDE.md 各分支独立

## 2. 目录布局

```
E:\Desktop\
├─ devtools\                 ← 主仓库（恒停 master，挂载点）
│   ├─ .git\                 ← 所有 worktree 共享这一份
│   ├─ README.md             ← 分支台账（master 专有）
│   ├─ CLAUDE.md             ← 指针版（→ reference/）
│   ├─ reference\            ← 治理文档（本文件）
│   └─ .gitignore
└─ devtoolsManager\          ← worktree 集合
    ├─ devtools-<name>\      ← worktree: tool/<name> 或 feature/<name>
    └─ ...
```

## 3. 分支命名

| 前缀 | 用途 | 示例 |
|---|---|---|
| `feature/xxx` | 正式业务模块 | `feature/sweetheart` |
| `tool/xxx` | 通用工具/组件 | `tool/claduelight` |
| `demo/xxx` | 临时测试/演示 | `demo/playground` |

## 4. 新增工具（两类纳入流程）

> 纳入操作**始终在主仓库（停 master）执行**，确保治理文档在执行者上下文。

### 4.1 A 类：基于 master 的新项目（同栈 / 可继承模板）

```bash
cd E:/Desktop/devtools              # 主仓库恒停 master，不 checkout 业务分支
git branch tool/<name> master        # 继承 master 公共文档 + reference/（用 branch 建支，勿 checkout -b，否则下句 worktree add 报 already used）
git worktree add ../devtoolsManager/devtools-<name> tool/<name>
cd ../devtoolsManager/devtools-<name>
# CLAUDE.md 已继承指针版 → 顶部指针下方追加项目规则
# 开发 → git add/commit → push 需用户手动或明确授权（全局规则：仅 commit 不 push）
# 回 master 更新 README 台账
```

### 4.2 B 类：已在别处裸开发 / 独立项目（无 git）

```bash
cd E:/Desktop/devtools              # 主仓库恒停 master
git branch tool/<name> master        # 勿 checkout -b（会导致 worktree add 报 already used）
# 若目标路径被旧目录占用，先 worktree 到 -wt 临时目录
git worktree add ../devtoolsManager/devtools-<name>-wt tool/<name>
# 从原目录拷代码：拷源码/配置/资源，不拷 node_modules dist .codegraph（新 worktree 重装/重建）
# ⚠️ CLAUDE.md 合而非覆盖：保留 worktree 继承的指针版开头 + 追加项目 CLAUDE.md（harness 块等）
# .gitignore 补 .claude/ 与 .env*
cd ../devtoolsManager/devtools-<name>-wt
git add . && git commit -m "feat: 导入 <name>"
# push 需用户手动或明确授权（全局规则：仅 commit 不 push）
# 原目录清理后：git worktree move devtools-<name>-wt devtools-<name>
```

**B 类关键**：CLAUDE.md 必须合并保留治理指针（见 §6），否则该 worktree 会"不知道"自己在 devtools 体系内。

### 4.3 C 类：已有 git 历史的项目（保留完整历史纳入）

```bash
cd E:/Desktop/devtools              # 主仓库恒停 master
git fetch <原项目路径> master         # 拉入原仓库全部历史
git branch tool/<name> FETCH_HEAD    # 从原历史顶端建支（与 master 无共同祖先，正常）
git worktree add ../devtoolsManager/devtools-<name> tool/<name>
# checkout 已含全部内容，无需拷文件；若原目录有未提交改动才需补拷
# 治理补齐：CLAUDE.md 顶部注入指针（§6 模板）+ 拷入 reference/ + 换用 master 版 .gitignore
# .claude/ 若被历史跟踪 → git rm -r --cached .claude
git add -A && git commit -m "chore: 纳入 devtools worktree 体系"
```

（2026-10-02 实例：mysql-mcp-pro → `tool/mysql-mcp-pro` @ `aa7af1e`）

## 5. 并行开发

- 每个 worktree = 独立目录 + 独立 IDE / Claude Code 窗口，**并行零干扰**
- 各自 commit/push，分支互不影响
- 主仓库恒停 master，随时可维护公共文档

## 6. 公共文档策略与治理传递

| 文件 | 处理 |
|---|---|
| `CLAUDE.md` | **各分支独立** + 顶部统一治理指针；项目规则独一无二，不跨分支同步 |
| `reference/worktree-architecture.md` | 治理全文，各分支基于 master **自动继承**，稳定后不强制同步 |
| `README.md` | master 专有（分支台账） |
| `.gitignore` | 基于继承；建议含 `.claude/`、`.env*`、各语言产物 |

**治理传递闭环**：

```
纳入新项目（在主仓库 master 执行）
   ↓ 主仓库 CLAUDE.md + reference/ 已在执行者上下文
   知道完整流程 → 执行纳入
   ↓ 纳入末步：给新 worktree 的 CLAUDE.md 注入/保留指针
   新 worktree 就绪
   ↓ 之后在该 worktree，Claude 读 CLAUDE.md 见指针
   按需 Read reference/worktree-architecture.md
```

**CLAUDE.md 顶部指针模板**（各 worktree 须保留）：
```
> 本项目属 devtools worktree 体系。新增工具/并行开发/worktree 运维 → Read `reference/worktree-architecture.md`。
```

## 7. worktree 运维命令

```bash
git worktree list                                     # 查看所有 worktree
git worktree add ../devtoolsManager/devtools-X tool/X # 新增
git worktree remove ../devtoolsManager/devtools-X     # 删除（分支保留）
git worktree move <old-path> <new-path>               # 移动/改名
git worktree prune                                    # 清理失效引用
```

## 8. 台账维护

新增/迭代工具后，在 master 的 `README.md`「分支台账」补/更新一行（分支、用途、最新 commit、时间、内容）：

```bash
cd E:/Desktop/devtools   # 确保 master
git rev-parse tool/<name>   # 取 commit 哈希
# 填入 README 台账 → commit → push origin master
```

## 9. 避坑

- **CLAUDE.md 不跨分支同步**：各分支独立，强行同步会覆盖项目规则与 harness 块
- **B 类 CLAUDE.md 必须合并**：裸目录 CLAUDE.md 会覆盖继承的指针版，丢失治理逻辑
- **worktree add 目标须空**：路径非空会失败，先清理或用 `-wt` 中转
- **目录占用锁**：worktree 内有 dev server / IDE 时无法 `mv` / `move`，先停进程
- **node_modules / .codegraph 不进 git**：新 worktree 需 `pnpm install` / `codegraph init`
- **master 禁业务代码**：仅 README / CLAUDE / reference / .gitignore
- **worktree 目录名守 `devtools-<name>` 前缀**：2026-09-26 批次曾裸名+整仓 clone 方式迁入 5 个（WeChatMsg-master 等），属流程漂移；新迁入一律走本文 §4 流程带前缀
- **禁用全局 `git-commit-agent`**：不掌握本架构约束；提交手动执行
