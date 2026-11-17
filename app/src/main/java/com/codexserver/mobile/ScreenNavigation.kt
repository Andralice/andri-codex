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
