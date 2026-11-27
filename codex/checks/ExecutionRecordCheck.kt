package com.codexserver.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExecutionRecordCheck {
    private fun tool(id: String, text: String = "", type: String = "commandExecution", stage: String = "命令执行") =
        ChatLine("tool", text, id, "turn", stage = stage, status = "completed", toolType = type)

    @Test fun gitAndOrdinaryCommandDetailsRenderWithoutTheReportedRegexCrash() {
        val cases = listOf(
            Triple("On branch main\nnothing to commit\n", "git status", "commandExecution"),
            Triple("BUILD SUCCESSFUL\n", "./gradlew :app:compileDebugKotlin", "commandExecution"),
            Triple("找到 3 处结果。", "rg example", "commandExecution"),
            Triple("{\"result\":true}", "workspace_get_state", "dynamicToolCall"),
            Triple("", "", "commandExecution")
        )
        for ((source, command, type) in cases) {
            val highlighted = highlightTimelineContent(source, command, type)
            assertEquals(source, highlighted.text)
            assertTrue(highlighted.spanStyles.all { it.start >= 0 && it.end <= source.length && it.start < it.end })
        }
    }

    @Test fun sourceAndMalformedOutputRemainReadableAcrossHighlightingBranches() {
        val cases = listOf(
            "package example\ninternal fun hello() = \"world\"" to "cat Sample.kt",
            "def hello():\n    return True" to "python3 script.py",
            "export const hello = () => true" to "node script.js",
            "\"unfinished\\" to "cat data.json",
            "/* unterminated\n😀\u0000" to "git diff",
            "x".repeat(48_000) to "git show"
        )
        for ((source, command) in cases) assertEquals(source,
            highlightTimelineContent(source, command, "commandExecution").text)
    }

    @Test fun blankSummariesCannotCreateEmptyRecordsOrNestedGroups() {
        val empty = tool("reason", " \n", "reasoning", "思考摘要")
        val git = tool("git", "On branch main", stage = "git status")
        assertTrue(foldCompletedProcessLines(listOf(empty), null, false).isEmpty())
        val record = foldCompletedProcessLines(listOf(empty, git), null, false).single()
        assertEquals(listOf(git), record.processItems)
        assertEquals(listOf(git), processTimelineEntries(listOf(empty, git)).flatMap { it.items })
        assertEquals(listOf(git), groupProcessSteps(listOf(empty, git)).flatMap { it.items })
        assertEquals("Git 操作", groupProcessSteps(record.processItems).single().type)
        // Stored protocol data is preserved; filtering changes only the view.
        assertEquals(" \n", empty.text)
    }

    @Test fun summaryPlaceholderBecomesVisibleOnlyWhenPublicSummaryArrives() {
        val params = JSONObject().put("turnId", "turn").put("itemId", "reason")
        var lines = applyChatNotification(emptyList(), "item/started", params.put("item",
            JSONObject("""{"id":"reason","type":"reasoning","summary":[],"content":["not a public summary"]}""")))
        assertTrue(foldCompletedProcessLines(lines, "turn", true).isEmpty())
        lines = applyChatNotification(lines, "item/reasoning/summaryTextDelta", params.put("delta", "正在核对结果。"))
        assertEquals("正在核对结果。", foldCompletedProcessLines(lines, "turn", true).single().processItems.single().text)
    }

    @Test fun noOutputStepsHaveExplicitStatesWhileRealCommandsStillHaveDetails() {
        val empty = tool("empty", type = "dynamicToolCall", stage = "workspace_get_state")
        assertFalse(hasExecutionRecordBody(empty))
        assertEquals("已完成，未返回详细内容", executionRecordEmptyHint(empty))
        assertEquals("正在执行，尚未收到详细内容", executionRecordEmptyHint(empty.copy(status = "inProgress")))
        assertEquals("执行失败，未返回详细内容", executionRecordEmptyHint(empty.copy(status = "failed")))
        val command = tool("git", stage = "git status")
        assertTrue(hasExecutionRecordBody(command))
        assertEquals("git status", executionRecordBody(command))
        assertEquals("git status\nOn branch main", executionRecordBody(command.copy(text = "On branch main")))
        assertEquals("git status\nOn branch main", executionRecordBody(command.copy(text = "git status\nOn branch main")))
    }

    @Test fun metadataOnlyCompletionKeepsStreamedGitOutputAndExitStatus() {
        val params = JSONObject().put("turnId", "turn").put("itemId", "git")
        val item = JSONObject().put("id", "git").put("type", "commandExecution")
            .put("command", "git status").put("cwd", "/project").put("status", "inProgress")
        var lines = applyChatNotification(emptyList(), "item/started", params.put("item", item))
        lines = applyChatNotification(lines, "item/commandExecution/outputDelta", params.put("delta", "On branch "))
        lines = applyChatNotification(lines, "item/commandExecution/outputDelta", params.put("delta", "main\n"))
        assertEquals("git status\n/project\nOn branch main\n", lines.single().text)
        item.put("status", "completed").put("exitCode", 0)
        lines = applyChatNotification(lines, "item/completed", params.put("item", item))
        assertEquals("git status\n/project\nOn branch main\n退出码：0", lines.single().text)
        assertEquals("completed", lines.single().status)
        assertEquals("Git 操作", processStepType(lines.single()))
        lines = applyChatNotification(lines, "item/completed", params.put("item", item))
        assertEquals(1, Regex("退出码：0").findAll(lines.single().text).count())
    }

    @Test fun fullCompletionOutputRemainsAuthoritativeAndDoesNotDuplicateDeltas() {
        val previous = tool("tool:git", "partial output", stage = "git status")
        val item = JSONObject("""{"id":"git","type":"commandExecution","command":"git status","aggregatedOutput":"complete output","exitCode":1,"status":"failed"}""")
        val lines = applyChatNotification(listOf(previous), "item/completed",
            JSONObject().put("turnId", "turn").put("item", item))
        assertEquals("git status\ncomplete output\n退出码：1", lines.single().text)
        assertEquals("failed", lines.single().status)
    }

    @Test fun imagesQuestionsFailuresAndProgressOrderAreNotFilteredAway() {
        val image = ChatLine("assistant", "", "image", "turn", images = listOf("codex/preview.png"), phase = "commentary")
        val question = ChatLine("request", "", "question", "turn", stage = "等待你的回答", status = "pending",
            questions = listOf(ChatQuestion("选择", listOf("继续", "停止"))))
        val failed = tool("failed").copy(status = "failed")
        val empty = tool("empty", type = "reasoning", stage = "思考摘要")
        val items = listOf(empty, image, question, failed)
        assertEquals(listOf(image, question, failed), visibleProcessItems(items))
        assertTrue(hasExecutionRecordBody(image))
        assertTrue(hasExecutionRecordBody(question))
        val record = foldCompletedProcessLines(items, null, false).single()
        assertEquals("failed", record.status)
        assertEquals(listOf(image, question, failed), record.processItems)
    }
}
