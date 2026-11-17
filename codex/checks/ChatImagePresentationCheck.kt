package com.codexserver.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatImagePresentationCheck {
    private val project = "$WORKSPACE_ROOT/andri-codex"
    private val image = "$project/codex/inbox/upload-1000015355.jpg"

    @Test fun uploadedImageInTextAndStructuredAttachmentHasOneDisplaySource() {
        val prompt = "这个发送的图片也不协调，为什么显示两个"
        val text = "$prompt\n手机图片（已保存至项目 codex/inbox）：\n$image"
        val item = JSONObject().put("type", "userMessage").put("id", "upload-check")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))
                .put(JSONObject().put("type", "localImage").put("path", image)))
        val line = parseChatItem(item)!!
        assertEquals(listOf(image), userMessageImageSources(line.text, line.images, project))
        assertEquals(prompt, removePlainWorkspaceImagePaths(line.text, plainWorkspaceImagePaths(line.text, project), project))
        assertEquals(text, line.text)
        assertEquals(listOf(image), line.images)
    }

    @Test fun aliasesAreDeduplicatedWithoutMergingDifferentImages() {
        val second = "$CODEX_STORAGE_ROOT/作品/upload-1000015355.jpg"
        val text = "$image\n[同一张](file://$image)\n[另一张]($second)"
        assertEquals(listOf(image, second), userMessageImageSources(text,
            listOf("codex/inbox/upload-1000015355.jpg", image, "file://$image"), project))
        val spaced = "$project/codex/inbox/图 1.png"
        assertEquals(listOf(spaced), distinctChatImageSources(listOf(spaced, workspaceFileLink(spaced), "file://${spaced.replace(" ", "%20")}"), project))
        assertEquals(listOf(image), userMessageImageSources("file://$image", emptyList(), project))
        assertEquals(listOf(image), plainWorkspaceImagePaths("file://$image", project))
        assertTrue(plainWorkspaceImagePaths("[同一张](file://$image)", project).isEmpty())
    }

    @Test fun olderTextOnlyUploadsStillAppearOnceWithoutTransportLabels() {
        val text = "手机图片（已保存至项目 codex/inbox）：\n$image"
        assertEquals(listOf(image), userMessageImageSources(text, emptyList(), project))
        assertEquals("", removePlainWorkspaceImagePaths(text, plainWorkspaceImagePaths(text, project), project))
    }

    @Test fun embeddedAndSignedImagesRemainDistinctWhenTheyReferToDifferentSources() {
        val inline = "data:image/png;base64,aGVsbG8="
        val first = "https://example.invalid/a.png?sig=1"
        val second = "https://example.invalid/a.png?sig=2"
        assertEquals(listOf(inline, first, second), userMessageImageSources("![原图]($first)\n$second",
            listOf(inline, inline, first), project))
    }

    @Test fun hidingTransportDetailsPreservesCodeExamplesAndOtherAttachments() {
        val video = "$project/codex/export.mp4"
        val document = "$project/codex/readme.md"
        val example = "```text\n手机图片（已保存至项目 codex/inbox）：\n$project/codex/example.png\n```"
        val text = "比较图片，然后阅读文档。\n$example\n$video\n$document\n手机图片（已保存至项目 codex/inbox）：\n$image"
        val paths = plainWorkspaceImagePaths(text, project)
        assertEquals(listOf(image), paths)
        val displayed = removePlainWorkspaceImagePaths(text, paths, project)
        assertTrue(displayed.contains(example))
        assertTrue(displayed.contains(video))
        assertTrue(displayed.contains(document))
        assertFalse(displayed.contains(image))
    }

    @Test fun imageMentionsDoNotEraseUnrelatedTextOrExternalPaths() {
        val text = "请对比 $image，再检查 /etc/private.png。"
        assertEquals(listOf(image), userMessageImageSources(text.replace("，", ", "), emptyList(), project))
        val separate = "保留这段文字。\n$image\n/etc/private.png"
        assertEquals("保留这段文字。\n\n/etc/private.png",
            removePlainWorkspaceImagePaths(separate, listOf(image), project))
    }
}
