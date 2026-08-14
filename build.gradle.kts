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
