package com.codexserver.mobile

// AnimatedContent keeps outgoing destinations alive after activeId has changed.
// Carry their conversation with them instead of reading the new global selection.
internal data class MainScreen(val destination: String, val thread: CodexThread? = null,
    val workspace: WorkspaceSessionState? = null, val project: String? = null) {
    val key: String = when (destination) {
        "workspace" -> "$destination:${requireNotNull(thread).id}:${project.orEmpty()}"
        "chat", "thread-loading" -> "$destination:${requireNotNull(thread).id}"
        else -> destination
    }
}

internal enum class ChatSwipe { SIDEBAR, WORKSPACE }

/** Right swipes open conversations from the chat body; left swipes open the workspace. */
internal fun chatSwipeTarget(delta: Float): ChatSwipe? = when {
    delta > 0f -> ChatSwipe.SIDEBAR
    delta < 0f -> ChatSwipe.WORKSPACE
    else -> null
}
