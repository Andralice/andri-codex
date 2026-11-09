package com.codexserver.mobile

internal val terminalTaskStatuses = setOf("completed", "failed", "interrupted", "cancelled")

/** Shared delivery state for live events and background recovery. Reading must survive replays. */
internal data class TaskResultState(
    val turnId: String = "",
    val unreadStatus: String = "",
    val notifiedTurnId: String = "",
    val readTurnId: String = ""
) {
    fun record(id: String, status: String): TaskResultState = when {
        status !in terminalTaskStatuses -> this
        id.isNotBlank() && id == turnId -> this
        else -> copy(turnId = id, unreadStatus = status)
    }

    fun acknowledge(): TaskResultState = copy(unreadStatus = "", readTurnId = turnId)
    fun needsNotification(): Boolean = turnId.isBlank() || (turnId != notifiedTurnId && turnId != readTurnId)
    fun delivered(): TaskResultState = copy(notifiedTurnId = turnId)
}

internal fun taskResultTitle(status: String): String = when (status) {
    "completed" -> "任务已完成"
    "failed" -> "任务执行失败"
    else -> "任务已中断"
}
