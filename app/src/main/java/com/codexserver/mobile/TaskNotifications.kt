package com.codexserver.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build

internal data class TaskNotificationResult(val sent: Boolean, val stage: String, val reason: String = "")

internal object TaskNotifications {
    // Keep task-result alerts independently configurable from the background
    // connection's persistent notification.
    private const val CHANNEL_ID = "task_completion_heads_up"
    private const val NOTIFICATION_ID = 2001

    fun revision(context: Context, threadId: String): Long = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        .getLong("thread.$threadId.taskResultRevision", 0L)

    fun settingsIntent(context: Context): Intent {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.let { ensureChannel(it) }
        return if (manager?.areNotificationsEnabled() == true) {
            Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
        } else Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    // Both entry points run in this process. Serialize the preference check and post so
    // WorkManager and the live socket cannot deliver the same result twice.
    @Synchronized
    fun complete(context: Context, threadId: String, turnId: String, status: String,
        threadTitle: String, project: String, detail: String, expectedRevision: Long? = null): TaskNotificationResult {
        if (threadId.isBlank() || status !in terminalTaskStatuses) return TaskNotificationResult(false, "invalid_result")
        if (expectedRevision != null && revision(context, threadId) != expectedRevision) {
            return TaskNotificationResult(false, "superseded")
        }
        val settings = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        val state = readState(settings, threadId).record(turnId, status)
        saveState(settings, threadId, state)
        if (!state.needsNotification()) return TaskNotificationResult(true, "already_handled")
        val result = show(context, threadId, threadTitle, project, taskResultTitle(status), detail)
        if (result.sent) saveState(settings, threadId, state.delivered())
        return result
    }

    @Synchronized
    fun acknowledge(context: Context, threadId: String) {
        val settings = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        saveState(settings, threadId, readState(settings, threadId).acknowledge())
        settings.edit().putLong("thread.$threadId.taskResultRevision", revision(context, threadId) + 1L).apply()
        context.getSystemService(NotificationManager::class.java)?.cancel(threadId, NOTIFICATION_ID)
    }

    private fun readState(settings: SharedPreferences, id: String) = TaskResultState(
        turnId = settings.getString("thread.$id.lastResultTurnId", "").orEmpty(),
        unreadStatus = settings.getString("thread.$id.lastTaskStatus", "").orEmpty(),
        notifiedTurnId = settings.getString("thread.$id.lastNotifiedTurnId", "").orEmpty(),
        readTurnId = settings.getString("thread.$id.lastReadResultTurnId", "").orEmpty()
    )

    private fun saveState(settings: SharedPreferences, id: String, state: TaskResultState) {
        if (readState(settings, id) == state) return
        settings.edit().putString("thread.$id.lastResultTurnId", state.turnId)
            .putString("thread.$id.lastTaskStatus", state.unreadStatus)
            .putString("thread.$id.lastNotifiedTurnId", state.notifiedTurnId)
            .putLong("thread.$id.taskResultRevision", settings.getLong("thread.$id.taskResultRevision", 0L) + 1L)
            .putString("thread.$id.lastReadResultTurnId", state.readTurnId).apply()
    }

    private fun ensureChannel(manager: NotificationManager) {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "任务横幅提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "任务完成、失败或中断时显示系统横幅，点击打开对应对话"
            enableVibration(true)
        })
    }

    private fun show(context: Context, threadId: String, threadTitle: String, project: String, title: String, detail: String): TaskNotificationResult {
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
                ensureChannel(manager)
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
            .setData(Uri.parse("andri://task/${Uri.encode(threadId)}"))
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
            manager.notify(threadId, NOTIFICATION_ID, builder
                .setSmallIcon(R.drawable.ic_task_notification)
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
