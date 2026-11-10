package com.codexserver.mobile

import java.io.File
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal val workspaceTextExtensions = setOf(
    "txt", "md", "markdown", "mdown", "json", "xml", "yaml", "yml", "toml", "kt", "kts", "java",
    "js", "ts", "tsx", "jsx", "py", "sh", "html", "htm", "css", "csv", "log", "diff", "patch",
    "sql", "rs", "go", "c", "h", "cpp", "hpp", "ini", "conf", "properties", "srt", "vtt", "svg"
)
internal val workspaceMarkdownExtensions = setOf("md", "markdown", "mdown")
internal enum class WorkspacePreviewKind { FILE, IMAGE, VIDEO, WEB, DIRECTORY }

internal fun workspaceFileLink(path: String): String = URI(null, null, path, null).toASCIIString()

internal data class WorkspacePreviewTarget(val source: String, val kind: WorkspacePreviewKind) {
    val remote: Boolean get() = source.startsWith("https://", true) || source.startsWith("http://", true)
    private val inlineImage: Boolean get() = source.startsWith("data:image/", true)
    val navigationLink: String get() = if (remote || inlineImage) source else workspaceFileLink(source)
    val name: String get() = if (inlineImage) "聊天图片" else (if (remote) URI(source).path.orEmpty() else source)
        .trimEnd('/').substringAfterLast('/').ifBlank { "文件" }
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val linkBase: String get() = if (remote) source else source.substringBeforeLast('/').ifBlank { "/" }
}

/** Resolve a document's relative links before handing them to the shared right pane. */
internal fun workspacePreviewTarget(value: String, base: String): WorkspacePreviewTarget? {
    if (value.startsWith("data:image/", true)) return WorkspacePreviewTarget(value, WorkspacePreviewKind.IMAGE)
    val resolved = if (base.startsWith("https://", true) || base.startsWith("http://", true)) {
        runCatching { URI(base).resolve(value.replace(" ", "%20")).toString() }.getOrNull() ?: return null
    } else value
    val link = chatLinkTarget(resolved, base) ?: return null
    val source = when (link) {
        is ChatLinkTarget.Workspace -> link.path
        is ChatLinkTarget.Image -> link.url
        is ChatLinkTarget.Web -> link.url
    }
    val target = WorkspacePreviewTarget(source, WorkspacePreviewKind.FILE)
    val kind = when {
        !target.remote && (source.endsWith('/') || source in workspaceReadRoots) -> WorkspacePreviewKind.DIRECTORY
        // Keep the browser's existing HTTPS policy for cleartext links.
        source.startsWith("http://", true) -> WorkspacePreviewKind.WEB
        target.extension in chatImageExtensions -> WorkspacePreviewKind.IMAGE
        target.extension in SshCodexClient.VIDEO_EXTENSIONS -> WorkspacePreviewKind.VIDEO
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

internal suspend fun downloadWorkspacePreviewUrl(url: String, directory: File): File = withContext(Dispatchers.IO) {
    directory.mkdirs()
    val file = File(directory, "${UUID.randomUUID()}-preview")
    var address = url
    try {
        repeat(6) {
            check(URI(address).scheme.equals("https", true)) { "文件链接仅支持 HTTPS" }
            val connection = URI(address).toURL().openConnection() as HttpsURLConnection
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
                    val maxBytes = 64L * 1024 * 1024
                    check(connection.contentLengthLong <= maxBytes) { "远程文件超过 64 MB" }
                    connection.inputStream.use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= maxBytes) { "远程文件超过 64 MB" }
                            output.write(buffer, 0, count)
                        }
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
