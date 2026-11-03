package com.codexserver.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import java.io.IOException

class ComposerBehaviorCheck {
    @Test fun workspaceConfirmationReportsTimeoutButPreservesCancellation() = runBlocking {
        val confirmed = JSONObject().put("cwd", WORKSPACE_ROOT)
        assertSame(confirmed, awaitWorkspaceSettingsConfirmation(CompletableDeferred(confirmed)))
        try {
            awaitWorkspaceSettingsConfirmation(CompletableDeferred())
            fail("Missing server confirmation must fail visibly")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("保存结果尚未确认"))
        }
        val cancelled = CompletableDeferred<JSONObject>().apply { cancel() }
        try {
            awaitWorkspaceSettingsConfirmation(cancelled)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun streamRevealKeepsUnicodeAndCatchesUpWithoutChangingSource() {
        val target = "你好😀e\u0301，流式文字"
        var visible = ""
        var frames = 0
        while (visible != target) {
            val next = nextStreamText(visible, target)
            assertTrue(target.startsWith(next))
            assertFalse(next.last().isHighSurrogate())
            assertNotEquals("你好😀e", next)
            assertTrue(next.length > visible.length)
            visible = next
            assertTrue(++frames < 30)
        }
        assertEquals("替换后的最终结果", nextStreamText(visible, "替换后的最终结果"))
        visible = ""
        val burst = "长回复".repeat(400)
        frames = 0
        while (visible != burst) { visible = nextStreamText(visible, burst); assertTrue(++frames < 150) }
    }

    @Test fun collapsedComposerReservesItsRaisedPositionAndReadingGap() {
        assertEquals(160f, chatBottomInset(58f, false))
        assertTrue(chatBottomInset(58f, false) > 58f + 60f + 18f)
        assertEquals(106f, chatBottomInset(64f, true))
        assertEquals(222f, chatBottomInset(180f, true))
    }

    @Test fun structuredQuestionsSurviveEmptyTextAndHistory() {
        val thread = JSONObject("""{"turns":[{"id":"turn-1","items":[{"id":"q","type":"agentMessage","text":"","questions":[{"title":"选哪一个？","options":["安卓","iOS"]}]}]}]}""")
        val line = parseMessages(thread).single()
        assertEquals("选哪一个？", line.questions.single().title)
        assertEquals(listOf("安卓", "iOS"), line.questions.single().options)
        assertFalse(line.streaming)
    }

    @Test fun realProtocolEventsBuildOneOrderedTimelineAndRespectCompletion() {
        fun params(itemId: String = "", delta: String = "") = JSONObject().put("threadId", "t").put("turnId", "turn")
            .put("itemId", itemId).put("delta", delta)
        var lines = applyChatNotification(emptyList(), "item/started", params().put("item",
            JSONObject("""{"id":"a","type":"agentMessage","text":""}""")))
        lines = applyChatNotification(lines, "item/agentMessage/delta", params("a", "你好"))
        lines = applyChatNotification(lines, "item/agentMessage/delta", params("a", "世界"))
        assertEquals("你好世界", lines.single().text)
        assertTrue(lines.single().streaming)
        lines = applyChatNotification(lines, "item/started", params().put("item",
            JSONObject("""{"id":"cmd","type":"commandExecution","command":"pwd","cwd":"/srv","status":"inProgress"}""")))
        lines = applyChatNotification(lines, "item/commandExecution/outputDelta", params("cmd", "/srv\n"))
        assertTrue(lines.last().text.endsWith("/srv\n"))
        lines = applyChatNotification(lines, "item/completed", params().put("item",
            JSONObject("""{"id":"cmd","type":"commandExecution","command":"pwd","cwd":"/srv","status":"completed","aggregatedOutput":"/srv\n","exitCode":0}""")))
        assertEquals(listOf("a", "tool:cmd"), lines.map { it.id })
        assertEquals("completed", lines.last().status)
        assertTrue(lines.last().text.contains("退出码：0"))
        lines = applyChatNotification(lines, "item/completed", params().put("item",
            JSONObject("""{"id":"a","type":"agentMessage","text":"最终权威内容"}""")))
        assertEquals("最终权威内容", lines.first().text)
        assertFalse(lines.first().streaming)
        val plan = applyChatNotification(lines, "turn/plan/updated", params().put("plan",
            org.json.JSONArray("""[{"step":"检查","status":"completed"},{"step":"修复","status":"inProgress"}]""")))
        assertTrue(plan.last().text.contains("✓ 检查"))
        assertTrue(plan.last().text.contains("● 修复"))
        assertEquals("completed", applyChatNotification(plan, "turn/completed", params().put("turn",
            JSONObject("""{"id":"turn","status":"completed"}"""))).last().status)
    }

    @Test fun toolHistoryShowsDiffAndSummariesButNotPrivateReasoningContent() {
        val diff = parseChatItem(JSONObject("""{"id":"f","type":"fileChange","status":"completed","changes":[{"path":"a.kt","diff":"+fix","kind":{"type":"update"}}]}"""))!!
        assertEquals("tool", diff.role)
        assertTrue(diff.text.contains("+fix"))
        val reasoning = parseChatItem(JSONObject("""{"id":"r","type":"reasoning","summary":["检查问题"],"content":["private text"]}"""))!!
        assertEquals("检查问题", reasoning.text)
        assertFalse(reasoning.text.contains("private"))
        val request = requestChatLine(JSONObject("""{"id":42,"method":"item/tool/requestUserInput","params":{"threadId":"t","turnId":"turn","questions":[{"id":"q","question":"选择项目？"}]}}"""))
        assertEquals("request:42", request.id)
        assertEquals("pending", request.status)
        assertEquals("选择项目？", request.text)
    }

    @Test fun emptyReasoningNeverCreatesVisibleRowsInLiveOrHistoricalShortChats() {
        val params = JSONObject().put("turnId", "short")
        var lines = applyChatNotification(emptyList(), "item/started", params.put("item",
            JSONObject("""{"id":"r","type":"reasoning","summary":[]}""")))
        assertEquals(1, lines.size) // Keep the item available for subsequent deltas.
        assertTrue(visibleChatLines(lines).isEmpty())
        lines = applyChatNotification(lines, "item/completed", params.put("item",
            JSONObject("""{"id":"a","type":"agentMessage","text":"你好"}""")))
        assertEquals(listOf("你好"), visibleChatLines(lines).map { it.text })
        val history = parseMessages(JSONObject("""{"turns":[{"id":"short","status":"completed","items":[{"id":"r","type":"reasoning","summary":["  "]},{"id":"a","type":"agentMessage","text":"你好"}]}]}"""))
        assertEquals(listOf("你好"), visibleChatLines(history).map { it.text })
        assertEquals(listOf("命令执行"), visibleChatLines(listOf(ChatLine("tool", "", "cmd", stage = "命令执行"))).map { it.stage })
    }

    @Test fun reasoningFollowsItsWholeTurnAndKeepsTheFinalAnswerSeparate() {
        fun params() = JSONObject().put("turnId", "long").put("itemId", "r")
        var lines = applyChatNotification(emptyList(), "item/started", params().put("item",
            JSONObject("""{"id":"r","type":"reasoning","summary":[]}""")))
        lines = applyChatNotification(lines, "item/reasoning/summaryTextDelta", params().put("delta", "检查服务状态"))
        assertEquals("检查服务状态", visibleChatLines(lines).single().text)
        assertTrue(reasoningSummaryActive(lines.single(), "long"))
        lines = applyChatNotification(lines, "item/completed", params().put("item",
            JSONObject("""{"id":"r","type":"reasoning","summary":["检查服务状态"]}""")))
        assertEquals("completed", lines.single().status)
        assertTrue(reasoningSummaryActive(lines.single(), "long")) // Reasoning item finished, task still running.
        lines = applyChatNotification(lines, "item/completed", params().put("item",
            JSONObject("""{"id":"a","type":"agentMessage","text":"服务正常，检查完成。"}""")))
        lines = applyChatNotification(lines, "turn/completed", params().put("turn",
            JSONObject("""{"id":"long","status":"completed"}""")))
        assertFalse(reasoningSummaryActive(lines.first(), null))
        assertFalse(reasoningSummaryActive(lines.first(), "next-turn"))
        assertFalse(reasoningSummaryActive(lines.first().copy(turnId = ""), ""))
        assertEquals("检查服务状态", visibleChatLines(lines).first().text) // Still available to expand manually.
        assertEquals("服务正常，检查完成。", visibleChatLines(lines).last().text)
        assertFalse(reasoningSummaryActive(lines.last(), "long"))
    }

    @Test fun executionModesMatchAppServerSchema() {
        val ask = ExecutionOptions("/home/ubuntu", ExecutionMode.ASK).params()
        assertEquals("on-request", ask.getString("approvalPolicy"))
        assertEquals("user", ask.getString("approvalsReviewer"))
        assertEquals("workspace-write", ask.getString("sandbox"))
        assertEquals("medium", ask.getJSONObject("config").getString("model_reasoning_effort"))
        assertEquals("high", ExecutionOptions("/home/ubuntu", ExecutionMode.ASK, "high").params(true).getString("effort"))
        val auto = ExecutionOptions("/srv/candybear", ExecutionMode.AUTO).params()
        assertEquals("auto_review", auto.getString("approvalsReviewer"))
        val full = ExecutionOptions("/srv/candybear", ExecutionMode.FULL).params()
        assertEquals("never", full.getString("approvalPolicy"))
        assertEquals("danger-full-access", full.getString("sandbox"))
        assertEquals("dangerFullAccess", ExecutionOptions("/srv/candybear", ExecutionMode.FULL).params(true).getJSONObject("sandboxPolicy").getString("type"))
        assertEquals(ExecutionMode.FULL, executionOptions(JSONObject().put("cwd", "/srv/candybear").put("approvalPolicy", "never").put("sandbox", "danger-full-access"), ExecutionOptions("/home/ubuntu")).mode)
        assertEquals(ExecutionMode.FULL, executionOptions(JSONObject().put("cwd", "/srv/candybear").put("approvalPolicy", "never").put("sandbox", JSONObject().put("type", "dangerFullAccess")), ExecutionOptions("/home/ubuntu")).mode)
        assertEquals(ExecutionMode.AUTO, executionOptions(JSONObject().put("cwd", "/srv/candybear")
            .put("approvalPolicy", "on-request").put("approvalsReviewer", "auto_review")
            .put("sandboxPolicy", JSONObject().put("type", "workspaceWrite")).put("effort", "high"),
            ExecutionOptions("/home/ubuntu")).mode)
        assertEquals("high", executionOptions(JSONObject().put("cwd", "/srv/candybear")
            .put("approvalPolicy", "on-request").put("approvalsReviewer", "user")
            .put("sandboxPolicy", JSONObject().put("type", "workspaceWrite")).put("effort", "high"),
            ExecutionOptions("/home/ubuntu")).effort)
    }

    @Test fun timelineErrorsStayAfterTheirTurnWhenHistoryMerges() {
        val reply = ChatLine("assistant", "结果", "reply", "turn-1")
        val failure = chatFailure(listOf(reply), "超时", "读取服务状态失败", "turn-1", "turnError:turn-1")
        val merged = mergeHistory(listOf(reply, failure), listOf(reply))
        assertEquals(listOf("assistant", "error"), merged.map { it.role })
        assertEquals("读取服务状态失败", merged.last().stage)
        assertEquals("超时", merged.last().text)
    }

    @Test fun historyCompletionClearsStaleStreamingState() {
        val saved = ChatLine("assistant", "最终结果", "reply", "turn-1", streaming = false, status = "completed")
        val stale = ChatLine("assistant", "最终结果", "reply", "turn-1", streaming = true, status = "inProgress")
        val merged = mergeHistory(listOf(saved), listOf(stale))
        assertFalse(merged.single().streaming)
        assertEquals("completed", merged.single().status)
    }
}
