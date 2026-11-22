package com.codexserver.mobile

import java.net.URI
import java.security.MessageDigest
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.parser.Parser

internal data class WorkspaceMedia(val source: String, val video: Boolean, val label: String) {
    val key: String = mediaSourceHash(source)
    val typeLabel: String get() = if (video) "视频" else "图片"
    val formatLabel: String get() {
        val extension = if (source.startsWith("data:image/", true)) {
            source.substringAfter('/').substringBefore(';').lowercase().let {
                when (it) { "jpeg" -> "jpg"; "svg+xml" -> "svg"; else -> it }
            }
        } else runCatching { URI(source.replace(" ", "%20")).path.orEmpty()
            .substringAfterLast('/').substringAfterLast('.', "").lowercase() }.getOrDefault("")
        return extension.takeIf { it in (if (video) SshCodexClient.VIDEO_EXTENSIONS else chatImageExtensions) }
            ?.uppercase().orEmpty()
    }
    val downloadName: String get() = if (source.startsWith("data:image/", true)) {
        val subtype = source.substringAfter("data:image/").substringBefore(';').lowercase()
        "图片." + when (subtype) { "jpeg" -> "jpg"; "svg+xml" -> "svg"; else -> subtype.takeIf { it in chatImageExtensions } ?: "png" }
    } else label
}

internal data class MediaDownloadState(val busy: Boolean = false, val saved: Boolean = false, val error: String = "",
    val progress: MediaTransferProgress = MediaTransferProgress())

internal fun mediaSourceHash(source: String): String = MessageDigest.getInstance("SHA-256")
    .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private val mediaParser = Parser.builder().build()
private val bareMediaLink = Regex("""(?<![\w/])(?:https://|file://|/|\./|\.\./|codex/)[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

/** Parse destinations rather than filenames, so query strings and encoded spaces survive. */
internal fun videoAttachmentPaths(text: String): List<String> = mediaAttachmentPaths(text, SshCodexClient.VIDEO_EXTENSIONS)

internal fun audioAttachmentPaths(text: String): List<String> = mediaAttachmentPaths(text, workspaceAudioMimeTypes.keys)

private fun mediaAttachmentPaths(text: String, extensions: Set<String>): List<String> {
    val paths = linkedSetOf<String>()
    fun add(value: String) {
        val extension = runCatching { URI(value.replace(" ", "%20")).path.orEmpty().substringAfterLast('.', "").lowercase() }
            .getOrDefault("")
        if (extension in extensions) paths += value
    }
    mediaParser.parse(text).accept(object : AbstractVisitor() {
        override fun visit(link: Link) { add(link.destination) }
        override fun visit(code: Code) { add(code.literal) }
        override fun visit(text: Text) {
            bareMediaLink.findAll(text.literal).forEach {
                add(it.value.trimEnd('.', ',', ';', ':', ')', ']', '}', '。', '，', '；', '：'))
            }
        }
    })
    return paths.toList()
}

/** Discovery depends on conversation references, never on a local cache file's existence. */
internal fun workspaceConversationMedia(messages: List<ChatLine>, cwd: String): List<WorkspaceMedia> {
    val media = linkedMapOf<String, WorkspaceMedia>()
    fun add(source: String, video: Boolean) {
        val target = workspacePreviewTarget(source, cwd) ?: return
        if (target.kind !in setOf(WorkspacePreviewKind.IMAGE, WorkspacePreviewKind.VIDEO) &&
            !source.startsWith("https://", true)) return
        if (target.source.startsWith("http://", true)) return
        val inline = source.startsWith("data:image/", true)
        val label = if (inline) "聊天图片" else target.name.take(120).ifBlank { if (video) "视频" else "图片" }
        val item = WorkspaceMedia(target.source, video, label)
        media.putIfAbsent(item.key, item)
    }
    fun read(line: ChatLine) {
        line.images.forEach { add(it, false) }
        if (line.role in setOf("assistant", "user", "tool")) {
            (markdownImageDestinations(line.text) + plainWorkspaceImagePaths(line.text, cwd)).forEach { add(it, false) }
            videoAttachmentPaths(line.text).forEach { add(it, true) }
        }
        line.processItems.asReversed().forEach(::read)
    }
    messages.asReversed().forEach(::read)
    return media.values.toList()
}
