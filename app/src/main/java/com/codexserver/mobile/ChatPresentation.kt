package com.codexserver.mobile

import java.text.BreakIterator
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** One character per frame for normal chunks, with bounded catch-up after a burst. */
internal fun nextStreamText(visible: String, target: String): String {
    if (!target.startsWith(visible)) return target
    if (visible == target) return visible
    val pending = target.codePointCount(visible.length, target.length)
    val count = ((pending + 23) / 24).coerceAtLeast(1)
    val boundary = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(target) }
    var end = visible.length
    repeat(count) { end = boundary.following(end).takeIf { it != BreakIterator.DONE } ?: target.length }
    return target.substring(0, end)
}

internal fun chatBottomInset(composerHeight: Float, expanded: Boolean): Float =
    if (expanded) composerHeight + 18f + 24f else 58f + 60f + 18f + 24f

internal val ChatLine.isReasoningSummary: Boolean
    get() = role == "tool" && stage == "思考摘要"

// Keep empty protocol items for incoming deltas, but never allocate a visible row for them.
internal fun visibleChatLines(lines: List<ChatLine>): List<ChatLine> =
    lines.filterNot { line ->
        (line.isReasoningSummary && line.text.isBlank()) ||
            (line.role == "assistant" && line.text.isBlank() &&
                line.images.isEmpty() && line.questions.isEmpty())
    }

/** Fold completed tool/reasoning records into one expandable process row before the final answer. */
internal fun foldCompletedProcessLines(lines: List<ChatLine>, activeTurnId: String?, threadRunning: Boolean): List<ChatLine> {
    fun fold(line: ChatLine): Boolean = line.role == "tool" && line.turnId.isNotBlank() &&
        (!threadRunning || (activeTurnId?.isNotBlank() == true && line.turnId != activeTurnId))
    val groups = lines.withIndex().filter { fold(it.value) }.groupBy { it.value.turnId }
    val firstIndices = groups.mapValues { (_, rows) -> rows.minOf { it.index } }
    return lines.mapIndexedNotNull { index, line ->
        if (!fold(line)) line
        else if (firstIndices[line.turnId] == index) ChatLine(
            role = "thought", text = "", id = "thought:${line.turnId}", turnId = line.turnId,
            stage = "Codex 汇报摘要 · 执行记录 ${groups.getValue(line.turnId).size} 项", status = "completed",
            processItems = groups.getValue(line.turnId).map { it.value }
        ) else null
    }
}

internal fun reasoningSummaryActive(line: ChatLine, activeTurnId: String?): Boolean =
    line.isReasoningSummary && line.turnId.isNotBlank() && line.turnId == activeTurnId

internal fun requestChatLine(request: JSONObject): ChatLine {
    val params = request.optJSONObject("params") ?: JSONObject()
    val input = request.text("method") == "item/tool/requestUserInput"
    val questions = params.optJSONArray("questions") ?: JSONArray()
    val text = if (input) (0 until questions.length()).mapNotNull { i -> questions.optJSONObject(i)?.text("question") }.joinToString("\n\n")
        else listOf(params.text("command"), params.text("reason"), params.text("cwd")).filter { it.isNotBlank() }.joinToString("\n")
    return ChatLine("request", text, "request:" + request.opt("id"), params.text("turnId"),
        stage = if (input) "等待你的回答" else "请求执行审批", status = "pending")
}

/** Shared by the live event path and regression checks; history uses parseChatItem. */
internal fun applyChatNotification(current: List<ChatLine>, method: String, params: JSONObject): List<ChatLine> {
    val turnId = params.text("turnId")
    val itemId = params.text("itemId")
    fun put(line: ChatLine) = upsertChat(current, line.copy(turnId = turnId))
    return when (method) {
        "item/started", "item/completed" -> {
            val item = params.optJSONObject("item") ?: return current
            val line = parseChatItem(item) ?: return current
            val existing = current.firstOrNull { it.id == line.id }
            put(line.copy(streaming = line.role == "assistant" && method == "item/started",
                status = item.text("status").ifBlank { if (method == "item/started") "inProgress" else "completed" },
                text = if (line.text.isEmpty()) existing?.text.orEmpty() else line.text))
        }
        "item/agentMessage/delta" -> {
            if (itemId.isBlank()) return current
            val previous = current.firstOrNull { it.id == itemId } ?: ChatLine("assistant", "", itemId)
            put(previous.copy(text = previous.text + params.text("delta"), streaming = true))
        }
        "item/commandExecution/outputDelta", "item/fileChange/outputDelta",
        "item/reasoning/summaryTextDelta", "item/plan/delta", "item/mcpToolCall/progress" -> {
            if (itemId.isBlank()) return current
            val roleTitle = when (method) {
                "item/reasoning/summaryTextDelta" -> "思考摘要"
                "item/plan/delta" -> "执行计划"
                "item/mcpToolCall/progress" -> "工具进度"
                "item/fileChange/outputDelta" -> "文件变更"
                else -> "命令执行"
            }
            val previous = current.firstOrNull { it.id == "tool:$itemId" }
                ?: ChatLine("tool", "", "tool:$itemId", stage = roleTitle)
            val delta = if (method == "item/mcpToolCall/progress") params.text("message") + "\n" else params.text("delta")
            val part = if (method == "item/reasoning/summaryTextDelta") params.optInt("summaryIndex", 0) else 0
            // summaryPartAdded itself has no text; use its index only when text arrives.
            val separator = if (part > 0 && previous.afterId != part.toString() && previous.text.isNotEmpty()) "\n\n" else ""
            put(previous.copy(text = previous.text + separator + delta, status = "inProgress", afterId = part.toString()))
        }
        "turn/plan/updated" -> {
            val plan = params.optJSONArray("plan") ?: JSONArray()
            val text = (0 until plan.length()).mapNotNull { index -> plan.optJSONObject(index)?.let { step ->
                val state = when (step.text("status")) { "completed" -> "✓"; "inProgress" -> "●"; else -> "○" }
                "$state ${step.text("step")}"
            } }.joinToString("\n")
            put(ChatLine("tool", listOf(params.text("explanation"), text).filter { it.isNotBlank() }.joinToString("\n"),
                "plan:$turnId", stage = "执行计划", status = "inProgress"))
        }
        "turn/completed" -> current.map { line ->
            if (line.turnId == params.optJSONObject("turn")?.text("id")) line.copy(streaming = false,
                status = if (line.status == "inProgress") params.optJSONObject("turn")?.text("status").orEmpty() else line.status) else line
        }
        else -> current
    }
}
