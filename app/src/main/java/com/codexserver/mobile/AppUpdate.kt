package com.codexserver.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

@Suppress("DEPRECATION")
internal fun verifyUpdateApk(context: Context, file: File, info: UpdateInfo, allowHistorical: Boolean = false) {
    val manager = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val archive = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("无法读取 APK 安装信息")
    val installed = manager.getPackageInfo(context.packageName, flags)
    check(archive.packageName == context.packageName) { "更新包不属于当前应用" }
    val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
    check(code == info.versionCode.toLong() && (allowHistorical || code > BuildConfig.VERSION_CODE)) { "更新包版本与清单不一致" }
    check(archive.versionName == info.versionName) { "更新包版本名与清单不一致" }
    fun signatures(value: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) value.signingInfo?.apkContentsSigners else value.signatures
        return signatures.orEmpty().map { it.toCharsString() }.toSet()
    }
    val expected = signatures(installed)
    check(expected.isNotEmpty() && signatures(archive) == expected) { "更新包签名与已安装应用不一致" }
}

internal fun installUpdate(context: Context, file: File) {
    if (!context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName)))
        return
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })
}
