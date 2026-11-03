package com.codexserver.mobile

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
