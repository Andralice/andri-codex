package com.codexserver.mobile

import org.json.JSONArray
import org.json.JSONObject

/** A browser-like workspace tab. Tabs of the same kind remain independent. */
internal data class WorkspaceTabState(
    val id: String,
    val kind: Int,
    val title: String,
    val location: String = "",
    val previews: List<String> = emptyList(),
    val history: Boolean = false,
    val commit: String = "",
    val commitTitle: String = ""
)

/** Per-conversation navigation, saved independently of the workspace screen's composition. */
internal data class WorkspaceSessionState(
    val tab: Int = 7,
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
    val positions: Map<String, WorkspacePosition> = emptyMap(),
    val tabs: List<WorkspaceTabState> = listOf(WorkspaceTabState("start", 7, "新标签页")),
    val activeTabId: String? = "start"
) {
    val activeTab: WorkspaceTabState? get() = tabs.firstOrNull { it.id == activeTabId }

    fun openTab(kind: Int, title: String = workspaceTabTitle(kind), location: String = ""): WorkspaceSessionState {
        require(kind in 0..7)
        val opened = WorkspaceTabState(java.util.UUID.randomUUID().toString(), kind, title, location)
        return copy(tab = kind, tabs = tabs + opened, activeTabId = opened.id,
            directory = if (kind == 0 && location.isNotBlank()) location else directory,
            browserAddress = if (kind == 2) location else browserAddress,
            browserUrl = if (kind == 2) location else browserUrl,
            history = false, commit = "", commitTitle = "",
            returnTab = if (kind == 5) tab else returnTab,
            previewFromPanel = if (kind == 5) true else previewFromPanel,
            previews = opened.previews)
    }

    fun openHomeTab(): WorkspaceSessionState = openTab(7, "新标签页")

    fun select(tab: Int): WorkspaceSessionState = openTab(tab)

    fun activateTab(id: String): WorkspaceSessionState {
        val selected = tabs.firstOrNull { it.id == id } ?: return this
        return copy(tab = selected.kind, activeTabId = selected.id,
            directory = if (selected.kind == 0 && selected.location.isNotBlank()) selected.location else directory,
            browserAddress = if (selected.kind == 2) selected.location else browserAddress,
            browserUrl = if (selected.kind == 2) selected.location else browserUrl,
            previews = selected.previews,
            history = selected.history,
            commit = selected.commit,
            commitTitle = selected.commitTitle,
            previewFromPanel = if (selected.kind == 5) previewFromPanel else false)
    }

    fun closeTab(id: String): WorkspaceSessionState {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return this
        val remaining = tabs.filterNot { it.id == id }
        val nextTabs = remaining.ifEmpty { listOf(WorkspaceTabState("start", 7, "新标签页")) }
        if (activeTabId != id) return copy(tabs = nextTabs)
        val next = nextTabs.getOrNull((index - 1).coerceAtMost(nextTabs.lastIndex)) ?: nextTabs.first()
        val activated = copy(tabs = nextTabs, activeTabId = next.id).activateTab(next.id)
        return activated.copy(tabs = nextTabs)
    }

    fun updateActiveLocation(location: String): WorkspaceSessionState {
        val selected = activeTab ?: return copy(directory = location)
        val updated = selected.copy(location = location)
        return copy(tabs = tabs.map { if (it.id == selected.id) updated else it },
            directory = if (selected.kind == 0) location else directory,
            browserAddress = if (selected.kind == 2) location else browserAddress,
            browserUrl = if (selected.kind == 2) location else browserUrl)
    }
    fun updateActiveGit(history: Boolean = this.history, commit: String = this.commit,
        commitTitle: String = this.commitTitle): WorkspaceSessionState {
        val selected = activeTab ?: return copy(history = history, commit = commit, commitTitle = commitTitle)
        val updated = selected.copy(history = history, commit = commit, commitTitle = commitTitle)
        return copy(history = history, commit = commit, commitTitle = commitTitle,
            tabs = tabs.map { if (it.id == selected.id) updated else it })
    }
    fun openPreview(source: String, fromPanel: Boolean = true): WorkspaceSessionState {
        val inlineMedia = source.startsWith("data:", ignoreCase = true)
        val selected = activeTab
        if (fromPanel && selected?.kind == 5) {
            val history = if (selected.previews.lastOrNull() == source) selected.previews else (selected.previews + source).takeLast(40)
            val updated = selected.copy(title = source.takeUnless { inlineMedia }?.substringAfterLast('/')?.ifBlank { source } ?: "预览",
                location = source.takeUnless { inlineMedia }.orEmpty(), previews = history)
            return copy(tab = 5, tabs = tabs.map { if (it.id == selected.id) updated else it },
                activeTabId = selected.id, previews = history, previewFromPanel = true)
        }
        val opened = WorkspaceTabState(java.util.UUID.randomUUID().toString(), 5,
            source.takeUnless { inlineMedia }?.substringAfterLast('/')?.ifBlank { source } ?: "预览",
            source.takeUnless { inlineMedia }.orEmpty(), listOf(source))
        return copy(tab = 5, returnTab = tab, tabs = tabs + opened, activeTabId = opened.id,
            previewFromPanel = fromPanel, previews = opened.previews, history = false, commit = "", commitTitle = "")
    }

    fun closePreview(): WorkspaceSessionState {
        val previewTab = activeTab?.takeIf { it.kind == 5 } ?: return copy(tab = returnTab)
        return closeTab(previewTab.id).copy(previewFromPanel = false)
    }

    fun previewBack(): WorkspaceSessionState {
        val selected = activeTab?.takeIf { it.kind == 5 } ?: return this
        if (selected.previews.size <= 1) return closePreview()
        val history = selected.previews.dropLast(1)
        val current = history.last()
        val inlineMedia = current.startsWith("data:", ignoreCase = true)
        val updated = selected.copy(location = current.takeUnless { inlineMedia }.orEmpty(), previews = history,
            title = current.takeUnless { inlineMedia }?.substringAfterLast('/')?.ifBlank { current } ?: "预览")
        return copy(tabs = tabs.map { if (it.id == selected.id) updated else it }, previews = history)
    }

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
        put("workspaceTabs", JSONArray().apply { tabs.takeLast(24).forEach { page ->
            put(JSONObject().put("id", page.id).put("kind", page.kind).put("title", page.title)
                .put("location", page.location).put("previews", JSONArray(page.previews.filterNot { it.startsWith("data:", true) }.takeLast(40)))
                .put("history", page.history).put("commit", page.commit).put("commitTitle", page.commitTitle)
            )
        } })
        put("activeWorkspaceTabId", activeTabId.orEmpty())
    }.toString()

    companion object {
        fun decode(value: String): WorkspaceSessionState = runCatching {
            val json = JSONObject(value)
            val tabs = setOf(0, 1, 2, 3, 4, 5, 6, 7)
            val back = json.optInt("returnTab").takeIf { it in 0..7 } ?: 0
            val previews = json.optJSONArray("previews")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.takeLast(40)
            }.orEmpty()
            val tab = json.optInt("tab", 7).takeIf { it in tabs } ?: 7
            val positions = json.optJSONObject("positions")?.let { values ->
                values.keys().asSequence().take(50).mapNotNull { key -> values.optJSONArray(key)?.let {
                    key to WorkspacePosition(it.optInt(0).coerceAtLeast(0), it.optInt(1).coerceAtLeast(0))
                } }.toMap()
            }.orEmpty()
            val directory = json.optString("directory")
            val savedWorkspaceTabs = json.optJSONArray("workspaceTabs")?.let { array ->
                (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { page ->
                    val kind = page.optInt("kind", 7).takeIf { it in tabs } ?: 7
                    val history = page.optJSONArray("previews")?.let { items ->
                        (0 until items.length()).mapNotNull { items.optString(it).takeIf(String::isNotBlank) }.takeLast(40)
                    }.orEmpty()
                    WorkspaceTabState(page.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }, kind,
                        page.optString("title").ifBlank { workspaceTabTitle(kind) }, page.optString("location"), history,
                        page.optBoolean("history"), page.optString("commit").takeIf { it.matches(Regex("[0-9a-f]{40,64}")) }.orEmpty(),
                        page.optString("commitTitle"))
                } }
            }?.takeLast(24).orEmpty()
            val workspaceTabs = savedWorkspaceTabs.ifEmpty {
                if (json.has("tab")) listOf(WorkspaceTabState("legacy-${tab}", if (tab == 5 && previews.isEmpty()) back else tab,
                    workspaceTabTitle(if (tab == 5 && previews.isEmpty()) back else tab), directory, previews))
                else listOf(WorkspaceTabState("start", 7, "新标签页"))
            }
            val activeId = json.optString("activeWorkspaceTabId").takeIf { id -> workspaceTabs.any { it.id == id } }
                ?: workspaceTabs.last().id
            val activeTab = workspaceTabs.first { it.id == activeId }
            WorkspaceSessionState(
                tab = activeTab.kind,
                returnTab = back,
                directory = directory,
                previews = activeTab.previews.ifEmpty { if (activeTab.kind == 5) previews else emptyList() },
                history = activeTab.history || json.optBoolean("history"),
                commit = activeTab.commit.ifBlank { json.optString("commit").takeIf { it.matches(Regex("[0-9a-f]{40,64}")) }.orEmpty() },
                commitTitle = activeTab.commitTitle.ifBlank { json.optString("commitTitle") },
                browserUrl = activeTab.location.takeIf { activeTab.kind == 2 } ?: json.optString("browserUrl"),
                browserAddress = activeTab.location.takeIf { activeTab.kind == 2 } ?: json.optString("browserAddress"),
                browserScrollX = json.optInt("browserScrollX").coerceAtLeast(0),
                browserScrollY = json.optInt("browserScrollY").coerceAtLeast(0),
                previewFromPanel = json.optBoolean("previewFromPanel"),
                positions = positions,
                tabs = workspaceTabs,
                activeTabId = activeId
            )
        }.getOrDefault(WorkspaceSessionState())
    }
}

internal fun workspaceTabTitle(kind: Int): String = when (kind) {
    0 -> "文件"
    1 -> "变更"
    2 -> "网页"
    3 -> "媒体"
    4 -> "侧边聊天"
    5 -> "预览"
    6 -> "终端"
    else -> "新标签页"
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
