package com.codexserver.mobile

import kotlinx.coroutines.*

/** A blocking SSH library must never hold the screen or a retry gate indefinitely. */
internal suspend fun awaitConnectionAttempt(
    timeoutMillis: Long = 45_000,
    abort: () -> Unit,
    connect: suspend () -> Unit
) {
    val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val worker = workerScope.async { connect() }
    try {
        withTimeout(timeoutMillis) { worker.await() }
    } catch (error: Exception) {
        // Closing a socket can itself block in a third-party library. Keep it off the UI thread.
        CoroutineScope(Dispatchers.IO).launch { abort() }
        throw error
    } finally {
        workerScope.cancel()
    }
}

internal fun connectionStageLabel(stage: String): String = when (stage) {
    "connect-start" -> "正在连接服务器…"
    "tcp-connected" -> "服务器已响应，正在协商 SSH…"
    "ssh-key-exchange-start" -> "正在验证服务器身份…"
    "ssh-key-exchange-complete" -> "正在验证 SSH 私钥…"
    "ssh-authenticated", "ssh-session" -> "SSH 已连接，正在启动 Codex 通道…"
    "ssh-exec" -> "正在与 Codex 握手…"
    "websocket" -> "正在初始化 Codex…"
    "initialized" -> "Codex 已连接，正在恢复对话…"
    else -> "正在连接服务器…"
}
