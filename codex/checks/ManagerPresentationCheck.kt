package com.codexserver.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManagerPresentationCheck {
    private val source = JSONObject().put("kind", "monitor_followup").put("summary", "补充验证").put("eventId", "event")
    private fun line(role: String, text: String, id: String = role, origin: String = "monitor_followup") =
        ChatLine(role, text, id, "turn", origin = origin, originSummary = "补充验证", monitorEventId = "event")

    @Test fun followupCollapsesAllOutputAndPreservesEveryOriginalItem() {
        val raw = listOf(line("user", "后台要求"), line("tool", "large output"), line("assistant", "result"))
        val record = visibleChatLines(raw).single()
        assertEquals("monitor", record.role)
        assertEquals("补充验证", record.text)
        assertEquals(raw, record.processItems)
        assertEquals(record, foldCompletedProcessLines(listOf(record), null, false).single())
    }

    @Test fun backgroundReviewsDisappearButApprovalsQuestionsAndErrorsRemain() {
        for (origin in listOf("monitor_review", "monitor_notice")) {
            val approval = line("request", "批准？", origin = origin)
            val question = line("assistant", "选择？", "question", origin).copy(questions = listOf(ChatQuestion("选择", listOf("一", "二"))))
            val error = line("error", "中断", origin = origin)
            val raw = listOf(line("user", "review json", origin = origin), line("assistant", "review result", origin = origin), approval, question, error)
            assertEquals(listOf(approval, question, error), visibleChatLines(raw))
        }
    }

    @Test fun genuineUserTextIsNeverFilteredByWording() {
        val ordinary = line("user", "【任务管家自动跟进；仅继续原用户已授权的工作】\n监控事件：event\nhello", origin = "")
        assertEquals(listOf(ordinary), visibleChatLines(listOf(ordinary)))
    }

    @Test fun userSteeringInsideAutomaticTurnAndItsAnswerStayVisible() {
        val automated = line("user", "自动要求")
        val steering = line("user", "先给我看方案", "steering", "")
        val answer = line("assistant", "方案")
        val result = visibleChatLines(listOf(automated, steering, answer))
        assertEquals(listOf("monitor", "user", "assistant"), result.map { it.role })
        assertEquals(listOf(automated), result.first().processItems)
        assertEquals(steering, result[1])
    }

    @Test fun historyAndLiveEventsProduceTheSameCollapsedRecord() {
        val items = listOf(
            JSONObject().put("id", "u").put("type", "userMessage").put("andriOrigin", source)
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "跟进要求"))),
            JSONObject().put("id", "a").put("type", "agentMessage").put("text", "实际结果").put("andriOrigin", source))
        val turn = JSONObject().put("id", "turn").put("andriOrigin", source).put("items", JSONArray(items))
        val history = parseMessages(JSONObject().put("turns", JSONArray().put(turn)))
        var live = emptyList<ChatLine>()
        items.forEach { item -> live = applyChatNotification(live, "item/completed", JSONObject().put("turnId", "turn").put("item", item).put("andriOrigin", source)) }
        val a = visibleChatLines(history).single(); val b = visibleChatLines(live).single()
        assertEquals(a.id, b.id)
        assertEquals(a.text, b.text)
        assertEquals(a.processItems.map { it.text }, b.processItems.map { it.text })
    }

    @Test fun deltaAndCompletionMetadataSurviveWithoutConvertingHumanSteering() {
        val human = line("user", "我的消息", "human", "")
        var live = applyChatNotification(listOf(human), "item/agentMessage/delta", JSONObject().put("turnId", "turn").put("itemId", "a").put("delta", "结果").put("andriOrigin", source))
        assertEquals("monitor_followup", live.last().origin)
        live = applyChatNotification(live, "turn/completed", JSONObject().put("turn", JSONObject().put("id", "turn").put("status", "completed").put("andriOrigin", source)))
        assertEquals("", live.first().origin)
        assertFalse(live.last().streaming)
        assertEquals(listOf("user", "assistant"), visibleChatLines(live).map { it.role })
    }

    @Test fun overviewParsesPagingAndEvidenceBoundaries() {
        val row = JSONObject().put("id", "e").put("threadId", "t").put("created", 12.5).put("phase", "done")
            .put("outcome", "仍缺真机验收").put("inspection", JSONObject().put("report", JSONObject().put("summary", "核对完成").put("limitations", JSONArray().put("非真机"))))
        val result = parseManagerOverview(JSONObject().put("available", true).put("managerThreadId", "manager")
            .put("counts", JSONObject().put("done", 2).put("needs_attention", 1)).put("nextCursor", "12.5:e").put("entries", JSONArray().put(row)))
        assertEquals(3, result.recordCount)
        assertEquals(1, result.attentionCount)
        assertEquals(12500L, result.entries.single().created)
        assertEquals(listOf("非真机"), result.entries.single().inspectionLimitations)
        assertEquals("12.5:e", result.nextCursor)
        assertEquals("本轮已关闭", monitorPhaseLabel("done"))
        assertEquals("", parseManagerOverview(JSONObject().put("nextCursor", JSONObject.NULL)).nextCursor)
    }

    private fun inboxOverview(questions: List<String>, pending: List<String> = questions,
                              notifications: List<String> = emptyList(), questionCursor: String = "", noticeCursor: String = ""): ManagerOverview {
        fun rows(ids: List<String>) = JSONArray(ids.map { id -> JSONObject().put("id", id).put("eventId", id)
            .put("threadId", "task").put("kind", "question").put("message", "需要决定：$id").put("created", 12.5) })
        return parseManagerOverview(JSONObject().put("available", true).put("inbox", JSONObject()
            .put("questions", rows(questions)).put("notifications", rows(notifications))
            .put("pendingQuestionIds", JSONArray(pending)).put("questionsCount", pending.size)
            .put("notificationsCount", notifications.size).put("questionsNextCursor", questionCursor)
            .put("notificationsNextCursor", noticeCursor)))
    }

    @Test fun independentInboxParsesQuestionsAndRemainsCompatibleWithOlderServers() {
        val inbox = inboxOverview(listOf("old-question"), questionCursor = "cursor").inbox!!
        assertEquals("需要决定：old-question", inbox.questions.single().message)
        assertEquals(12500L, inbox.questions.single().created)
        assertEquals("cursor", inbox.questionsNextCursor)
        assertEquals(setOf("old-question"), inbox.pendingQuestionIds)
        assertNull(parseManagerOverview(JSONObject().put("available", true)).inbox)
    }

    @Test fun refreshingPagedHistoryRemovesAnsweredQuestionsAndAddsNewQuestions() {
        val old = inboxOverview(listOf("answered", "older"), questionCursor = "older-cursor")
        val fresh = inboxOverview(listOf("new"), pending = listOf("new", "older"))
        val merged = mergeManagerOverview(old, fresh, "refresh").inbox!!
        assertEquals(listOf("new", "older"), merged.questions.map { it.id })
        assertEquals("older-cursor", merged.questionsNextCursor)
        assertEquals(2, merged.questionsCount)
    }

    @Test fun loadingOlderQuestionsPreservesOrderAndDoesNotDuplicateOrReviveAnswers() {
        val old = inboxOverview(listOf("new", "answered"), questionCursor = "next", notifications = listOf("notice"), noticeCursor = "notice-next")
        val fresh = inboxOverview(listOf("new", "older"), pending = listOf("new", "older"), notifications = listOf("new-notice"))
        val merged = mergeManagerOverview(old, fresh, "questions").inbox!!
        assertEquals(listOf("new", "older"), merged.questions.map { it.id })
        assertEquals("", merged.questionsNextCursor)
        assertEquals("notice-next", merged.notificationsNextCursor)
        assertEquals(listOf("new-notice", "notice"), merged.notifications.map { it.id })
    }

    @Test fun failedAndQueuedDeliveryNeverClaimsTheQuestionWasShown() {
        assertTrue(managerQuestionDeliveryLabel("failed").contains("未确认送达"))
        assertTrue(managerQuestionDeliveryLabel("queued").contains("准备中"))
        assertTrue(managerQuestionDeliveryLabel("shown").contains("可前往"))
    }
}
