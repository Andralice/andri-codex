package com.codexserver.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

internal data class TaskNotificationResult(val sent: Boolean, val stage: String, val reason: String = "")

internal object TaskNotifications {
    // Keep task-result alerts independently configurable from the background
    // connection's persistent notification.
    private const val CHANNEL_ID = "task_completion_heads_up"

    fun show(context: Context, threadId: String, threadTitle: String, project: String, title: String, detail: String): TaskNotificationResult {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return TaskNotificationResult(false, "permission", "POST_NOTIFICATIONS 未授权")
        }
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return TaskNotificationResult(false, "notification_manager", "系统通知服务不可用")
        if (!manager.areNotificationsEnabled()) {
            return TaskNotificationResult(false, "app_notifications_disabled", "系统设置中已关闭 Andri 通知")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "任务横幅提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Andri 在后台时，于其他应用上方显示任务完成、失败或中断提醒"
                })
                val channel = manager.getNotificationChannel(CHANNEL_ID)
                if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) {
                    return TaskNotificationResult(false, "channel_disabled", "系统设置中已关闭任务横幅提醒分类")
                }
            } catch (error: Exception) {
                android.util.Log.w("TaskNotifications", "Unable to prepare task notification channel", error)
                return TaskNotificationResult(false, "channel_setup", error.message ?: error.javaClass.simpleName)
            }
        }
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("threadId", threadId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, threadId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val content = buildString {
            append("项目：").append(project)
            if (detail.isNotBlank()) append(" · ").append(detail)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return runCatching {
            manager.notify((threadId + title + System.currentTimeMillis()).hashCode(), builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("$title：$threadTitle")
                .setContentText(content)
                .setTicker("$title：$threadTitle")
                .setStyle(Notification.BigTextStyle().bigText(content))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(Notification.PRIORITY_MAX)
                .setDefaults(Notification.DEFAULT_ALL)
                .build())
            TaskNotificationResult(true, "posted")
        }.getOrElse {
            android.util.Log.w("TaskNotifications", "Unable to post task notification", it)
            TaskNotificationResult(false, "notify", it.message ?: it.javaClass.simpleName)
        }
    }
}
