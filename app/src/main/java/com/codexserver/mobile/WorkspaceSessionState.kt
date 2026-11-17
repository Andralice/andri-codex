package com.codexserver.mobile

import org.json.JSONArray
import org.json.JSONObject

/** Per-conversation navigation, saved independently of the workspace screen's composition. */
internal data class WorkspaceSessionState(
    val tab: Int = 0,
    val returnTab: Int = 0,
    val directory: String = "",
    val previews: List<String> = emptyList(),
    val history: Boolean = false,
    val commit: String = "",
    val commitTitle: String = "",
    val browserUrl: String = "",
    val browserAddress: String = "",
    val browserScrollX: Int = 0,
    val browserScrollY: Int = 0,
    val previewFromPanel: Boolean = false,
    val positions: Map<String, WorkspacePosition> = emptyMap()
) {
    fun select(tab: Int): WorkspaceSessionState {
        require(tab in 0..5)
        return copy(tab = tab, returnTab = if (tab == 5) returnTab else tab,
            previewFromPanel = if (tab == 5) true else previewFromPanel)
    }
    fun openPreview(source: String, fromPanel: Boolean = true): WorkspaceSessionState = copy(tab = 5,
        returnTab = if (tab == 5) returnTab else tab,
        previewFromPanel = if (tab == 5 && fromPanel) previewFromPanel else fromPanel,
        previews = if (!fromPanel) listOf(source) else if (previews.lastOrNull() == source) previews else (previews + source).takeLast(40))
    fun closePreview(): WorkspaceSessionState = copy(tab = returnTab)
    fun previewBack(): WorkspaceSessionState = if (previews.size > 1) copy(previews = previews.dropLast(1)) else closePreview()

    fun at(key: String, position: WorkspacePosition): WorkspaceSessionState =
        copy(positions = (positions + (key to position)).entries.toList().takeLast(50).associate { it.toPair() })

    fun encode(): String = JSONObject().apply {
        put("tab", tab); put("returnTab", returnTab); put("directory", directory)
        // An inline image can be several MB; it is only kept in the live session.
        put("previews", JSONArray(previews.filterNot { it.startsWith("data:", true) }))
        put("history", history); put("commit", commit); put("commitTitle", commitTitle)
        put("browserUrl", browserUrl); put("browserAddress", browserAddress)
        put("browserScrollX", browserScrollX); put("browserScrollY", browserScrollY); put("previewFromPanel", previewFromPanel)
        put("positions", JSONObject().apply { positions.forEach { (key, position) ->
            if (key.length <= 2048) put(key, JSONArray(listOf(position.index, position.offset)))
        } })
    }.toString()

    companion object {
        fun decode(value: String): WorkspaceSessionState = runCatching {
            val json = JSONObject(value)
            val tabs = setOf(0, 1, 2, 3, 4, 5)
            val back = json.optInt("returnTab").takeIf { it in 0..4 } ?: 0
            val previews = json.optJSONArray("previews")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.takeLast(40)
            }.orEmpty()
            val tab = json.optInt("tab").takeIf { it in tabs } ?: 0
            val positions = json.optJSONObject("positions")?.let { values ->
                values.keys().asSequence().take(50).mapNotNull { key -> values.optJSONArray(key)?.let {
                    key to WorkspacePosition(it.optInt(0).coerceAtLeast(0), it.optInt(1).coerceAtLeast(0))
                } }.toMap()
            }.orEmpty()
            WorkspaceSessionState(if (tab == 5 && previews.isEmpty()) back else tab, back,
                json.optString("directory"), previews, json.optBoolean("history"),
                json.optString("commit").takeIf { it.matches(Regex("[0-9a-f]{40,64}")) }.orEmpty(),
                json.optString("commitTitle"), json.optString("browserUrl"), json.optString("browserAddress"),
                json.optInt("browserScrollX").coerceAtLeast(0), json.optInt("browserScrollY").coerceAtLeast(0), json.optBoolean("previewFromPanel"), positions)
        }.getOrDefault(WorkspaceSessionState())
    }
}

internal data class WorkspacePosition(val index: Int = 0, val offset: Int = 0)

internal fun workspaceSessionKey(id: String, project: String): String =
    "$id:${project.trimEnd('/').ifBlank { "/" }}"

internal fun workspaceDirectory(saved: String, cwd: String): String =
    saved.takeIf { it == cwd || it.startsWith(cwd.trimEnd('/') + "/") } ?: cwd

internal data class GitCommit(val revision: String, val parents: List<String>, val timeSeconds: Long,
    val author: String, val title: String)
internal data class GitHistoryState(val commits: List<GitCommit> = emptyList(), val hasMore: Boolean = false,
    val busy: Boolean = false, val loaded: Boolean = false, val error: String = "")
internal data class GitCommitDiffState(val text: String = "", val busy: Boolean = false,
    val loaded: Boolean = false, val error: String = "")

internal fun gitHistoryArguments(offset: Int, anchor: String? = null): List<String> {
    require(offset in 0..100_000)
    require(anchor == null || anchor.matches(Regex("[0-9a-f]{40,64}")))
    return listOf("log", "--no-decorate", "-n", "31", "--skip=$offset",
        "--format=%H%x00%P%x00%ct%x00%an%x00%s%x00", anchor ?: "HEAD", "--")
}

internal fun gitCommitDiffArguments(revision: String): List<String> {
    require(revision.matches(Regex("[0-9a-f]{40,64}")))
    // Merge commits are compared with their first parent; root commits show their initial files.
    return listOf("show", "--format=", "--first-parent", "--diff-merges=first-parent", "--root",
        "--find-renames", "--no-ext-diff", "--no-color", revision, "--")
}

internal fun parseGitHistory(output: String): List<GitCommit> {
    if (output.isBlank()) return emptyList()
    val fields = output.trimEnd('\r', '\n').split('\u0000').let { if (it.last().isEmpty()) it.dropLast(1) else it }
    require(fields.size % 5 == 0) { "提交记录格式不完整，请刷新重试" }
    return fields.chunked(5).map { row ->
        val hash = row[0].trim()
        require(hash.matches(Regex("[0-9a-f]{40,64}"))) { "提交标识无效" }
        GitCommit(hash, row[1].split(' ').filter(String::isNotBlank), row[2].toLong(), row[3], row[4])
    }
}
