# CodeQuest 手动发布 Checklist

> 无 CI/CD，每次发版按本清单手动执行。这份清单就是你的"质量门"——
> 本次 262 JCEF 事故正是因为缺了"verifier + untilBuild 上限"两步才暴露给用户。

## 0. 前置（首次/发版前核对）
- [ ] `plugin.xml` 的 `<id>`/`<name>`/`<vendor>` 是你自己的（当前 `com.chenxiaochen.codequest` / CodeQuest / Chen Xiaochen）
- [ ] `build.sbt` 的 `pluginVersion` 与本次发版号一致
- [ ] 若上架 Marketplace：账号下该 plugin id 的上传 token 已备好

## 1. 构建目标版本
每个要支持的 IDE 版本分别构建（`-Dintellij.version`）：

| IDE | 参数 | untilBuild（由 versions.scala 自动设） |
|----|------|-----------|
| 2025.2 | `-Dintellij.version=252` | 252.* |
| 2026.2 | `-Dintellij.version=262` | 262.* |

命令行有 sbt：
```bash
sbt -Dintellij.version=252 clean packageArtifact
sbt -Dintellij.version=262 clean packageArtifact
```
命令行无 sbt（本机当前情况）：在 IntelliJ 的 sbt shell 里跑 `packageArtifact`（默认 252）。
> 262 首次构建会触发 sbt-idea-plugin 下载 IC-262 SDK，若 `intellijBuild262` build 号不对会下载失败，
> 到 https://data.services.jetbrains.com/products/releases?code=IIC 核对后改 `versions.scala`。

## 2. Plugin Verifier（发布前必做 —— 代替 CI 的自动校验）
能抓"缺依赖 / 用了内部 API"等问题。本次 `com.intellij.modules.jcef` 缺失这类问题，verifier 在发布前就能报。

**方式 A（推荐，图形化）**：IDE → `Plugin DevKit → Plugin Verifier`，选目标 IDE build，指向产物 zip。

**方式 B（官方 CLI，确定性可用）**：
```bash
# 下载 https://github.com/JetBrains/intellij-plugin-verifier/releases
java -jar verifier-cli-*.jar check-plugin target/plugin/CodeQuest-0.1.0-252.zip IC-252.23892.409
java -jar verifier-cli-*.jar check-plugin target/plugin/CodeQuest-0.1.0-262.zip IC-262.8665.337
```

**方式 C（sbt task，若 sbt-idea-plugin 版本支持）**：
```bash
sbt -Dintellij.version=252 verifyPlugin
```
> sbt-idea-plugin 不同版本 verifier task 名/可用性有差异；A/B 更稳。

- [ ] 252 verifier 无致命问题（Missing Dependency / Internal API）
- [ ] 262 verifier 无致命问题

## 3. 打可安装 zip
```bash
bash scripts/package.sh 252 --zip   # → target/plugin/CodeQuest-252.zip
bash scripts/package.sh 262 --zip
```

## 4. 自测（至少在最高目标版本上）
- [ ] 目标 IDE `Install Plugin from Disk` 装 zip → 重启
- [ ] 打开题目描述侧边栏，渲染正常（验证 JCEF 链路）
- [ ] `Help → Show Log in Explorer`，无 `NoClassDefFoundError` / `IllegalStateException`

## 5. 发布
- **Marketplace**：JetBrains 账号 → 你的 plugin id 页面 → 上传 zip
- **自用 / 小范围**：直接分发 zip

## 6. 归档
- [ ] 更新 `CHANGELOG.md`
- [ ] （可选）`git tag v0.1.0-252` / `v0.1.0-262`
