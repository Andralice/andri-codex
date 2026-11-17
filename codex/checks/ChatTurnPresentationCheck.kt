package com.codexserver.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatTurnPresentationCheck {
    private fun progress(id: String, text: String = "正在检查", turn: String = "turn") =
        ChatLine("assistant", text, id, turn, phase = "commentary")
    private fun answer(turn: String = "turn") =
        ChatLine("assistant", "已改好源码：\n\n结果与验证。", "answer:$turn", turn, phase = "final_answer")

    @Test fun screenshotProgressAndToolsBecomeOneFoldedRecordWithOnlyFinalAnswerExpanded() {
        val user = ChatLine("user", "修改页面", "user", "turn")
        val first = progress("first")
        val tool = ChatLine("tool", "BUILD SUCCESSFUL", "build", "turn", stage = "命令执行")
        val last = progress("last", "构建已通过，等待静态检查。")
        val final = answer()
        val rows = foldCompletedProcessLines(listOf(user, first, tool, last, final), null, false)
        assertEquals(listOf("user", "thought", "assistant"), rows.map { it.role })
        assertEquals(listOf(first, tool, last), rows[1].processItems)
        assertEquals(final, rows.last())
        assertEquals(listOf(final), rows.filter { assistantReplyCanCopy(it, null, false) })
    }

    @Test fun phasesRemainAuthoritativeWhenLateCommentaryArrivesAfterTheAnswer() {
        val final = answer()
        val rows = foldCompletedProcessLines(listOf(progress("start"), final, progress("late")), null, false)
        assertEquals(listOf(final), rows.filter { it.role == "assistant" })
        assertEquals(listOf("start", "late"), rows.first().processItems.map { it.id })
    }

    @Test fun olderServersKeepTheLastMessageOfEachTurnAndNeverMergeAcrossTurns() {
        val first = listOf(progress("p1", turn = "one").copy(phase = ""), answer("one").copy(phase = ""))
        val second = listOf(progress("p2", turn = "two").copy(phase = ""), answer("two").copy(phase = ""))
        val rows = foldCompletedProcessLines(first + second, null, false)
        assertEquals(listOf("thought", "assistant", "thought", "assistant"), rows.map { it.role })
        assertEquals(listOf("one", "two"), rows.filter { it.role == "assistant" }.map { it.turnId })
        assertEquals(listOf("p1", "p2"), rows.filter { it.role == "thought" }.flatMap { it.processItems }.map { it.id })
    }

    @Test fun livePhasesSurviveDeltasAndCompletionSoOnlyCompletedFinalAnswersCanBeCopied() {
        val params = JSONObject().put("turnId", "turn").put("itemId", "reply")
        var lines = applyChatNotification(emptyList(), "item/started", params.put("item",
            JSONObject().put("id", "reply").put("type", "agentMessage").put("phase", "commentary")))
        lines = applyChatNotification(lines, "item/agentMessage/delta", params.put("delta", "正在验证"))
        assertEquals("commentary", lines.single().phase)
        assertFalse(assistantReplyCanCopy(lines.single(), "turn", true))
        lines = applyChatNotification(lines, "item/completed", params.put("item",
            JSONObject().put("id", "reply").put("type", "agentMessage").put("text", "验证中")))
        assertEquals("commentary", lines.single().phase)
        val final = answer()
        assertFalse(assistantReplyCanCopy(final.copy(streaming = true), null, false))
        assertFalse(assistantReplyCanCopy(final, "turn", true))
        assertTrue(assistantReplyCanCopy(final, null, false))
        assertTrue(assistantReplyCanCopy(final, "another-turn", true))
    }

    @Test fun historyAndLiveEventsUseTheSamePhasesAndPresentation() {
        val items = listOf(
            JSONObject().put("id", "p").put("type", "agentMessage").put("phase", "commentary").put("text", "检查中"),
            JSONObject().put("id", "a").put("type", "agentMessage").put("phase", "final_answer").put("text", "已改好源码")
        )
        val history = parseMessages(JSONObject().put("turns", org.json.JSONArray().put(JSONObject()
            .put("id", "turn").put("status", "completed").put("items", org.json.JSONArray(items)))))
        var live = emptyList<ChatLine>()
        items.forEach { live = applyChatNotification(live, "item/completed", JSONObject().put("turnId", "turn").put("item", it)) }
        val historicalRows = foldCompletedProcessLines(history, null, false)
        val liveRows = foldCompletedProcessLines(live, null, false)
        assertEquals(historicalRows.map { it.id }, liveRows.map { it.id })
        assertEquals("a", historicalRows.last().id)
        assertEquals("p", historicalRows.first().processItems.single().id)
    }

    @Test fun approvalsQuestionsAndErrorsStayVisibleWhileEarlierImagesRemainInTheRecord() {
        val image = progress("image").copy(images = listOf("codex/preview.png"))
        val request = ChatLine("request", "请批准", "request", "turn", status = "pending")
        val question = ChatLine("assistant", "选哪个？", "question", "turn", questions = listOf(ChatQuestion("选项", listOf("一", "二"))))
        val error = ChatLine("error", "任务中断", "error", "turn")
        val rows = foldCompletedProcessLines(listOf(image, request, question, error), null, false)
        assertEquals(listOf("thought", "request", "assistant", "error"), rows.map { it.role })
        assertEquals(image.images, rows.first().processItems.single().images)
        assertFalse(assistantReplyCanCopy(question, null, false))
    }

    @Test fun interruptedCommentaryNeverBecomesACopyableFinalAnswer() {
        val line = progress("interrupted").copy(status = "interrupted")
        val rows = foldCompletedProcessLines(listOf(line), null, false)
        assertEquals("interrupted", rows.single().status)
        assertTrue(rows.none { assistantReplyCanCopy(it, null, false) })
        val unscoped = listOf(answer().copy(turnId = ""), answer("other").copy(turnId = ""))
        assertEquals(unscoped, foldCompletedProcessLines(unscoped, null, false))
    }

    @Test fun progressCommentaryGetsItsOwnLevelAboveCommandDetails() {
        val progress = ChatLine("assistant", "正在检查", "progress", "turn", phase = "commentary")
        val command = ChatLine("tool", "BUILD SUCCESSFUL", "command", "turn", stage = "命令执行")
        val groups = groupProcessItems(listOf(progress, command))
        assertEquals(listOf(progress), groups.progress)
        assertEquals(listOf(command), groups.commands)
    }
}
