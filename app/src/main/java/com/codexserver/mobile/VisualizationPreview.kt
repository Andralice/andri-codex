package com.codexserver.mobile

import java.io.File
import java.net.URI
import org.json.JSONObject

// Android's ICU rejects an unescaped closing brace even though desktop Java accepts it.
private val visualizationToken = Regex("\uE200visualize\uE202(\\{[^\\r\\n]*?\\})\uE201")
private val markdownCodeOrPreview = Regex(
    "(?m)^ {0,3}(`{3,}|~{3,})[^\\n]*\\n[\\s\\S]*?(?:^ {0,3}\\1[ \\t]*$|\\z)|(`+)[\\s\\S]*?\\2|${visualizationToken.pattern}"
)

/** Turn host-specific references into ordinary links; examples inside code stay literal. */
internal fun visualizationPreviewLinks(markdown: String): String = markdownCodeOrPreview.replace(markdown) { match ->
    val token = visualizationToken.matchEntire(match.value) ?: return@replace match.value
    val path = runCatching { JSONObject(token.groupValues[1]).getString("path") }.getOrNull()
        ?: return@replace match.value
    if (!isReadableWorkspacePath(path)) return@replace match.value
    val target = workspacePreviewTarget(workspaceFileLink(path), WORKSPACE_ROOT)
    if (target == null || target.remote || target.extension !in setOf("html", "htm")) match.value
    else "[打开交互预览](<${target.navigationLink}>)"
}

internal const val WORKSPACE_HTML_ORIGIN = "https://andri-preview.invalid"
internal fun workspaceHtmlUrl(target: WorkspacePreviewTarget): String =
    if (target.remote) target.source else WORKSPACE_HTML_ORIGIN + target.navigationLink

/** Only serve sibling resources to a local HTML page, never unrelated server files. */
internal fun workspaceHtmlResource(url: String, target: WorkspacePreviewTarget): String? {
    if (target.remote) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.rawAuthority != "andri-preview.invalid") return null
    val source = chatLinkTarget(uri.rawPath.orEmpty(), target.linkBase) as? ChatLinkTarget.Workspace ?: return null
    return source.path.takeIf { it.startsWith(target.linkBase.trimEnd('/') + "/") }
}

internal fun readWorkspaceHtml(file: File): String {
    check(file.length() <= 8L * 1024 * 1024) { "HTML 文件超过 8 MB，请保存后打开" }
    return file.readText(Charsets.UTF_8)
}

internal fun workspaceHtmlDocument(html: String, stylesheet: String, runtime: String): String {
    if (Regex("<(?:!doctype|html|head|body)\\b", RegexOption.IGNORE_CASE).containsMatchIn(html)) return html
    return """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<style>$stylesheet
html>body{padding:12px;margin:0}img,video{max-width:100%}</style></head><body>
${runtime.replace("<!--__INLINE_VISUALIZATION_FRAGMENT__-->", html)}
</body></html>"""
}
