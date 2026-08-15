package com.codequest.infra.execution

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Java 本地运行器(WP16):`javac`(当前 IDE 运行时 JDK 自带)编译到临时目录后
 * `java -cp` 运行;入口类名取文件名基名(与模板文件名规则 = PascalCase 类名一致)。
 */
class JavaRunner : LanguageRunner {
    override val languageId: String = "java"

    private val javac: File? = executableInJavaHome("javac")
    private val java: File? = executableInJavaHome("java")

    override fun isAvailable(): Boolean = javac != null && java != null

    override fun unavailableReason(): String? =
        if (isAvailable()) {
            null
        } else {
            "javac/java not found under java.home (${System.getProperty("java.home")})"
        }

    override fun run(
        entryFile: Path,
        stdin: String,
        timeoutSeconds: Long,
    ): RunResult {
        val compiler = requireNotNull(javac) { "javac not found: ${unavailableReason()}" }
        val runtime = requireNotNull(java) { "java not found: ${unavailableReason()}" }
        val outDir = Files.createTempDirectory("cq-java-classes")
        val compile =
            ProcessRunner.run(
                listOf(compiler.absolutePath, "-d", outDir.toString(), entryFile.toString()),
                workingDir = entryFile.parent?.toFile(),
                stdin = "",
                timeoutSeconds = timeoutSeconds,
            )
        if (!compile.succeeded) {
            return RunResult(compile.exitCode, compile.stdout, "javac failed:\n${compile.stderr}", timedOut = compile.timedOut)
        }
        val mainClass = entryFile.fileName.toString().removeSuffix(".java")
        return ProcessRunner.run(
            listOf(runtime.absolutePath, "-cp", outDir.toString(), mainClass),
            workingDir = entryFile.parent?.toFile(),
            stdin = stdin,
            timeoutSeconds = timeoutSeconds,
        )
    }

    private fun executableInJavaHome(name: String): File? {
        val javaHome = System.getProperty("java.home") ?: return null
        val executable = File(javaHome, "bin/${exeName(name)}")
        return executable.takeIf { it.isFile && it.canExecute() }
    }

    private fun exeName(name: String): String = if (isWindows()) "$name.exe" else name
}

/**
 * Kotlin 本地运行器(WP16):依赖 PATH 上的 `kotlinc` 发行版(`kotlin` 启动器);
 * 机器未装时 [isAvailable] = false,UI 如实禁用并提示。
 *
 * 注:后续可用 IDE 内嵌 Kotlin 编译器替代 PATH 依赖(留作扩展点)。
 */
class KotlinRunner : LanguageRunner {
    override val languageId: String = "kotlin"

    override fun isAvailable(): Boolean = unavailableReason() == null

    override fun unavailableReason(): String? =
        if (resolveCommand("kotlin") != null) {
            null // kotlin 启动器存在即可用(脚本式运行入口 .kt 由启动器内部调编译器)
        } else {
            "kotlin launcher not found on PATH (install a Kotlin compiler distribution)"
        }

    override fun run(
        entryFile: Path,
        stdin: String,
        timeoutSeconds: Long,
    ): RunResult {
        val kotlin = resolveCommand("kotlin") ?: error(unavailableReason() ?: "kotlin launcher missing")
        return ProcessRunner.run(
            listOf(kotlin, entryFile.toString()),
            workingDir = entryFile.parent?.toFile(),
            stdin = stdin,
            timeoutSeconds = timeoutSeconds,
        )
    }

    /** Windows 下 ProcessBuilder 不解析 PATHEXT,手工探测常见扩展名。 */
    private fun resolveCommand(name: String): String? {
        val candidates = if (isWindows()) listOf("$name.bat", "$name.exe", "$name.cmd", name) else listOf(name)
        val pathDirs =
            System
                .getenv("PATH")
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
        for (dir in pathDirs) {
            for (candidate in candidates) {
                val file = File(dir, candidate)
                if (file.isFile) return file.absolutePath
            }
        }
        return null
    }
}

/** 语言 → 运行器注册表;未注册语言一律不可运行(UI 提示「暂不支持本地运行」)。 */
class RunnerRegistry(
    private vararg val runners: LanguageRunner,
) {
    fun forLanguage(languageId: String): LanguageRunner? = runners.firstOrNull { it.languageId == languageId }

    /** 该语言是否存在运行器且工具链可用。 */
    fun isRunnable(languageId: String): Boolean = forLanguage(languageId)?.isAvailable() == true
}

private fun isWindows(): Boolean = System.getProperty("os.name")?.lowercase()?.contains("win") == true
