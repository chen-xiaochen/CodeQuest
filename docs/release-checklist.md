# CodeQuest 发布 Checklist(Kotlin/Gradle 版)

> 单轨 Gradle 构建:前端产物由 `copyWebviewDist` 自动内嵌进插件 zip,**无独立打包脚本**。
> CI(`.github/workflows/ci-main.yml`)已收敛为同一套门禁(前端 build → `./gradlew check test buildPlugin`);
> 本清单只覆盖 CI 之外的人工步骤(verifier、真机自测、发布归档),它是发布前的最后一道质量门。

## 0. 前置核对(版本号修改点)

- [ ] `build.gradle.kts` 的 `version` 已改为本次发版号(如 `1.0.0`,去 `-SNAPSHOT`)——**版本号只在此处声明**,plugin.xml 中的版本由 Gradle 注入
- [ ] `kotlin/src/main/resources/META-INF/plugin.xml` 的 `<id>`/`<name>`/`<vendor>` 正确(当前 `com.codequest` / CodeQuest / CodeQuest Team)
- [ ] `CHANGELOG.md` 已补本次版本条目(Keep a Changelog 格式)
- [ ] (可选)`build.gradle.kts` 的 `pluginConfiguration` 中补充本次 change-notes
- [ ] 若上架 Marketplace:该 plugin id 的上传 token 已备好

## 1. 构建(前端 → Gradle,两步)

```bash
cd frontend && npm ci && npm run build   # 1) → frontend/dist/(不入库)
./gradlew clean check test buildPlugin   # 2) 门禁 + 打包 → build/distributions/codequest-<version>.zip
```

- `check` 含 detekt + ktlint + `checkLayerDependencies`(分层依赖门禁)+ 全部测试(Kotlin 517+)
- 前端另跑 `npm test`(Vitest 95+);CI 会自动执行同两条命令
- `copyWebviewDist`(`processResources` 的依赖)自动把 `frontend/dist/` 同步入包;缺失时构建给出可行动报错,提示先跑 npm build

## 2. Plugin Verifier(发布前必做)

目标仅 2026.2+(sinceBuild=262,与构建基线一致),范围兼容风险小,但 verifier 仍能抓「缺依赖 / 用了内部 API」这类问题:

- **方式 A(推荐,图形化)**:IDE → `Plugin DevKit → Plugin Verifier`,选目标 IDE build,指向产物 zip
- **方式 B(官方 CLI,确定性可用)**:
  ```bash
  # verifier 从 https://github.com/JetBrains/intellij-plugin-verifier/releases 下载
  java -jar verifier-cli-*.jar check-plugin build/distributions/codequest-1.0.0.zip IC-262.8665.258
  ```
  build 号到 https://data.services.jetbrains.com/products/releases?code=IIC 核对后替换。

- [ ] verifier 无致命问题(Missing Dependency / Internal API)

## 3. 自测(至少在 2026.2 上)

- [ ] `./gradlew runIde` 沙箱冒烟:插件加载 0 ERROR(沙箱日志 `.intellijPlatform/sandbox/codequest/*/log/idea.log`),资源服务启动日志出现,`/api/health` 返回 ok
- [ ] 真机 `Install Plugin from Disk` 装 zip → 重启
- [ ] Problems 页平台下拉含全部 6 平台;题面侧边栏渲染正常(验证 JCEF 链路)
- [ ] `Help → Show Log`,无 `NoClassDefFoundError` / `IllegalStateException`
- [ ] 按 `docs/m3-manual-checklist.md` / `docs/m4-manual-checklist.md` / `docs/m5-manual-checklist.md` 的手工项逐平台过登录/提交/统计/检索/题解链路

## 4. 发布

- **Marketplace**:JetBrains 账号 → 该 plugin id 页面 → 上传 zip
- **自用 / 小范围**:直接分发 zip

## 5. 归档

- [ ] `CHANGELOG.md` 定稿
- [ ] `git tag v<version>`(如 `v1.0.0`)
