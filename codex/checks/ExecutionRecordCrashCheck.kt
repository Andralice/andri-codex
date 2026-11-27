package com.codexserver.mobile

import java.util.regex.PatternSyntaxException
import org.junit.Assert.*
import org.junit.Test

class ExecutionRecordCrashCheck {
    // Exact broken Kotlin detector shipped in 0.1.94. Reading a file with an unknown
    // extension and running a script both reach it before choosing a language.
    private val brokenDetector = """(?m)^\s*(?:package\s+[\w.]+|data\s+class\s+|(?:public\s+)?(?:class|interface|enum)\s+|(?:internal\s+|private\s+)?fun\s+|(?:val|var)\s+\w+"""

    @Test fun shippedDetectorReproducesTheCrashAndOutputSurvivesIt() {
        assertThrows(PatternSyntaxException::class.java) { Regex(brokenDetector) }
        for (command in listOf("cat README.md", "python3 codex/check.py")) {
            val output = "$command\n/project\n    缩进内容 😀\n退出码：0"
            val result = highlightExecutionRecord(output, command, "commandExecution") { text, _, _ ->
                Regex(brokenDetector).containsMatchIn(text)
                error("The broken pattern must throw first")
            }
            assertEquals(output, result.text.text)
            assertTrue(result.text.spanStyles.isEmpty())
            assertTrue(result.failure is PatternSyntaxException)
        }
    }

    @Test fun fixedRendererOpensFileReadsAndScriptsWithColors() {
        for ((command, output) in listOf(
            "cat README.md" to "# Readme\n    original indentation",
            "sed -n '1,30p' Example.kt" to "package demo\nfun main() = 42",
            "python3 check.py" to "def check():\n    return True\ncheck()",
            "bash deploy.sh" to "export RESULT='ok'\nexit 0"
        )) {
            val result = highlightExecutionRecord(output, command, "commandExecution")
            assertNull(command, result.failure)
            assertEquals(output, result.text.text)
            assertFalse(command, result.text.spanStyles.isEmpty())
        }
    }

    @Test fun failedHighlighterInitializationAndLaterAttemptsKeepOutputReadable() {
        val output = "读取文件\n    return 1\n"
        for (failure in listOf(
            ExceptionInInitializerError(PatternSyntaxException("bad rule", brokenDetector, -1)),
            NoClassDefFoundError("Could not initialize highlighter")
        )) {
            val result = highlightExecutionRecord(output, "cat example.py", "commandExecution") { _, _, _ ->
                throw failure
            }
            assertEquals(output, result.text.text)
            assertSame(failure, result.failure)
        }
    }

    @Test fun fatalVmErrorsAreNotHiddenAsHighlightFailures() {
        assertThrows(OutOfMemoryError::class.java) {
            highlightExecutionRecord("output", "cat file", "commandExecution") { _, _, _ ->
                throw OutOfMemoryError("simulated")
            }
        }
    }
}
