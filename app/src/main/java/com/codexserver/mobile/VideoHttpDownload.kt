package com.codexserver.mobile

import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class PermanentMediaDownloadException(message: String) : IOException(message)

internal fun mediaRangeTotal(header: String?, offset: Long): Long {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(header.orEmpty())
        ?: throw IOException("服务器返回了无效的下载断点")
    val (start, end, total) = match.destructured
    val size = total.toLong()
    if (start.toLong() != offset || end.toLong() !in offset until size) throw IOException("服务器返回的下载断点不一致")
    return size
}

/** Range + If-Range prevents joining bytes from two versions of a signed URL. */
internal suspend fun downloadResumableVideoUrl(source: String, part: File,
    onProgress: suspend (MediaTransferProgress) -> Unit): Unit = withContext(Dispatchers.IO) {
    val headers = File(part.path + ".http")
    recoverMediaPartial(part)
    val previous = runCatching { JSONObject(headers.readText()) }.getOrNull()
    val validator = previous?.optString("validator").orEmpty()
    if (part.length() > 0 && validator.isBlank()) part.delete()
    var offset = part.length()
    var url = URL(source)
    var connection: HttpsURLConnection? = null
    try {
        for (redirect in 0..5) {
            require(url.protocol.equals("https", true)) { "视频链接必须使用 HTTPS" }
            onProgress(MediaTransferProgress("正在连接视频下载链接", part.length()))
            val opened = url.openConnection() as HttpsURLConnection
            connection = opened
            opened.connectTimeout = 20_000
            opened.readTimeout = 30_000
            opened.instanceFollowRedirects = false
            opened.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) {
                opened.setRequestProperty("Range", "bytes=$offset-")
                opened.setRequestProperty("If-Range", validator)
            }
            val status = opened.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                if (redirect == 5) throw PermanentMediaDownloadException("视频链接重定向过多")
                url = URL(url, opened.getHeaderField("Location") ?: throw IOException("视频链接重定向无效"))
                opened.disconnect()
                connection = null
                continue
            }
            if (status in setOf(401, 403, 404, 410)) throw PermanentMediaDownloadException("视频链接已失效或无权访问（$status），请刷新链接后重试")
            if (status == 416 && offset > 0) {
                val size = Regex("bytes \\*/(\\d+)").matchEntire(opened.getHeaderField("Content-Range").orEmpty())
                    ?.groupValues?.get(1)?.toLongOrNull()
                val currentValidator = opened.getHeaderField("ETag").orEmpty()
                    .takeUnless { it.startsWith("W/") }.orEmpty().ifBlank { opened.getHeaderField("Last-Modified").orEmpty() }
                if (size == offset && currentValidator.isNotBlank() && currentValidator == validator) return@withContext
                check(part.delete()) { "无法重置视频下载断点" }
                headers.delete()
                offset = 0
                opened.disconnect()
                connection = null
                continue
            }
            if (status !in setOf(200, 206)) throw IOException("视频下载暂不可用（$status），将自动重试")
            val total = if (status == 206) mediaRangeTotal(opened.getHeaderField("Content-Range"), offset)
                else opened.contentLengthLong.coerceAtLeast(0)
            require(total <= SshCodexClient.MAX_CHAT_VIDEO_BYTES) { "视频超过 4 GB" }
            val etag = opened.getHeaderField("ETag").orEmpty().takeUnless { it.startsWith("W/") }.orEmpty()
            val modified = opened.getHeaderField("Last-Modified").orEmpty()
            val currentValidator = etag.ifBlank { modified }
            val identity = "$source\n$currentValidator"
            if (status == 200 && offset > 0) check(part.delete()) { "无法重置视频下载断点" }
            val start = prepareMediaPartial(part, identity, total)
            if (status == 206 && start != offset) throw IOException("视频内容已更新，正在重新开始下载")
            val staged = File(headers.path + ".new")
            staged.writeText(JSONObject().put("validator", currentValidator).toString())
            check(staged.renameTo(headers)) { "无法保存视频下载信息" }
            opened.inputStream.use { appendMediaPartial(part, total, start, it, onProgress) }
            return@withContext
        }
    } finally { connection?.disconnect() }
}
