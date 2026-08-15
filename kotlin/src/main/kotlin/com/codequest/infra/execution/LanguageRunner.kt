package com.codequest.infra.execution

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 本地语言运行器(WP16):直接进程调用语言工具链编译并运行入口文件,
 * 用 stdin/stdout 驱动测试用例比对(替代旧版「创建临时运行配置」方案,
 * 无需 IDE 运行配置体系,进程隔离、超时可控)。
 *
 * 扩展点:新语言 = 实现 [LanguageRunner] 并注册进 [RunnerRegistry]。
 */
interface LanguageRunner {
    /** 语言标识(与 WorkspaceLanguage.id 一致)。 */
    val languageId: String

    /** 当前机器工具链是否可用。 */
    fun isAvailable(): Boolean

    /** 不可用原因(供 UI 如实提示)。 */
    fun unavailableReason(): String?

    /**
     * 运行入口文件:喂入 [stdin],收集 stdout/stderr。
     *
     * @throws IllegalStateException 工具链不可用时(调用前应先检查 [isAvailable])
     */
    fun run(
        entryFile: Path,
        stdin: String,
        timeoutSeconds: Long,
    ): RunResult
}

/** 单次运行结果;timedOut=true 时 exitCode 无意义。 */
data class RunResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
) {
    val succeeded: Boolean get() = !timedOut && exitCode == 0
}

/**
 * 输出比对(WP16):与 OJ 判定一致的宽松比较——
 * 统一换行符、忽略每行行尾空白与整体首尾空白行。
 */
object OutputComparator {
    fun matches(
        expected: String,
        actual: String,
    ): Boolean = normalize(expected) == normalize(actual)

    private fun normalize(text: String): String =
        text
            .replace("\r\n", "\n")
            .lineSequence()
            .map { it.trimEnd() }
            .dropWhile { it.isEmpty() }
            .toList()
            .dropLastWhile { it.isEmpty() }
            .joinToString("\n")
}

/** 进程执行公共支撑:stdin 喂入 + 流异步收集 + 超时强杀。 */
internal object ProcessRunner {
    fun run(
        command: List<String>,
        workingDir: File?,
        stdin: String,
        timeoutSeconds: Long,
    ): RunResult {
        val process =
            ProcessBuilder(command)
                .apply { workingDir?.let { directory(it) } }
                .start()
        val stdoutFuture =
            java.util.concurrent.CompletableFuture
                .supplyAsync { process.inputStream.bufferedReader().readText() }
        val stderrFuture =
            java.util.concurrent.CompletableFuture
                .supplyAsync { process.errorStream.bufferedReader().readText() }
        process.outputStream.bufferedWriter().use { writer ->
            writer.write(stdin)
            writer.flush()
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return RunResult(
                -1,
                stdoutFuture.handle { text, _ -> text ?: "" }.get(),
                stderrFuture.handle { text, _ -> text ?: "" }.get(),
                timedOut = true,
            )
        }
        return RunResult(process.exitValue(), stdoutFuture.get(), stderrFuture.get(), timedOut = false)
    }
}
