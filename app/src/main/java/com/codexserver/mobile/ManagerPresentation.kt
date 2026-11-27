package com.codexserver.mobile

import org.json.JSONObject

internal val ChatLine.isMonitorActivity: Boolean
    get() = origin in setOf("monitor_review", "monitor_followup", "monitor_notice")

/** Server provenance is authoritative; text resembling a service prompt is ordinary user text. */
internal fun managerChatLines(lines: List<ChatLine>): List<ChatLine> {
    val turns = lines.filter { it.turnId.isNotBlank() }.groupBy { it.turnId }
    val grouped = turns.filterValues { rows -> rows.any { it.isMonitorActivity } }
    val emitted = mutableSetOf<String>()
    return lines.mapNotNull { line ->
        val rows = grouped[line.turnId] ?: return@mapNotNull line
        val origin = rows.firstOrNull { it.isMonitorActivity } ?: return@mapNotNull line
        val mixed = rows.any { it.role == "user" && !it.isMonitorActivity }
        if (line.role in setOf("request", "error") || line.questions.isNotEmpty()) return@mapNotNull line
        if (mixed && !(line.role == "user" && line.isMonitorActivity)) return@mapNotNull line
        if (origin.origin in setOf("monitor_review", "monitor_notice")) return@mapNotNull null
        if (!emitted.add(line.turnId)) return@mapNotNull null
        val folded = if (mixed) rows.filter { it.role == "user" && it.isMonitorActivity }
            else rows.filter { it.role !in setOf("request", "error") && it.questions.isEmpty() }
        ChatLine("monitor", origin.originSummary.ifBlank { "继续处理原任务中尚未完成的事项" },
            "monitor:${line.turnId}", line.turnId, stage = "管家跟进",
            status = folded.firstOrNull { it.status in setOf("failed", "interrupted", "declined") }?.status ?: "completed",
            processItems = folded, origin = "monitor_followup", originSummary = origin.originSummary,
            monitorEventId = origin.monitorEventId)
    }
}

internal fun monitorFollowupText(text: String): String {
    val prefix = "【任务管家自动跟进；仅继续原用户已授权的工作】\n监控事件："
    if (!text.startsWith(prefix)) return text
    val body = text.substringAfter('\n').substringAfter('\n')
    return body.substringBefore("\n保留原任务的权限和审批规则；不要扩大范围。已有用户问题或审批时等待用户，不得代答。")
}

internal fun monitorPhaseLabel(phase: String): String = when (phase) {
    "pending", "review_sending" -> "等待检查"
    "reviewing" -> "检查中"
    "retry_wait" -> "等待自动恢复"
    "inspecting" -> "独立检查中"
    "decided" -> "正在处理"
    "followup_sending", "followup_queued", "followup_starting" -> "跟进待启动"
    "followed_up" -> "已发起跟进"
    "done" -> "本轮已关闭"
    "needs_attention" -> "待处理"
    "superseded" -> "已有后续进展"
    "skipped" -> "无需跟进"
    "ignored" -> "不在监控范围"
    else -> "状态待核对"
}

internal data class ManagerEvent(
    val id: String, val threadId: String, val title: String, val created: Long, val phase: String,
    val summary: String, val followup: String, val question: String, val outcome: String,
    val outcomePhase: String, val evidenceLevel: String, val inspectionSummary: String,
    val inspectionLimitations: List<String>
)
internal data class ManagerOverview(
    val available: Boolean, val message: String = "", val threadId: String = "", val enabled: Boolean = false,
    val heartbeatAge: Long = 0, val generatedAt: Long = 0, val recordCount: Int = 0, val followupCount: Int = 0,
    val attentionCount: Int = 0, val entries: List<ManagerEvent> = emptyList(), val nextCursor: String = "",
    val inbox: ManagerInbox? = null
)

internal data class ScheduledTask(
    val id: String, val threadId: String, val title: String, val prompt: String,
    val timezone: String, val repeatDaily: Boolean, val nextRunAt: Long, val state: String,
    val detail: String, val lastRunAt: Long
)

internal fun parseScheduledTasks(value: JSONObject): List<ScheduledTask> = buildList {
    val rows = value.optJSONArray("items") ?: return@buildList
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        add(ScheduledTask(row.text("id"), row.text("thread_id"), row.text("title"), row.text("prompt"),
            row.text("timezone"), row.optInt("repeat_daily") != 0, (row.optDouble("next_run_at") * 1000).toLong(),
            row.text("state"), row.text("detail"), (row.optDouble("last_run_at") * 1000).toLong()))
    }
}

internal fun scheduledTaskStateLabel(state: String): String = when (state) {
    "scheduled" -> "待执行"
    "dispatching", "queue_sending" -> "正在提交"
    "queued" -> "等待当前任务完成"
    "running" -> "执行中"
    "completed" -> "已完成"
    "needs_attention" -> "需要检查"
    "delivery_unknown" -> "发送状态待核实"
    "unsupported" -> "会话不支持"
    "cancelled" -> "已取消"
    else -> "状态待核对"
}

internal data class ManagerInboxItem(
    val id: String, val eventId: String, val threadId: String, val title: String, val created: Long,
    val kind: String, val message: String, val detail: String, val delivery: String, val status: String
)

internal data class ManagerInbox(
    val questions: List<ManagerInboxItem>, val notifications: List<ManagerInboxItem>,
    val questionsCount: Int, val notificationsCount: Int, val questionsNextCursor: String,
    val notificationsNextCursor: String, val pendingQuestionIds: Set<String>
)

private fun parseManagerInbox(value: JSONObject): ManagerInbox {
    fun rows(name: String): List<ManagerInboxItem> = buildList {
        val list = value.optJSONArray(name)
        for (i in 0 until (list?.length() ?: 0)) {
            val row = list?.optJSONObject(i) ?: continue
            add(ManagerInboxItem(row.text("id"), row.text("eventId"), row.text("threadId"), row.text("title"),
                (row.optDouble("created", 0.0) * 1000).toLong(), row.text("kind"), row.text("message"),
                row.text("detail"), row.text("delivery"), row.text("status")))
        }
    }
    val ids = value.optJSONArray("pendingQuestionIds")
    return ManagerInbox(rows("questions"), rows("notifications"), value.optInt("questionsCount"),
        value.optInt("notificationsCount"), value.text("questionsNextCursor"), value.text("notificationsNextCursor"),
        buildSet { for (i in 0 until (ids?.length() ?: 0)) add(ids!!.getString(i)) })
}

/** Refresh the inbox even after paging history, removing answers from older loaded pages. */
internal fun mergeManagerOverview(previous: ManagerOverview?, fresh: ManagerOverview, section: String): ManagerOverview {
    if (previous == null || !fresh.available || section.isBlank()) return fresh
    val old = previous.inbox
    val updated = fresh.inbox
    val inbox = if (old == null || updated == null) updated else updated.copy(
        questions = (if (section == "questions") old.questions + updated.questions else updated.questions + old.questions)
            .distinctBy { it.id }.filter { it.id in updated.pendingQuestionIds },
        notifications = (if (section == "notifications") old.notifications + updated.notifications else updated.notifications + old.notifications)
            .distinctBy { it.id },
        questionsNextCursor = if (section == "questions") updated.questionsNextCursor else old.questionsNextCursor,
        notificationsNextCursor = if (section == "notifications") updated.notificationsNextCursor else old.notificationsNextCursor)
    return fresh.copy(
        entries = (if (section == "records") previous.entries + fresh.entries else fresh.entries + previous.entries).distinctBy { it.id },
        nextCursor = if (section == "records") fresh.nextCursor else previous.nextCursor, inbox = inbox)
}

internal fun managerQuestionDeliveryLabel(delivery: String): String = when (delivery) {
    "shown" -> "可前往管家回答"
    "sending", "queued", "starting" -> "问答正在准备中，也可前往管家说明决定"
    "failed", "unknown" -> "问答卡未确认送达，请前往管家说明决定"
    else -> "等待你的决定，可前往管家说明"
}

internal fun parseManagerOverview(value: JSONObject): ManagerOverview {
    val counts = value.optJSONObject("counts") ?: JSONObject()
    val rows = value.optJSONArray("entries")
    return ManagerOverview(value.optBoolean("available"), value.text("message"), value.text("managerThreadId"),
        value.optBoolean("enabled"), value.optDouble("heartbeatAgeSeconds", 0.0).toLong(),
        (value.optDouble("generatedAt", 0.0) * 1000).toLong(),
        counts.keys().asSequence().sumOf { counts.optInt(it).coerceAtLeast(0) }, value.optInt("followupsStarted"),
        counts.optInt("needs_attention"), buildList {
            for (i in 0 until (rows?.length() ?: 0)) {
                val r = rows?.optJSONObject(i) ?: continue
                val report = r.optJSONObject("inspection")?.optJSONObject("report")
                val limits = report?.optJSONArray("limitations")
                add(ManagerEvent(r.text("id"), r.text("threadId"), r.text("title"),
                    (r.optDouble("created", 0.0) * 1000).toLong(), r.text("phase"), r.text("summary"),
                    r.text("followup"), r.text("question"), r.text("outcome"), r.text("outcomePhase"),
                    r.text("evidenceLevel"), report?.text("summary").orEmpty(),
                    buildList { for (j in 0 until (limits?.length() ?: 0)) add(limits?.optString(j).orEmpty()) }))
            }
        }, value.text("nextCursor"), value.optJSONObject("inbox")?.let(::parseManagerInbox))
}
