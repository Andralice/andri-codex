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
