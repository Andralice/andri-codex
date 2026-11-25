package com.codexserver.mobile

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal val workspaceTextExtensions = setOf(
    "txt", "md", "markdown", "mdown", "json", "xml", "yaml", "yml", "toml", "kt", "kts", "java",
    "js", "ts", "tsx", "jsx", "py", "sh", "html", "htm", "css", "csv", "log", "diff", "patch",
    "sql", "rs", "go", "c", "h", "cpp", "hpp", "ini", "conf", "properties", "srt", "vtt", "svg"
)
internal val workspaceMarkdownExtensions = setOf("md", "markdown", "mdown")
internal val workspaceHtmlExtensions = setOf("html", "htm")
internal enum class WorkspacePreviewKind { FILE, IMAGE, VIDEO, AUDIO, WEB, DIRECTORY }

internal fun workspaceFileLink(path: String): String = URI(null, null, path, null).toASCIIString().replace(":", "%3A")

internal data class WorkspacePreviewTarget(val source: String, val kind: WorkspacePreviewKind,
    val location: WorkspaceSourceLocation? = null) {
    val remote: Boolean get() = source.startsWith("https://", true) || source.startsWith("http://", true)
    private val inlineImage: Boolean get() = source.startsWith("data:image/", true)
    val navigationLink: String get() = if (remote || inlineImage) source else workspaceFileLink(source) +
        (location?.let { "#L${it.line}" + (it.column?.let { column -> "C$column" } ?: "") } ?: "")
    val name: String get() = if (inlineImage) "聊天图片" else (if (remote) URI(source).path.orEmpty() else source)
        .trimEnd('/').substringAfterLast('/').ifBlank { "文件" }
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val displayName: String get() = name + (location?.let { ":${it.line}" + (it.column?.let { column -> ":$column" } ?: "") } ?: "")
    val linkBase: String get() = if (remote) source else source.substringBeforeLast('/').ifBlank { "/" }
}

/** Resolve a document's relative links before handing them to the shared right pane. */
internal fun workspacePreviewTarget(value: String, base: String): WorkspacePreviewTarget? {
    if (value.startsWith("data:image/", true)) return WorkspacePreviewTarget(value, WorkspacePreviewKind.IMAGE)
    val resolved = if (base.startsWith("https://", true) || base.startsWith("http://", true)) {
        runCatching { URI(base).resolve(value.replace(" ", "%20")).toString() }.getOrNull() ?: return null
    } else value
    val reference = workspaceSourceReference(resolved)
    val link = chatLinkTarget(reference.link, base) ?: return null
    val source = when (link) {
        is ChatLinkTarget.Workspace -> link.path
        is ChatLinkTarget.Image -> link.url
        is ChatLinkTarget.Web -> link.url
    }
    val target = WorkspacePreviewTarget(source, WorkspacePreviewKind.FILE,
        reference.location.takeIf { link is ChatLinkTarget.Workspace })
    val kind = when {
        !target.remote && (source.endsWith('/') || source in workspaceReadRoots) -> WorkspacePreviewKind.DIRECTORY
        // Keep the browser's existing HTTPS policy for cleartext links.
        source.startsWith("http://", true) -> WorkspacePreviewKind.WEB
        target.remote && target.extension in workspaceHtmlExtensions -> WorkspacePreviewKind.WEB
        target.extension in chatImageExtensions -> WorkspacePreviewKind.IMAGE
        target.extension in SshCodexClient.VIDEO_EXTENSIONS -> WorkspacePreviewKind.VIDEO
        target.extension in workspaceAudioMimeTypes -> WorkspacePreviewKind.AUDIO
        !target.remote || target.extension in workspaceTextExtensions ||
            target.extension in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "7z", "rar", "apk") -> WorkspacePreviewKind.FILE
        else -> WorkspacePreviewKind.WEB
    }
    return target.copy(kind = kind)
}

internal data class WorkspacePreviewText(val text: String, val truncated: Boolean)

internal fun readWorkspacePreviewText(file: File): WorkspacePreviewText {
    val limit = 192_000
    return file.reader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(limit + 1)
        var count = 0
        while (count < buffer.size) {
            val read = reader.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        var end = count.coerceAtMost(limit)
        if (end < count && end > 0 && buffer[end - 1].isHighSurrogate()) end--
        WorkspacePreviewText(String(buffer, 0, end), count > limit)
    }
}

internal suspend fun downloadWorkspacePreviewUrl(url: String, directory: File,
    maxBytes: Long = 64L * 1024 * 1024): File = withContext(Dispatchers.IO) {
    directory.mkdirs()
    val file = File(directory, "${UUID.randomUUID()}-preview")
    var address = url
    try {
        repeat(6) {
            val scheme = URI(address).scheme
            check(scheme.equals("https", true) || scheme.equals("http", true)) { "文件链接地址无效" }
            val connection = URI(address).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val next = checkNotNull(connection.getHeaderField("Location")) { "文件跳转地址为空" }
                    address = URI(address).resolve(next).toString()
                } else {
                    check(code in 200..299) { "文件读取失败（HTTP $code）" }
                    val expectedLength = connection.contentLengthLong
                    check(expectedLength <= maxBytes) { "文件超过 ${maxBytes / 1024 / 1024} MB" }
                    connection.inputStream.use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= maxBytes) { "文件超过 ${maxBytes / 1024 / 1024} MB" }
                            output.write(buffer, 0, count)
                        }
                        check(total > 0L && (expectedLength < 0L || total == expectedLength)) { "文件下载不完整，请重试" }
                    } }
                    return@withContext file
                }
            } finally { connection.disconnect() }
        }
        error("文件链接跳转次数过多")
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}
