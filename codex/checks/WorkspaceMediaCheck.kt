package com.codexserver.mobile

import org.junit.Assert.*
import org.junit.Test

class WorkspaceMediaCheck {
    private val project = "$WORKSPACE_ROOT/media-check"

    @Test fun mediaTypesAndFormatsAreRecognizableBeforeDownloading() {
        val video = WorkspaceMedia("https://example.invalid/成品.MP4?token=secret&name=photo.png", true, "成品.MP4")
        assertEquals("视频", video.typeLabel)
        assertEquals("MP4", video.formatLabel)
        val image = WorkspaceMedia("$project/codex/图 1.webp", false, "图 1.webp")
        assertEquals("图片", image.typeLabel)
        assertEquals("WEBP", image.formatLabel)
        assertEquals("JPG", WorkspaceMedia("data:image/jpeg;base64,aGVsbG8=", false, "图片").formatLabel)
        assertEquals("SVG", WorkspaceMedia("data:image/svg+xml;base64,aGVsbG8=", false, "图片").formatLabel)
        assertEquals("", WorkspaceMedia("https://example.invalid/download?id=1.mp4", true, "成品").formatLabel)
    }

    @Test fun undownloadedImagesAndRemoteVideosAreListedWithoutLocalFiles() {
        val video = "https://example.invalid/成品.MP4?download=1&sig=test#play"
        val lines = listOf(ChatLine("assistant", "[原视频](<$video>)\n![图片](codex/not-downloaded.png)"))
        val rows = workspaceConversationMedia(lines, project)
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.source == "$project/codex/not-downloaded.png" && !it.video })
        val remote = rows.single { it.video }
        assertEquals(workspacePreviewTarget(video, project)!!.source, remote.source)
        assertEquals("成品.MP4", remote.label)
    }

    @Test fun signedAndReferenceVideosKeepFullDestinationsAndSpaces() {
        val remote = "https://example.invalid/clip.webm?token=example&name=clip.mp4"
        val local = "$CODEX_STORAGE_ROOT/作品/剪辑 (2).mov"
        val text = "[播放][video]\n\n[video]: <$remote>\n\n[下载](<${workspaceFileLink(local)}>)\n`codex/export.mp4`"
        assertEquals(listOf(remote, workspaceFileLink(local), "codex/export.mp4"), videoAttachmentPaths(text))
        assertEquals(listOf(remote), videoAttachmentPaths("视频地址：$remote。"))
    }

    @Test fun generatedMediaInToolResultsAndFoldedProcessesIsDiscoverable() {
        val image = "$CODEX_STORAGE_ROOT/results/new.png"
        val video = "$CODEX_STORAGE_ROOT/results/new.mp4"
        val tool = ChatLine("tool", "输出文件：$image\n$video")
        val folded = ChatLine("thought", "执行完成", processItems = listOf(tool))
        val rows = workspaceConversationMedia(listOf(folded), project)
        assertEquals(setOf(image, video), rows.map { it.source }.toSet())
        assertTrue(rows.single { it.source == video }.video)
    }

    @Test fun repeatedLinksAreNormalizedAndLongRepliesDoNotHideLaterMedia() {
        val image = "$project/codex/图 1.png"
        val older = ChatLine("assistant", "[图](<${workspaceFileLink(image)}>)")
        val newer = ChatLine("assistant", "文字".repeat(65_000) + "\n![原图](<file://${image.replace(" ", "%20")}>)")
        val rows = workspaceConversationMedia(listOf(older, newer), project)
        assertEquals(1, rows.size)
        assertEquals(image, rows.single().source)
        assertEquals(mediaSourceHash(image), rows.single().key)
    }

    @Test fun unsafePathsAndCodeExamplesDoNotBecomeDownloadEntries() {
        val lines = listOf(ChatLine("assistant", """
            [外部文件](/etc/private.mp4)
            [外部图片](/etc/private.png)
            [脚本](javascript:open.mp4)
            [文档](codex/readme.md)
            ```text
            https://example.invalid/example.mp4
            ![示例](codex/example.png)
            ```
        """.trimIndent()))
        assertTrue(workspaceConversationMedia(lines, project).isEmpty())
    }

    @Test fun inlineAttachmentsAreAvailableWithoutAFileAndSignedUrlsHaveDistinctKeys() {
        val inline = "data:image/png;base64,aGVsbG8="
        val rows = workspaceConversationMedia(listOf(ChatLine("user", "", images = listOf(inline, inline))), project)
        assertEquals(inline, rows.single().source)
        assertEquals("聊天图片", rows.single().label)
        assertEquals("图片.png", rows.single().downloadName)
        assertEquals("图片.jpg", WorkspaceMedia("data:image/jpeg;base64,aGVsbG8=", false, "聊天图片").downloadName)
        assertEquals("图片.svg", WorkspaceMedia("data:image/svg+xml;base64,aGVsbG8=", false, "聊天图片").downloadName)
        assertNotEquals(mediaSourceHash("https://example.invalid/a.mp4?sig=1"), mediaSourceHash("https://example.invalid/a.mp4?sig=2"))
    }
}
