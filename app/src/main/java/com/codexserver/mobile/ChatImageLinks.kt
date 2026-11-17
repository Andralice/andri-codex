package com.codexserver.mobile

import java.net.URI
import java.nio.file.Paths
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.parser.Parser

internal val chatImageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "heic", "heif", "svg")
private val chatImageParser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
private val bareImageUrl = Regex("""https://[^\s<>"']+""", RegexOption.IGNORE_CASE)
private val bareWorkspaceImagePath = Regex("""(?<![\w`/:(<])((?:file://|/|\./|\.\./|codex/)[^\s<>"'`]+)""")

private fun parseChatLink(value: String): URI? =
    runCatching { URI(value.replace(" ", "%20")) }.getOrNull()

private fun workspacePathFromLink(uri: URI, baseDirectory: String?): String? {
    val path = when {
        uri.scheme.equals("file", true) && (uri.host == null || uri.host.equals("localhost", true)) ->
            uri.path?.takeIf { it.startsWith('/') }
        uri.scheme == null && uri.rawAuthority == null -> uri.path
        uri.scheme == null && uri.host.equals("home", true) -> "/home" + uri.path.orEmpty()
        else -> null
    }?.takeIf { it.isNotBlank() && '\u0000' !in it } ?: return null

    val absolute = if (path.startsWith('/')) path else {
        if (baseDirectory == null) return path
        baseDirectory.trimEnd('/') + "/" + path
    }
    val normalized = Paths.get(absolute).normalize().toString()
    if (!isReadableWorkspacePath(normalized)) return null
    return if (path.endsWith('/')) "$normalized/" else normalized
}

internal sealed interface ChatLinkTarget {
    data class Workspace(val path: String) : ChatLinkTarget
    data class Image(val url: String) : ChatLinkTarget
    data class Web(val url: String) : ChatLinkTarget
}

internal fun chatLinkTarget(value: String, baseDirectory: String): ChatLinkTarget? {
    val uri = parseChatLink(value) ?: return null
    workspacePathFromLink(uri, baseDirectory)?.let { return ChatLinkTarget.Workspace(it) }
    if (uri.host == null) return null
    val url = uri.toASCIIString()
    return when {
        uri.scheme.equals("https", true) && isImagePath(uri.path) -> ChatLinkTarget.Image(url)
        uri.scheme.equals("https", true) || uri.scheme.equals("http", true) -> ChatLinkTarget.Web(url)
        else -> null
    }
}

private fun isImagePath(path: String?): Boolean =
    path?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase() in chatImageExtensions

/** Keep the URL for web images; decode workspace links to paths that SFTP can open. */
private fun chatImageSource(destination: String, explicitImage: Boolean): String? {
    if (destination.isBlank() || '\u0000' in destination) return null
    if (explicitImage && destination.startsWith("data:image/", true)) return destination

    val uri = parseChatLink(destination) ?: return null
    return when {
        uri.scheme.equals("https", true) -> uri.toASCIIString()
            .takeIf { explicitImage || isImagePath(uri.path) }
        uri.scheme.equals("http", true) -> destination.takeIf { explicitImage }
        else -> workspacePathFromLink(uri, baseDirectory = null)?.takeIf { explicitImage || isImagePath(it) }
    }
}

internal fun markdownImageDestinations(markdown: String): List<String> {
    val sources = linkedSetOf<String>()
    chatImageParser.parse(markdown).accept(object : AbstractVisitor() {
        override fun visit(image: Image) {
            chatImageSource(image.destination, explicitImage = true)?.let(sources::add)
        }

        override fun visit(link: Link) {
            chatImageSource(link.destination, explicitImage = false)?.let(sources::add)
            // A link may wrap an image; don't scan its link label for a second URL.
            var child = link.firstChild
            while (child != null) {
                if (child !is Text) child.accept(this)
                child = child.next
            }
        }

        override fun visit(text: Text) {
            bareImageUrl.findAll(text.literal).forEach { match ->
                val url = match.value.trimEnd('.', ',', ';', ':', ')', ']', '}')
                chatImageSource(url, explicitImage = false)?.let(sources::add)
            }
        }
    })
    return sources.toList()
}

/** Find image paths written as plain text by a server response. */
internal fun plainWorkspaceImagePaths(markdown: String, baseDirectory: String): List<String> =
    bareWorkspaceImagePath.findAll(markdown).mapNotNull { match ->
        if (isInsideMarkdownCode(markdown, match.range.first) || isUrlPathSuffix(markdown, match.range.first)) return@mapNotNull null
        val raw = match.groupValues[1].trimEnd('.', ',', ';', ':', '。', '，', '；', '：', ')', ']', '}')
        val target = chatLinkTarget(raw, baseDirectory) as? ChatLinkTarget.Workspace ?: return@mapNotNull null
        target.path.takeIf { it.substringAfterLast('/').substringAfterLast('.').lowercase() in chatImageExtensions }
    }.distinct().toList()

/** A path-looking suffix inside file:// or https:// is not a second relative image. */
private fun isUrlPathSuffix(text: String, offset: Int): Boolean {
    val tokenStart = text.lastIndexOfAny(charArrayOf(' ', '\t', '\r', '\n', '<', '>', '"', '\'', '`'), offset - 1) + 1
    val scheme = text.indexOf("://", tokenStart)
    return scheme >= tokenStart && scheme < offset
}

/** Text references and structured attachments can describe the same uploaded image. */
internal fun distinctChatImageSources(sources: List<String>, baseDirectory: String): List<String> =
    sources.filter(String::isNotBlank).map { workspacePreviewTarget(it, baseDirectory)?.source ?: it }.distinct()

internal fun userMessageImageSources(text: String, attachments: List<String>, baseDirectory: String): List<String> =
    distinctChatImageSources(attachments + markdownImageDestinations(text) + plainWorkspaceImagePaths(text, baseDirectory), baseDirectory)

private val uploadedImageNotice = Regex("""(?m)^[ \t]*(?:手机)?图片\s*[（(]已保存至项目\s*codex/inbox[)）][ \t]*[：:]?[ \t]*(?:\r?\n|$)""")

/** Hide generated transport details in the view without changing the message sent to the model. */
internal fun removePlainWorkspaceImagePaths(markdown: String, paths: List<String>, baseDirectory: String): String {
    if (paths.isEmpty()) return markdown
    val withoutPaths = bareWorkspaceImagePath.replace(markdown) { match ->
        if (isInsideMarkdownCode(markdown, match.range.first) || isUrlPathSuffix(markdown, match.range.first)) return@replace match.value
        val raw = match.groupValues[1]
        val trimmed = raw.trimEnd('.', ',', ';', ':', '。', '，', '；', '：', ')', ']', '}')
        val path = (chatLinkTarget(trimmed, baseDirectory) as? ChatLinkTarget.Workspace)?.path
        if (path !in paths) return@replace match.value
        raw.removePrefix(trimmed)
    }
    return uploadedImageNotice.replace(withoutPaths) { match ->
        if (isInsideMarkdownCode(withoutPaths, match.range.first)) match.value else ""
    }.trim('\r', '\n')
}

internal fun isInsideMarkdownCode(markdown: String, offset: Int): Boolean {
    var ticks = 0
    var index = 0
    while (index < offset) {
        if (markdown[index] == '`') ticks++
        index++
    }
    return ticks % 2 == 1
}
