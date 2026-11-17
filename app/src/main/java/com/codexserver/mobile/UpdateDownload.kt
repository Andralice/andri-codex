package com.codexserver.mobile

import kotlinx.coroutines.CancellationException
import java.io.File

internal data class DownloadedUpdate(val info: UpdateInfo, val file: File)

internal fun UpdateInfo.samePackage(other: UpdateInfo): Boolean =
    versionCode == other.versionCode && versionName == other.versionName &&
        apkPath == other.apkPath && sha256 == other.sha256 && sizeBytes == other.sizeBytes

// Fetch the live manifest for each action; the dialog's earlier version is only a hint.
internal suspend fun downloadCurrentUpdate(
    directory: File,
    readLatest: suspend () -> UpdateInfo?,
    download: suspend (UpdateInfo, File) -> Unit,
    verify: suspend (File, UpdateInfo) -> Unit
): DownloadedUpdate? {
    val info = readLatest() ?: return null
    val file = File(directory, "update-${info.versionCode}-${info.sha256}.apk")
    try {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建更新目录" }

        // The download is deliberately keyed by the complete release identity. A
        // verified package can therefore survive a dialog dismissal or process
        // recreation and be used immediately instead of being fetched again.
        if (file.isFile) {
            val cached = try {
                verify(file, info)
                DownloadedUpdate(info, file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (cached != null) {
                val latest = readLatest()
                if (latest != null && info.samePackage(latest)) return cached
                // Do not fetch a package that was superseded (or whose live
                // manifest could not be confirmed) just because an older cache
                // file happened to be present.
                file.delete()
                return null
            }
            file.delete()
        }

        download(info, file)
        verify(file, info)
        val latest = readLatest()
        if (latest == null || !info.samePackage(latest)) {
            file.delete()
            return null
        }
        return DownloadedUpdate(info, file)
    } catch (e: Exception) {
        file.delete()
        throw e
    }
}

internal suspend fun installCurrentUpdate(
    downloaded: DownloadedUpdate,
    readLatest: suspend () -> UpdateInfo?,
    verify: suspend (File, UpdateInfo) -> Unit,
    install: (File) -> Unit
): Boolean {
    verify(downloaded.file, downloaded.info)
    val latest = readLatest()
    if (latest == null || !downloaded.info.samePackage(latest)) {
        downloaded.file.delete()
        return false
    }
    install(downloaded.file)
    return true
}
