package com.codexserver.mobile

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspacePreviewCheck {
    private val project = "$WORKSPACE_ROOT/自媒体"

    @Test fun inlineChatImagesRemainImagesAcrossPreviewNavigation() {
        val source = "data:image/png;base64,aGVsbG8="
        val target = workspacePreviewTarget(source, project)!!
        assertEquals(WorkspacePreviewKind.IMAGE, target.kind)
        assertFalse(target.remote)
        assertEquals("聊天图片", target.name)
        assertEquals(source, target.navigationLink)
        assertEquals(target, workspacePreviewTarget(target.navigationLink, project))
    }

    @Test fun chatFileImageVideoAndDirectoryLinksShareThePreviewResolver() {
        val cases = mapOf(
            "codex/prompt.md" to WorkspacePreviewKind.FILE,
            "codex/main.kt" to WorkspacePreviewKind.FILE,
            "codex/图片%201.PNG" to WorkspacePreviewKind.IMAGE,
            "codex/成品.MP4" to WorkspacePreviewKind.VIDEO,
            "codex/report.pdf" to WorkspacePreviewKind.FILE,
            "codex/" to WorkspacePreviewKind.DIRECTORY
        )
        cases.forEach { (link, expected) ->
            assertEquals(link, expected, workspacePreviewTarget(link, project)?.kind)
        }
        assertEquals("$project/codex/图片 1.PNG", workspacePreviewTarget("codex/图片%201.PNG", project)?.source)
    }

    @Test fun documentLinksResolveAgainstTheirOwnDirectoryAndKeepUnicodeNames() {
        val document = workspacePreviewTarget("codex/分镜/prompt.md", project)!!
        val sibling = workspacePreviewTarget("下一镜.markdown", document.linkBase)!!
        assertEquals("$project/codex/分镜/下一镜.markdown", sibling.source)
        assertEquals("下一镜.markdown", sibling.name)
        assertEquals("$project/codex/图片.png", workspacePreviewTarget("../图片.png", document.linkBase)?.source)
    }

    @Test fun remoteMediaAndMarkdownAreRecognizedWithQueriesAndFragments() {
        assertEquals(WorkspacePreviewKind.VIDEO,
            workspacePreviewTarget("https://example.com/video.MP4?token=test#t=2", project)?.kind)
        val document = workspacePreviewTarget("https://example.com/docs/guide.MD?download=1", project)!!
        assertEquals(WorkspacePreviewKind.FILE, document.kind)
        assertEquals("guide.MD", document.name)
        val image = workspacePreviewTarget("../images/图 1.png?size=large", document.linkBase)!!
        assertEquals(WorkspacePreviewKind.IMAGE, image.kind)
        assertEquals("https://example.com/images/%E5%9B%BE%201.png?size=large", image.source)
        assertEquals("https://example.com/docs/next.md", workspacePreviewTarget("next.md", document.linkBase)?.source)
        assertEquals(WorkspacePreviewKind.WEB, workspacePreviewTarget("https://example.com/guide", project)?.kind)
    }

    @Test fun documentNavigationKeepsTheExistingWorkspaceAndSchemeBoundaries() {
        for (link in listOf("javascript:alert(1)", "file://other-host$project/prompt.md", "/etc/passwd", "../../../外部.md")) {
            assertNull(link, workspacePreviewTarget(link, project))
        }
        assertEquals(WorkspacePreviewKind.WEB, workspacePreviewTarget("http://example.com/video.mp4", project)?.kind)
    }

    @Test fun savedPreviewNavigationDoesNotMistakeFilenameCharactersForUrlFragments() {
        val path = "$project/codex/图 #1 (原图).png"
        val target = workspacePreviewTarget(workspaceFileLink(path), project)!!
        assertEquals(path, target.source)
        assertEquals("图 #1 (原图).png", target.name)
        assertEquals(target, workspacePreviewTarget(target.navigationLink, project))
        assertEquals(WorkspacePreviewKind.IMAGE, target.kind)
    }

    @Test fun largeDocumentPreviewIsBoundedAndDoesNotSplitEmoji() {
        val folder = File("codex/preview-checks").apply { mkdirs() }
        val file = File.createTempFile("preview-", ".md", folder)
        try {
            val original = "字".repeat(191_999) + "😀" + "后续".repeat(1000)
            file.writeText(original, Charsets.UTF_8)
            val preview = readWorkspacePreviewText(file)
            assertTrue(preview.truncated)
            assertEquals("字".repeat(191_999), preview.text)
            assertFalse(preview.text.last().isHighSurrogate())
            assertEquals(original, file.readText(Charsets.UTF_8))
            file.writeText("# 标题\n\n**正文**", Charsets.UTF_8)
            assertEquals(WorkspacePreviewText("# 标题\n\n**正文**", false), readWorkspacePreviewText(file))
        } finally { file.delete(); folder.delete() }
    }

    @Test fun remoteFileDownloadRejectsCleartextBeforeOpeningANetworkConnection() = runBlocking {
        val folder = File("codex/preview-download-checks").apply { mkdirs() }
        try {
            try {
                downloadWorkspacePreviewUrl("http://example.invalid/file.md", folder)
                fail("HTTP downloads must use the browser's existing HTTPS policy")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("HTTPS"))
            }
            assertTrue(folder.listFiles().orEmpty().isEmpty())
        } finally { folder.delete() }
    }
}
