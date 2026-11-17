package com.codexserver.mobile

/** Reads durable state each time, so reconnects and a new process share reminder history. */
internal class UpdateReminderPolicy(
    private val read: () -> Set<String>,
    private val write: (Set<String>) -> Unit
) {
    fun shouldRemind(versionCode: Int): Boolean = versionCode.toString() !in read()
    fun markReminded(versionCode: Int) = write(read() + versionCode.toString())
}

internal data class WorkspaceDiffSection(val id: String, val title: String, val lines: List<String>) {
    val additions: Int get() = lines.count { it.startsWith('+') && !it.startsWith("+++") }
    val deletions: Int get() = lines.count { it.startsWith('-') && !it.startsWith("---") }
}

/** Preserve the status summary (including untracked files) and each staged/unstaged patch. */
internal fun workspaceDiffSections(output: String, defaultStage: String = "工作区状态"): List<WorkspaceDiffSection> {
    val sections = mutableListOf<WorkspaceDiffSection>()
    var stage = defaultStage
    var lines = mutableListOf<String>()
    var patch = false
    fun flush() {
        if (lines.none { it.isNotBlank() }) { lines.clear(); return }
        val content = lines.toList()
        val title = if (!patch) stage else {
            val destination = content.firstOrNull { it.startsWith("+++ ") }?.removePrefix("+++ ")
            val original = content.firstOrNull { it.startsWith("--- ") }?.removePrefix("--- ")
            val path = destination?.takeUnless { it == "/dev/null" } ?: original
            val name = path?.removePrefix("b/")?.removePrefix("a/")
                ?: content.first().substringAfter(" b/", content.first().removePrefix("diff --git "))
            "$name · $stage"
        }
        sections += WorkspaceDiffSection("${sections.size}:$title", title, content)
        lines = mutableListOf()
    }
    output.lineSequence().forEach { line ->
        when {
            line.startsWith("--- Git 仓库：") -> Unit
            line == "--- 已暂存的改动 ---" || line == "--- 未暂存的改动 ---" -> {
                flush(); stage = if (line.contains("未暂存")) "未暂存" else "已暂存"; patch = false
            }
            line.startsWith("diff --git ") -> { flush(); patch = true; lines += line }
            else -> lines += line
        }
    }
    flush()
    return sections
}

/** A retrying transport failure needs no banner; configuration/authentication failures still do. */
internal fun connectionFailureNeedsAttention(automatic: Boolean, failure: Throwable): Boolean {
    if (!automatic) return true
    val transient = generateSequence(failure) { error -> error.cause?.takeUnless { cause -> cause === error } }
        .take(16).any { it is java.net.SocketException || it is java.net.SocketTimeoutException ||
            it is java.net.UnknownHostException || it is java.util.concurrent.TimeoutException ||
            it is kotlinx.coroutines.TimeoutCancellationException }
    return !transient
}
