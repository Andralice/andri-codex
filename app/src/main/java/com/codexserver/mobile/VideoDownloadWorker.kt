package com.codexserver.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.work.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun mediaServerIdentity(host: String, port: Int, username: String) = "$username@$host:$port"
internal const val VIDEO_PROGRESS_PREFERENCES = "video-download-progress"

internal object VideoDownloadFiles {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun key(server: String, base: String, source: String) = mediaSourceHash("$server\n$base\n$source")
    fun directory(context: Context, key: String) = File(context.filesDir, "video-downloads/$key").apply { mkdirs() }

    suspend fun cache(context: Context, key: String, source: String, base: String,
        client: SshCodexClient, onProgress: suspend (MediaTransferProgress) -> Unit = {}): File =
        locks.getOrPut(key) { Mutex() }.withLock {
            val directory = directory(context, key)
            val extension = runCatching { java.net.URI(source).path.substringAfterLast('.').lowercase() }
                .getOrDefault("mp4").takeIf { it in SshCodexClient.VIDEO_EXTENSIONS } ?: "mp4"
            val complete = File(directory, "complete.$extension")
            if (complete.isFile && complete.length() > 0) {
                complete.setLastModified(System.currentTimeMillis())
                return@withLock complete
            }
            val part = File(directory, "video.part")
            if (source.startsWith("https://", true)) downloadResumableVideoUrl(source, part, onProgress)
            else client.downloadWorkspaceVideo(source, base, part, onProgress)
            check(part.length() > 0 && part.renameTo(complete)) { "无法保存已下载的视频" }
            complete
        }
}

/** WorkManager owns the task and its destination URI across process death and reboot. */
internal class VideoDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var notificationChannelReady = false
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestFile = inputData.getString("request")?.let(::File) ?: return@withContext Result.failure()
        val request = runCatching { JSONObject(requestFile.readText()) }.getOrNull() ?: return@withContext Result.failure()
        val source = request.getString("source")
        val base = request.getString("base")
        val destination = Uri.parse(request.getString("destination"))
        val gallery = request.optBoolean("gallery")
        val key = request.getString("key")
        val mediaKey = request.getString("mediaKey")
        val settings = applicationContext.getSharedPreferences("connection", Context.MODE_PRIVATE)
        val checkpoints = applicationContext.getSharedPreferences(VIDEO_PROGRESS_PREFERENCES, Context.MODE_PRIVATE)
        val host = settings.getString("host", "").orEmpty()
        val port = settings.getString("port", "22")?.toIntOrNull() ?: 22
        val username = settings.getString("username", "").orEmpty()
        val directory = VideoDownloadFiles.directory(applicationContext, key)
        var progress = MediaTransferProgress(downloaded = File(directory, "video.part").length())
        var lastCheckpoint = 0L
        var lastCheckpointStage = ""
        suspend fun report(next: MediaTransferProgress) {
            progress = if (next.total == 0L && progress.total > 0) next.copy(total = progress.total) else next
            val data = progressData(progress)
            // Persist a checkpoint for the media row and foreground notification.
            val now = System.nanoTime()
            if (progress.stage != lastCheckpointStage || now - lastCheckpoint >= 5_000_000_000L) {
                // Do not rewrite the large connection/conversation preferences for every chunk.
                checkpoints.edit().putString(mediaKey, progressJson(progress).toString()).apply()
                lastCheckpoint = now
                lastCheckpointStage = progress.stage
            }
            setProgress(data)
            setForeground(foreground(request.optString("label", "视频"), progress))
        }
        try {
            require(request.getString("server") == mediaServerIdentity(host, port, username)) { "服务器账户已切换，请重新发起下载" }
            report(progress.copy(stage = "正在准备下载"))
            val secrets = SecureStore(applicationContext)
            val privateKey = secrets.get("privateKey")
            require(privateKey.isNotBlank()) { "SSH 登录信息不可用，请重新连接后重试" }
            val client = SshCodexClient(host, port, username, privateKey, secrets.get("passphrase")) { }
            // Dedicated SFTP authenticates independently; never start a model/app-server connection.
            val file = VideoDownloadFiles.cache(applicationContext, key, source, base, client, ::report)
            settings.edit().putString("media.cache.$mediaKey", file.absolutePath).apply()
            report(MediaTransferProgress("正在保存到相册", 0, file.length()))
            val resolver = applicationContext.contentResolver
            if (gallery) resolver.update(destination, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 1) }, null, null)
            checkNotNull(resolver.openOutputStream(destination, "wt")) { "无法写入视频保存位置" }.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    var last = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val now = System.nanoTime()
                        if (now - last > 500_000_000L || copied == file.length()) {
                            report(MediaTransferProgress("正在保存到相册", copied, file.length()))
                            last = now
                        }
                    }
                    output.flush()
                }
            }
            if (gallery) check(resolver.update(destination, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null) > 0) { "无法发布相册视频" }
            settings.edit().putString("media.saved.$mediaKey", destination.toString()).commit()
            runCatching {
                val activeKeys = WorkManager.getInstance(applicationContext).getWorkInfosByTag(VideoDownloadScheduler.TAG).get()
                    .filter { !it.state.isFinished }.flatMap { it.tags }.filter { it.startsWith("transfer-key:") }
                    .mapTo(mutableSetOf(key)) { it.removePrefix("transfer-key:") }
                trimCompletedVideos(File(applicationContext.filesDir, "video-downloads"), activeKeys)
            }
            Result.success(progressData(MediaTransferProgress("已保存到相册", file.length(), file.length())))
        } catch (cancelled: CancellationException) {
            // Worker stop/network loss/reboot must leave both the task and its part file intact.
            throw cancelled
        } catch (failure: Exception) {
            val permanent = failure is PermanentMediaDownloadException || failure is IllegalArgumentException ||
                (failure is IllegalStateException && failure.javaClass.simpleName != "ForegroundServiceStartNotAllowedException") ||
                failure is SecurityException || failure.message?.contains("ENOSPC", true) == true ||
                (failure is com.jcraft.jsch.SftpException && failure.id in setOf(2, 3))
            if (permanent) {
                runCatching { applicationContext.contentResolver.delete(destination, null, null) }
                Result.failure(Data.Builder().putString("error", failure.message ?: "视频下载失败").build())
            } else {
                val retained = maxOf(File(directory, "video.part").length(), directory.listFiles()
                    ?.filter { it.name.startsWith("complete.") }?.maxOfOrNull { it.length() } ?: 0L)
                val waiting = progress.copy(stage = "连接中断，等待网络后自动续传", downloaded = retained, bytesPerSecond = 0)
                checkpoints.edit().putString(mediaKey, progressJson(waiting).toString()).commit()
                setProgress(progressData(waiting))
                Result.retry()
            }
        }
    }

    private fun foreground(label: String, progress: MediaTransferProgress): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (!notificationChannelReady) {
            manager.createNotificationChannel(NotificationChannel("video_downloads", "视频下载", NotificationManager.IMPORTANCE_LOW))
            notificationChannelReady = true
        }
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(applicationContext, "video_downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(label.take(90))
            .setContentText(progress.description).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, ((progress.fraction ?: 0f) * 100).toInt(), progress.fraction == null).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(id.hashCode(), notification)
    }
}

internal fun progressJson(progress: MediaTransferProgress) = JSONObject().put("stage", progress.stage)
    .put("downloaded", progress.downloaded).put("total", progress.total).put("speed", progress.bytesPerSecond)

internal fun progressData(progress: MediaTransferProgress) = Data.Builder().putString("stage", progress.stage)
    .putLong("downloaded", progress.downloaded).putLong("total", progress.total).putLong("speed", progress.bytesPerSecond).build()

internal object VideoDownloadScheduler {
    const val TAG = "workspace-video-download"
    private val enqueueLock = Mutex()

    suspend fun enqueue(context: Context, media: WorkspaceMedia, base: String, destination: Uri, gallery: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            enqueueLock.withLock {
                val settings = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
                val server = mediaServerIdentity(settings.getString("host", "").orEmpty(),
                    settings.getString("port", "22")?.toIntOrNull() ?: 22, settings.getString("username", "").orEmpty())
                val key = VideoDownloadFiles.key(server, base, media.source)
                val name = "$TAG-$key"
                val manager = WorkManager.getInstance(context)
                if (manager.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }) return@withLock false
                val directory = VideoDownloadFiles.directory(context, key)
                val request = File(directory, "request-${java.util.UUID.randomUUID()}.json")
                val data = JSONObject().put("source", media.source).put("base", base).put("destination", destination.toString())
                    .put("gallery", gallery).put("key", key).put("mediaKey", media.key).put("server", server).put("label", media.label)
                FileOutputStream(request).use { output -> output.write(data.toString().toByteArray()); output.fd.sync() }
                val work = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                    .setInputData(Data.Builder().putString("request", request.absolutePath).build())
                    .addTag(TAG).addTag("media-key:${media.key}").addTag("transfer-key:$key")
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
                settings.edit().putString("media.work.${media.key}", work.id.toString()).remove("media.saved.${media.key}").commit()
                context.getSharedPreferences(VIDEO_PROGRESS_PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putString(media.key, progressJson(MediaTransferProgress()).toString()).commit()
                manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, work).result.get()
                true
            }
        }
}
