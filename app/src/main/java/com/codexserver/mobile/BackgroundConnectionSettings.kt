package com.codexserver.mobile

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun BackgroundConnectionSettingsButton() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { ActionLabel("settings", "后台连接设置") }
    if (open) BackgroundConnectionSettingsDialog { open = false }
}

@Composable
internal fun BackgroundConnectionSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    var unrestricted by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    DisposableEffect(lifecycle, powerManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unrestricted = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun openSettings(battery: Boolean) {
        val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        val intent = if (battery) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) else appSettings
        if (runCatching { context.startActivity(intent) }.isFailure &&
            runCatching { context.startActivity(appSettings) }.isFailure) {
            Toast.makeText(context, "请在系统设置中打开 Andri 的电池与后台运行权限", Toast.LENGTH_LONG).show()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("后台连接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("连接后，Andri 会通过常驻通知维持连接，并在连接中断后自动重试。长时间后台连接会增加耗电。")
                Text(if (unrestricted) "系统电池优化：已允许不优化。" else "系统电池优化：仍有限制。请在省电设置中找到 Andri，选择不优化或允许后台运行。")
                Text("部分手机还需要在应用设置中开启自启动，并将电池策略设为无限制。")
                Text("断网、系统强行停止或手动强制停止应用，仍会中断连接。")
            }
        },
        confirmButton = { TextButton(onClick = { openSettings(true) }) { ActionLabel("bolt", "打开省电设置") } },
        dismissButton = { TextButton(onClick = { openSettings(false) }) { ActionLabel("settings", "应用系统设置") } }
    )
}
