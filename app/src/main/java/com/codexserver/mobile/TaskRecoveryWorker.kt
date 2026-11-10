package com.codexserver.mobile

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Durable follow-up for tasks whose completion event may arrive while the UI process is gone. */
internal class TaskRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val threadId = inputData.getString(KEY_THREAD_ID)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val baseDirectory = inputData.getString(KEY_CWD).orEmpty().ifBlank { WORKSPACE_ROOT }
        val settings = applicationContext.getSharedPreferences("connection", Context.MODE_PRIVATE)
        val host = settings.getString("host", "").orEmpty()
        val username = settings.getString("username", "").orEmpty()
        val port = settings.getString("port", "22")?.toIntOrNull() ?: 22
        val secrets = runCatching { SecureStore(applicationContext) }.getOrNull() ?: return Result.failure()
        val privateKey = runCatching { secrets.get("privateKey") }.getOrDefault("")
        val passphrase = runCatching { secrets.get("passphrase") }.getOrDefault("")
        if (host.isBlank() || username.isBlank() || privateKey.isBlank()) return Result.failure()

        CacheMaintenance.trim(applicationContext.cacheDir)
        val client = SshCodexClient(host, port, username, privateKey, passphrase) { }
        val resultRevision = TaskNotifications.revision(applicationContext, threadId)
        return try {
            client.connect()
            val result = client.readThread(threadId)
            val thread = result.optJSONObject("thread") ?: return Result.retry()
            val turns = thread.optJSONArray("turns") ?: org.json.JSONArray()
            val latestTurn = turns.optJSONObject(turns.length() - 1)
            val latestStatus = latestTurn?.text("status").orEmpty()
            if (latestStatus == "inProgress") return Result.retry()
            val latestTurnId = latestTurn?.text("id").orEmpty()
            if (latestStatus in terminalTaskStatuses) {
                val row = parseThread(thread)
                val notification = TaskNotifications.complete(applicationContext, threadId, latestTurnId, latestStatus,
                    row.title, projectNameForPath(row.cwd.ifBlank { baseDirectory }),
                    latestTurn?.optJSONObject("error")?.text("message").orEmpty().take(180), resultRevision)
                if (notification.stage == "superseded") return Result.retry()
                if (!notification.sent) android.util.Log.w("TaskRecovery", "Task notification unavailable: ${notification.stage}")
            }
            val latestTurnMessages = parseMessages(thread).filter { latestTurnId.isBlank() || it.turnId == latestTurnId }
            val imagePaths = taskImagePaths(latestTurnMessages)
            var downloadFailed = false
            if (imagePaths.isNotEmpty()) {
                val folder = File(applicationContext.cacheDir, "markdown-images")
                imagePaths.forEach { path ->
                    try {
                        if (path.startsWith("https://", true)) cacheRemoteImage(path)
                        else client.cacheWorkspaceImage(path, baseDirectory, folder)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        downloadFailed = true
                    }
                }
            }
            CacheMaintenance.trim(applicationContext.cacheDir)
            if (downloadFailed) return Result.retry()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        } finally {
            client.close()
        }
    }

    private fun taskImagePaths(lines: List<ChatLine>): List<String> {
        val result = linkedSetOf<String>()
        lines.asSequence().filter { it.role == "assistant" }.forEach { line ->
            line.images.forEach { value -> addWorkspacePath(result, value) }
            markdownImageDestinations(line.text).forEach { value ->
                if (!value.startsWith("data:", true) && !value.startsWith("http://", true)) result += value
            }
        }
        return result.toList()
    }

    private fun addWorkspacePath(target: MutableSet<String>, raw: String) {
        val value = raw.trim()
        if (value.isBlank() || value.startsWith("data:", true) || value.startsWith("http://", true) || value.startsWith("#")) return
        if (value.startsWith("https://", true)) {
            target += value
            return
        }
        val path = if (value.startsWith("file:", true)) {
            runCatching { java.net.URI(value).path }.getOrNull() ?: return
        } else value.substringBefore('#').substringBefore('?')
        if (path.isNotBlank()) target += path
    }

    private fun cacheRemoteImage(source: String) {
        val uri = URL(source)
        check(uri.protocol.equals("https", true)) { "只缓存 HTTPS 图片" }
        val folder = File(applicationContext.cacheDir, "linked-images").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val target = File(folder, "$key.img")
        if (target.isFile && target.length() in 1..SshCodexClient.MAX_CHAT_IMAGE_BYTES.toLong()) {
            target.setLastModified(System.currentTimeMillis())
            return
        }
        val connection = (uri.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            setRequestProperty("User-Agent", "Andri/Android")
        }
        try {
            connection.connect()
            check(connection.url.protocol.equals("https", true)) { "图片重定向到不安全链接" }
            check(connection.responseCode in 200..299) { "图片服务器返回 HTTP ${connection.responseCode}" }
            check(connection.contentType.orEmpty().substringBefore(';').startsWith("image/", true)) { "链接返回的不是图片" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= SshCodexClient.MAX_CHAT_IMAGE_BYTES) { "图片超过 10 MB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.isNotEmpty()) { "图片为空" }
            val staged = File(folder, "$key-${id}.tmp")
            try {
                staged.writeBytes(bytes)
                if (!staged.renameTo(target)) check(target.isFile && target.length() == bytes.size.toLong()) { "无法缓存图片" }
            } finally { staged.delete() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val KEY_THREAD_ID = "threadId"
        const val KEY_CWD = "cwd"
    }
}

internal object TaskRecoveryScheduler {
    fun enqueue(context: Context, threadId: String, cwd: String, delaySeconds: Long = 15L) {
        if (threadId.isBlank()) return
        val request = OneTimeWorkRequestBuilder<TaskRecoveryWorker>()
            .setInputData(Data.Builder().putString(TaskRecoveryWorker.KEY_THREAD_ID, threadId)
                .putString(TaskRecoveryWorker.KEY_CWD, cwd).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "task-recovery-$threadId", ExistingWorkPolicy.REPLACE, request
        )
    }
}

/** Bounded age and size cleanup for regenerable app-private downloads. */
internal object CacheMaintenance {
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_IMAGE_BYTES = 200L * 1024 * 1024
    private const val MAX_VIDEO_BYTES = 1_100L * 1024 * 1024
    private const val MAX_FILE_BYTES = 200L * 1024 * 1024

    fun trim(cacheDir: File, now: Long = System.currentTimeMillis()) {
        trimDirectory(File(cacheDir, "markdown-images"), MAX_IMAGE_BYTES, now)
        trimDirectory(File(cacheDir, "linked-images"), 100L * 1024 * 1024, now)
        trimDirectory(File(cacheDir, "workspace-videos"), MAX_VIDEO_BYTES, now)
        trimDirectory(File(cacheDir, "workspace-files"), MAX_FILE_BYTES, now)
    }

    private fun trimDirectory(directory: File, maxBytes: Long, now: Long) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        files.filter { it.extension == "tmp" || now - it.lastModified() > MAX_AGE_MS }
            .forEach { it.delete() }
        val retained = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = retained.sumOf { it.length() }
        for (file in retained) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }
}
