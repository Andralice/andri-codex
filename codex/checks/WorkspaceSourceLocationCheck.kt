package com.codexserver.mobile

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSourceLocationCheck {
    private val project = "$WORKSPACE_ROOT/andri-codex"
    private val path = "$project/app/src/main/java/com/codexserver/mobile/MainActivity.kt"

    @Test fun screenshotSourceLinkOpensTheActualKotlinFile() {
        val target = workspacePreviewTarget("$path:1626", project)!!
        assertEquals(path, target.source)
        assertEquals("MainActivity.kt", target.name)
        assertEquals("kt", target.extension)
        assertEquals("MainActivity.kt:1626", target.displayName)
        assertEquals(WorkspacePreviewKind.FILE, target.kind)
        assertEquals(WorkspaceSourceLocation(1626), target.location)
        assertEquals(target, workspacePreviewTarget(target.navigationLink, project))
    }

    @Test fun sourceCoordinatesWorkForRelativeFileAndFragmentLinks() {
        for (link in listOf("MainActivity.kt:862:9", "./MainActivity.kt:862:9", "file://$path:862:9",
            "file://localhost$path#L862C9", "$path#L862C9", "$path#L862C9-L870")) {
            val target = workspacePreviewTarget(link, path.substringBeforeLast('/'))!!
            assertEquals(link, path, target.source)
            assertEquals(link, WorkspaceSourceLocation(862, 9), target.location)
            assertEquals(target, workspacePreviewTarget(target.navigationLink, project))
        }
        assertEquals(WorkspaceSourceLocation(12), workspacePreviewTarget("$path#L12-L16", project)?.location)
    }

    @Test fun differentLocationsInTheSameFileHaveDistinctNavigationEntries() {
        val first = workspacePreviewTarget("$path:1626", project)!!
        val second = workspacePreviewTarget("$path:862", project)!!
        assertEquals(first.source, second.source)
        assertNotEquals(first.navigationLink, second.navigationLink)
        assertEquals(path.substringBeforeLast('/'), first.linkBase)
    }

    @Test fun savedLinksPreserveLiteralColonsHashesAndUnicodeInFilenames() {
        val literal = "$project/codex/源码 #L12:123.kt:456"
        val target = workspacePreviewTarget(workspaceFileLink(literal), project)!!
        assertEquals(literal, target.source)
        assertNull(target.location)
        assertEquals(target, workspacePreviewTarget(target.navigationLink, project))
    }

    @Test fun locationsDoNotChangeRemoteUrlsOrBypassWorkspaceBoundaries() {
        for (link in listOf("https://example.com:8443/code.kt:1626#L12", "https://example.com/guide#L12")) {
            val target = workspacePreviewTarget(link, project)!!
            assertEquals(link, target.source)
            assertNull(target.location)
        }
        for (link in listOf("/etc/passwd:12", "../../../etc/passwd:12", "file://other-host$path:12",
            "javascript:12", "content://provider/file.kt:12")) {
            assertNull(link, workspacePreviewTarget(link, project))
        }
        assertNull(workspacePreviewTarget("$path:99999999999999999999", project)?.location)
    }

    @Test fun sourceOffsetsUseOneBasedLinesAndClampColumns() {
        val text = "first\r\n第二行\nlast\n"
        assertEquals(0, workspaceSourceOffset(text, WorkspaceSourceLocation(1)))
        assertEquals(text.indexOf('第'), workspaceSourceOffset(text, WorkspaceSourceLocation(2)))
        assertEquals(text.indexOf('二'), workspaceSourceOffset(text, WorkspaceSourceLocation(2, 2)))
        assertEquals(text.indexOf('\n', text.indexOf('第')), workspaceSourceOffset(text, WorkspaceSourceLocation(2, Int.MAX_VALUE)))
        assertEquals(text.length, workspaceSourceOffset(text, WorkspaceSourceLocation(4)))
        assertNull(workspaceSourceOffset(text, WorkspaceSourceLocation(5)))
        assertNull(workspaceSourceOffset(text, WorkspaceSourceLocation(Int.MAX_VALUE)))
        assertNull(workspaceSourceOffset(text, WorkspaceSourceLocation(0)))
    }

    @Test fun resolvedLinkCanReadARealFileAndLocateTheReferencedText() {
        val directory = File("codex/source-location-checks").apply { mkdirs() }
        val file = File.createTempFile("source-", ".kt", directory)
        try {
            val content = "// first\nfun selected() = 42\n"
            file.writeText(content)
            val target = workspacePreviewTarget("${file.absolutePath}:2:5", project)!!
            val preview = readWorkspacePreviewText(File(target.source))
            assertEquals(content, preview.text)
            val offset = workspaceSourceOffset(preview.text, target.location!!)!!
            assertTrue(preview.text.substring(offset).startsWith("selected"))
        } finally { file.delete(); directory.delete() }
    }
}
