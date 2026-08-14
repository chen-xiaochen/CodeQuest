plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.codequest"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// 独立源码目录:与旧 sbt 构建的 src/main/scala、src/main/resources、src/test 完全隔离,
// 旧 Scala 插件与 Gradle/Kotlin 新工程互不影响(sbt 不识别 kotlin/ 目录)。
// 注意必须用 setSrcDirs 替换(而非 srcDir 追加),否则 Gradle 默认的 src/main/resources
// 会把旧 Scala 插件的 META-INF/plugin.xml 一并打进新插件,造成资源冲突。
sourceSets {
    main {
        kotlin.setSrcDirs(listOf("kotlin/src/main/kotlin"))
        java.setSrcDirs(emptyList<String>())
        resources.setSrcDirs(listOf("kotlin/src/main/resources"))
    }
    test {
        kotlin.setSrcDirs(listOf("kotlin/src/test/kotlin"))
        java.setSrcDirs(emptyList<String>())
        resources.setSrcDirs(emptyList<String>())
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.2")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
}

// ---------------------------------------------------------------------------
// 代码质量门禁(WP10):detekt + ktlint + 分层依赖检查,全部纳入 `check` 任务链。
// 采用「独立 configuration + JavaExec」方式而非 Gradle 插件:
// - detekt 官方插件对 Gradle 9.x 的兼容性尚不稳定,CLI 方式零侵入
// - 依赖只经 Maven Central(本地走 GRADLE_USER_HOME 阿里云镜像)解析
// ---------------------------------------------------------------------------
val detektCli by configurations.creating
val ktlintCli by configurations.creating

dependencies {
    "detektCli"("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
    "ktlintCli"("com.pinterest.ktlint:ktlint-cli:1.8.0")
}

val kotlinMainSrc = "kotlin/src/main/kotlin"

// detekt:静态分析,规则集 = 默认规则 + config/detekt.yml 覆盖
tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Runs detekt static analysis over kotlin/src/main/kotlin."
    mainClass = "io.gitlab.arturbosch.detekt.cli.Main"
    classpath = detektCli
    args(
        "--input", kotlinMainSrc,
        "--config", "config/detekt.yml",
        "--build-upon-default-config",
        "--report", "txt:${layout.buildDirectory.get()}/reports/detekt/detekt.txt",
    )
}

// ktlint:官方 intellij_idea 代码风格(可通过 .editorconfig 调整)
tasks.register<JavaExec>("ktlint") {
    group = "verification"
    description = "Runs ktlint format checking over kotlin/src/main/kotlin."
    mainClass = "com.pinterest.ktlint.Main"
    classpath = ktlintCli
    args(kotlinMainSrc)
}

// 分层依赖检查(spec §1):依赖方向 ui → platform → spi → core,infra 实现 core 仓库接口。
// 禁止反向 import:core→{spi,platform,infra,ui}、spi→{platform,infra,ui}、platform→{infra,ui}、infra→ui。
// 另:platform 下各 OJ 子包(leetcode/leetcodecn/luogu/atcoder/codeforces/hackerrank...)互相 import 禁令。
// 扫描 import 语句(含通配符 import),发现违规即令构建失败。
tasks.register("checkLayerDependencies") {
    group = "verification"
    description = "Enforces layered architecture dependency rules on Kotlin imports."
    doLast {
        val packageRegex = Regex("""^\s*package\s+([\w.]+)""")
        val importRegex = Regex("""^\s*import\s+([\w.*]+)""")

        // 各层禁止 import 的下层/外层包前缀
        val forbiddenByLayer = mapOf(
            "core" to listOf("spi", "platform", "infra", "ui"),
            "spi" to listOf("platform", "infra", "ui"),
            "platform" to listOf("infra", "ui"),
            "infra" to listOf("ui"),
            // ui 处于最顶层,可 import 所有层
        )

        data class Violation(val file: String, val from: String, val to: String, val rule: String)

        val violations = mutableListOf<Violation>()
        val srcRoot = rootProject.layout.projectDirectory.dir(kotlinMainSrc).asFile

        srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                val pkg = packageRegex.find(text)?.groupValues?.get(1) ?: return@forEach // 无 package 声明的文件跳过
                val layer = pkg.takeIf { it.startsWith("com.codequest.") }
                    ?.removePrefix("com.codequest.")
                    ?.substringBefore('.')
                    ?: return@forEach

                // 该文件所处的 OJ 子包(若在 com.codequest.platform.<oj> 之下)
                val ownOj = pkg.takeIf { it.startsWith("com.codequest.platform.") }
                    ?.removePrefix("com.codequest.platform.")
                    ?.takeIf { it.isNotEmpty() }
                    ?.substringBefore('.')

                text.lineSequence().forEach { line ->
                    val imported = importRegex.find(line)?.groupValues?.get(1) ?: return@forEach
                    if (!imported.startsWith("com.codequest.")) return@forEach

                    val targetLayer = imported.removePrefix("com.codequest.").substringBefore('.')
                    // 规则 1:层间反向/越层依赖
                    forbiddenByLayer[layer]?.forEach { banned ->
                        if (targetLayer == banned) {
                            violations += Violation(
                                file.relativeTo(srcRoot).path, pkg, imported,
                                "$layer -> $banned 反向/越层依赖",
                            )
                        }
                    }

                    // 规则 2:platform 各 OJ 子包互相 import 禁令
                    if (layer == "platform" && imported.startsWith("com.codequest.platform.")) {
                        val targetOj = imported.removePrefix("com.codequest.platform.")
                            .takeIf { it.isNotEmpty() }
                            ?.substringBefore('.')
                        if (targetOj != null && ownOj != null && targetOj != ownOj) {
                            violations += Violation(
                                file.relativeTo(srcRoot).path, pkg, imported,
                                "platform OJ 子包互引禁令: $ownOj -> $targetOj",
                            )
                        }
                    }
                }
            }

        if (violations.isNotEmpty()) {
            val detail = violations.joinToString("\n") { "  - ${it.file} [${it.rule}]: ${it.from} imports ${it.to}" }
            throw GradleException(
                "分层依赖检查失败,共 ${violations.size} 处违规(依赖方向必须满足 ui → platform → spi → core,infra 仅可依赖 core):\n$detail",
            )
        }
        logger.lifecycle("分层依赖检查通过:未发现反向/越层依赖或 platform OJ 子包互引。")
    }
}

tasks.named("check") {
    dependsOn("detekt", "ktlint", "checkLayerDependencies")
}
