package com.codequest.infra.execution

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** [unit] 本地运行输出比较(WP16)。 */
class OutputComparatorTest {
    @Test
    fun `equal after trailing whitespace normalization`() {
        assertTrue(OutputComparator.matches("6\n", "6"))
        assertTrue(OutputComparator.matches("6", "6\n\n"))
        assertTrue(OutputComparator.matches("a b \nc", "a b\nc"))
        assertTrue(OutputComparator.matches("a\r\nc", "a\nc"))
    }

    @Test
    fun `different content fails`() {
        assertEquals(false, OutputComparator.matches("6", "7"))
        assertNotEquals(true, OutputComparator.matches("a\nb", "b\na"))
    }
}

/** [integration] Java/Kotlin 本地运行器(WP16):真实进程编译运行入口文件。 */
class LocalRunnerTest {
    @Test
    fun `java runner compiles and runs entry file with stdin`() {
        val runner = JavaRunner()
        assumeAvailable(runner)
        val dir = Files.createTempDirectory("cq-java-run")
        val file = dir.resolve("TwoSum.java")
        file.toFile().writeText(
            """
            import java.util.Scanner;
            class TwoSum {
                public static void main(String[] args) {
                    Scanner scanner = new Scanner(System.in);
                    System.out.println("hello " + scanner.nextLine());
                }
            }
            """.trimIndent(),
        )
        val result = runner.run(file, "world", timeoutSeconds = 60)
        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("hello world", result.stdout.trim())
    }

    @Test
    fun `java runner reports nonzero exit on runtime failure`() {
        val runner = JavaRunner()
        assumeAvailable(runner)
        val dir = Files.createTempDirectory("cq-java-run")
        val file = dir.resolve("Boom.java")
        file.toFile().writeText("class Boom { public static void main(String[] a) { throw new IllegalStateException(\"boom\"); } }")
        val result = runner.run(file, "", timeoutSeconds = 60)
        assertNotEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("boom"), result.stderr)
    }

    @Test
    fun `kotlin runner runs when available`() {
        val runner = KotlinRunner()
        assumeAvailable(runner)
        val dir = Files.createTempDirectory("cq-kotlin-run")
        val file = dir.resolve("Main.kt")
        file.toFile().writeText("fun main() { println(readLine()) }")
        val result = runner.run(file, "kotlin-input", timeoutSeconds = 120)
        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("kotlin-input", result.stdout.trim())
    }

    @Test
    fun `registry exposes runner status honestly`() {
        val registry = RunnerRegistry(JavaRunner(), KotlinRunner())
        assertEquals(true, registry.isRunnable("java"))
        // 未知语言一律不可运行
        assertEquals(false, registry.isRunnable("python3"))
        val java = registry.forLanguage("java")
        assertTrue(java is JavaRunner)
    }

    private fun assumeAvailable(runner: LanguageRunner) {
        assumeTrue(runner.isAvailable(), "runner for ${runner.languageId} not available on this machine: ${runner.unavailableReason()}")
    }
}

private fun assumeTrue(
    condition: Boolean,
    message: String,
) {
    org.junit.jupiter.api.Assumptions.assumeTrue(condition, message)
}
