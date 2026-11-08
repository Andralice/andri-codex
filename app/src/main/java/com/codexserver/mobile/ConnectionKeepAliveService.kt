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
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** Owns background execution, while the Activity and service share one process session. */
class ConnectionKeepAliveService : Service() {
    companion object {
        private const val CHANNEL_ID = "connection_keep_alive"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_HOLD = "com.codexserver.mobile.action.HOLD_CONNECTION"

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
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivity: ConnectivityManager? = null
    private var lastNetwork: Network? = null
    private var networkCallbackRegistered = false
    private var notificationText = "正在维持或恢复服务器连接"
    private val maintenance = object : Runnable {
        override fun run() {
            // Timeout bounds the lock if maintenance stops unexpectedly; renew while connected.
            wakeLock?.acquire(120_000L)
            val session = ConnectionSession.get(application)
            session.maintainBackgroundConnection()
            val text = if (session.connected) "服务器已连接 · 可在后台接收任务进度"
                else if (session.connecting) "正在建立服务器连接"
                else "正在自动恢复服务器连接"
            if (text != notificationText) {
                notificationText = text
                if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                }
            }
            handler.postDelayed(this, 15_000L)
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = lastNetwork != null && lastNetwork != network
            lastNetwork = network
            ConnectionSession.get(application).maintainBackgroundConnection(networkChanged = changed)
        }
    }

    override fun onCreate() {
        super.onCreate()
        service = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Andri:ServerConnection")
            .apply { setReferenceCounted(false) }
        connectivity = getSystemService(ConnectivityManager::class.java)
        lastNetwork = connectivity?.activeNetwork
        runCatching {
            connectivity?.registerDefaultNetworkCallback(networkCallback, handler)
            networkCallbackRegistered = connectivity != null
        }.onFailure { android.util.Log.w("AndriConnection", "Network monitoring unavailable", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        client = pendingClient ?: client
        pendingClient = null
        val restore = getSharedPreferences("connection", MODE_PRIVATE).getBoolean("autoReconnect", false)
        if (client == null && !restore) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        handler.removeCallbacks(maintenance)
        handler.post(maintenance)
        // Android can recreate the service; the shared session then reloads encrypted credentials.
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (networkCallbackRegistered) runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        client = null
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
            .setContentText(notificationText)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
