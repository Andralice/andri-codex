package com.codexserver.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatImageLinksCheck {
    @Test fun workspacePngLinkProducesPreview() {
        val path = "$WORKSPACE_ROOT/自媒体/codex/云间小味/角色设定/女主角角色卡.png"
        assertEquals(listOf(path), markdownImageDestinations("[女主角角色卡.png]($path)"))
    }

    @Test fun encodedFileLinkAndEmbeddedImageResolveToSamePath() {
        val path = "$WORKSPACE_ROOT/自媒体/codex/图 1.png"
        val markdown = "![预览](<$path>)\n[原图](file://$WORKSPACE_ROOT/自媒体/codex/图%201.png)"
        assertEquals(listOf(path), markdownImageDestinations(markdown))
    }

    @Test fun originalImageLinksOpenWorkspaceFilesWithDecodedPaths() {
        val project = "$WORKSPACE_ROOT/自媒体"
        val path = "$project/codex/图 1.png"
        for (link in listOf(path, "file://$path", "codex/图%201.png", "./codex/图%201.png")) {
            assertEquals(ChatLinkTarget.Workspace(path), chatLinkTarget(link, project))
        }
        assertEquals(ChatLinkTarget.Workspace("$project/codex/"), chatLinkTarget("codex/", project))
        assertNull(chatLinkTarget("%2e%2e/%2e%2e/外部.png", project))
        assertNull(chatLinkTarget("file://other-host$path", project))
    }

    @Test fun imageUrlsUseImageViewerAndWebPagesUseWebPreview() {
        val image = "https://example.invalid/cloud.PNG?width=1024&format=png"
        val page = "https://example.invalid/guide"
        assertEquals(ChatLinkTarget.Image(image), chatLinkTarget(image, WORKSPACE_ROOT))
        assertEquals(ChatLinkTarget.Web(page), chatLinkTarget(page, WORKSPACE_ROOT))
    }

    @Test fun previewsRealImagesAndLeavesCodeAndOtherLinksAlone() {
        val markdown = """
            ![相对路径][preview]

            https://example.invalid/cloud.PNG?width=1024&format=png

            [工作区外](/tmp/外部.png) [文档](codex/README.md)

            ```markdown
            ![示例](codex/example.png)
            ```

            [preview]: <codex/图片 (2).png>
        """.trimIndent()

        assertEquals(listOf("codex/图片 (2).png", "https://example.invalid/cloud.PNG?width=1024&format=png"),
            markdownImageDestinations(markdown))
    }
}
