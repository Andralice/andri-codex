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
    val attentionCount: Int = 0, val entries: List<ManagerEvent> = emptyList(), val nextCursor: String = ""
)

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
        }, value.text("nextCursor"))
}
