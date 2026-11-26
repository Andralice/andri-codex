package com.codexserver.mobile

/**
 * Small protocol state machine for the gap between turn/start acceptance and
 * the first server notification. It is deliberately independent of Android
 * so the race paths can be regression tested on the JVM.
 */
internal class TurnAcceptanceState {
    private val completed = mutableMapOf<String, LinkedHashSet<String>>()

    fun rememberCompleted(threadId: String, turnId: String) {
        if (threadId.isBlank() || turnId.isBlank()) return
        val ids = completed.getOrPut(threadId) { linkedSetOf() }
        ids += turnId
        while (ids.size > 16) ids.remove(ids.first())
    }

    /** Returns true when the accepted turn should be made visible as running. */
    fun accept(threadId: String, turnId: String, currentTurnId: String): Boolean =
        threadId.isNotBlank() && turnId.isNotBlank() &&
            turnId !in completed[threadId].orEmpty() &&
            (currentTurnId.isBlank() || currentTurnId == turnId)
}

internal fun chatBottomProgressLabel(
    sending: Boolean,
    queueSubmitting: Boolean,
    running: Boolean,
    hasActiveProcessRecord: Boolean,
    activity: String
): String = when {
    sending -> "正在发送消息…"
    queueSubmitting -> "正在启动排队任务…"
    running && !hasActiveProcessRecord -> activity.takeIf { it.isNotBlank() }
        ?: "已接收，等待 Andri 开始处理…"
    else -> ""
}
