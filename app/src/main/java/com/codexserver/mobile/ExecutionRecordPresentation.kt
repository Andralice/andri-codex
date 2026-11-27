package com.codexserver.mobile

import androidx.compose.ui.text.AnnotatedString
import org.json.JSONObject

internal data class ExecutionRecordHighlight(val text: AnnotatedString, val failure: Throwable? = null)

/** Coloring is optional: a broken rule must never prevent opening the actual output.
 * Kept outside WorkspaceSyntaxHighlighting so its failed class initialization is caught too.
 */
internal fun highlightExecutionRecord(
    source: String,
    command: String,
    toolType: String,
    highlighter: (String, String, String) -> AnnotatedString = { text, stage, type ->
        highlightTimelineContent(text, stage, type)
    }
): ExecutionRecordHighlight = try {
    ExecutionRecordHighlight(highlighter(source, command, toolType))
} catch (error: RuntimeException) {
    ExecutionRecordHighlight(AnnotatedString(source), error)
} catch (error: ExceptionInInitializerError) {
    ExecutionRecordHighlight(AnnotatedString(source), error)
} catch (error: NoClassDefFoundError) {
    ExecutionRecordHighlight(AnnotatedString(source), error)
}

/** Keep protocol placeholders in storage, but do not turn them into empty steps. */
internal fun visibleProcessItems(items: List<ChatLine>): List<ChatLine> = items.filterNot { item ->
    (item.isReasoningSummary && item.text.isBlank()) ||
        (item.role == "assistant" && item.text.isBlank() && item.images.isEmpty() && item.questions.isEmpty())
}

internal fun executionRecordBody(item: ChatLine): String {
    val command = item.stage.takeIf {
        item.toolType == "commandExecution" && it.isNotBlank() && it != "命令执行"
    }
    return if (command != null && !item.text.startsWith(command))
        listOf(command, item.text).filter(String::isNotBlank).joinToString("\n") else item.text
}

internal fun hasExecutionRecordBody(item: ChatLine): Boolean =
    executionRecordBody(item).isNotBlank() || item.images.isNotEmpty() || item.questions.isNotEmpty()

internal fun executionRecordEmptyHint(item: ChatLine): String = when (item.status) {
    "inProgress" -> "正在执行，尚未收到详细内容"
    "pending" -> "等待处理，尚未收到详细内容"
    "failed" -> "执行失败，未返回详细内容"
    "interrupted" -> "执行已中断，未返回详细内容"
    "declined" -> "已拒绝执行"
    "completed" -> "已完成，未返回详细内容"
    else -> "未收到详细内容"
}

/** Some completion events omit aggregatedOutput after already streaming it. */
internal fun toolEventDisplayText(item: JSONObject, parsed: ChatLine, previous: ChatLine?): String {
    if (parsed.toolType == "commandExecution" && item.text("aggregatedOutput").isBlank() &&
        previous != null && previous.text.isNotBlank()) {
        val exit = if (item.isNull("exitCode")) "" else "退出码：${item.optInt("exitCode")}"
        return if (exit.isBlank() || previous.text.lineSequence().lastOrNull() == exit) previous.text
            else previous.text.trimEnd('\n') + "\n" + exit
    }
    return parsed.text.ifEmpty { previous?.text.orEmpty() }
}
