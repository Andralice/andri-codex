package com.codexserver.mobile

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualizationPreviewCheck {
    private val project = "$WORKSPACE_ROOT/andri-codex"
    private val path = "$project/codex/预览 #1 (新版).html"
    private fun marker(path: String) = "\uE200visualize\uE202${JSONObject().put("path", path).put("mode", "wide")}\uE201"

    @Test fun plainImagePathsResolveDeduplicateAndRejectOutsideRoots() {
        val image = "$project/codex/图 1.png"
        val encoded = workspaceFileLink(image)
        val storage = "$CODEX_STORAGE_ROOT/图片/result.webp"
        val markdown = "生成完成： $encoded，\n$encoded\n$storage\n/etc/private.png"
        assertEquals(listOf(image, storage), plainWorkspaceImagePaths(markdown, project))
        assertEquals(listOf("$project/codex/result.png"),
            plainWorkspaceImagePaths("图片： codex/result.png。", project))
    }

    @Test fun plainImageExamplesInsideCodeAreNotShownAsAttachments() {
        val markdown = "`codex/inline.png`\n```text\n$project/codex/example.png\n```\n$project/codex/result.png"
        assertEquals(listOf("$project/codex/result.png"), plainWorkspaceImagePaths(markdown, project))
    }

    @Test fun nativeVisualizationReferenceBecomesAnOrdinaryWorkspacePreviewLink() {
        val markdown = "第一版做好了。\n\n${marker(path)}\n\n试试切换页面。"
        val result = visualizationPreviewLinks(markdown)
        val link = "[打开交互预览](<${workspaceFileLink(path)}>)"
        assertEquals("第一版做好了。\n\n$link\n\n试试切换页面。", result)
        assertEquals(path, workspacePreviewTarget(workspaceFileLink(path), project)?.source)
        assertEquals(result, visualizationPreviewLinks(result))
        val storage = "$CODEX_STORAGE_ROOT/设计稿/新预览.htm"
        assertTrue(visualizationPreviewLinks(marker(storage)).contains(workspaceFileLink(storage)))
    }

    @Test fun examplesInsideCodeAndMalformedOrUnsupportedReferencesStayLiteral() {
        for (source in listOf("`${marker(path)}`", "```text\n${marker(path)}\n```",
            "~~~text\n${marker(path)}\n~~~", "```text\n${marker(path)}",
            "\uE200visualize\uE202{bad json}\uE201", marker("/etc/private.html"),
            marker("https://example.com/page.html"), marker("$project/codex/image.png"))) {
            assertEquals(source, source, visualizationPreviewLinks(source))
        }
        val source = "`${marker(path)}`\n${marker(path)}\n${marker("$project/codex/second.html")}"
        val result = visualizationPreviewLinks(source)
        assertTrue(result.startsWith("`${marker(path)}`\n"))
        assertEquals(2, Regex("打开交互预览").findAll(result).count())
    }

    @Test fun htmlPreviewLoadsTheWholeDocumentBeyondTheTextPreviewLimit() {
        val folder = File("codex/html-preview-checks").apply { mkdirs() }
        val file = File.createTempFile("preview-", ".html", folder)
        try {
            val html = "<style>/*" + "字体数据".repeat(60_000) + "*/</style><button>切换页面</button><script>window.ready=true</script>"
            file.writeText(html)
            assertTrue(readWorkspacePreviewText(file).truncated)
            val complete = readWorkspaceHtml(file)
            assertEquals(html, complete)
            val wrapped = workspaceHtmlDocument(complete, "body{color:black}",
                "<!--__INLINE_VISUALIZATION_FRAGMENT__--><script>window.runtime=true</script>")
            assertTrue(wrapped.contains(html))
            assertTrue(wrapped.contains("name=\"viewport\""))
            assertTrue(wrapped.contains("window.runtime=true"))
            val standalone = "<!doctype html><html><head><title>标题</title></head><body>完整网页</body></html>"
            assertEquals(standalone, workspaceHtmlDocument(standalone, "css", "runtime"))
            java.io.RandomAccessFile(file, "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
            assertThrows(IllegalStateException::class.java) { readWorkspaceHtml(file) }
        } finally { file.delete(); folder.delete() }
    }

    @Test fun localHtmlCanLoadEncodedSiblingAssetsWithoutOpeningUnrelatedServerPaths() {
        val target = workspacePreviewTarget(workspaceFileLink(path), project)!!
        val url = workspaceHtmlUrl(target)
        assertTrue(url.startsWith("$WORKSPACE_HTML_ORIGIN/"))
        assertEquals(path, workspaceHtmlResource(url, target))
        val resource = java.net.URI(url).resolve("assets/图%201.svg?v=2").toASCIIString()
        assertEquals("$project/codex/assets/图 1.svg", workspaceHtmlResource(resource, target))
        for (blocked in listOf("$WORKSPACE_HTML_ORIGIN/etc/passwd", "$WORKSPACE_HTML_ORIGIN$project/codex-old/app.js",
            "$WORKSPACE_HTML_ORIGIN$project/codex/%2e%2e/private.txt", "https://example.com/app.js",
            "file:///etc/passwd", "https://user@andri-preview.invalid$project/codex/app.js")) {
            assertNull(blocked, workspaceHtmlResource(blocked, target))
        }
        val remote = workspacePreviewTarget("https://example.com/docs/demo.html", project)!!
        assertEquals(remote.source, workspaceHtmlUrl(remote))
        assertNull(workspaceHtmlResource(url, remote))
    }
}
