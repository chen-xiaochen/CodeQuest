# CodeQuest Kotlin 重写 M6:删旧工程/CI 收敛/文档/发布 1.0(收官)

重写收官:删除旧 Scala/sbt 工程与旧前端,CI 收敛为 Gradle 单轨,文档重写,定版 1.0.0 发布。设计依据:spec §7(M6)。用户已批准:旧工程连同工作区未提交改动一并清理。

## 删除旧 Scala/sbt 工程
- [ ] 删除旧源码与构建:src/main/scala、src/main/resources(旧 plugin.xml/图标等)、src/main/233|241|252 兼容层残留、src/test/scala、build.sbt、project/、jooq-codegen.xml、gen/、testResources/(sbt 专用)、bash.exe.stackdump、temp/(若为旧工程临时物,逐项甄别后删);git checkout/restore 丢弃旧侧未提交改动后 rm
- [ ] 删除旧前端:webview/(旧 4 应用,node_modules 不入库直接 rm)
- [ ] 保留:kotlin/、frontend/、docs/、database/(设计稿)、.github/(下 WP 改)、CLAUDE.md、LICENSE、gradle 全套、scripts/(甄别:新工程引用的保留)
- [ ] 验证:./gradlew clean check test buildPlugin --rerun-tasks 全绿(删除不伤新工程);产物 zip 完整
- [ ] [acceptance] git status 干净(除 M6 自身提交);全仓 grep 无 com.wenjunhuang 残留引用

## CI 收敛与文档更新
依赖:先完成「删除旧 Scala/sbt 工程」
- [ ] .github/workflows/ci-main.yml:移除 sbt build job,仅保留 gradle job;检查其他 workflow(ci.yml 等)是否有旧引用,统一收敛
- [ ] README.md 重写:CodeQuest Kotlin 版(项目定位/功能矩阵 6 平台×能力/架构分层图/SPI 扩展指南(引 docs/spi-validation.md)/安装说明/开发指南(gradlew+npm)/Apache 2.0 与原项目声明保留);README_en.md 同步;DESCRIPTION.md 更新
- [ ] CHANGELOG.md:1.0.0 条目(重写要点汇总,Kotlin/Gradle/6 平台/SPI/统计/检索/题解,Keep a Changelog 格式)
- [ ] docs/release-checklist.md 更新为新构建流程(gradlew buildPlugin + 前端 npm build)
- [ ] spec 状态更新:docs/superpowers/specs/2026-08-14-*.md frontmatter status plan→done(M6 终态)

## 定版发布与最终验收
依赖:先完成「CI 收敛与文档更新」
- [ ] 版本定版 1.0.0:build.gradle.kts version 移除 -SNAPSHOT;plugin.xml change-notes 补 1.0.0
- [ ] 构建发布产物:gradlew clean check test buildPlugin + frontend build,产出 codequest-1.0.0.zip 并验证内容
- [ ] 最终验收:沙箱 runIde 冒烟(插件加载 0 ERROR/6 平台注册/主要页面可开);全量门禁终态绿
- [ ] 重写总报告(docs/rewrite-final-report.md):M1-M6 里程碑总览/成功标准逐条对照(spec 4 条)/遗留边界汇总(手工 checklist 索引)/新旧工程对比(代码量/测试数/平台)
- [ ] git log 整理确认(无 Co-Authored-By/Conventional Commits 抽查);提交不 push(用户手动)

## 成功标准
- 全部工作单元 checklist 通过
- 仓库只含 Kotlin 新工程:旧 Scala/sbt/webview 零残留(grep 实证),CI 单轨 Gradle
- 产物 codequest-1.0.0.zip 可安装;文档(README/CHANGELOG/release-checklist)与实现一致
- spec 4 条成功标准逐条给出终版结论(含证据索引)
