package com.codexserver.mobile

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal data class MediaTransferProgress(
    val stage: String = "等待网络或调度",
    val downloaded: Long = 0,
    val total: Long = 0,
    val bytesPerSecond: Long = 0,
) {
    val fraction: Float? get() = total.takeIf { it > 0 }?.let {
        (downloaded.toDouble() / it).coerceIn(0.0, 1.0).toFloat()
    }
    val description: String get() = buildString {
        append(stage)
        if (total > 0) {
            append(" · ${((fraction ?: 0f) * 100).toInt()}%")
            append(" · ${mediaByteLabel(downloaded)} / ${mediaByteLabel(total)}")
        } else if (downloaded > 0) append(" · 已保留 ${mediaByteLabel(downloaded)}")
        if (bytesPerSecond > 0) append(" · ${mediaByteLabel(bytesPerSecond)}/s")
    }
}

internal fun mediaByteLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** Only evict completed caches; active work and every unfinished part are retained. */
internal fun trimCompletedVideos(root: File, activeKeys: Set<String>,
    maxBytes: Long = SshCodexClient.MAX_CHAT_VIDEO_BYTES + 512L * 1024 * 1024,
    now: Long = System.currentTimeMillis()) {
    val complete = root.listFiles()?.filter { it.isDirectory }?.flatMap { directory ->
        directory.listFiles()?.filter { it.isFile && it.name.startsWith("complete.") }.orEmpty()
    }.orEmpty().sortedBy { it.lastModified() }
    var total = complete.sumOf { it.length() }
    for (file in complete) {
        if (file.parentFile?.name in activeKeys) continue
        if (total <= maxBytes && now - file.lastModified() < 30L * 24 * 60 * 60 * 1000) continue
        val size = file.length()
        if (file.delete()) total -= size
    }
}

/** A stable part file plus source identity survives worker/process/device restarts. */
internal fun prepareMediaPartial(file: File, sourceIdentity: String, total: Long): Long {
    val metadata = File(file.path + ".source")
    val previous = runCatching { JSONObject(metadata.readText()) }.getOrNull()
    val matches = previous != null && previous.optString("identity") == sourceIdentity && previous.optLong("size") == total
    if (!matches || (total > 0 && file.length() > total)) {
        check(!file.exists() || file.delete()) { "无法重置视频下载断点" }
    } else {
        recoverMediaPartial(file)
    }
    file.parentFile?.mkdirs()
    writeMediaCheckpoint(file, JSONObject().put("identity", sourceIdentity).put("size", total), file.length())
    return file.length()
}

/** A power failure may leave an unflushed tail beyond the last durable checkpoint. */
internal fun recoverMediaPartial(file: File): Long {
    if (!file.exists()) return 0
    val metadata = runCatching { JSONObject(File(file.path + ".source").readText()) }.getOrNull()
    val durable = (metadata?.optLong("durable", 0) ?: 0).coerceAtLeast(0).coerceAtMost(file.length())
    if (file.length() > durable) RandomAccessFile(file, "rw").use { output ->
        output.setLength(durable)
        output.fd.sync()
    }
    return durable
}

private fun writeMediaCheckpoint(file: File, metadata: JSONObject, durable: Long) {
    val target = File(file.path + ".source")
    val staged = File(target.path + ".new")
    FileOutputStream(staged).use { output ->
        output.write(metadata.put("durable", durable).toString().toByteArray())
        output.fd.sync()
    }
    check(staged.renameTo(target)) { "无法保存视频下载断点" }
}

/** The input starts exactly at offset; append only, never truncate on a failed read. */
internal suspend fun appendMediaPartial(
    file: File, total: Long, offset: Long, input: InputStream,
    onProgress: suspend (MediaTransferProgress) -> Unit,
) {
    require(offset == file.length() && offset >= 0 && (total <= 0 || offset <= total))
    var downloaded = offset
    var checkpoint = offset
    var lastReport = System.nanoTime()
    var lastBytes = offset
    val metadata = JSONObject(File(file.path + ".source").readText())
    onProgress(MediaTransferProgress("正在下载", offset, total))
    FileOutputStream(file, true).use { output ->
        val buffer = ByteArray(256 * 1024)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if ((total > 0 && downloaded + count > total) || downloaded + count > SshCodexClient.MAX_CHAT_VIDEO_BYTES)
                    throw IOException("服务器文件在下载中发生变化或视频超过 4 GB，请重新校验")
                output.write(buffer, 0, count)
                downloaded += count
                // Bound lost progress on abrupt power loss; resume always uses disk length.
                if (downloaded - checkpoint >= 4L * 1024 * 1024) {
                    output.fd.sync()
                    writeMediaCheckpoint(file, metadata, downloaded)
                    checkpoint = downloaded
                }
                val now = System.nanoTime()
                if (now - lastReport >= 500_000_000L || downloaded == total) {
                    val speed = ((downloaded - lastBytes) * 1_000_000_000.0 / (now - lastReport).coerceAtLeast(1)).toLong()
                    onProgress(MediaTransferProgress("正在下载", downloaded, total, speed))
                    lastBytes = downloaded
                    lastReport = now
                }
            }
        } finally {
            output.fd.sync()
            writeMediaCheckpoint(file, metadata, downloaded)
        }
    }
    if (total > 0 && downloaded != total) throw IOException("视频传输中断，已保留 ${mediaByteLabel(downloaded)}，将从断点继续")
}
