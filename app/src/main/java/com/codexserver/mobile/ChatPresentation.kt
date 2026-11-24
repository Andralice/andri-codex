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
    if (expanded) composerHeight + 18f + 24f else 56f + 18f + 24f

internal val ChatLine.isReasoningSummary: Boolean
    get() = role == "tool" && stage == "思考摘要"

// Keep empty protocol items for incoming deltas, but never allocate a visible row for them.
internal fun visibleChatLines(lines: List<ChatLine>): List<ChatLine> =
    lines.filterNot { line ->
        (line.isReasoningSummary && line.text.isBlank()) ||
            (line.role == "assistant" && line.text.isBlank() &&
                line.images.isEmpty() && line.questions.isEmpty())
    }

/** Keep contiguous progress commentary together without changing execution order. */
internal data class ProcessTimelineEntry(
    val progress: Boolean,
    val items: List<ChatLine>
)

internal data class ProcessStepGroup(val type: String, val items: List<ChatLine>)

internal fun processStepType(item: ChatLine): String {
    if (item.role == "assistant") return item.stage.ifBlank { "进度说明" }
    if (item.role != "tool") return item.stage.ifBlank { "执行步骤" }
    return when (item.toolType) {
        "commandExecution" -> commandExecutionCategory(item.stage)
        "fileChange" -> "文件变更"
        "mcpToolCall" -> item.stage.ifBlank { "MCP 工具" }
        "dynamicToolCall" -> item.stage.ifBlank { "动态工具" }
        "webSearch" -> "网页搜索"
        "plan" -> "执行计划"
        "reasoning" -> "思考摘要"
        else -> when {
            item.isReasoningSummary -> "思考摘要"
            item.stage == "文件变更" -> "文件变更"
            item.stage == "执行计划" -> "执行计划"
            item.stage == "工具进度" -> "工具进度"
            item.stage.startsWith("搜索：") -> "网页搜索"
            item.stage.isBlank() || item.stage.startsWith("{") || item.stage.contains('\n') -> "终端命令"
            else -> commandExecutionCategory(item.stage)
                .takeUnless { it == "终端命令" } ?: item.stage
        }
    }
}

internal fun commandExecutionCategory(command: String): String {
    val lowered = command.lowercase(Locale.ROOT)
    val tokens = lowered.split(Regex("[\\s;&|()]+"))
        .map { it.trim('"', '\'', '`').substringAfterLast('/') }
    fun hasCommand(vararg names: String) = tokens.any { it in names }
    val usesBuildTool = hasCommand("gradle", "gradlew", "mvn", "mvnw", "npm", "pnpm", "yarn", "cargo", "go", "make")
    val testTask = tokens.any { token ->
        val task = token.substringAfterLast(':')
        task in setOf("pytest", "tox", "unittest", "test", "check", "lint", "verify") ||
            task.startsWith("test") || task.startsWith("lint") || task.startsWith("check")
    }
    val buildTask = tokens.any { token ->
        val task = token.substringAfterLast(':')
        task in setOf("build", "assemble", "compile", "package", "bundle", "release") ||
            task.startsWith("build") || task.startsWith("assemble") || task.startsWith("compile")
    }
    return when {
        hasCommand("pytest", "tox", "unittest") || usesBuildTool && testTask -> "运行测试"
        usesBuildTool && buildTask -> "项目构建"
        hasCommand("git") -> "Git 操作"
        hasCommand("rg", "grep", "egrep", "fgrep", "ag") -> "搜索内容"
        hasCommand("find", "fd", "locate") -> "查找文件"
        hasCommand("docker", "podman", "kubectl", "helm") -> "容器操作"
        hasCommand("systemctl", "service", "pm2", "supervisorctl") -> "服务管理"
        hasCommand("curl", "wget", "http") -> "网络请求"
        hasCommand("cat", "sed", "awk", "head", "tail", "less", "bat") -> "读取文件"
        hasCommand("cp", "mv", "rm", "mkdir", "touch", "tee", "apply_patch") ||
            lowered.contains("write_text(") || lowered.contains("outputstream") -> "文件操作"
        hasCommand("ps", "top", "df", "du", "free", "hostname", "uptime", "ss", "lsblk") -> "系统检查"
        hasCommand("python", "python3", "node", "bash", "sh", "java") -> "运行脚本"
        command.isBlank() || command == "命令执行" -> "终端命令"
        else -> "终端命令"
    }
}

/** Group adjacent steps of the same kind while preserving the original execution order. */
internal fun groupProcessSteps(items: List<ChatLine>): List<ProcessStepGroup> {
    val groups = mutableListOf<ProcessStepGroup>()
    items.forEach { item ->
        val type = processStepType(item)
        val last = groups.lastOrNull()
        if (last?.type == type) groups[groups.lastIndex] = last.copy(items = last.items + item)
        else groups += ProcessStepGroup(type, listOf(item))
    }
    return groups
}

internal fun processTimelineEntries(items: List<ChatLine>): List<ProcessTimelineEntry> {
    val entries = mutableListOf<ProcessTimelineEntry>()
    items.forEach { item ->
        val progress = item.role == "assistant"
        if (entries.lastOrNull()?.progress == progress) {
            val last = entries.removeAt(entries.lastIndex)
            entries += last.copy(items = last.items + item)
        } else {
            entries += ProcessTimelineEntry(progress, listOf(item))
        }
    }
    return entries
}

/** One expanded answer per turn; preceding commentary and tools share a folded record.
 * Older servers omit phase, so their last non-commentary message is the answer. */
internal fun foldCompletedProcessLines(lines: List<ChatLine>, activeTurnId: String?, threadRunning: Boolean): List<ChatLine> {
    val turns = lines.withIndex().filter { it.value.turnId.isNotBlank() }.groupBy { it.value.turnId }
    val answers = turns.mapValues { (_, rows) ->
        val candidates = rows.filter { it.value.role == "assistant" &&
            (it.value.text.isNotBlank() || it.value.images.isNotEmpty() || it.value.questions.isNotEmpty()) }
        (candidates.lastOrNull { it.value.phase == "final_answer" }
            ?: candidates.lastOrNull { it.value.phase != "commentary" })?.index
    }
    fun fold(index: Int, line: ChatLine): Boolean = line.turnId.isNotBlank() &&
        (line.role == "tool" || line.role == "assistant" && line.questions.isEmpty() && answers[line.turnId] != index)
    val groups = lines.withIndex().filter { fold(it.index, it.value) }.groupBy { it.value.turnId }
    val firstIndices = groups.mapValues { (_, rows) -> rows.first().index }
    return lines.mapIndexedNotNull { index, line ->
        if (!fold(index, line)) line
        else if (firstIndices[line.turnId] == index) ChatLine(
            role = "thought", text = "", id = "thought:${line.turnId}", turnId = line.turnId,
            stage = "执行记录", status = when {
                threadRunning && line.turnId == activeTurnId -> "inProgress"
                else -> groups.getValue(line.turnId).map { it.value.status }
                    .firstOrNull { it in setOf("failed", "interrupted", "declined") } ?: "completed"
            },
            processItems = groups.getValue(line.turnId).map { it.value }
        ) else null
    }
}

/** Called after folding: progress, streaming and interactive questions have no copy action. */
internal fun assistantReplyCanCopy(line: ChatLine, activeTurnId: String?, threadRunning: Boolean): Boolean =
    line.role == "assistant" && line.phase != "commentary" && !line.streaming && line.questions.isEmpty() &&
        line.text.isNotBlank() && !(threadRunning && (activeTurnId.isNullOrBlank() || line.turnId == activeTurnId))

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
                text = if (line.text.isEmpty()) existing?.text.orEmpty() else line.text,
                phase = line.phase.ifBlank { existing?.phase.orEmpty() }))
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
                ?: ChatLine("tool", "", "tool:$itemId", stage = roleTitle, toolType = when (method) {
                    "item/commandExecution/outputDelta" -> "commandExecution"
                    "item/fileChange/outputDelta" -> "fileChange"
                    "item/reasoning/summaryTextDelta" -> "reasoning"
                    "item/plan/delta" -> "plan"
                    else -> "mcpToolCall"
                })
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
                "plan:$turnId", stage = "执行计划", status = "inProgress", toolType = "plan"))
        }
        "turn/completed" -> current.map { line ->
            if (line.turnId == params.optJSONObject("turn")?.text("id")) line.copy(streaming = false,
                status = if (line.status == "inProgress") params.optJSONObject("turn")?.text("status").orEmpty() else line.status) else line
        }
        else -> current
    }
}
