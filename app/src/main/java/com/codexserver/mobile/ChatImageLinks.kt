package com.codexserver.mobile

import java.net.URI
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.parser.Parser

internal val chatImageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "heic", "heif", "svg")
private val chatImageParser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
private val bareImageUrl = Regex("""https://[^\s<>"']+""", RegexOption.IGNORE_CASE)

/** Keep the URL for web images; decode workspace links to paths that SFTP can open. */
private fun chatImageSource(destination: String, explicitImage: Boolean): String? {
    if (destination.isBlank() || '\u0000' in destination) return null
    if (explicitImage && destination.startsWith("data:image/", true)) return destination

    val uri = runCatching { URI(destination.replace(" ", "%20")) }.getOrNull() ?: return null
    val path = uri.path?.takeIf { it.isNotBlank() && '\u0000' !in it }
    fun supportedImagePath(value: String?) =
        value?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase() in chatImageExtensions

    return when {
        uri.scheme.equals("https", true) -> destination.replace(" ", "%20")
            .takeIf { explicitImage || supportedImagePath(path) }
        uri.scheme.equals("http", true) -> destination.takeIf { explicitImage }
        uri.scheme.equals("file", true) && (uri.host == null || uri.host.equals("localhost", true)) ->
            path?.takeIf { it.startsWith("$WORKSPACE_ROOT/") && (explicitImage || supportedImagePath(it)) }
        uri.scheme == null && uri.rawAuthority == null ->
            path?.takeIf { (!it.startsWith('/') || it.startsWith("$WORKSPACE_ROOT/")) &&
                (explicitImage || supportedImagePath(it)) }
        else -> null
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
