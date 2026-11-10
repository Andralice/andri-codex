package com.codexserver.mobile

import org.junit.Assert.assertEquals
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
