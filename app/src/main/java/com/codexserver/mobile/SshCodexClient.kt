package com.codexserver.mobile

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.io.OutputStream
import com.jcraft.jsch.ChannelSftp
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import android.util.Base64

data class CodexThread(
    val id: String, val title: String, val model: String = "",
    val running: Boolean = false, val turnId: String = "", val cwd: String = "",
    val updatedAt: Long = 0L
)
data class ChatQuestion(val title: String, val options: List<String> = emptyList())
data class QueuedSubmission(val id: String, val clientUserMessageId: String, val text: String)
data class ImageUpload(val dataUrl: String, val label: String)
internal const val MAX_CHAT_LINES_PER_PAGE = 500
internal const val INITIAL_THREAD_TURNS_LIMIT = 8
internal const val THREAD_TURNS_PAGE_LIMIT = 10
internal const val THREAD_TURNS_COMPACT_PAGE_LIMIT = 3

internal fun shouldRetryCompactTurnsPage(error: Exception): Boolean =
    error is IOException && error.message.orEmpty().startsWith("thread/turns/list 等待响应超时")

internal suspend fun <T> retryCompactTurnsPage(
    fullPage: suspend () -> T,
    compactPage: suspend () -> T
): T = try {
    fullPage()
} catch (error: Exception) {
    if (!shouldRetryCompactTurnsPage(error)) throw error
    compactPage()
}

internal suspend fun awaitWorkspaceSettingsUpdate(updated: Deferred<JSONObject>): JSONObject? =
    withTimeoutOrNull(5_000) { updated.await() }

internal suspend fun awaitWorkspaceSettingsConfirmation(updated: Deferred<JSONObject>): JSONObject =
    awaitWorkspaceSettingsUpdate(updated)
        ?: throw IOException("服务器未确认工作区设置，保存结果尚未确认；请重新连接核对后再操作")
data class ChatLine(val role: String, val text: String, val id: String = "", val turnId: String = "",
    val stage: String = "", val afterId: String = "", val streaming: Boolean = false,
    val questions: List<ChatQuestion> = emptyList(), val status: String = "", val images: List<String> = emptyList(),
    val processItems: List<ChatLine> = emptyList())
// turn/start accepts UserInput, not the model provider's ContentItem wire format.
internal fun userInput(
    prompt: String,
    imageDataUrls: List<String> = emptyList(),
    localImagePaths: List<String> = emptyList()
): JSONArray =
    JSONArray().put(JSONObject().put("type", "text").put("text", prompt)).apply {
        imageDataUrls.forEach { put(JSONObject().put("type", "image").put("url", it)) }
        localImagePaths.forEach { put(JSONObject().put("type", "localImage").put("path", it)) }
    }
internal fun userInput(prompt: String, imageDataUrl: String?): JSONArray =
    userInput(prompt, listOfNotNull(imageDataUrl))
internal fun filterThreads(rows: List<CodexThread>, runningIds: Set<String>, query: String, onlyRunning: Boolean): List<CodexThread> =
    rows.filter { (!onlyRunning || it.id in runningIds) &&
        (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) || it.model.contains(query.trim(), ignoreCase = true)) }
internal fun projectNameForPath(path: String): String {
    val root = WORKSPACE_ROOT.trimEnd('/')
    val normalized = path.trimEnd('/')
    if (normalized == root || !normalized.startsWith("$root/")) return "未指定项目"
    return normalized.removePrefix("$root/").substringBefore('/').ifBlank { "未指定项目" }
}
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkPath: String,
    val sha256: String,
    val notes: String,
    val sizeBytes: Long
)

internal fun parseUpdateInfo(value: JSONObject): UpdateInfo {
    val versionCode = value.getInt("versionCode")
    val versionName = value.getString("versionName")
    val apkPath = value.getString("apkPath")
    val sha256 = value.getString("sha256").lowercase()
    val sizeBytes = value.optLong("sizeBytes", -1L)
    check(versionCode > 0) { "更新版本号无效" }
    check(versionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+([.-][A-Za-z0-9]+)?"))) { "更新版本名无效" }
    check(sha256.matches(Regex("[0-9a-f]{64}"))) { "更新包校验值无效" }
    check(apkPath.startsWith(SshCodexClient.RELEASE_ROOT) &&
        apkPath.substring(SshCodexClient.RELEASE_ROOT.length).matches(Regex("[A-Za-z0-9._-]+\\.apk"))) {
        "更新包路径不在受信任发布目录内"
    }
    check(sizeBytes in 1..SshCodexClient.MAX_UPDATE_BYTES) { "更新包大小无效" }
    return UpdateInfo(versionCode, versionName, apkPath, sha256, value.optString("notes"), sizeBytes)
}

internal fun JSONObject.text(key: String): String =
    if (isNull(key)) "" else optString(key)

internal fun parseThread(row: JSONObject, model: String = ""): CodexThread {
    val turns = row.optJSONArray("turns") ?: JSONArray()
    val active = (0 until turns.length()).mapNotNull { turns.optJSONObject(it) }
        .lastOrNull { it.text("status") == "inProgress" }
    val title = row.text("name").ifBlank { row.text("title") }
        .ifBlank { threadPreviewTitle(row.opt("preview")) }
    return CodexThread(
        row.text("id"),
        title.ifBlank { "新对话" }.take(120),
        model,
        row.optJSONObject("status")?.text("type") == "active" || active != null,
        active?.text("id").orEmpty(), row.text("cwd"),
        threadTimestamp(row.opt("updatedAt") ?: row.opt("updated_at"))
    )
}

private fun threadTimestamp(value: Any?): Long = when (value) {
    is Number -> value.toLong().let { if (it in 1 until 10_000_000_000L) it * 1_000 else it }
    is String -> value.toLongOrNull()?.let { number ->
        if (number in 1 until 10_000_000_000L) number * 1_000 else number
    } ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
    else -> 0L
}

private fun threadPreviewTitle(preview: Any?): String = when (preview) {
    is String -> preview
    is JSONObject -> preview.text("text").ifBlank { preview.text("content") }
    is JSONArray -> (0 until preview.length()).mapNotNull { index ->
        preview.optJSONObject(index)?.text("text")?.takeIf { it.isNotBlank() }
    }.joinToString(" ")
    else -> ""
}.replace(Regex("\\s+"), " ").trim()

internal fun parseChatItem(item: JSONObject): ChatLine? {
    val role = when (item.text("type")) {
        "userMessage" -> "user"
        "agentMessage" -> "assistant"
        else -> return toolChatLine(item)
    }
    val content = item.optJSONArray("content") ?: JSONArray()
    val imageValues = buildList {
        fun collect(value: Any?) {
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) collect(value.opt(index))
                is JSONObject -> {
                    if (value.text("type") == "localImage") {
                        value.text("path").takeIf(String::isNotBlank)?.let { add(it) }
                    }
                    if (value.text("type") == "image") {
                        val imageUrl = listOf("url", "image_url", "data").firstNotNullOfOrNull { key ->
                            when (val candidate = value.opt(key)) {
                                is String -> candidate.takeIf(String::isNotBlank)
                                is JSONObject -> listOf("url", "data").firstNotNullOfOrNull { nestedKey ->
                                    candidate.optString(nestedKey).takeIf { it.isNotBlank() && it != "null" }
                                }
                                else -> null
                            }
                        }
                        imageUrl?.let { add(it) }
                    }
                    // Some app-server versions wrap content parts (or image_url) in
                    // nested objects. Walk them so every image in the user message survives.
                    value.keys().forEach { key -> collect(value.opt(key)) }
                }
            }
        }
        collect(content)
    }.distinct()
    val value = item.text("text").ifBlank { (0 until content.length()).mapNotNull { content.optJSONObject(it)?.takeIf { p -> p.text("type") == "text" }?.text("text") }.joinToString("\n") }
    val questions = item.optJSONArray("questions") ?: JSONArray()
    return ChatLine(role, value, item.text("id"), questions = (0 until questions.length()).mapNotNull { index ->
        val question = questions.optJSONObject(index) ?: return@mapNotNull null
        val options = question.optJSONArray("options") ?: JSONArray()
        ChatQuestion(question.text("title"), (0 until options.length()).map { options.optString(it) })
    }, images = imageValues)
}

internal fun toolChatLine(item: JSONObject, output: String = "", completed: Boolean = true): ChatLine? {
    val id = item.text("id")
    val type = item.text("type")
    if (id.isBlank() || type in listOf("userMessage", "agentMessage")) return null
    val title = when (type) {
        "commandExecution" -> item.text("command").ifBlank { "命令执行" }
        "fileChange" -> "文件变更"
        "mcpToolCall" -> listOf(item.text("server"), item.text("tool")).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "MCP 工具" }
        "dynamicToolCall" -> item.text("tool").ifBlank { "动态工具" }
        "webSearch" -> "搜索：" + item.text("query")
        "plan" -> "执行计划"
        "reasoning" -> "思考摘要"
        else -> type.ifBlank { "工具输出" }
    }
    fun formatted(key: String): String = when (val value = item.opt(key)) {
        null, JSONObject.NULL -> ""
        is JSONObject -> value.toString(2)
        is JSONArray -> value.toString(2)
        else -> value.toString()
    }
    val detail = when (type) {
        "commandExecution" -> listOf(item.text("command"), item.text("cwd"),
            item.text("aggregatedOutput").ifBlank { output },
            if (item.isNull("exitCode")) "" else "退出码：${item.optInt("exitCode")}").filter { it.isNotBlank() }.joinToString("\n")
        "fileChange" -> formatted("changes").ifBlank { output }
        "plan" -> item.text("text").ifBlank { output }
        // Display only the summary the server provides, never fabricate private reasoning.
        "reasoning" -> (item.optJSONArray("summary") ?: JSONArray()).let { summary ->
            (0 until summary.length()).map { summary.optString(it) }.joinToString("\n\n") }.ifBlank { output }
        "webSearch" -> listOf(item.text("query"), formatted("results")).filter { it.isNotBlank() }.joinToString("\n")
        else -> listOf(formatted("arguments"), formatted("result"), formatted("error"), formatted("contentItems"),
            formatted("output"), output).filter { it.isNotBlank() }.joinToString("\n")
    }
    val status = item.text("status").ifBlank { if (completed) "completed" else "inProgress" }
    return ChatLine("tool", detail, "tool:$id", stage = title, status = status)
}

internal fun parseMessages(thread: JSONObject): List<ChatLine> {
    val turns = thread.optJSONArray("turns") ?: JSONArray()
    val newestFirst = mutableListOf<ChatLine>()
    var truncated = false
    loop@ for (i in turns.length() - 1 downTo 0) {
            val turn = turns.optJSONObject(i) ?: continue
            val items = turn.optJSONArray("items") ?: JSONArray()
            if (turn.text("status") == "failed") {
                if (newestFirst.size >= MAX_CHAT_LINES_PER_PAGE) { truncated = true; break }
                val id = turn.text("id")
                newestFirst.add(ChatLine("error",
                    turn.optJSONObject("error")?.text("message").orEmpty().ifBlank { "Andri 任务失败" },
                    "turnError:$id", id, "任务执行失败"))
            }
            for (j in items.length() - 1 downTo 0) {
                if (newestFirst.size >= MAX_CHAT_LINES_PER_PAGE) { truncated = true; break@loop }
                items.optJSONObject(j)?.let { item ->
                    parseChatItem(item)?.let { if (it.text.isNotBlank() || it.images.isNotEmpty() || it.questions.isNotEmpty() || it.role == "tool") newestFirst.add(it.copy(turnId = turn.text("id"))) }
                }
            }
    }
    val result = newestFirst.asReversed().toMutableList()
    if (truncated) result.add(0, ChatLine("tool", "本页内容过多，只显示最近 $MAX_CHAT_LINES_PER_PAGE 条消息；可继续加载更早历史。",
        "history-truncated:" + turns.optJSONObject(turns.length() - 1)?.text("id").orEmpty(), stage = "历史消息较多"))
    return result
}

class SshCodexClient(
    private val host: String, private val port: Int, private val username: String,
    private val privateKey: String, private val passphrase: String,
    private val onConnectionStage: (String) -> Unit = {},
    onServerMessage: (JSONObject) -> Unit
) {
    private class EphemeralTextRun(val sideChat: Boolean) {
        val text = StringBuilder()
        var finalText = ""
        @Volatile var turnId = ""
        val completed = CompletableDeferred<String>()
    }

    @Volatile private var serverMessageHandler: (JSONObject) -> Unit = onServerMessage
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val pendingSettings = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val ephemeralThreadIds = ConcurrentHashMap.newKeySet<String>()
    private val ephemeralTextRuns = ConcurrentHashMap<String, EphemeralTextRun>()
    private val closed = AtomicBoolean(false)
    internal val diagnosticId = Integer.toHexString(System.identityHashCode(this))
    private val connectionTimings = mutableListOf<JSONObject>()
    private val connectionTimingsLock = Any()
    private val connectionStartedAt = System.nanoTime()
    private var session: Session? = null
    private var channel: ChannelExec? = null
    private var websocket: WebSocketClient? = null
    private val writeLock = Any()
    private val stderr = StringBuilder()

    fun setServerMessageHandler(handler: (JSONObject) -> Unit) {
        serverMessageHandler = handler
    }

    internal fun recordConnectionTiming(stage: String, elapsedMillis: Long, details: JSONObject = JSONObject()) {
        synchronized(connectionTimingsLock) {
            connectionTimings += JSONObject().put("stage", stage).put("elapsedMs", elapsedMillis).put("detail", details)
            if (connectionTimings.size > 32) connectionTimings.removeAt(0)
        }
    }

    internal fun connectionTimingReport(): JSONObject = JSONObject()
        .put("type", "connection_timing_trace")
        .put("time", System.currentTimeMillis())
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("events", JSONArray().apply {
            synchronized(connectionTimingsLock) {
                connectionTimings.forEach { put(JSONObject(it.toString())) }
            }
        })

    private fun markConnection(stage: String) {
        val elapsed = (System.nanoTime() - connectionStartedAt) / 1_000_000
        android.util.Log.d("AndriConnection", "[$diagnosticId] $stage elapsed=${elapsed}ms")
        recordConnectionTiming(stage, elapsed)
        onConnectionStage(stage)
    }

    /** Also used by recovery downloads; no Codex process or WebSocket is required. */
    suspend fun connectSshOnly() = withContext(Dispatchers.IO) {
        require(host.isNotBlank() && username.isNotBlank() && port in 1..65535) { "请填写有效的服务器地址、用户名和端口" }
        require(privateKey.contains("PRIVATE KEY")) { "请选择 SSH 私钥文件，不是 .pub 公钥文件" }
        markConnection("connect-start")
        try {
            val jsch = JSch()
            val recordedStages = ConcurrentHashMap.newKeySet<String>()
            // Only emit fixed stage names. Library messages can contain addresses and
            // authentication details, so never forward their raw text to Android logs.
            jsch.setInstanceLogger(object : com.jcraft.jsch.Logger {
                override fun isEnabled(level: Int) = level == com.jcraft.jsch.Logger.INFO
                override fun log(level: Int, message: String) {
                    val stage = when {
                        message == "Connection established" -> "tcp-connected"
                        message == "SSH_MSG_KEXINIT sent" -> "ssh-key-exchange-start"
                        message == "SSH_MSG_NEWKEYS received" -> "ssh-key-exchange-complete"
                        message.startsWith("Authentication succeeded (") -> "ssh-authenticated"
                        else -> return
                    }
                    if (recordedStages.add(stage)) markConnection(stage)
                }
            })
            // Use the same Ed25519 implementation on Android and in the JVM integration check.
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            val knownHost = if (port == 22) host else "[" + host + "]:" + port
            jsch.setKnownHosts(ByteArrayInputStream(
                (knownHost + " ssh-ed25519 " + PINNED_HOST_KEY + "\n").toByteArray(Charsets.UTF_8)
            ))
            jsch.addIdentity(
                "codex-mobile", privateKey.toByteArray(Charsets.UTF_8), null,
                passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            )
            val sshSession = jsch.getSession(username, host, port).apply {
                setConfig("StrictHostKeyChecking", "yes")
                setConfig("PreferredAuthentications", "publickey")
                setConfig("server_host_key", "ssh-ed25519")
                serverAliveInterval = 20_000
                serverAliveCountMax = 3
                timeout = 45_000
            }
            session = sshSession
            check(!closed.get()) { "连接已取消" }
            sshSession.connect(15_000)
            if (closed.get()) {
                sshSession.disconnect()
                error("连接已取消")
            }
            markConnection("ssh-session")
        } catch (error: Exception) {
            disconnect(error, report = false)
            throw error
        }
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            connectSshOnly()
            val exec = (session!!.openChannel("exec") as ChannelExec).apply {
                setCommand("bash -lc 'codex app-server proxy --sock /home/ubuntu/.codex/mobile-control/app-server.sock'")
            }
            channel = exec
            val input = exec.inputStream
            val output = exec.outputStream
            val errorInput = exec.errStream
            exec.connect(15_000)
            markConnection("ssh-exec")
            scope.launch {
                runCatching {
                    errorInput.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            synchronized(stderr) {
                                stderr.append(line).append('\n')
                                if (stderr.length > 4096) stderr.delete(0, stderr.length - 4096)
                            }
                        }
                    }
                }
            }
            val ws = object : WebSocketClient(URI("ws://localhost/")) {
                override fun onOpen(handshake: ServerHandshake) = Unit
                override fun onMessage(value: String) {
                    val message = runCatching { JSONObject(value) }.getOrNull() ?: return
                    val method = message.text("method")
                    val params = message.optJSONObject("params")
                    val startedThread = if (method == "thread/started") params?.optJSONObject("thread") else null
                    if (startedThread?.optBoolean("ephemeral") == true) {
                        startedThread.text("id").takeIf { it.isNotBlank() }?.let(ephemeralThreadIds::add)
                        return
                    }
                    val notificationThreadId = params?.text("threadId").orEmpty()
                    if (notificationThreadId in ephemeralThreadIds) {
                        val run = ephemeralTextRuns[notificationThreadId]
                        if (message.has("id")) {
                            val requestId = message.get("id")
                            run?.completed?.completeExceptionally(IllegalStateException("临时问答不能使用工具或发起审批"))
                            scope.launch { runCatching { rejectUnsupportedRequest(requestId, method) } }
                        } else if (run != null) handleEphemeralTextNotification(run, method, params ?: JSONObject())
                        return
                    }
                    if (message.optString("method") == "thread/settings/updated") {
                        val threadId = params?.text("threadId").orEmpty()
                        params?.optJSONObject("threadSettings")?.let { pendingSettings[threadId]?.complete(it) }
                    }
                    if (!message.has("method") && message.has("id")) {
                        val waiter = pending.remove(message.optLong("id", -1)) ?: return
                        if (message.has("error")) waiter.completeExceptionally(IllegalStateException(
                            message.optJSONObject("error")?.text("message").orEmpty().ifBlank { "服务器请求失败" }
                        )) else waiter.complete(message.optJSONObject("result") ?: JSONObject())
                    } else serverMessageHandler(message)
                }
                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    val details = synchronized(stderr) { stderr.toString().trim().takeLast(800) }
                    disconnect(IllegalStateException(
                        "SSH/Codex 通道已关闭 (" + code + ") " + reason + if (details.isBlank()) "" else "\n" + details
                    ), report = true)
                }
                override fun onError(error: Exception) { disconnect(error, report = true) }
            }
            websocket = ws
            ws.connectionLostTimeout = 30
            // The proxy carries WebSocket bytes. This adapter never opens a TCP connection.
            ws.setSocket(object : Socket() {
                override fun getInputStream(): InputStream = input
                override fun getOutputStream(): OutputStream = output
                override fun isConnected(): Boolean = exec.isConnected
                override fun isClosed(): Boolean = exec.isClosed
                override fun setTcpNoDelay(value: Boolean) = Unit
                override fun setReuseAddress(value: Boolean) = Unit
                override fun setReceiveBufferSize(value: Int) = Unit
                override fun getLocalSocketAddress() = InetSocketAddress("127.0.0.1", 0)
                override fun getRemoteSocketAddress() = InetSocketAddress("127.0.0.1", 80)
                override fun close() { exec.disconnect() }
            })
            check(ws.connectBlocking(20, TimeUnit.SECONDS)) { "Codex WebSocket 握手失败，请检查服务器 app-server" }
            markConnection("websocket")
            request("initialize", JSONObject()
                .put("clientInfo", JSONObject().put("name", "codex-server-android")
                    .put("title", "Andri Android").put("version", BuildConfig.VERSION_NAME))
                .put("capabilities", JSONObject().put("experimentalApi", true)))
            notify("initialized", JSONObject())
            markConnection("initialized")
        } catch (error: Exception) {
            disconnect(error, report = false)
            throw error
        }
    }

    suspend fun listThreads(firstPageOnly: Boolean = false): List<CodexThread> {
        val rows = mutableListOf<CodexThread>()
        var cursor = ""
        do {
            val params = JSONObject().put("limit", if (firstPageOnly) 50 else 100).put("archived", false)
                .put("modelProviders", JSONArray()).put("sortKey", "updated_at").put("sortDirection", "desc")
            if (cursor.isNotEmpty()) params.put("cursor", cursor)
            val result = request("thread/list", params)
            val data = result.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                data.optJSONObject(i)?.let { rows += parseThread(it) }
            }
            val next = result.text("nextCursor")
            check(next.isBlank() || next != cursor) { "服务器返回了重复的分页游标" }
            cursor = next
        } while (cursor.isNotBlank() && !firstPageOnly)
        return rows.distinctBy { it.id }
    }

    suspend fun startThread(cwd: String, model: String, approvalPolicy: String = "on-request",
        approvalsReviewer: String = "user", sandbox: String = "workspace-write"): JSONObject {
        require(cwd.startsWith("/")) { "服务器工作目录必须是绝对路径，例如 /home/ubuntu/project" }
        require(approvalPolicy in setOf("untrusted", "on-request", "never")) { "不支持的审批策略" }
        require(approvalsReviewer in setOf("user", "auto_review", "guardian_subagent")) { "不支持的审批审查者" }
        require(sandbox in setOf("read-only", "workspace-write", "danger-full-access")) { "不支持的访问模式" }
        val result = request("thread/start", JSONObject().put("cwd", cwd).put("model", model)
            .put("approvalPolicy", approvalPolicy).put("approvalsReviewer", approvalsReviewer)
            .put("sandbox", sandbox))
        check(!result.optJSONObject("thread")?.text("id").isNullOrBlank()) { "服务器没有返回对话 ID" }
        return result
    }

    suspend fun startThread(options: ExecutionOptions, model: String): JSONObject =
        request("thread/start", options.params().put("model", model)).also {
            check(!it.optJSONObject("thread")?.text("id").isNullOrBlank()) { "服务器没有返回对话 ID" }
        }

    suspend fun generateThreadTitle(prompt: String, model: String, options: ExecutionOptions): String {
        require(prompt.isNotBlank()) { "用于生成标题的首条消息为空" }
        val startParams = JSONObject().put("cwd", options.cwd).put("model", model).put("ephemeral", true)
            .put("approvalPolicy", "never").put("approvalsReviewer", "user").put("sandbox", "read-only")
            .put("developerInstructions", "You generate short conversation titles. Do not use tools. Output only the title, with no quotes or explanation.")
            .put("config", JSONObject().put("model_reasoning_effort", options.effort))
        val titlePrompt = "为下面这条 Codex 任务生成一个简短、准确的会话标题。使用用户原文的语言，优先 4 到 16 个字；只输出标题。\n\n用户首条消息：\n${prompt.take(4000)}"
        val titleThreadId = request("thread/start", startParams).optJSONObject("thread")?.text("id")
            ?.takeIf { it.isNotBlank() } ?: error("Codex 没有返回临时标题会话 ID")
        ephemeralThreadIds.add(titleThreadId)
        val completed = try {
            ephemeralTextAnswer(titleThreadId, model, titlePrompt, sideChat = false)
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(3_000) { runCatching { archiveThread(titleThreadId) } }
            }
            ephemeralThreadIds.remove(titleThreadId)
        }
        val title = completed.lineSequence().firstOrNull().orEmpty().trim()
            .removeSurrounding("\"").removeSurrounding("'")
            .removePrefix("标题：").removePrefix("标题:").trim().take(80)
        check(title.isNotBlank()) { "Codex 返回了空标题" }
        return title
    }

    internal suspend fun forkSideChat(parentId: String, cwd: String, model: String): String {
        val result = request("thread/fork", sideChatForkParams(parentId, cwd, model))
        val id = requireSideChatFork(parentId, result)
        ephemeralThreadIds.add(id)
        try {
            check(result.text("approvalPolicy") == "never" &&
                result.optJSONObject("sandbox")?.text("type") == "readOnly") {
                "Codex 未确认侧聊的只读权限，未发送侧边问题"
            }
            // A live fork can retain the parent's instructions. Mark the boundary in the child history too.
            val instruction = JSONObject().put("type", "message").put("role", "developer")
                .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", SIDE_CHAT_INSTRUCTIONS)))
            request("thread/inject_items", JSONObject().put("threadId", id).put("items", JSONArray().put(instruction)))
            return id
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                withTimeoutOrNull(3_000) { runCatching { closeSideChat(id) } }
            }
            throw failure
        }
    }

    internal suspend fun askSideChat(threadId: String, model: String, question: String): String {
        check(threadId in ephemeralThreadIds) { "临时侧边聊天已关闭" }
        return ephemeralTextAnswer(threadId, model, question, sideChat = true).trim().also {
            check(it.isNotBlank()) { "Codex 没有返回回答" }
        }
    }

    internal suspend fun closeSideChat(threadId: String) {
        if (threadId !in ephemeralThreadIds) return
        request("thread/unsubscribe", JSONObject().put("threadId", threadId))
        ephemeralThreadIds.remove(threadId)
    }

    private suspend fun ephemeralTextAnswer(threadId: String, model: String, prompt: String, sideChat: Boolean): String {
        val run = EphemeralTextRun(sideChat)
        check(ephemeralTextRuns.putIfAbsent(threadId, run) == null) { "临时会话 ID 重复" }
        var turnId = ""
        var finished = false
        try {
            turnId = sendTurn(threadId, model, prompt)
            if (turnId.isNotBlank()) run.turnId = turnId
            val reply = withTimeoutOrNull(90_000) { run.completed.await() } ?: error("Codex 回答超时，请重试")
            finished = true
            return reply
        } finally {
            withContext(NonCancellable) {
                if (!finished && turnId.isNotBlank()) withTimeoutOrNull(3_000) { runCatching { interrupt(threadId, turnId) } }
            }
            ephemeralTextRuns.remove(threadId, run)
        }
    }

    private fun handleEphemeralTextNotification(run: EphemeralTextRun, method: String, params: JSONObject) {
        val eventTurnId = params.text("turnId").ifBlank { params.optJSONObject("turn")?.text("id").orEmpty() }
        if (method == "turn/started" && run.turnId.isBlank()) run.turnId = eventTurnId
        if (eventTurnId.isNotBlank() && run.turnId.isNotBlank() && eventTurnId != run.turnId) return
        when (method) {
            "item/agentMessage/delta" -> synchronized(run.text) {
                run.text.append(params.text("delta").take(MAX_SIDE_CHAT_ANSWER - run.text.length))
            }
            "item/started" -> {
                val type = params.optJSONObject("item")?.text("type")
                if (run.sideChat && type != null && type !in setOf("userMessage", "agentMessage", "reasoning", "plan")) {
                    run.completed.completeExceptionally(IllegalStateException("侧边聊天仅支持问答，已停止工具请求"))
                }
            }
            "item/completed" -> {
                val item = params.optJSONObject("item") ?: return
                val message = parseChatItem(item)
                if (message?.role == "assistant" && message.text.isNotBlank()) synchronized(run.text) {
                    run.finalText = message.text.take(MAX_SIDE_CHAT_ANSWER)
                }
            }
            "turn/completed" -> {
                val turn = params.optJSONObject("turn")
                if (turn?.text("status") == "completed") {
                    val value = synchronized(run.text) { run.finalText.ifBlank { run.text.toString() } }
                    run.completed.complete(value)
                } else {
                    run.completed.completeExceptionally(IllegalStateException("Codex 临时问答未完成"))
                }
            }
            "error" -> if (!params.optBoolean("willRetry")) run.completed.completeExceptionally(IllegalStateException(
                params.optJSONObject("error")?.text("message").orEmpty().ifBlank { "Codex 临时问答失败" }
            ))
        }
    }

    suspend fun resumeThread(threadId: String, options: ExecutionOptions? = null): JSONObject {
        val result = request("thread/resume", (options?.params() ?: JSONObject()).put("threadId", threadId)
            .put("excludeTurns", true).put("initialTurnsPage", JSONObject()
                .put("limit", INITIAL_THREAD_TURNS_LIMIT).put("itemsView", "full").put("sortDirection", "desc")))
        val page = result.optJSONObject("initialTurnsPage") ?: listTurns(threadId)
        return hydratePage(result, page)
    }

    suspend fun updateThreadSettings(threadId: String, options: ExecutionOptions, model: String? = null): JSONObject {
        val updated = CompletableDeferred<JSONObject>()
        check(pendingSettings.putIfAbsent(threadId, updated) == null) { "设置正在保存，请稍候" }
        try {
            request("thread/settings/update", options.params(turn = true).put("threadId", threadId).apply {
                if (model != null) put("model", model)
            })
            // The update response is intentionally empty. Prefer the settings
            // notification, but some app-server builds acknowledge the update
            // without delivering it to this client. In that case, read the
            // thread back and require the selected cwd to have persisted.
            val settings = awaitWorkspaceSettingsUpdate(updated)
            val result = request("thread/read", JSONObject().put("threadId", threadId).put("includeTurns", false))
            val thread = result.optJSONObject("thread")
            val actualCwd = settings?.text("cwd").orEmpty().ifBlank { thread?.text("cwd").orEmpty() }
            check(actualCwd == options.cwd) {
                "服务器已接受设置更新，但核对到的工作区为 ${actualCwd.ifBlank { "未知" }}，所选工作区为 ${options.cwd}"
            }
            val requested = options.params(turn = true)
            result.put("approvalPolicy", settings?.text("approvalPolicy").orEmpty().ifBlank { options.mode.policy })
                .put("approvalsReviewer", settings?.text("approvalsReviewer").orEmpty().ifBlank { options.mode.reviewer })
                .put("sandbox", settings?.optJSONObject("sandboxPolicy") ?: requested.optJSONObject("sandboxPolicy") ?: JSONObject())
                .put("reasoningEffort", settings?.text("effort").orEmpty()
                    .ifBlank { thread?.text("reasoningEffort").orEmpty() }.ifBlank { options.effort })
                .put("cwd", actualCwd)
            check(executionOptions(result, options) == options) { "服务器未采用所选工作区、权限或推理强度" }
            return result
        } finally {
            pendingSettings.remove(threadId, updated)
        }
    }

    suspend fun readThread(threadId: String): JSONObject {
        val result = request("thread/read", JSONObject().put("threadId", threadId).put("includeTurns", false))
        return hydratePage(result, listTurns(threadId))
    }

    suspend fun renameThread(threadId: String, name: String) {
        request("thread/name/set", JSONObject().put("threadId", threadId).put("name", name))
    }

    suspend fun archiveThread(threadId: String) {
        request("thread/archive", JSONObject().put("threadId", threadId))
    }

    suspend fun listTurns(threadId: String, cursor: String = ""): JSONObject {
        suspend fun page(limit: Int, itemsView: String) = request("thread/turns/list", JSONObject()
            .put("threadId", threadId).put("limit", limit)
            .put("sortDirection", "desc").put("itemsView", itemsView).apply {
                if (cursor.isNotBlank()) put("cursor", cursor)
            })
        return retryCompactTurnsPage(
            fullPage = { page(THREAD_TURNS_PAGE_LIMIT, "full") },
            compactPage = { page(THREAD_TURNS_COMPACT_PAGE_LIMIT, "essential") }
        )
    }

    suspend fun sendTurn(
        threadId: String, model: String, prompt: String, imageDataUrls: List<String> = emptyList(),
        options: ExecutionOptions? = null, localImagePaths: List<String> = emptyList()
    ): String {
        val input = userInput(prompt, imageDataUrls, localImagePaths)
        val result = request("turn/start", (options?.params(turn = true) ?: JSONObject()).put("threadId", threadId).put("model", model).put("input", input))
        return result.optJSONObject("turn")?.text("id").orEmpty()
    }

    suspend fun addQueuedSubmission(threadId: String, prompt: String, localImagePaths: List<String> = emptyList()): QueuedSubmission {
        val userMessageId = java.util.UUID.randomUUID().toString()
        val result = request("thread/queue/add", JSONObject().put("threadId", threadId)
            .put("clientUserMessageId", userMessageId).put("input", userInput(prompt, localImagePaths = localImagePaths)))
        return parseQueuedSubmission(result.optJSONObject("queuedSubmission")
            ?: error("Codex 没有返回排队消息"))
    }

    suspend fun listQueuedSubmissions(threadId: String): List<QueuedSubmission> {
        val rows = mutableListOf<QueuedSubmission>()
        var cursor = ""
        do {
            val params = JSONObject().put("threadId", threadId)
            if (cursor.isNotBlank()) params.put("cursor", cursor)
            val result = request("thread/queue/list", params)
            val data = result.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) data.optJSONObject(i)?.let { rows += parseQueuedSubmission(it) }
            val next = result.text("nextCursor")
            check(next.isBlank() || next != cursor) { "Codex 返回重复的队列游标" }
            cursor = next
        } while (cursor.isNotBlank())
        return rows
    }

    suspend fun deleteQueuedSubmission(threadId: String, queuedSubmissionId: String) {
        request("thread/queue/delete", JSONObject().put("threadId", threadId).put("queuedSubmissionId", queuedSubmissionId))
    }

    suspend fun startQueuedSubmission(threadId: String, queuedSubmissionId: String): String {
        val result = request("thread/queue/start", JSONObject().put("threadId", threadId)
            .put("queuedSubmissionId", queuedSubmissionId))
        return result.optJSONObject("turn")?.text("id").orEmpty()
    }

    suspend fun interrupt(threadId: String, turnId: String) {
        request("turn/interrupt", JSONObject().put("threadId", threadId).put("turnId", turnId))
    }

    suspend fun steerTurn(threadId: String, turnId: String, prompt: String) {
        request("turn/steer", JSONObject().put("threadId", threadId).put("expectedTurnId", turnId).put("input", userInput(prompt)))
    }

    private fun readReleaseJson(sftp: ChannelSftp, path: String): String = sftp.get(path).use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(out.size() + count <= 256 * 1024) { "版本清单过大" }
            out.write(buffer, 0, count)
        }
        val bytes = out.toByteArray()
        check(bytes.size <= 256 * 1024) { "版本清单过大" }
        bytes.toString(Charsets.UTF_8)
    }

    suspend fun readUpdateInfo(path: String): UpdateInfo = withContext(Dispatchers.IO) {
        val sftp = openSftp()
        sftp.connect(15_000)
        try {
            val info = parseUpdateInfo(JSONObject(readReleaseJson(sftp, path)))
            check(sftp.stat(info.apkPath).size == info.sizeBytes) { "更新包大小与清单不一致" }
            info
        } finally { sftp.disconnect() }
    }

    suspend fun readReleaseHistory(): List<UpdateInfo> = withContext(Dispatchers.IO) {
        val sftp = openSftp()
        sftp.connect(15_000)
        try { parseReleaseHistory(JSONObject(readReleaseJson(sftp, RELEASE_ROOT + "history.json"))) }
        finally { sftp.disconnect() }
    }

    suspend fun downloadUpdate(info: UpdateInfo, output: OutputStream, onBytes: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        check(info.apkPath.startsWith(RELEASE_ROOT) && info.apkPath.substring(RELEASE_ROOT.length).matches(Regex("[A-Za-z0-9._-]+\\.apk"))) {
            "更新包路径不在受信任发布目录内"
        }
        val sftp = openSftp()
        sftp.connect(15_000)
        try {
            check(sftp.stat(info.apkPath).size == info.sizeBytes) { "更新包大小与清单不一致" }
            sftp.get(info.apkPath, object : OutputStream() {
                private var total = 0L
                override fun write(value: Int) {
                    check(++total <= info.sizeBytes && total <= MAX_UPDATE_BYTES) { "更新包超过清单大小" }
                    output.write(value); onBytes(total)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    total += length
                    check(total <= info.sizeBytes && total <= MAX_UPDATE_BYTES) { "更新包超过清单大小" }
                    output.write(bytes, offset, length)
                    onBytes(total)
                }
                override fun flush() = output.flush()
                override fun close() = output.close()
            })
        } finally { sftp.disconnect() }
    }

    suspend fun reportDiagnostic(report: JSONObject) = withContext(Dispatchers.IO) {
        val line = report.toString() + "\n"
        check(line.toByteArray(Charsets.UTF_8).size <= 4 * 1024) { "诊断记录过大" }
        val sftp = openSftp()
        sftp.connect(15_000)
        try {
            val home = sftp.realpath(".").trimEnd('/')
            val codexDir = "$home/.codex"
            val reportDir = "$codexDir/mobile-diagnostics"
            fun ensureDirectory(path: String) {
                try {
                    check(sftp.stat(path).isDir) { "诊断目录路径不是目录" }
                } catch (error: com.jcraft.jsch.SftpException) {
                    if (error.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw error
                    sftp.mkdir(path)
                    sftp.chmod(448, path) // 0700
                }
            }
            ensureDirectory(codexDir)
            ensureDirectory(reportDir)
            val logName = when (report.text("type")) {
                "chat_timeline_trace" -> "chat-timeline.jsonl"
                "connection_timing_trace" -> "connection-timing.jsonl"
                else -> "notification-errors.jsonl"
            }
            val logPath = "$reportDir/$logName"
            sftp.put(ByteArrayInputStream(line.toByteArray(Charsets.UTF_8)), logPath, ChannelSftp.APPEND)
            sftp.chmod(384, logPath) // 0600
        } finally { sftp.disconnect() }
    }

    private fun openSftp(): ChannelSftp {
        check(session?.isConnected == true) { "SSH 已断开，请重新连接" }
        return (session!!.openChannel("sftp") as? ChannelSftp)
            ?: error("无法打开 SSH 文件通道")
    }

    private fun readGlobalInstructionsFile(sftp: ChannelSftp, path: String): String? {
        val attrs = try { sftp.lstat(path) } catch (e: com.jcraft.jsch.SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null else throw e
        }
        check(attrs.isReg && !attrs.isLink) { "全局指令必须是普通文件，不能是目录或符号链接" }
        check(attrs.size <= MAX_GLOBAL_INSTRUCTION_BYTES) { "现有全局指令超过 32 KiB，无法在手机中编辑" }
        val bytes = sftp.get(path).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= MAX_GLOBAL_INSTRUCTION_BYTES) { "全局指令不能超过 32 KiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return decodeGlobalInstructions(bytes)
    }

    private fun readGlobalInstructions(sftp: ChannelSftp, home: String): GlobalInstructionsDocument {
        val override = readGlobalInstructionsFile(sftp, home.trimEnd('/') + "/AGENTS.override.md")
        val regular = if (override.isNullOrBlank()) readGlobalInstructionsFile(sftp, home.trimEnd('/') + "/AGENTS.md") else null
        return selectGlobalInstructions(home, override, regular)
    }

    suspend fun readGlobalInstructions(): GlobalInstructionsDocument = withContext(Dispatchers.IO) {
        val home = globalInstructionsHome(request("config/read", JSONObject().put("includeLayers", true)))
        val sftp = openSftp()
        try { sftp.connect(15_000); readGlobalInstructions(sftp, home) }
        finally { sftp.disconnect() }
    }

    suspend fun saveGlobalInstructions(original: GlobalInstructionsDocument, text: String): GlobalInstructionsDocument = withContext(Dispatchers.IO) {
        val bytes = encodeGlobalInstructions(text)
        val home = globalInstructionsHome(request("config/read", JSONObject().put("includeLayers", true)))
        check(home == original.home) { "服务器配置位置已变化，请重新读取" }
        val sftp = openSftp()
        var temporary: String? = null
        try {
            sftp.connect(15_000)
            check(sftp.getExtension("posix-rename@openssh.com") == "1") { "服务器不支持安全替换文件，未保存指令" }
            checkGlobalInstructionsUnchanged(original, readGlobalInstructions(sftp, home))
            if (original.content == text && original.exists) return@withContext original
            if (original.path.endsWith("/AGENTS.override.md") && text.isBlank())
                readGlobalInstructionsFile(sftp, home.trimEnd('/') + "/AGENTS.md")
            val suffix = java.util.UUID.randomUUID().toString()
            val staged = original.path + ".mobile-tmp-" + suffix
            temporary = staged
            sftp.put(ByteArrayInputStream(bytes), staged)
            sftp.chmod(384, staged) // 0600: the SSH account alone can read instructions.
            if (original.exists) {
                val backup = original.path + ".mobile-backup-" + suffix
                sftp.put(ByteArrayInputStream(encodeGlobalInstructions(original.content)), backup)
                sftp.chmod(384, backup)
            }
            checkGlobalInstructionsUnchanged(original, readGlobalInstructions(sftp, home))
            sftp.rename(staged, original.path)
            temporary = null
            check(readGlobalInstructionsFile(sftp, original.path) == text) { "保存后校验失败，请重新读取服务器指令" }
            readGlobalInstructions(sftp, home)
        } finally {
            temporary?.let { runCatching { sftp.rm(it) } }
            sftp.disconnect()
        }
    }

    suspend fun listDirectory(path: String): RemoteDirectory = withContext(Dispatchers.IO) {
        require(path.startsWith("/") && '\u0000' !in path) { "请输入服务器绝对路径" }
        val sftp = openSftp()
        sftp.connect(15_000)
        try {
            val resolved = sftp.realpath(path)
            check(sftp.stat(resolved).isDir) { "所选路径不是目录" }
            val entries = sftp.ls(resolved).filterIsInstance<ChannelSftp.LsEntry>()
                .filter { it.filename !in listOf(".", "..") }
            val children = entries.filter { it.attrs.isDir }
                .map { it.filename }.sortedWith(String.CASE_INSENSITIVE_ORDER)
            val files = entries.filterNot { it.attrs.isDir }
                .map { it.filename }.sortedWith(String.CASE_INSENSITIVE_ORDER)
            RemoteDirectory(resolved, children, files)
        } finally { sftp.disconnect() }
    }

    suspend fun uploadSharedFile(source: File, label: String, baseDirectory: String): String = withContext(Dispatchers.IO) {
        check(source.isFile && source.length() in 1..MAX_SHARED_FILE_BYTES) { "共享文件为空或超过 512 MB" }
        val sftp = openSftp()
        sftp.connect(15_000)
        try {
            val root = WORKSPACE_ROOT.trimEnd('/')
            val base = sftp.realpath(baseDirectory)
            check(base == root || base.startsWith("$root/")) { "文件只能上传到工作区目录" }
            val codex = "$base/codex"
            ensureSftpDirectory(sftp, codex)
            val resolvedCodex = sftp.realpath(codex)
            check(resolvedCodex == codex) { "项目 codex 目录不能是符号链接" }
            val inbox = "$codex/inbox"
            ensureSftpDirectory(sftp, inbox)
            val resolvedInbox = sftp.realpath(inbox)
            check(resolvedInbox == inbox && resolvedInbox.startsWith("$base/")) { "项目收件目录不能指向工作区之外" }
            val safeName = label.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "shared-file" }
            val remote = "$resolvedInbox/${java.util.UUID.randomUUID()}-$safeName"
            source.inputStream().use { sftp.put(it, remote) }
            check(sftp.stat(remote).size == source.length()) { "共享文件上传不完整" }
            remote
        } finally { sftp.disconnect() }
    }

    suspend fun uploadImages(images: List<ImageUpload>, baseDirectory: String): List<String> = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext emptyList()
        val decoded = images.map { image ->
            check(image.dataUrl.startsWith("data:image/", ignoreCase = true)) { "图片数据格式无效" }
            val mime = image.dataUrl.substringAfter("data:").substringBefore(';').lowercase()
            val bytes = Base64.decode(image.dataUrl.substringAfter(',', ""), Base64.DEFAULT)
            check(bytes.isNotEmpty() && bytes.size <= MAX_CHAT_IMAGE_BYTES) { "图片为空或超过 10 MB" }
            val extension = when (mime) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                "image/bmp" -> "bmp"
                "image/avif" -> "avif"
                "image/heic" -> "heic"
                "image/heif" -> "heif"
                else -> "img"
            }
            val originalName = image.label.substringAfterLast('/').substringAfterLast('\\')
            val stem = originalName.substringBeforeLast('.', originalName)
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(100).ifBlank { "image" }
            "$stem.$extension" to bytes
        }
        check(decoded.sumOf { it.second.size.toLong() } <= 30L * 1024 * 1024) { "图片总大小超过 30 MB" }
        val sftp = openSftp()
        sftp.connect(15_000)
        try {
            val root = WORKSPACE_ROOT.trimEnd('/')
            val base = sftp.realpath(baseDirectory)
            check(base == root || base.startsWith("$root/")) { "图片只能保存到工作区目录" }
            val codex = "$base/codex"
            ensureSftpDirectory(sftp, codex)
            val resolvedCodex = sftp.realpath(codex)
            check(resolvedCodex == codex) { "项目 codex 目录不能是符号链接" }
            val inbox = "$codex/inbox"
            ensureSftpDirectory(sftp, inbox)
            val resolvedInbox = sftp.realpath(inbox)
            check(resolvedInbox == inbox && resolvedInbox.startsWith("$base/")) { "项目收件目录不能指向工作区之外" }
            decoded.map { (name, bytes) ->
                val remote = "$resolvedInbox/${java.util.UUID.randomUUID()}-$name"
                ByteArrayInputStream(bytes).use { sftp.put(it, remote) }
                check(sftp.stat(remote).size == bytes.size.toLong()) { "图片上传不完整：$name" }
                remote
            }
        } finally { sftp.disconnect() }
    }

    suspend fun downloadWorkspaceFile(path: String, baseDirectory: String, destination: File): String = withContext(Dispatchers.IO) {
        require(path.isNotBlank() && '\u0000' !in path) { "文件路径无效" }
        val candidate = if (path.startsWith("/")) path else baseDirectory.trimEnd('/') + "/" + path
        val sftp = openSftp()
        sftp.connect(15_000)
        try {
            val resolved = sftp.realpath(candidate)
            val root = WORKSPACE_ROOT.trimEnd('/')
            check(resolved.startsWith("$root/")) { "只允许下载工作区内的文件" }
            val attrs = sftp.lstat(resolved)
            check(attrs.isReg && !attrs.isLink) { "只能下载普通文件" }
            check(attrs.size in 1..MAX_DOWNLOAD_FILE_BYTES) { "文件为空或超过 512 MB" }
            destination.parentFile?.mkdirs()
            var total = 0L
            sftp.get(resolved).use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= MAX_DOWNLOAD_FILE_BYTES) { "文件超过 512 MB" }
                    output.write(buffer, 0, count)
                }
            } }
            check(total == attrs.size) { "文件下载不完整" }
            resolved.substringAfterLast('/')
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally { sftp.disconnect() }
    }

    suspend fun readGitDiff(path: String): String = withContext(Dispatchers.IO) {
        val sftp = openSftp()
        sftp.connect(15_000)
        val resolved = try { sftp.realpath(path) } finally { sftp.disconnect() }
        val root = WORKSPACE_ROOT.trimEnd('/')
        check(resolved == root || resolved.startsWith("$root/")) { "只能查看工作区中的 Git 改动" }
        val quoted = "'" + resolved.replace("'", "'\\''") + "'"
        val command = "repo_root=\$(git -C $quoted rev-parse --show-toplevel 2>/dev/null) || { printf '当前目录不属于 Git 仓库：%s\\n请切换到项目目录后重试。\\n' $quoted; exit 2; }; set -e; printf '%s\\n' \"--- Git 仓库：\$repo_root ---\"; git -C \"\$repo_root\" status --short 2>&1; printf '\\n--- 已暂存的改动 ---\\n'; git -C \"\$repo_root\" diff --cached --no-ext-diff --no-color 2>&1; printf '\\n--- 未暂存的改动 ---\\n'; git -C \"\$repo_root\" diff --no-ext-diff --no-color 2>&1"
        val channel = session?.openChannel("exec") as? ChannelExec ?: error("无法打开 SSH 命令通道")
        try {
            channel.setCommand(command)
            channel.connect(10_000)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val input = channel.inputStream
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= MAX_GIT_DIFF_BYTES) { "改动内容超过 512 KiB，请缩小审阅范围" }
                output.write(buffer, 0, count)
            }
            val text = output.toString(Charsets.UTF_8.name()).trim()
            check(channel.exitStatus == 0) {
                text.ifBlank { "读取 Git 改动失败（退出码 ${channel.exitStatus}）" }
            }
            text
        } finally { channel.disconnect() }
    }

    suspend fun cacheWorkspaceImage(path: String, baseDirectory: String, cacheFolder: File): File = withContext(Dispatchers.IO) {
        require(path.isNotBlank() && '\u0000' !in path) { "图片路径无效" }
        val candidate = if (path.startsWith("/")) path else baseDirectory.trimEnd('/') + "/" + path
        val sftp = openSftp()
        sftp.connect(15_000)
        try {
            val resolved = sftp.realpath(candidate)
            val root = WORKSPACE_ROOT.trimEnd('/')
            check(resolved == root || resolved.startsWith("$root/")) { "只允许读取工作区中的图片" }
            check(resolved.substringAfterLast('.', "").lowercase() in chatImageExtensions) {
                "本地图片仅支持 PNG、JPEG、GIF、WebP、BMP、SVG、AVIF、HEIC 或 HEIF 文件"
            }
            val attrs = sftp.lstat(resolved)
            check(attrs.isReg && !attrs.isLink) { "图片路径不是普通文件" }
            check(attrs.size in 1L..MAX_CHAT_IMAGE_BYTES.toLong()) { "图片必须小于 10 MB" }
            cacheFolder.mkdirs()
            val extension = resolved.substringAfterLast('.', "img").lowercase()
            val cacheKey = MessageDigest.getInstance("SHA-256")
                .digest("$resolved\n${attrs.size}\n${attrs.getMTime()}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val target = File(cacheFolder, "$cacheKey.$extension")
            if (target.isFile && target.length() == attrs.size) {
                target.setLastModified(System.currentTimeMillis())
                return@withContext target
            }

            val bytes = sftp.get(resolved).use { input ->
                val output = ByteArrayOutputStream(attrs.size.toInt())
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_CHAT_IMAGE_BYTES) { "图片超过 10 MB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.isNotEmpty()) { "图片文件为空" }
            check(bytes.size.toLong() == attrs.size) { "图片下载不完整，请重试" }
            check(isSupportedImageBytes(bytes)) { "本地文件内容不是受支持的图片格式" }
            val staged = File(cacheFolder, "$cacheKey-${java.util.UUID.randomUUID()}.tmp")
            try {
                staged.writeBytes(bytes)
                if (!staged.renameTo(target)) {
                    check(target.isFile && target.length() == attrs.size) { "无法缓存服务器图片" }
                }
            } finally { staged.delete() }
            CacheMaintenance.trim(cacheFolder.parentFile ?: cacheFolder)
            target
        } finally { sftp.disconnect() }
    }

    suspend fun uploadWorkspaceVideo(source: File, name: String, baseDirectory: String): String = withContext(Dispatchers.IO) {
        check(source.isFile && source.length() in 1..MAX_CHAT_VIDEO_BYTES) { "视频文件无效或超过 1 GB" }
        val root = WORKSPACE_ROOT.trimEnd('/')
        val base = baseDirectory.trimEnd('/').ifBlank { root }
        require(base == root || base.startsWith("$root/")) { "当前工作区不在允许的项目目录中" }
        val sftp = openSftp()
        sftp.connect(15_000)
        try {
            val resolvedBase = sftp.realpath(base)
            check(resolvedBase == root || resolvedBase.startsWith("$root/")) { "当前工作区不在允许的项目目录中" }
            val folder = resolvedBase.trimEnd('/') + "/codex/incoming-videos"
            ensureSftpDirectory(sftp, resolvedBase.trimEnd('/') + "/codex")
            ensureSftpDirectory(sftp, folder)
            val resolvedFolder = sftp.realpath(folder)
            check(resolvedFolder.startsWith("$root/")) { "视频上传目录不在项目工作区内" }
            val extension = name.substringAfterLast('.', "").lowercase().takeIf { it in VIDEO_EXTENSIONS }
                ?: error("不支持的视频格式")
            val safeName = name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "video.$extension" }
            val remote = "$resolvedFolder/${java.util.UUID.randomUUID()}-$safeName"
            source.inputStream().use { sftp.put(it, remote) }
            check(sftp.stat(remote).size == source.length()) { "视频上传不完整" }
            remote
        } finally { sftp.disconnect() }
    }

    suspend fun downloadWorkspaceVideo(path: String, baseDirectory: String, destination: File): String = withContext(Dispatchers.IO) {
        require(path.isNotBlank() && '\u0000' !in path) { "视频路径无效" }
        val candidate = if (path.startsWith("/")) path else baseDirectory.trimEnd('/') + "/" + path
        val sftp = openSftp()
        sftp.connect(15_000)
        try {
            val resolved = sftp.realpath(candidate)
            val root = WORKSPACE_ROOT.trimEnd('/')
            check(resolved.startsWith("$root/")) { "只允许下载工作区中的视频" }
            check(resolved.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS) { "文件不是受支持的视频格式" }
            val attrs = sftp.lstat(resolved)
            check(attrs.isReg && !attrs.isLink) { "视频路径不是普通文件" }
            check(attrs.size in 1..MAX_CHAT_VIDEO_BYTES) { "视频文件为空或超过 1 GB" }
            destination.parentFile?.mkdirs()
            var total = 0L
            sftp.get(resolved).use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= MAX_CHAT_VIDEO_BYTES) { "视频超过 1 GB" }
                    output.write(buffer, 0, count)
                }
            } }
            check(total == attrs.size) { "视频下载不完整" }
            resolved.substringAfterLast('/')
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally { sftp.disconnect() }
    }

    private fun ensureSftpDirectory(sftp: ChannelSftp, path: String) {
        try { check(sftp.stat(path).isDir) { "目标路径不是目录" } }
        catch (error: com.jcraft.jsch.SftpException) {
            if (error.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw error
            sftp.mkdir(path)
        }
    }

    suspend fun resolveDirectory(path: String): String = withContext(Dispatchers.IO) {
        require(path.startsWith("/") && '\u0000' !in path) { "请输入服务器绝对路径" }
        val sftp = openSftp()
        sftp.connect(15_000)
        try {
            val resolved = sftp.realpath(path)
            check(sftp.stat(resolved).isDir) { "所选路径不是目录" }
            resolved
        } finally { sftp.disconnect() }
    }

    suspend fun createDirectory(parent: String, name: String): RemoteDirectory = withContext(Dispatchers.IO) {
        require(parent.startsWith("/") && '\u0000' !in parent) { "请输入服务器绝对路径" }
        require(name.matches(Regex("[A-Za-z0-9._\u4e00-\u9fff -]{1,80}"))) { "文件夹名称包含不支持的字符" }
        val sftp = openSftp()
        sftp.connect(15_000)
        try {
            val resolved = sftp.realpath(parent)
            check(sftp.stat(resolved).isDir) { "所选路径不是目录" }
            sftp.mkdir(resolved.trimEnd('/') + "/" + name)
            listDirectory(resolved.trimEnd('/') + "/" + name)
        } finally { sftp.disconnect() }
    }

    suspend fun respondToServerRequest(id: Any, result: JSONObject) {
        write(JSONObject().put("id", id).put("result", result))
    }

    suspend fun rejectUnsupportedRequest(id: Any, method: String) {
        write(JSONObject().put("id", id).put("error", JSONObject()
            .put("code", -32601).put("message", "Android client does not support " + method)))
    }

    private suspend fun request(method: String, params: JSONObject): JSONObject {
        check(!closed.get()) { "SSH 已断开，请重新连接" }
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val startedAt = System.nanoTime()
        var succeeded = false
        val recordTiming = method in setOf("initialize", "thread/list", "thread/resume", "thread/read", "thread/turns/list")
        try {
            val result = withTimeout(45_000) {
                write(JSONObject().put("id", id).put("method", method).put("params", params))
                deferred.await()
            }
            succeeded = true
            return result
        } catch (error: TimeoutCancellationException) {
            throw IOException(method + " 等待响应超时，请刷新状态后再操作，避免重复发送", error)
        } finally {
            pending.remove(id)
            if (recordTiming) {
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000
                android.util.Log.d("AndriConnection", "[$diagnosticId] rpc=$method elapsed=${elapsed}ms success=$succeeded")
                recordConnectionTiming("rpc-$method", (System.nanoTime() - connectionStartedAt) / 1_000_000,
                    JSONObject().put("requestMs", elapsed).put("success", succeeded))
            }
        }
    }

    private suspend fun notify(method: String, params: JSONObject) {
        write(JSONObject().put("method", method).put("params", params))
    }

    private suspend fun write(message: JSONObject) = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            check(!closed.get()) { "SSH 已断开，请重新连接" }
            val ws = checkNotNull(websocket) { "Codex 通道尚未连接" }
            check(ws.isOpen) { "Codex 通道已断开" }
            ws.send(message.toString())
        }
    }

    private fun disconnect(error: Exception, report: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        pendingSettings.values.forEach { it.completeExceptionally(error) }
        pendingSettings.clear()
        ephemeralTextRuns.values.forEach { it.completed.completeExceptionally(error) }
        runCatching { websocket?.closeConnection(1000, "Client disconnected") }
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        if (report) serverMessageHandler(JSONObject().put("localError", error.message ?: "SSH 连接已断开"))
        scope.cancel()
    }

    /** True only while the SSH exec channel and the tunneled Codex socket are usable. */
    fun isAlive(): Boolean = !closed.get() &&
        session?.isConnected == true && channel?.isConnected == true && websocket?.isOpen == true

    fun close() = disconnect(IOException("连接已关闭"), report = false)

    companion object {
        const val RELEASE_ROOT = "/home/ubuntu/codex/mobile-client/releases/"
        const val MAX_UPDATE_BYTES = 200L * 1024 * 1024
        const val MAX_CHAT_IMAGE_BYTES = 10 * 1024 * 1024
        const val MAX_CHAT_VIDEO_BYTES = 1024L * 1024 * 1024
        const val MAX_SHARED_FILE_BYTES = 512L * 1024 * 1024
        const val MAX_DOWNLOAD_FILE_BYTES = 512L * 1024 * 1024
        const val MAX_GIT_DIFF_BYTES = 512 * 1024
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "webm", "mkv", "avi")
        private const val PINNED_HOST_KEY = "AAAAC3NzaC1lZDI1NTE5AAAAIEW2C/nhNSwYslEGZ5kqoPmpEXFqqYZ9Rv7SSchBBOp+"
    }
}

private fun isSupportedImageBytes(bytes: ByteArray): Boolean =
    bytes.size >= 12 && (
        isPngImage(bytes) ||
            (bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) ||
            bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "GIF" ||
            (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP") ||
            bytes.copyOfRange(0, 2).toString(Charsets.US_ASCII) == "BM" ||
            (bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" &&
                bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) in
                    setOf("avif", "avis", "heic", "heix", "hevc", "hevx", "mif1", "msf1")) ||
            isSvgImage(bytes)
    )

private fun parseQueuedSubmission(value: JSONObject): QueuedSubmission {
    val input = value.optJSONArray("input") ?: JSONArray()
    val text = (0 until input.length()).mapNotNull { i -> input.optJSONObject(i)?.takeIf { it.text("type") == "text" }?.text("text") }
        .joinToString("\n")
    val hasImage = (0 until input.length()).any {
        val type = input.optJSONObject(it)?.text("type")
        type == "image" || type == "localImage"
    }
    return QueuedSubmission(value.text("id"), value.text("clientUserMessageId"),
        text + if (hasImage) "  ·  图片" else "")
}

internal fun chronologicalTurns(page: JSONObject): JSONArray {
    val data = page.optJSONArray("data") ?: JSONArray()
    return JSONArray().apply { for (i in data.length() - 1 downTo 0) put(data.getJSONObject(i)) }
}

internal fun hydratePage(result: JSONObject, page: JSONObject): JSONObject {
    result.getJSONObject("thread").put("turns", chronologicalTurns(page))
    return result.put("historyCursor", page.text("nextCursor"))
}
