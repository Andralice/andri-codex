package com.codexserver.mobile

import org.junit.Assert.*
import org.junit.Test

class WorkspaceAudioCheck {
    private val project = "$WORKSPACE_ROOT/自媒体"

    @Test fun reportedM4aSampleOpensAsAudioAcrossLinksAndRestoredNavigation() {
        val path = "$CODEX_STORAGE_ROOT/你离开的事实_Pianoboy_试听片段.m4a"
        for (link in listOf(path, workspaceFileLink(path), "file://$path")) {
            val target = workspacePreviewTarget(link, project)!!
            assertEquals(WorkspacePreviewKind.AUDIO, target.kind)
            assertEquals(path, target.source)
            assertEquals(target, workspacePreviewTarget(target.navigationLink, project))
            assertEquals("audio/mp4", workspaceAudioMimeTypes[target.extension])
        }
    }

    @Test fun audioRoutesHandleRelativePathsUppercaseExtensionsAndSignedUrls() {
        for (extension in workspaceAudioMimeTypes.keys) {
            val local = workspacePreviewTarget("codex/试听.$extension", project)!!
            assertEquals(WorkspacePreviewKind.AUDIO, local.kind)
            assertEquals("$project/codex/试听.$extension", local.source)
            val url = "https://example.invalid/试听.${extension.uppercase()}?token=test&filename=not-video.mp4#play"
            val remote = workspacePreviewTarget(url, project)!!
            assertEquals(WorkspacePreviewKind.AUDIO, remote.kind)
            assertTrue(remote.source.contains("?token=test&filename=not-video.mp4#play"))
            assertEquals(remote, workspacePreviewTarget(remote.navigationLink, project))
        }
        assertEquals(WorkspacePreviewKind.WEB, workspacePreviewTarget("http://example.invalid/test.mp3", project)?.kind)
        assertEquals(WorkspacePreviewKind.WEB, workspacePreviewTarget("https://example.invalid/download?name=test.mp3", project)?.kind)
        assertNull(workspacePreviewTarget("/etc/private.mp3", project))
        assertNull(workspacePreviewTarget("javascript:play.mp3", project))
        assertEquals(WorkspacePreviewKind.FILE, workspacePreviewTarget("codex/notes.txt", project)?.kind)
    }

    @Test fun chatAudioReferencesPreserveEncodedNamesAndQueriesAndExcludeCodeBlocks() {
        val local = "$CODEX_STORAGE_ROOT/试听/歌曲 (2).m4a"
        val remote = "https://example.invalid/clip.MP3?token=test&name=photo.png"
        val text = "[试听][audio]\n\n[audio]: <$remote>\n\n[本地试听](<${workspaceFileLink(local)}>)\n" +
            "`codex/demo.wav`\ncodex/second.flac，\n~~~text\nhttps://example.invalid/example.mp3\n~~~"
        assertEquals(listOf(remote, workspaceFileLink(local), "codex/demo.wav", "codex/second.flac"), audioAttachmentPaths(text))
        assertEquals(listOf(remote), audioAttachmentPaths("试听地址：$remote。"))
        assertTrue(videoAttachmentPaths(text).isEmpty())
        assertTrue(audioAttachmentPaths("[网页](codex/page.html)\n[图片](codex/image.png)\n[视频](codex/video.mp4)").isEmpty())
    }
}
