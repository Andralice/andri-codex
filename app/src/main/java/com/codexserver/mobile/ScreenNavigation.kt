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

/** Keep the left edge for conversations; the rest of a right swipe opens the workspace.
 * A left swipe from the right edge also follows the workspace's slide-in direction. */
internal fun chatSwipeTarget(start: Float, width: Float, delta: Float, edge: Float): ChatSwipe? = when {
    delta > 0f -> if (start < edge) ChatSwipe.SIDEBAR else ChatSwipe.WORKSPACE
    delta < 0f && start >= width - edge -> ChatSwipe.WORKSPACE
    else -> null
}
