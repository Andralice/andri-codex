package com.codexserver.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** Keeps the SSH/WebSocket client owned by the process while Android backgrounds the UI. */
class ConnectionKeepAliveService : Service() {
    companion object {
        private const val CHANNEL_ID = "connection_keep_alive"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_HOLD = "com.codexserver.mobile.action.HOLD_CONNECTION"
        private const val ACTION_STOP = "com.codexserver.mobile.action.STOP_CONNECTION"

        @Volatile private var pendingClient: SshCodexClient? = null
        @Volatile private var service: ConnectionKeepAliveService? = null

        fun hold(context: Context, client: SshCodexClient) {
            service?.let { running ->
                running.client = client
                return
            }
            pendingClient = client
            val intent = Intent(context, ConnectionKeepAliveService::class.java).setAction(ACTION_HOLD)
            context.startForegroundService(intent)
        }

        fun currentClient(): SshCodexClient? = service?.client?.takeIf { it.isAlive() }
        fun isActive(): Boolean = service != null

        fun clear(client: SshCodexClient) {
            if (pendingClient === client) pendingClient = null
            service?.let { current -> if (current.client === client) current.client = null }
        }

        fun release(context: Context, client: SshCodexClient? = null) {
            val ownsConnection = service?.let { client == null || it.client === client }
                ?: (client == null || pendingClient === client)
            if (!ownsConnection) return
            if (client == null || pendingClient === client) pendingClient = null
            service?.let { current ->
                if (client == null || current.client === client) current.client = null
            }
            context.stopService(Intent(context, ConnectionKeepAliveService::class.java))
        }
    }

    @Volatile internal var client: SshCodexClient? = null

    override fun onCreate() {
        super.onCreate()
        service = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                client = null
                pendingClient = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            else -> client = pendingClient ?: client
        }
        pendingClient = null
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (service === this) service = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "服务器连接", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Andri 在后台维持服务器连接时显示"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Andri 后台连接已启用")
            .setContentText("正在维持或恢复服务器连接")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
