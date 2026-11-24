package com.codexserver.mobile

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSessionCheck {
    @Test fun navigationAndReadingPositionsSurviveRestart() {
        val state = WorkspaceSessionState(tab = 1, returnTab = 1, directory = "/project/app/中文",
            history = true, commit = "a".repeat(40), commitTitle = "修复界面",
            browserUrl = "https://example.com/page", browserAddress = "example.com/page",
            browserScrollX = 12, browserScrollY = 635)
            .copy(tabs = listOf(WorkspaceTabState("files", 1, "变更")), activeTabId = "files")
            .at("files:/project/app/中文", WorkspacePosition(15, 27))
            .at("git:history", WorkspacePosition(23, 18))
            .at("read:file.kt", WorkspacePosition(offset = 3072))
            .openPreview("file:///project/app/中文/文件.kt")
        assertEquals(state, WorkspaceSessionState.decode(state.encode()))
        assertEquals(1, WorkspaceSessionState.decode(state.encode()).closePreview().tab)
    }

    @Test fun nestedPreviewReturnsToItsEntryAndCanBeResumedFromWorkspace() {
        val chat = WorkspaceSessionState().select(3).openPreview("first", fromPanel = false)
        val nested = chat.openPreview("second")
        assertTrue(nested.previewFromPanel)
        assertEquals(listOf("first"), nested.previewBack().previews)
        assertEquals(3, nested.previewBack().previewBack().tab)
        val resumed = nested.closePreview().select(5)
        assertTrue(resumed.previewFromPanel)
        assertEquals(3, resumed.closePreview().tab)
        val anotherChatLink = nested.openPreview("unrelated", fromPanel = false)
        assertEquals(listOf("unrelated"), anotherChatLink.previews)
        assertFalse(anotherChatLink.previewFromPanel)
    }

    @Test fun invalidSavedStateAndInlineMediaHaveSafeDefaults() {
        assertEquals(WorkspaceSessionState(), WorkspaceSessionState.decode("not json"))
        val invalid = WorkspaceSessionState.decode("""{"tab":99,"returnTab":99,"commit":"HEAD;evil","browserScrollY":-1,"positions":{"files":[-1,-5]}}""")
        assertEquals(7, invalid.tab)
        assertEquals(0, invalid.returnTab)
        assertEquals("", invalid.commit)
        assertEquals(0, invalid.browserScrollY)
        assertEquals(WorkspacePosition(), invalid.positions["files"])
        val inline = WorkspaceSessionState().select(2).openPreview("data:image/png;base64,large")
        val restored = WorkspaceSessionState.decode(inline.encode())
        assertEquals(5, restored.tab)
        assertTrue(restored.previews.isEmpty())
        assertEquals(2, restored.closePreview().tab)
        assertFalse(inline.encode().contains("base64"))
    }

    @Test fun projectDirectoryRestorationDoesNotCrossProjectBoundaries() {
        assertEquals("/projects/a/src", workspaceDirectory("/projects/a/src", "/projects/a"))
        assertEquals("/projects/a", workspaceDirectory("/projects/ab/src", "/projects/a"))
        assertEquals("/projects/b", workspaceDirectory("/projects/a/src", "/projects/b"))
        assertEquals("/projects/a", workspaceDirectory("", "/projects/a"))
        assertNotEquals(workspaceSessionKey("thread-a", "/projects/a"), workspaceSessionKey("thread-b", "/projects/a"))
        assertNotEquals(workspaceSessionKey("thread-a", "/projects/a"), workspaceSessionKey("thread-a", "/projects/b"))
        assertEquals(workspaceSessionKey("thread-a", "/projects/a"), workspaceSessionKey("thread-a", "/projects/a/"))
    }

    @Test fun savedPositionsAndPreviewStackRemainBounded() {
        var state = WorkspaceSessionState()
        repeat(100) { state = state.at("file:$it", WorkspacePosition(it, it)).openPreview("file:$it") }
        assertEquals(50, state.positions.size)
        assertEquals(40, state.previews.size)
        assertEquals(state, WorkspaceSessionState.decode(state.encode()))
    }

    @Test fun cleanWorkingTreeStillHasHistoryAndRealCommitPatches() = inRepository { repo ->
        File(repo, "中文 文件.txt").writeText("old\n")
        val root = commit(repo, "初始提交")
        File(repo, "中文 文件.txt").writeText("new\nnext\n")
        val next = commit(repo, "更新内容")
        assertEquals("", git(repo, listOf("status", "--porcelain")))
        val history = history(repo)
        assertEquals(listOf(next, root), history.map { it.revision })
        assertEquals("更新内容", history.first().title)
        assertEquals("沈知晚", history.first().author)
        assertTrue(history.last().parents.isEmpty())
        val initial = git(repo, gitCommitDiffArguments(root))
        assertTrue(initial.contains("+old"))
        val patch = git(repo, gitCommitDiffArguments(next))
        assertTrue(patch.contains("-old"))
        assertTrue(patch.contains("+new"))
        assertTrue(patch.contains("+next"))
        val row = workspaceDiffSections(patch, "提交差异").single()
        assertTrue(row.title.contains("中文 文件.txt"))
        assertTrue(row.title.endsWith("提交差异"))
        assertEquals(2, row.additions)
        assertEquals(1, row.deletions)
    }

    @Test fun mergeDiffIncludesChangesAgainstFirstParent() = inRepository { repo ->
        File(repo, "base.txt").writeText("base\n")
        commit(repo, "root")
        git(repo, listOf("checkout", "-b", "side"))
        File(repo, "side.txt").writeText("side change\n")
        commit(repo, "side")
        git(repo, listOf("checkout", "main"))
        File(repo, "main.txt").writeText("main change\n")
        val main = commit(repo, "main")
        git(repo, listOf("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
            "-c", "commit.gpgsign=false", "merge", "--no-ff", "--no-edit", "side"))
        val merge = history(repo).first()
        assertEquals(2, merge.parents.size)
        assertEquals(main, merge.parents.first())
        val patch = git(repo, gitCommitDiffArguments(merge.revision))
        assertTrue(patch.contains("+side change"))
        assertFalse(patch.contains("+main change"))
    }

    @Test fun pagingStaysAnchoredWhenNewCommitsArrive() = inRepository { repo ->
        repeat(33) { commit(repo, "entry $it", allowEmpty = true) }
        val first = history(repo)
        assertEquals(31, first.size)
        val anchor = first.first().revision
        val expectedOlder = first[30].revision
        commit(repo, "new after page opened", allowEmpty = true)
        val older = history(repo, 30, anchor)
        assertEquals(3, older.size)
        assertEquals(expectedOlder, older.first().revision)
        assertTrue(first.take(30).none { previous -> older.any { it.revision == previous.revision } })
        assertEquals("entry 0", older.last().title)
    }

    @Test fun emptySubjectsAndMalformedHistoryAreHandled() = inRepository { repo ->
        assertTrue(parseGitHistory("").isEmpty())
        val revision = commit(repo, "", allowEmpty = true)
        val row = history(repo).single()
        assertEquals(revision, row.revision)
        assertEquals("", row.title)
        assertThrows(IllegalArgumentException::class.java) { parseGitHistory("broken\u0000entry") }
        assertThrows(IllegalArgumentException::class.java) { gitCommitDiffArguments("HEAD; echo unsafe") }
        assertThrows(IllegalArgumentException::class.java) { gitHistoryArguments(0, "--all") }
        assertThrows(IllegalArgumentException::class.java) { gitHistoryArguments(-1) }
    }

    private fun inRepository(check: (File) -> Unit) {
        val parent = File("codex/workspace-git-checks").apply { mkdirs() }
        val repo = Files.createTempDirectory(parent.toPath(), "project ' 中文 ").toFile()
        try {
            git(repo, listOf("init", "-b", "main"))
            // Avoid invoking developer hooks in the temporary test repositories.
            git(repo, listOf("config", "core.hooksPath", File(repo, "no-hooks").absolutePath))
            check(repo)
        } finally { repo.deleteRecursively(); parent.delete() }
    }

    private fun commit(repo: File, title: String, allowEmpty: Boolean = false): String {
        git(repo, listOf("add", "--all"))
        git(repo, listOf("-c", "user.name=沈知晚", "-c", "user.email=test@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "--no-verify", "--allow-empty-message") +
            (if (allowEmpty) listOf("--allow-empty") else emptyList()) + listOf("-m", title))
        return git(repo, listOf("rev-parse", "HEAD"))
    }

    private fun history(repo: File, offset: Int = 0, anchor: String? = null): List<GitCommit> {
        return parseGitHistory(git(repo, gitHistoryArguments(offset, anchor)))
    }

    private fun git(repo: File, args: List<String>): String {
        val process = ProcessBuilder(listOf("git", "-c", "core.quotepath=false", "-C", repo.absolutePath) + args)
            .redirectErrorStream(true).apply { environment()["GIT_CONFIG_NOSYSTEM"] = "1" }.start()
        assertTrue("Git check timed out", process.waitFor(20, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        assertEquals(output, 0, process.exitValue())
        return output
    }
}
