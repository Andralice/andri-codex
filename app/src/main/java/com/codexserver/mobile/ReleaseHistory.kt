package com.codexserver.mobile

import org.json.JSONObject
import java.io.File

internal fun parseReleaseHistory(value: JSONObject): List<UpdateInfo> {
    val rows = value.getJSONArray("releases")
    check(rows.length() <= 200) { "历史版本过多" }
    return (0 until rows.length()).map { parseUpdateInfo(rows.getJSONObject(it)) }
        .also { releases ->
            check(releases.map { it.versionCode }.distinct().size == releases.size) { "历史版本号重复" }
        }.sortedByDescending { it.versionCode }
}

/** A history selection is tied to its own release, even when a newer version is published. */
internal suspend fun downloadHistoricalRelease(
    directory: File,
    selected: UpdateInfo,
    readHistory: suspend () -> List<UpdateInfo>,
    download: suspend (UpdateInfo, File) -> Unit,
    verify: suspend (File, UpdateInfo) -> Unit
): DownloadedUpdate {
    suspend fun confirm() {
        check(readHistory().any { it.samePackage(selected) }) { "所选历史包已变更或下架，请刷新列表" }
    }
    confirm()
    check(directory.isDirectory || directory.mkdirs()) { "无法创建下载目录" }
    val file = File(directory, "history-${selected.versionCode}-${selected.sha256}.apk")
    try {
        download(selected, file)
        verify(file, selected)
        confirm()
        return DownloadedUpdate(selected, file)
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}
