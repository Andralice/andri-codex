package com.codexserver.mobile

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import android.util.Base64

data class ImageAttachment(val dataUrl: String, val label: String)
data class VideoAttachment(val file: File, val label: String, val sizeBytes: Long)
data class SharedFileAttachment(val file: File, val label: String, val sizeBytes: Long, val mimeType: String)
data class ContextReference(val id: String, val label: String, val prompt: String)
data class QueuedTask(val id: String, val clientUserMessageId: String, val prompt: String,
    val autoStartAllowed: Boolean = false)
data class TaskCompletionAlert(val id: String, val threadId: String, val threadTitle: String,
    val project: String, val title: String, val detail: String)

class MobileViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val UPDATE_MANIFEST = "/home/ubuntu/codex/mobile-client/releases/latest.json"
        const val PENDING_SHARED_FILES = "__pending_shared_files__"
    }
    private val settings = application.getSharedPreferences("connection", 0)
    private val conversationCache = LocalConversationCache(application)
    private val workspaceVideoJobs = ConcurrentHashMap<String, Deferred<File>>()
    private var cacheWriteJob: Job? = null
    private val secrets = runCatching { SecureStore(application) }.getOrNull()
    var host by mutableStateOf(settings.getString("host", "82.156.142.57").orEmpty())
    var port by mutableStateOf(settings.getString("port", "22").orEmpty())
    var username by mutableStateOf(settings.getString("username", "ubuntu").orEmpty())
    var cwd by mutableStateOf(settings.getString("cwd", WORKSPACE_ROOT).orEmpty().let { if (it.isBlank() || it.trimEnd('/') == "/home/ubuntu") WORKSPACE_ROOT else it })
    private var defaultMode by mutableStateOf(runCatching { ExecutionMode.valueOf(settings.getString("executionMode", "ASK").orEmpty()) }.getOrDefault(ExecutionMode.ASK))
    private val threadExecution = mutableStateMapOf<String, ExecutionOptions>()
    private val taskStatuses = mutableStateMapOf<String, String>()
    // Keep the newest turn identity even after it finishes. App-server notifications
    // can arrive late around reconnects; threadId alone is not enough to distinguish
    // a stale event from the turn currently being shown.
    private val latestTurnIds = mutableMapOf<String, String>()
    private val timelineSyncing = mutableSetOf<String>()
    private val chatDiagnosticTrail = mutableMapOf<String, MutableList<JSONObject>>()
    private val lastChatDiagnosticUpload = mutableMapOf<String, Long>()
    private val pendingChatDiagnosticReasons = mutableMapOf<String, String>()
    private var threadSelectionGeneration = 0
    private val threadNames = ConcurrentHashMap<String, String>()
    private val titleGenerationStarted = ConcurrentHashMap.newKeySet<String>()
    var workspaceListing by mutableStateOf<RemoteDirectory?>(null)
        private set
    var workspaceBusy by mutableStateOf(false)
        private set
    var workspaceError by mutableStateOf("")
        private set
    var workspaceStatus by mutableStateOf("")
        private set
    var globalInstructionsDocument by mutableStateOf<GlobalInstructionsDocument?>(null)
        private set
    var globalInstructionsDraft by mutableStateOf("")
        private set
    var globalInstructionsBusy by mutableStateOf(false)
        private set
    var globalInstructionsError by mutableStateOf("")
        private set
    var globalInstructionsMessage by mutableStateOf("")
        private set
    var requestError by mutableStateOf("")
        private set
    var privateKey by mutableStateOf(runCatching { secrets?.get("privateKey") }.getOrNull().orEmpty())
    var passphrase by mutableStateOf(runCatching { secrets?.get("passphrase") }.getOrNull().orEmpty())
    var connected by mutableStateOf(false)
        private set
    var hasCachedSession by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("通过 SSH 连接 82，管理多个 Andri 长对话")
    var error by mutableStateOf(if (secrets == null) "手机安全存储不可用；本次连接仍可使用，密钥不会持久保存" else "")
    var model by mutableStateOf("gpt-6-luna")
    private var defaultReasoningEffort by mutableStateOf(settings.getString("reasoningEffort", "medium").orEmpty().ifBlank { "medium" })
    var threadSearch by mutableStateOf("")
    var onlyRunningThreads by mutableStateOf(false)
    var activeId by mutableStateOf<String?>(null)
        private set
    var openingThreadId by mutableStateOf<String?>(null)
        private set
    var threadOpenError by mutableStateOf("")
        private set
    var responding by mutableStateOf(false)
        private set
    val threads = mutableStateListOf<CodexThread>()
    private val localEmptyThreads = mutableSetOf<String>()
    private val localThreadIds = mutableSetOf<String>()
    private var listedThreadIds = emptySet<String>()
    val running = mutableStateMapOf<String, String>()
    val sending = mutableStateListOf<String>()
    val messages = mutableStateMapOf<String, List<ChatLine>>()
    val queuedTasks = mutableStateMapOf<String, List<QueuedTask>>()
    val historyCursors = mutableStateMapOf<String, String>()
    val drafts = mutableStateMapOf<String, String>()
    val imageAttachments = mutableStateMapOf<String, ImageAttachment>()
    val videoAttachments = mutableStateMapOf<String, VideoAttachment>()
    val sharedFileAttachments = mutableStateMapOf<String, List<SharedFileAttachment>>()
    val contextReferences = mutableStateMapOf<String, List<ContextReference>>()
    var gitDiff by mutableStateOf("")
        private set
    var gitDiffBusy by mutableStateOf(false)
        private set
    var gitDiffError by mutableStateOf("")
        private set
    var fileBrowserPath by mutableStateOf("")
        private set
    var downloadedWorkspaceFile by mutableStateOf<File?>(null)
        private set
    var downloadedWorkspaceFileName by mutableStateOf("")
        private set
    var workspaceFileBusy by mutableStateOf(false)
        private set
    var workspaceFileError by mutableStateOf("")
        private set
    val requests = mutableStateListOf<JSONObject>()
    val toolItems = mutableStateMapOf<String, JSONObject>()
    val activity = mutableStateMapOf<String, String>()
    val answeredQuestionItems = mutableStateListOf<String>()
    val taskCompletionAlerts = mutableStateListOf<TaskCompletionAlert>()
    var updateInfo by mutableStateOf<UpdateInfo?>(null)
        private set
    var updateBusy by mutableStateOf(false)
        private set
    var updateError by mutableStateOf("")
        private set
    private var downloadedUpdate by mutableStateOf<DownloadedUpdate?>(null)
    val updateFile: File? get() = downloadedUpdate?.file
    var updateChecking by mutableStateOf(false)
        private set
    var updateDialog by mutableStateOf(false)
        private set
    var updateMessage by mutableStateOf("")
        private set
    private var updateJob: Job? = null
    private var checkJob: Job? = null
    private var client: SshCodexClient? = null
    private val recovery = ConnectionRecovery()
    private var connectionJob: Job? = null
    private var threadOpenJob: Job? = null
    private var reconnectJob: Job? = null
    private var inForeground = true
    private fun connectionTime() = System.nanoTime() / 1_000_000
    private var reconnectOnForeground = settings.getBoolean("autoReconnect", privateKey.isNotBlank())
    val queueSubmitting = mutableStateListOf<String>()
    val activeThread: CodexThread? get() = threads.firstOrNull { it.id == activeId }

    init {
        restoreSharedFiles()
        restoreContextReferences()
        conversationCache.load()?.let { cached ->
            threads.addAll(cached.threads)
            messages.putAll(cached.messages)
            cached.messages.forEach { (id, lines) ->
                recordChatDiagnostic("cache.restore", id, "", JSONObject()
                    .put("lineCount", lines.size).put("tail", diagnosticLineIds(lines)))
            }
            model = cached.model
            activeId = cached.lastThreadId?.takeIf { id -> cached.threads.any { it.id == id } }
            hasCachedSession = cached.threads.isNotEmpty() || cached.messages.isNotEmpty()
        }
        cacheWriteJob = viewModelScope.launch {
            snapshotFlow {
                val cachedThreads = threads.take(100)
                val lastThread = activeId ?: settings.getString("lastThreadId", null)
                val includedIds = (listOfNotNull(lastThread) + cachedThreads.take(12).map { it.id }).distinct()
                CachedConversationState(
                    threads = cachedThreads,
                    messages = includedIds.mapNotNull { id -> messages[id]?.let { id to it.takeLast(250) } }.toMap(),
                    lastThreadId = lastThread,
                    model = model
                )
            }.collectLatest { snapshot ->
                delay(500)
                withContext(Dispatchers.IO) { conversationCache.save(snapshot) }
                hasCachedSession = snapshot.threads.isNotEmpty() || snapshot.messages.isNotEmpty()
            }
        }
    }
    fun projectFor(id: String): String = projectNameForPath(
        savedExecution(id)?.cwd?.takeIf { it.isNotBlank() }
            ?: threads.firstOrNull { it.id == id }?.cwd?.takeIf { it.isNotBlank() }
            ?: threadExecution[id]?.cwd.orEmpty()
    )
    fun lastTaskStatus(id: String): String = taskStatuses[id].orEmpty().ifBlank { settings.getString("thread.$id.lastTaskStatus", "").orEmpty() }
        .takeIf { it.isNotBlank() }?.let { state ->
            when (state) { "completed" -> "最近任务已完成"; "failed" -> "最近任务失败"; else -> "最近任务已中断" }
        }.orEmpty()
    private fun clearTaskStatus(id: String) {
        taskStatuses.remove(id)
        settings.edit().remove("thread.$id.lastTaskStatus").apply()
    }
    private fun autoStartQueueIds(threadId: String): Set<String> =
        settings.getStringSet("thread.$threadId.autoStartQueueIds", emptySet()).orEmpty().toSet()
    private fun allowQueueAutoStart(threadId: String, queueId: String) {
        settings.edit().putStringSet("thread.$threadId.autoStartQueueIds", autoStartQueueIds(threadId) + queueId).apply()
    }
    private fun consumeQueueAutoStart(threadId: String, queueId: String) {
        val ids = autoStartQueueIds(threadId) - queueId
        settings.edit().putStringSet("thread.$threadId.autoStartQueueIds", ids).apply()
    }
    fun dismissTaskCompletionAlert() {
        if (taskCompletionAlerts.isNotEmpty()) taskCompletionAlerts.removeAt(0)
    }
    fun openTaskCompletionThread() {
        val alert = taskCompletionAlerts.firstOrNull() ?: return
        dismissTaskCompletionAlert()
        if (connected) openThread(alert.threadId)
    }
    val currentExecution: ExecutionOptions
        get() = activeId?.let { threadExecution[it] } ?: ExecutionOptions(activeThread?.cwd?.ifBlank { cwd } ?: cwd, defaultMode, defaultReasoningEffort)
    val approvalPolicy: String get() = currentExecution.mode.policy
    val approvalsReviewer: String get() = currentExecution.mode.reviewer
    val sandbox: String get() = currentExecution.mode.sandbox

    fun reasoningEfforts(forModel: String = model): List<String> = when (forModel) {
        "gpt-6.1-sol" -> listOf("low", "medium", "high", "xhigh", "max", "ultra")
        else -> listOf("low", "medium", "high", "xhigh", "max")
    }

    fun selectModel(value: String) {
        model = value
        if (currentExecution.effort !in reasoningEfforts(value)) setReasoningEffort(reasoningEfforts(value).first())
    }

    fun setReasoningEffort(value: String) {
        if (value !in reasoningEfforts()) return
        val id = activeId
        if (id != null) {
            val current = threadExecution[id] ?: currentExecution
            rememberExecution(id, current.copy(effort = value))
        } else {
            defaultReasoningEffort = value
            settings.edit().putString("reasoningEffort", value).apply()
        }
    }

    private fun savedExecution(id: String): ExecutionOptions? {
        val path = settings.getString("thread.$id.cwd", null) ?: return null
        val mode = runCatching { ExecutionMode.valueOf(settings.getString("thread.$id.mode", "ASK").orEmpty()) }.getOrDefault(ExecutionMode.ASK)
        val effort = settings.getString("thread.$id.effort", defaultReasoningEffort).orEmpty().ifBlank { defaultReasoningEffort }
        return ExecutionOptions(path, mode, effort)
    }

    private fun rememberExecution(id: String, options: ExecutionOptions) {
        threadExecution[id] = options
        settings.edit().putString("thread.$id.cwd", options.cwd).putString("thread.$id.mode", options.mode.name)
            .putString("thread.$id.effort", options.effort).apply()
    }

    fun importKey(value: String) {
        if (!value.contains("PRIVATE KEY")) {
            error = "请选择 SSH 私钥文件；.pub 文件是公钥，不能用于登录"
            return
        }
        privateKey = value
        runCatching { secrets?.put("privateKey", value) }
            .onFailure { error = "密钥可用于本次连接，但保存失败：" + it.message }
        status = "SSH 私钥已导入"
    }

    fun connect(preserveUpdateState: Boolean = false, automatic: Boolean = false) {
        if (busy || connectionJob?.isActive == true) return
        val validPort = port.toIntOrNull()
        if (host.isBlank() || username.isBlank() || validPort == null || validPort !in 1..65535) {
            error = "请填写有效的服务器地址、用户名和端口"
            return
        }
        val token = recovery.begin(connectionTime(), automatic) ?: return
        reconnectJob?.cancel()
        busy = true
        error = ""
        // A foreground reconnect replaces the dead SSH socket, but must not make an
        // update the user already started disappear from the UI.
        cancelUpdates(preserveState = preserveUpdateState)
        connected = false
        client?.let { previous ->
            previous.close()
        }
        running.clear()
        sending.clear()
        requests.clear()
        settings.edit().putString("host", host.trim()).putString("port", port)
            .putString("username", username.trim()).putString("cwd", cwd)
            .putString("executionMode", defaultMode.name).apply()
        runCatching { secrets?.put("passphrase", passphrase) }
        status = "正在建立 SSH 安全连接…"
        lateinit var connection: SshCodexClient
        connection = SshCodexClient(host.trim(), validPort, username.trim(), privateKey, passphrase) { event ->
            viewModelScope.launch {
                if (client === connection && recovery.isCurrent(token)) {
                    if (event.has("localError")) recovery.failed(token, connectionTime())
                    handleEvent(event)
                }
            }
        }
        client = connection
        ConnectionKeepAliveService.hold(getApplication(), connection)
        connectionJob = viewModelScope.launch {
            try {
                connection.connect()
                if (client !== connection || !recovery.isCurrent(token)) return@launch
                val listed = connection.listThreads()
                if (client !== connection || !recovery.isCurrent(token)) return@launch
                replaceThreads(listed)
                threads.filter { it.running }.forEach {
                    running[it.id] = it.turnId
                    if (it.turnId.isNotBlank()) latestTurnIds[it.id] = it.turnId
                }
                val restore = activeId ?: settings.getString("lastThreadId", null)
                if (!restore.isNullOrBlank()) {
                    status = "正在恢复上次对话…"
                    val selectionGeneration = threadSelectionGeneration
                    try { loadThread(restore, selectionGeneration) }
                    catch (e: Exception) {
                        if (e is CancellationException) throw e
                        error = "已连接；恢复上次对话失败：" + e.message
                    }
                }
                if (client !== connection || !recovery.isCurrent(token)) return@launch
                connected = true
                recovery.connected(token, connectionTime())
                flushPendingChatDiagnostics()
                activeId?.let(::startNativeQueueIfIdle)
                reconnectOnForeground = true
                settings.edit().putBoolean("autoReconnect", true).apply()
                status = "已通过 SSH 连接到 82"
                checkForUpdate()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                connection.close()
                if (client === connection && recovery.isCurrent(token)) {
                    ConnectionKeepAliveService.clear(connection)
                    recovery.failed(token, connectionTime())
                    client = null
                    connected = false
                    error = e.message ?: "连接失败"
                    status = "连接失败"
                    scheduleReconnect()
                }
            } finally {
                if (client === connection || client == null) busy = false
            }
        }
    }

    private fun scheduleReconnect() {
        if ((!inForeground && !ConnectionKeepAliveService.isActive()) || !reconnectOnForeground || reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            val wait = recovery.delayMillis(connectionTime()).coerceAtLeast(2_000)
            status = "连接中断，${wait / 1000} 秒后自动重连"
            delay(wait)
            reconnectJob = null
            if ((inForeground || ConnectionKeepAliveService.isActive()) && reconnectOnForeground && !connected) {
                if (busy || connectionJob?.isActive == true) scheduleReconnect()
                else connect(preserveUpdateState = true, automatic = true)
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        recovery.reset()
        connectionJob?.cancel()
        cancelUpdates()
        reconnectOnForeground = false
        settings.edit().putBoolean("autoReconnect", false).apply()
        client?.let { connection ->
            ConnectionKeepAliveService.release(getApplication(), connection)
            connection.close()
        }
        ConnectionKeepAliveService.release(getApplication())
        client = null
        connected = false
        busy = false
        responding = false
        running.clear()
        sending.clear()
        requests.clear()
        status = "已断开连接"
    }

    private fun upsert(thread: CodexThread) {
        val named = threadNames[thread.id]
        val index = threads.indexOfFirst { it.id == thread.id }
        val previous = threads.getOrNull(index)
        val title = named?.takeIf { it.isNotBlank() }?.take(120) ?: thread.title
        val incoming = thread.copy(title = title)
        if (index < 0) threads.add(0, incoming) else threads[index] = incoming.copy(
            model = incoming.model.ifBlank { previous?.model.orEmpty() }, cwd = incoming.cwd.ifBlank { previous?.cwd.orEmpty() })
    }

    private fun promoteThread(id: String) {
        val index = threads.indexOfFirst { it.id == id }
        if (index > 0) threads.add(0, threads.removeAt(index))
    }

    private fun applySnapshot(result: JSONObject) {
        val thread = result.optJSONObject("thread") ?: return
        val row = parseThread(thread, result.text("model"))
        if (row.id.isBlank()) return
        val old = messages[row.id].orEmpty()
        val history = parseMessages(thread)
        val merged = mergeHistory(history, old, latestPage = true, activeTurnId = row.turnId)
        val hasServerTitle = thread.text("name").isNotBlank() || thread.text("title").isNotBlank()
        val firstPrompt = merged.firstOrNull { it.role == "user" && it.text.isNotBlank() }?.text.orEmpty()
        val previewMatchesFirstPrompt = row.id == activeId && row.turnId.isBlank() && !hasServerTitle &&
            firstPrompt.isNotBlank() && row.title == firstPrompt.replace(Regex("\\s+"), " ").trim().take(120)
        val displayRow = if (previewMatchesFirstPrompt) row.copy(title = "新对话") else row
        upsert(displayRow)
        if (row.running) running[row.id] = row.turnId else running.remove(row.id)
        val historyIds = history.map { it.id }.filter { it.isNotBlank() }.toSet()
        val serverOrderMismatch = merged.filter { it.id in historyIds }.map { it.id } !=
            history.filter { it.id.isNotBlank() }.map { it.id }
        recordChatDiagnostic("history.snapshot", row.id, "", JSONObject()
            .put("serverLineCount", history.size)
            .put("cachedLineCount", old.size)
            .put("mergedLineCount", merged.size)
            .put("serverOrderMismatch", serverOrderMismatch)
            .put("serverTail", diagnosticLineIds(history))
            .put("mergedTail", diagnosticLineIds(merged)))
        if (serverOrderMismatch) {
            recordChatDiagnostic("history.order_mismatch", row.id, "")
            uploadChatDiagnostic("history_order_mismatch", row.id)
        }
        messages[row.id] = merged
        if (activeId == row.id && displayRow.turnId.isBlank()) maybeGenerateThreadTitle(row.id)
        (history.lastOrNull { it.turnId.isNotBlank() }?.turnId
            ?: old.lastOrNull { it.turnId.isNotBlank() }?.turnId)
            ?.let { latestTurnIds[row.id] = it }
        if (row.running && row.turnId.isNotBlank()) latestTurnIds[row.id] = row.turnId
        if (row.id !in historyCursors) historyCursors[row.id] = result.text("historyCursor")
    }

    private fun maybeGenerateThreadTitle(id: String) {
        if (threads.firstOrNull { it.id == id }?.title != "新对话" || !running[id].isNullOrBlank() ||
            !titleGenerationStarted.add(id)) return
        val firstPrompt = messages[id].orEmpty().firstOrNull { it.role == "user" && it.text.isNotBlank() }?.text.orEmpty()
        val titleClient = client
        if (firstPrompt.isBlank() || titleClient == null) {
            titleGenerationStarted.remove(id)
            return
        }
        val titleModel = threads.firstOrNull { it.id == id }?.model?.ifBlank { model } ?: model
        val titleOptions = threadExecution[id] ?: savedExecution(id) ?: currentExecution
        recordChatDiagnostic("title_generation_started", id, "", JSONObject().put("model", titleModel))
        viewModelScope.launch {
            runCatching {
                val title = titleClient.generateThreadTitle(firstPrompt, titleModel, titleOptions)
                if (client === titleClient && threadNames[id].isNullOrBlank() &&
                    threads.firstOrNull { it.id == id }?.title == "新对话") {
                    titleClient.renameThread(id, title)
                    threadNames[id] = title
                    threads.firstOrNull { it.id == id }?.let { upsert(it.copy(title = title)) }
                    recordChatDiagnostic("title_generation_completed", id, "", JSONObject().put("title", title))
                }
            }.onFailure {
                if (it !is CancellationException) {
                    titleGenerationStarted.remove(id)
                    recordChatDiagnostic("title_generation_failed", id, "", JSONObject()
                        .put("reason", it.message.orEmpty().take(160)))
                    uploadChatDiagnostic("title_generation_failed", id)
                }
            }
        }
    }

    private fun diagnosticLineIds(lines: List<ChatLine>): JSONArray = JSONArray().apply {
        lines.takeLast(4).forEach { line ->
            put(JSONObject().put("id", line.id).put("turnId", line.turnId).put("role", line.role))
        }
    }

    private fun recordChatDiagnostic(step: String, threadId: String, turnId: String, detail: JSONObject = JSONObject()) {
        val row = JSONObject().put("at", System.currentTimeMillis()).put("step", step)
            .put("threadId", threadId).put("turnId", turnId).put("detail", detail)
        synchronized(chatDiagnosticTrail) {
            val events = chatDiagnosticTrail.getOrPut(threadId) { mutableListOf() }
            events += row
            if (events.size > 16) events.removeAt(0)
        }
    }

    private fun hasRecentMobileTurnRequest(threadId: String): Boolean {
        val cutoff = System.currentTimeMillis() - 30_000
        return synchronized(chatDiagnosticTrail) {
            chatDiagnosticTrail[threadId].orEmpty().any { event ->
                val step = event.text("step")
                event.optLong("at") >= cutoff && step in setOf(
                    "composer.turn.start.request", "queue.start.request")
            }
        }
    }

    private fun uploadChatDiagnostic(reason: String, threadId: String) {
        val connection = client ?: return
        if (!connected) {
            pendingChatDiagnosticReasons[threadId] = reason
            return
        }
        val now = System.currentTimeMillis()
        val previous = lastChatDiagnosticUpload[threadId] ?: 0L
        if (now - previous < 60_000) return
        lastChatDiagnosticUpload[threadId] = now
        val trace = synchronized(chatDiagnosticTrail) { chatDiagnosticTrail[threadId].orEmpty().toList() }
        val report = JSONObject().put("type", "chat_timeline_trace").put("time", now)
            .put("appVersion", BuildConfig.VERSION_NAME).put("reason", reason)
            .put("threadId", threadId).put("activeTurnId", running[threadId].orEmpty())
            .put("latestTurnId", latestTurnIds[threadId].orEmpty())
        val events = JSONArray().apply { trace.forEach { put(JSONObject(it.toString())) } }
        report.put("events", events)
        while (report.toString().toByteArray(Charsets.UTF_8).size > 3_500 && events.length() > 1) {
            events.remove(0)
        }
        viewModelScope.launch {
            runCatching { connection.reportDiagnostic(report) }
                .onFailure { android.util.Log.w("MobileDiagnostics", "Unable to upload chat timeline trace", it) }
        }
    }

    private fun flushPendingChatDiagnostics() {
        val pending = pendingChatDiagnosticReasons.toMap()
        pendingChatDiagnosticReasons.clear()
        pending.forEach { (threadId, reason) -> uploadChatDiagnostic(reason, threadId) }
    }

    private fun syncThreadTimeline(id: String, diagnosticReason: String = "") {
        val connection = client ?: return
        if (!connected || !timelineSyncing.add(id)) return
        viewModelScope.launch {
            try {
                val result = connection.readThread(id)
                if (client === connection) {
                    applySnapshot(result)
                    if (diagnosticReason.isNotBlank()) uploadChatDiagnostic(diagnosticReason, id)
                }
            } catch (e: Exception) {
                if (e !is CancellationException && client === connection) {
                    reportChatError(e.message.orEmpty(), "同步历史消息失败", id)
                }
            } finally {
                timelineSyncing.remove(id)
            }
        }
    }

    private suspend fun loadThread(id: String, selectionGeneration: Int? = null) {
        fun maySelectThread() = selectionGeneration == null || selectionGeneration == threadSelectionGeneration
        clearTaskStatus(id)
        drafts[id] = settings.getString("draft.$id", drafts[id].orEmpty()).orEmpty()
        val connection = checkNotNull(client) { "SSH 已断开" }
        if (id in localEmptyThreads && id !in listedThreadIds) {
            if (maySelectThread()) activeId = id
            threadExecution[id] = savedExecution(id) ?: ExecutionOptions(cwd, defaultMode, defaultReasoningEffort)
            if (maySelectThread()) settings.edit().putString("lastThreadId", id).apply()
            return
        }
        runCatching { refreshQueue(id, connection) }
            .onFailure { if (it !is CancellationException) reportChatError(it.message.orEmpty(), "读取 Codex 排队消息失败", id) }
        val saved = savedExecution(id)
        var result = try {
            connection.resumeThread(id, saved)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (id !in localEmptyThreads) throw e
            // The server may not persist an empty thread until its first message.
            if (maySelectThread()) activeId = id
            threadExecution[id] = saved ?: ExecutionOptions(cwd, defaultMode, defaultReasoningEffort)
            if (maySelectThread()) settings.edit().putString("lastThreadId", id).apply()
            return
        }
        val actual = executionOptions(result, saved ?: ExecutionOptions(cwd, defaultMode, defaultReasoningEffort))
        if (saved != null && actual != saved && !parseThread(result.getJSONObject("thread")).running) {
            result = connection.updateThreadSettings(id, saved)
        }
        applySnapshot(result)
        threadExecution[id] = executionOptions(result, saved ?: ExecutionOptions(cwd, defaultMode, defaultReasoningEffort))
        if (maySelectThread()) {
            activeId = id
            result.text("model").takeIf { it.isNotBlank() }?.let { model = it }
            settings.edit().putString("lastThreadId", id).apply()
            if (connected) startNativeQueueIfIdle(id)
        }
    }

    fun openThread(id: String) {
        if (busy) return
        val selectionGeneration = ++threadSelectionGeneration
        bindPendingSharedFiles(id)
        clearTaskStatus(id)
        if (messages.containsKey(id)) {
            activeId = id
            openingThreadId = null
            threadOpenError = ""
            settings.edit().putString("lastThreadId", id).apply()
            if (connected && client?.isAlive() == true) {
                viewModelScope.launch {
                    runCatching { loadThread(id, selectionGeneration) }
                        .onFailure { if (it !is CancellationException) reportChatError(it.message.orEmpty(), "同步对话失败", id) }
                }
            }
            return
        }
        if (!connected || client?.isAlive() != true) {
            if (threads.none { it.id == id }) return
            activeId = id
            openingThreadId = null
            threadOpenError = ""
            settings.edit().putString("lastThreadId", id).apply()
            status = "显示本地缓存；连接恢复后会同步"
            return
        }
        threadOpenJob?.cancel()
        activeId = id
        openingThreadId = id
        threadOpenError = ""
        error = ""
        status = "正在加载对话…"
        busy = true
        threadOpenJob = viewModelScope.launch {
            try {
                loadThread(id, selectionGeneration)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (openingThreadId == id && selectionGeneration == threadSelectionGeneration)
                    threadOpenError = e.message?.takeIf { it.isNotBlank() } ?: "加载对话失败"
            } finally {
                if (openingThreadId == id && selectionGeneration == threadSelectionGeneration) {
                    openingThreadId = null
                    busy = false
                }
            }
        }
    }

    fun newThread() {
        threadSelectionGeneration++
        operation("新建对话", null) {
            val options = ExecutionOptions(cwd, defaultMode, defaultReasoningEffort)
            val result = checkNotNull(client).startThread(options, model)
            applySnapshot(result)
            activeId = result.getJSONObject("thread").getString("id")
            bindPendingSharedFiles(activeId!!)
            localEmptyThreads += activeId!!
            localThreadIds += activeId!!
            settings.edit().putBoolean("thread.$activeId.localThread", true)
                .putBoolean("thread.$activeId.localEmpty", true)
                .putString("thread.$activeId.title", "新对话")
                .putString("thread.$activeId.model", model)
                .putString("thread.$activeId.cwd", options.cwd).apply()
            rememberExecution(activeId!!, executionOptions(result, options))
            settings.edit().putString("lastThreadId", activeId).apply()
        }
    }

    fun renameActiveThread(name: String) {
        val id = activeId ?: return
        val cleaned = name.trim().take(120)
        if (cleaned.isBlank() || cleaned == activeThread?.title) return
        operation("修改对话名称", id) {
            checkNotNull(client).renameThread(id, cleaned)
            threadNames[id] = cleaned
            threads.firstOrNull { it.id == id }?.let { upsert(it.copy(title = cleaned)) }
        }
    }

    fun back() {
        threadSelectionGeneration++
        threadOpenJob?.cancel()
        threadOpenJob = null
        openingThreadId = null
        threadOpenError = ""
        busy = false
        activeId = null
        error = ""
    }

    fun editGlobalInstructions(text: String) {
        globalInstructionsDraft = text
        globalInstructionsMessage = ""
    }

    fun loadGlobalInstructions() {
        if (globalInstructionsBusy) return
        val connection = client ?: run { globalInstructionsError = "SSH 已断开，请重新连接"; return }
        globalInstructionsBusy = true
        globalInstructionsError = ""
        globalInstructionsMessage = ""
        globalInstructionsDocument = null
        viewModelScope.launch {
            try {
                val document = connection.readGlobalInstructions()
                check(client === connection && connected) { "SSH 连接已变更，请重新读取" }
                globalInstructionsDocument = document
                globalInstructionsDraft = document.content
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                globalInstructionsError = "读取失败：" + (e.message ?: "未知错误")
            } finally { globalInstructionsBusy = false }
        }
    }

    fun saveGlobalInstructions() {
        if (globalInstructionsBusy) return
        val connection = client ?: run { globalInstructionsError = "SSH 已断开，请重新连接后保存"; return }
        val original = globalInstructionsDocument ?: return
        val text = globalInstructionsDraft
        globalInstructionsBusy = true
        globalInstructionsError = ""
        globalInstructionsMessage = ""
        viewModelScope.launch {
            try {
                check(client === connection && connected) { "SSH 连接已变更，请重新读取" }
                val actual = connection.saveGlobalInstructions(original, text)
                check(client === connection && connected) { "SSH 连接已变更，请重新读取以确认保存结果" }
                globalInstructionsDocument = actual
                globalInstructionsDraft = actual.content
                globalInstructionsMessage = "已保存到服务器。请新建对话使用更新后的指令。"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                globalInstructionsError = "保存失败：" + (e.message ?: "未知错误")
            } finally { globalInstructionsBusy = false }
        }
    }

    fun browseWorkspace(path: String = currentExecution.cwd) {
        val connection = client ?: run { workspaceError = "SSH 已断开，请重新连接后浏览"; return }
        if (workspaceBusy) return
        workspaceBusy = true
        workspaceError = ""
        workspaceListing = null
        workspaceStatus = "正在读取目录…"
        viewModelScope.launch {
            try { workspaceListing = connection.listDirectory(path) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                workspaceError = "无法读取工作区：" + (e.message ?: "未知错误")
            }
            finally { workspaceBusy = false; workspaceStatus = "" }
        }
    }

    fun showWorkspaceError(message: String) { workspaceError = message }

    fun createWorkspaceFolder(name: String, done: (String) -> Unit = {}) {
        val connection = client ?: return
        val parent = workspaceListing?.path ?: currentExecution.cwd
        if (workspaceBusy) return
        workspaceBusy = true
        workspaceError = ""
        workspaceStatus = "正在创建项目目录…"
        viewModelScope.launch {
            try {
                val created = connection.createDirectory(parent, name.trim())
                workspaceListing = created
                done(created.path)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                workspaceError = "新建项目失败：" + (e.message ?: "未知错误")
            } finally { workspaceBusy = false; workspaceStatus = "" }
        }
    }

    fun saveExecution(options: ExecutionOptions, done: () -> Unit) {
        val connection = client ?: run { workspaceError = "SSH 已断开，请重新连接后保存"; return }
        val id = activeId
        if (workspaceBusy || busy) return
        if (id != null && (id in running || id in sending)) {
            workspaceError = "任务运行中，请停止任务或等待完成后保存"
            return
        }
        workspaceBusy = true
        workspaceError = ""
        viewModelScope.launch {
            try {
                options.params() // Validate before contacting the server.
                workspaceStatus = "正在校验所选目录…"
                val path = connection.resolveDirectory(options.cwd)
                val selected = options.copy(cwd = path)
                check(client === connection && connected) { "SSH 连接已变更，请重试" }
                if (id == null) {
                    cwd = path
                    defaultMode = selected.mode
                    defaultReasoningEffort = selected.effort
                    settings.edit().putString("cwd", path).putString("executionMode", defaultMode.name)
                        .putString("reasoningEffort", defaultReasoningEffort).apply()
                } else {
                    check(id !in running && id !in sending) { "请等待当前任务完成后修改设置" }
                    workspaceStatus = "正在保存并核对服务器设置…"
                    val result = connection.updateThreadSettings(id, selected)
                    val actual = executionOptions(result, selected)
                    check(actual == selected) { "服务器未采用所选工作区或权限，请检查服务端限制" }
                    applySnapshot(result)
                    rememberExecution(id, actual)
                }
                done()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                workspaceError = "保存失败：" + (e.message ?: "未知错误")
            } finally { workspaceBusy = false; workspaceStatus = "" }
        }
    }

    fun reportChatError(value: String, stage: String, id: String? = activeId, turnId: String = "", eventId: String = "") {
        if (value.isBlank()) return
        if (id.isNullOrBlank()) { error = "$stage：$value"; return }
        val current = messages[id].orEmpty()
        if (current.any { it.role == "error" && it.stage == stage && it.turnId == turnId && it.text == value }) return
        messages[id] = upsertChat(current, chatFailure(current, value, stage, turnId, eventId))
    }

    fun importImage(bytes: ByteArray, mimeType: String, label: String = "图片") {
        val id = activeId ?: return
        check(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024) { "图片需小于 10 MB" }
        check(mimeType.startsWith("image/")) { "请选择图片文件" }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        imageAttachments[id] = ImageAttachment("data:$mimeType;base64,$encoded", label.take(80))
        videoAttachments.remove(id)
        error = ""
    }

    fun importVideo(file: File, label: String) {
        val id = activeId ?: return
        check(file.isFile && file.length() in 1..SshCodexClient.MAX_CHAT_VIDEO_BYTES) { "视频文件无效或超过 1 GB" }
        check(label.substringAfterLast('.', "").lowercase() in SshCodexClient.VIDEO_EXTENSIONS) { "暂不支持该视频格式" }
        videoAttachments[id] = VideoAttachment(file, label.take(100), file.length())
        imageAttachments.remove(id)
        error = ""
    }

    fun removeVideo() { activeId?.let(videoAttachments::remove) }

    fun importSharedFile(file: File, label: String, mimeType: String) {
        check(file.isFile && file.length() in 1..SshCodexClient.MAX_SHARED_FILE_BYTES) { "共享文件无效或超过 512 MB" }
        val id = activeId ?: PENDING_SHARED_FILES
        val current = sharedFileAttachments[id].orEmpty()
        check(current.size < 20) { "一次最多添加 20 个文件" }
        check(current.sumOf { it.sizeBytes } + file.length() <= SshCodexClient.MAX_SHARED_FILE_BYTES) { "一次分享的文件总大小不能超过 512 MB" }
        sharedFileAttachments[id] = current + SharedFileAttachment(file, label.take(180), file.length(), mimeType)
        persistSharedFiles()
        error = ""
    }

    fun removeSharedFile(index: Int) {
        val id = activeId ?: PENDING_SHARED_FILES
        val rows = sharedFileAttachments[id].orEmpty()
        rows.getOrNull(index)?.file?.delete()
        sharedFileAttachments[id] = rows.filterIndexed { position, _ -> position != index }
        persistSharedFiles()
    }

    fun sharedFilesFor(id: String): List<SharedFileAttachment> = sharedFileAttachments[id].orEmpty()

    private fun bindPendingSharedFiles(id: String) {
        val pending = sharedFileAttachments.remove(PENDING_SHARED_FILES).orEmpty()
        if (pending.isNotEmpty()) {
            sharedFileAttachments[id] = sharedFileAttachments[id].orEmpty() + pending
            persistSharedFiles()
        }
        val pendingContext = contextReferences.remove(PENDING_SHARED_FILES).orEmpty()
        if (pendingContext.isNotEmpty()) {
            contextReferences[id] = contextReferences[id].orEmpty() + pendingContext
            persistContextReferences()
        }
    }

    fun contextFor(id: String): List<ContextReference> = contextReferences[id].orEmpty()

    fun addContextReference(label: String, prompt: String) {
        val id = activeId ?: PENDING_SHARED_FILES
        val current = contextReferences[id].orEmpty()
        check(current.size < 20) { "一次最多添加 20 项上下文" }
        contextReferences[id] = current + ContextReference(java.util.UUID.randomUUID().toString(),
            label.take(180).ifBlank { "上下文" }, prompt.take(24_000))
        persistContextReferences()
    }

    fun addWorkspaceFileContext(path: String, label: String) {
        addContextReference(label, "服务器工作区文件：$path\n请阅读该文件，并按本次任务需要进行修改。")
    }

    fun removeContextReference(referenceId: String) {
        val id = activeId ?: PENDING_SHARED_FILES
        contextReferences[id] = contextReferences[id].orEmpty().filterNot { it.id == referenceId }
        persistContextReferences()
    }

    fun importSharedText(text: String, title: String = "") {
        val value = text.trim().take(20_000)
        if (value.isBlank()) return
        val url = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE).find(value)?.value?.trimEnd('.', ',', ')', ']')
        val label = title.takeIf { it.isNotBlank() }?.take(160)
            ?: url?.substringAfter("//")?.substringBefore('/')?.take(120)
            ?: "分享的文字"
        val prompt = if (url != null) "网页上下文：$label\nURL：$url\n请尝试读取网页内容；若无法访问，再使用分享附带的文字。\n$value"
            else "分享的文字上下文：\n$value"
        addContextReference(label, prompt)
    }

    private fun restoreContextReferences() {
        val root = runCatching { JSONObject(settings.getString("contextReferences", "{}").orEmpty()) }.getOrNull() ?: return
        root.keys().forEach { threadId ->
            val rows = root.optJSONArray(threadId) ?: return@forEach
            contextReferences[threadId] = buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val prompt = row.optString("prompt").take(24_000)
                    if (prompt.isNotBlank()) add(ContextReference(row.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                        row.optString("label", "上下文"), prompt))
                }
            }.take(20)
        }
    }

    private fun persistContextReferences() {
        val root = JSONObject().apply {
            contextReferences.forEach { (threadId, rows) ->
                put(threadId, JSONArray().apply { rows.take(20).forEach { row -> put(JSONObject()
                    .put("id", row.id).put("label", row.label).put("prompt", row.prompt)) } })
            }
        }
        settings.edit().putString("contextReferences", root.toString()).apply()
    }

    private fun restoreSharedFiles() {
        val root = runCatching { JSONObject(settings.getString("sharedFiles", "{}").orEmpty()) }.getOrNull() ?: return
        val allowed = File(getApplication<Application>().cacheDir, "shared-files").canonicalPath + "/"
        root.keys().forEach { threadId ->
            val rows = root.optJSONArray(threadId) ?: return@forEach
            val restored = buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val file = runCatching { File(row.getString("path")).canonicalFile }.getOrNull() ?: continue
                    if (!file.path.startsWith(allowed) || !file.isFile || file.length() !in 1..SshCodexClient.MAX_SHARED_FILE_BYTES) continue
                    add(SharedFileAttachment(file, row.optString("label", file.name), file.length(),
                        row.optString("mimeType", "application/octet-stream")))
                }
            }
            if (restored.isNotEmpty()) sharedFileAttachments[threadId] = restored
        }
    }

    private fun persistSharedFiles() {
        val root = JSONObject().apply {
            sharedFileAttachments.forEach { (threadId, rows) ->
                put(threadId, JSONArray().apply {
                    rows.filter { it.file.isFile }.forEach { file -> put(JSONObject()
                        .put("path", file.file.absolutePath).put("label", file.label).put("mimeType", file.mimeType)) }
                })
            }
        }
        settings.edit().putString("sharedFiles", root.toString()).apply()
    }

    suspend fun cacheWorkspaceFile(path: String, baseDirectory: String): File {
        val connection = client ?: error("SSH 已断开，无法读取工作区文件")
        val (destination, name) = withContext(Dispatchers.IO) {
            val directory = File(getApplication<Application>().cacheDir, "workspace-files").apply { mkdirs() }
            val label = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "workspace-file" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
            val file = File(directory, java.util.UUID.randomUUID().toString() + "-" + label)
            file to connection.downloadWorkspaceFile(path, baseDirectory, file)
        }
        downloadedWorkspaceFileName = name
        downloadedWorkspaceFile = destination
        return destination
    }

    fun browseProjectFiles(path: String = currentExecution.cwd) {
        fileBrowserPath = path
        browseWorkspace(path)
    }

    fun loadGitDiff() {
        if (gitDiffBusy) return
        val connection = client ?: return
        val path = currentExecution.cwd
        gitDiffBusy = true
        gitDiffError = ""
        gitDiff = ""
        viewModelScope.launch {
            try { gitDiff = connection.readGitDiff(path) }
            catch (e: Exception) { if (e !is CancellationException) gitDiffError = e.message.orEmpty() }
            finally { gitDiffBusy = false }
        }
    }

    fun loadProjectFiles(path: String = currentExecution.cwd) {
        fileBrowserPath = path
        browseWorkspace(path)
    }

    fun clearDownloadedWorkspaceFile() {
        downloadedWorkspaceFile?.delete()
        downloadedWorkspaceFile = null
        downloadedWorkspaceFileName = ""
    }

    fun removeImage() { activeId?.let(imageAttachments::remove) }

    suspend fun cacheWorkspaceImage(path: String, baseDirectory: String): String = withContext(Dispatchers.IO) {
        val connection = client ?: error("SSH 已断开，无法读取服务器图片")
        val image = connection.readWorkspaceImage(path, baseDirectory)
        val extension = image.name.substringAfterLast('.', "img").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "img"
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((baseDirectory + "\n" + path + "\n").toByteArray(Charsets.UTF_8))
        val key = digest.digest(image.bytes)
            .joinToString("") { "%02x".format(it) }
        val folder = File(getApplication<Application>().cacheDir, "markdown-images").apply { mkdirs() }
        val target = File(folder, "$key.$extension")
        val staged = File(folder, "$key-${java.util.UUID.randomUUID()}.tmp")
        staged.writeBytes(image.bytes)
        if (target.exists()) target.delete()
        check(staged.renameTo(target)) { "无法缓存服务器图片" }
        target.toURI().toString()
    }

    suspend fun cacheWorkspaceVideo(path: String, baseDirectory: String): File = withContext(Dispatchers.IO) {
        val folder = File(getApplication<Application>().cacheDir, "workspace-videos").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-256").digest((baseDirectory + "\n" + path).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val target = File(folder, "$key.${path.substringAfterLast('.', "mp4").lowercase()}")
        if (target.isFile && target.length() > 0L) return@withContext target
        val download = workspaceVideoJobs[key] ?: run {
            val created = viewModelScope.async(Dispatchers.IO) {
                if (target.isFile && target.length() > 0L) return@async target
                val connection = client ?: error("SSH 已断开，无法读取服务器视频")
                val staged = File(folder, "$key-${java.util.UUID.randomUUID()}.tmp")
                try {
                    connection.downloadWorkspaceVideo(path, baseDirectory, staged)
                    check(staged.renameTo(target)) { "无法缓存服务器视频" }
                    target
                } finally { staged.delete() }
            }
            val existing = workspaceVideoJobs.putIfAbsent(key, created)
            if (existing != null) {
                created.cancel()
                existing
            } else {
                created.invokeOnCompletion { workspaceVideoJobs.remove(key, created) }
                created
            }
        }
        download.await()
    }

    fun refresh() = operation("刷新对话") {
        val connection = checkNotNull(client) { "SSH 已断开" }
        val rows = connection.listThreads()
        replaceThreads(rows)
        rows.forEach {
            if (it.running) {
                running[it.id] = running[it.id] ?: it.turnId
                if (it.turnId.isNotBlank()) latestTurnIds[it.id] = it.turnId
            } else running.remove(it.id)
        }
        activeId?.takeIf { it in listedThreadIds }?.let { applySnapshot(connection.readThread(it)) }
    }

    private fun replaceThreads(rows: List<CodexThread>) {
        val serverIds = rows.mapTo(mutableSetOf()) { it.id }
        listedThreadIds = serverIds
        val prefs = settings.all
        val localIds = prefs.keys.asSequence()
            .filter { it.startsWith("thread.") && it.endsWith(".localThread") }
            .filter { prefs[it] == true }
            .map { it.removePrefix("thread.").removeSuffix(".localThread") }
            .toSet()
        localThreadIds.clear()
        localThreadIds.addAll(localIds - serverIds)
        localEmptyThreads.clear()
        localEmptyThreads.addAll(localIds.filter { it !in serverIds && settings.getBoolean("thread.$it.localEmpty", false) })
        localIds.filter { it in serverIds }.forEach { id ->
            settings.edit().remove("thread.$id.localThread").remove("thread.$id.localEmpty").apply()
        }
        val localRows = localThreadIds.asSequence().map { id ->
            CodexThread(id, settings.getString("thread.$id.title", "新对话").orEmpty(),
                settings.getString("thread.$id.model", "").orEmpty(), cwd = settings.getString("thread.$id.cwd", cwd).orEmpty())
        }.toList()
        threads.clear()
        threads.addAll((rows + localRows).distinctBy { it.id })
    }

    fun onForeground() {
        inForeground = true
        if (busy) return
        if (!connected) {
            val retained = ConnectionKeepAliveService.currentClient()
            if (retained != null) {
                client = retained
                retained.setServerMessageHandler { event ->
                    viewModelScope.launch { if (client === retained) handleEvent(event) }
                }
                connected = true
                busy = true
                status = "正在同步后台连接状态…"
                viewModelScope.launch {
                    try {
                        val listed = retained.listThreads()
                        if (client !== retained) return@launch
                        replaceThreads(listed)
                        threads.filter { it.running }.forEach {
                            running[it.id] = it.turnId
                            if (it.turnId.isNotBlank()) latestTurnIds[it.id] = it.turnId
                        }
                        val restore = activeId ?: settings.getString("lastThreadId", null)
                        val selectionGeneration = threadSelectionGeneration
                        if (!restore.isNullOrBlank()) runCatching { loadThread(restore, selectionGeneration) }
                        if (client === retained) {
                            busy = false
                            status = "已通过 SSH 连接到 82"
                            flushPendingChatDiagnostics()
                            activeId?.let(::startNativeQueueIfIdle)
                            checkForUpdate()
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (client === retained) {
                            ConnectionKeepAliveService.release(getApplication(), retained)
                            retained.close()
                            client = null
                            connected = false
                            busy = false
                            status = "后台连接已失效，正在重新连接…"
                            if (reconnectOnForeground) connect(preserveUpdateState = true, automatic = true)
                        }
                    }
                }
                return
            }
        }
        val connection = client
        if (connected && (connection == null || !connection.isAlive())) {
            // The process can survive while Android suspends or drops the socket. Reconnect
            // from the persisted SSH settings instead of leaving a stale "已连接" screen.
            connection?.let {
                ConnectionKeepAliveService.release(getApplication(), it)
                it.close()
            }
            client = null
            connected = false
            running.clear()
            sending.clear()
            requests.clear()
            status = "连接已失效，正在重新连接…"
            if (reconnectOnForeground) {
                if (recovery.delayMillis(connectionTime()) > 0) scheduleReconnect()
                else connect(preserveUpdateState = true, automatic = true)
            }
        } else if (connected) {
            refresh()
            checkForUpdate()
        } else if (reconnectOnForeground) {
            if (recovery.delayMillis(connectionTime()) > 0) scheduleReconnect()
            else connect(preserveUpdateState = true, automatic = true)
        }
    }

    fun onBackground() {
        inForeground = false
        if (!ConnectionKeepAliveService.isActive()) {
            reconnectJob?.cancel()
            reconnectJob = null
        }
        // Keep the existing SSH channel open and let the foreground service own it.
    }

    fun openUpdates() { updateDialog = true; checkForUpdate(manual = true) }

    private suspend fun readLiveUpdate(connection: SshCodexClient): UpdateInfo? {
        val info = connection.readUpdateInfo(UPDATE_MANIFEST)
        check(client === connection && connected) { "连接已变更，请重新检查更新" }
        if (info.versionCode <= BuildConfig.VERSION_CODE) {
            clearUpdateFile()
            updateInfo = null
            updateMessage = "当前已是最新版本 " + BuildConfig.VERSION_NAME
            return null
        }
        if (downloadedUpdate?.info?.samePackage(info) == false) clearUpdateFile()
        updateInfo = info
        updateMessage = "发现新版本 " + info.versionName
        return info
    }

    private fun failUpdate(prefix: String, error: Exception) {
        clearUpdateFile()
        updateInfo = null
        updateMessage = "无法确认最新更新，请重新检查"
        updateError = prefix + (error.message ?: "未知错误")
    }

    fun checkForUpdate(manual: Boolean = false) {
        val connection = client ?: return
        if (updateChecking || updateBusy) return
        updateChecking = true
        if (manual) updateMessage = "正在确认最新版本…"
        checkJob = viewModelScope.launch {
            try {
                readLiveUpdate(connection)
                updateError = ""
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (client === connection && connected) {
                    failUpdate("更新检查失败：", e)
                }
            } finally { if (client === connection) updateChecking = false }
        }
    }

    fun downloadUpdate() {
        val connection = client ?: return
        if (!connected || updateBusy || updateChecking) return
        updateBusy = true
        updateError = ""
        clearUpdateFile()
        updateJob = viewModelScope.launch {
            try {
                downloadedUpdate = downloadCurrentUpdate(
                    File(getApplication<Application>().cacheDir, "updates"),
                    readLatest = { readLiveUpdate(connection) },
                    download = { info, file -> withContext(Dispatchers.IO) {
                        file.outputStream().use { connection.downloadUpdate(info, it) }
                    } },
                    verify = ::verifyDownloadedUpdate
                )
                if (downloadedUpdate == null && updateInfo != null) updateMessage = "下载期间版本已更新，请下载最新版本 " + updateInfo!!.versionName
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (client === connection) failUpdate("更新下载失败：", e)
            } finally { if (client === connection) updateBusy = false }
        }
    }

    fun prepareUpdateInstall(install: (File) -> Unit) {
        val downloaded = downloadedUpdate ?: return
        val connection = client ?: return
        if (!connected || updateBusy || updateChecking) return
        updateBusy = true
        updateError = ""
        updateJob = viewModelScope.launch {
            try {
                val installed = installCurrentUpdate(downloaded,
                    readLatest = { readLiveUpdate(connection) },
                    verify = ::verifyDownloadedUpdate, install = install)
                if (!installed && updateInfo != null) updateMessage = "版本已更新，请先下载 " + updateInfo!!.versionName
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (client === connection) failUpdate("更新安装检查失败：", e)
            } finally { if (client === connection) updateBusy = false }
        }
    }

    private suspend fun verifyDownloadedUpdate(file: File, info: UpdateInfo) {
        withContext(Dispatchers.IO) {
            check(file.length() == info.sizeBytes) { "更新包下载不完整" }
            check(sha256(file).equals(info.sha256, ignoreCase = true)) { "更新包校验失败" }
        }
        verifyUpdateApk(getApplication(), file, info)
    }

    fun dismissUpdate() { updateDialog = false }

    fun reportInstallError(value: String) { updateError = value }

    private fun cancelUpdates(preserveState: Boolean = false) {
        updateJob?.cancel()
        checkJob?.cancel()
        updateBusy = false
        updateChecking = false
        if (!preserveState) {
            updateDialog = false
            updateInfo = null
            updateError = ""
            updateMessage = ""
            clearUpdateFile()
        }
    }

    fun clearUpdateFile() { downloadedUpdate?.file?.delete(); downloadedUpdate = null }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun loadOlder() {
        val id = activeId ?: return
        val cursor = historyCursors[id].orEmpty()
        if (cursor.isBlank()) return
        operation("加载历史", id) {
            val page = checkNotNull(client).listTurns(id, cursor)
            val history = parseMessages(JSONObject().put("turns", chronologicalTurns(page)))
            messages[id] = mergeHistory(history, messages[id].orEmpty())
            historyCursors[id] = page.text("nextCursor")
        }
    }

    private fun operation(stage: String = "操作", targetId: String? = activeId, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = ""
        viewModelScope.launch {
            try { block() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                reportChatError(e.message ?: "未知错误", stage + "失败", targetId)
            } finally { busy = false }
        }
    }

    fun send() {
        val id = activeId ?: return
        val rawPrompt = drafts[id].orEmpty().trim()
        val prompt = rawPrompt
        val attachment = imageAttachments[id]
        val video = videoAttachments[id]
        val sharedFiles = sharedFileAttachments[id].orEmpty()
        val references = contextReferences[id].orEmpty()
        val connection = client ?: return
        if (!connected || (rawPrompt.isBlank() && attachment == null && video == null && sharedFiles.isEmpty() && references.isEmpty()) || id in sending) return
        if (id in running) { enqueueDraft(); return }
        val selectedModel = model
        sending += id
        error = ""
        val options = threadExecution[id] ?: savedExecution(id) ?: currentExecution
        viewModelScope.launch {
            try {
                val videoPath = video?.let { connection.uploadWorkspaceVideo(it.file, it.label, threads.firstOrNull { thread -> thread.id == id }?.cwd?.ifBlank { cwd } ?: cwd) }
                val remoteFiles = sharedFiles.map { file ->
                    connection.uploadSharedFile(file.file, file.label, threads.firstOrNull { thread -> thread.id == id }?.cwd?.ifBlank { cwd } ?: cwd)
                }
                val fallback = when {
                    attachment != null -> "请查看这张图片。"
                    video != null -> "请查看这个视频。"
                    sharedFiles.isNotEmpty() -> "请阅读我分享的文件并按内容处理。"
                    else -> "请根据我选定的上下文完成任务。"
                }
                val composedPrompt = listOf(prompt.ifBlank { fallback }, videoPath?.let { "视频附件（原画质文件）：$it" },
                    remoteFiles.takeIf { it.isNotEmpty() }?.let { "手机分享的文件（位于项目 codex/inbox）：\n" + it.joinToString("\n") },
                    references.takeIf { it.isNotEmpty() }?.joinToString("\n\n") { "【上下文：${it.label}】\n${it.prompt}" })
                    .filterNotNull().filter(String::isNotBlank).joinToString("\n")
                recordChatDiagnostic("composer.turn.start.request", id, "", JSONObject()
                    .put("hasImage", attachment != null).put("hasVideo", video != null)
                    .put("sharedFileCount", sharedFiles.size).put("contextCount", references.size))
                val turnId = connection.sendTurn(id, selectedModel, composedPrompt, attachment?.dataUrl, options)
                recordChatDiagnostic("composer.turn.start.accepted", id, turnId)
                // turn/completed may arrive before the response on a very short turn; events own running state.
                if (id in running && running[id].isNullOrBlank()) running[id] = turnId
                promoteThread(id)
                drafts[id] = ""
                settings.edit().remove("draft.$id").apply()
                localEmptyThreads.remove(id)
                settings.edit().remove("thread.$id.localEmpty").apply()
                imageAttachments.remove(id)
                videoAttachments.remove(id)
                sharedFileAttachments.remove(id)?.forEach { it.file.delete() }
                persistSharedFiles()
                contextReferences.remove(id)
                persistContextReferences()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reportChatError(e.message ?: "未知错误", "发送消息失败", id)
            } finally {
                sending.remove(id)
            }
        }
    }

    fun updateDraft(id: String, value: String) {
        drafts[id] = value
        settings.edit().putString("draft.$id", value).apply()
    }

    fun enqueueDraft() {
        val id = activeId ?: return
        val raw = drafts[id].orEmpty().trim()
        val attachment = imageAttachments[id]
        val video = videoAttachments[id]
        val sharedFiles = sharedFileAttachments[id].orEmpty()
        val references = contextReferences[id].orEmpty()
        val connection = client ?: return
        if (!connected || id !in running || (raw.isBlank() && attachment == null && video == null && sharedFiles.isEmpty() && references.isEmpty()) || id in queueSubmitting) return
        queueSubmitting += id
        viewModelScope.launch {
            try {
                val videoPath = video?.let { connection.uploadWorkspaceVideo(it.file, it.label, threads.firstOrNull { thread -> thread.id == id }?.cwd?.ifBlank { cwd } ?: cwd) }
                val remoteFiles = sharedFiles.map { file ->
                    connection.uploadSharedFile(file.file, file.label, threads.firstOrNull { thread -> thread.id == id }?.cwd?.ifBlank { cwd } ?: cwd)
                }
                val fallback = when {
                    attachment != null -> "请查看这张图片。"
                    video != null -> "请查看这个视频。"
                    sharedFiles.isNotEmpty() -> "请阅读我分享的文件并按内容处理。"
                    else -> "请根据我选定的上下文完成任务。"
                }
                val queuedPrompt = listOf(raw.ifBlank { fallback }, videoPath?.let { "视频附件（原画质文件）：$it" },
                    remoteFiles.takeIf { it.isNotEmpty() }?.let { "手机分享的文件（位于项目 codex/inbox）：\n" + it.joinToString("\n") },
                    references.takeIf { it.isNotEmpty() }?.joinToString("\n\n") { "【上下文：${it.label}】\n${it.prompt}" })
                    .filterNotNull().filter(String::isNotBlank).joinToString("\n")
                recordChatDiagnostic("composer.queue.add.request", id, "", JSONObject()
                    .put("hasImage", attachment != null).put("hasVideo", video != null)
                    .put("sharedFileCount", sharedFiles.size).put("contextCount", references.size))
                val added = connection.addQueuedSubmission(id, queuedPrompt, attachment?.dataUrl)
                allowQueueAutoStart(id, added.id)
                recordChatDiagnostic("composer.queue.add.accepted", id, "", JSONObject()
                    .put("queuedSubmissionId", added.id).put("clientUserMessageId", added.clientUserMessageId))
                val updated = queuedTasks[id].orEmpty().filterNot { it.id == added.id } +
                    QueuedTask(added.id, added.clientUserMessageId, added.text, autoStartAllowed = true)
                queuedTasks[id] = updated
                drafts[id] = ""
                settings.edit().remove("draft.$id").apply()
                localEmptyThreads.remove(id)
                settings.edit().remove("thread.$id.localEmpty").apply()
                imageAttachments.remove(id)
                videoAttachments.remove(id)
                sharedFileAttachments.remove(id)?.forEach { it.file.delete() }
                persistSharedFiles()
                contextReferences.remove(id)
                persistContextReferences()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reportChatError(e.message ?: "Codex 原生队列请求失败", "加入排队失败", id)
            } finally {
                queueSubmitting.remove(id)
                if (id !in running) startNativeQueueIfIdle(id)
            }
        }
    }

    fun removeQueuedTask(taskId: String) {
        val id = activeId ?: return
        val connection = client ?: return
        if (id in queueSubmitting) return
        queueSubmitting += id
        viewModelScope.launch {
            try {
                connection.deleteQueuedSubmission(id, taskId)
                consumeQueueAutoStart(id, taskId)
                refreshQueue(id, connection)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reportChatError(e.message ?: "Codex 原生队列删除失败", "移除排队消息失败", id)
            } finally { queueSubmitting.remove(id) }
        }
    }

    private suspend fun refreshQueue(id: String, connection: SshCodexClient) {
        val rows = connection.listQueuedSubmissions(id)
        if (client !== connection) return
        queuedTasks[id] = rows.map { QueuedTask(it.id, it.clientUserMessageId, it.text,
            autoStartAllowed = it.id in autoStartQueueIds(id)) }
    }

    private fun refreshQueueInBackground(id: String, connection: SshCodexClient) {
        viewModelScope.launch {
            runCatching { refreshQueue(id, connection) }
                .onFailure { if (it !is CancellationException) reportChatError(it.message.orEmpty(), "读取 Codex 排队消息失败", id) }
        }
    }

    private fun startNativeQueueIfIdle(id: String) {
        if (!connected || id in running || id in queueSubmitting) return
        val connection = client ?: return
        queueSubmitting += id
        viewModelScope.launch {
            try {
                val queued = connection.listQueuedSubmissions(id)
                if (client !== connection || !connected || id in running) return@launch
                queuedTasks[id] = queued.map { QueuedTask(it.id, it.clientUserMessageId, it.text,
                    autoStartAllowed = it.id in autoStartQueueIds(id)) }
                val next = queued.firstOrNull() ?: return@launch
                if (next.id !in autoStartQueueIds(id)) {
                    recordChatDiagnostic("queue.held_for_confirmation", id, "", JSONObject()
                        .put("queuedSubmissionId", next.id).put("clientUserMessageId", next.clientUserMessageId))
                    uploadChatDiagnostic("unrecognized_queued_submission", id)
                    return@launch
                }
                recordChatDiagnostic("queue.start.request", id, "", JSONObject()
                    .put("queuedSubmissionId", next.id).put("clientUserMessageId", next.clientUserMessageId))
                val turnId = connection.startQueuedSubmission(id, next.id)
                check(turnId.isNotBlank()) { "Codex 没有返回排队任务的回合 ID" }
                consumeQueueAutoStart(id, next.id)
                recordChatDiagnostic("queue.start.accepted", id, "", JSONObject().put("queuedSubmissionId", next.id))
                refreshQueue(id, connection)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reportChatError(e.message ?: "Codex 无法启动排队消息", "启动原生队列失败", id)
            } finally { queueSubmitting.remove(id) }
        }
    }

    fun startQueuedTask(taskId: String) {
        val id = activeId ?: return
        val connection = client ?: return
        if (!connected || id in running || id in queueSubmitting) return
        queueSubmitting += id
        viewModelScope.launch {
            try {
                val queue = connection.listQueuedSubmissions(id)
                check(queue.firstOrNull()?.id == taskId) { "请先处理排在前面的消息，保持队列顺序" }
                recordChatDiagnostic("queue.start.manual.request", id, "", JSONObject()
                    .put("queuedSubmissionId", taskId)
                    .put("clientUserMessageId", queue.first().clientUserMessageId))
                val turnId = connection.startQueuedSubmission(id, taskId)
                check(turnId.isNotBlank()) { "Codex 没有返回排队任务的回合 ID" }
                consumeQueueAutoStart(id, taskId)
                recordChatDiagnostic("queue.start.manual.accepted", id, turnId,
                    JSONObject().put("queuedSubmissionId", taskId))
                refreshQueue(id, connection)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reportChatError(e.message ?: "Codex 无法启动排队消息", "启动排队消息失败", id)
            } finally { queueSubmitting.remove(id) }
        }
    }

    fun stop() {
        val id = activeId ?: return
        operation("停止任务", id) {
            val connection = checkNotNull(client)
            if (running[id].isNullOrBlank()) applySnapshot(connection.readThread(id))
            val turnId = running[id].orEmpty()
            check(turnId.isNotBlank()) { "没有找到运行中的回合，请刷新" }
            connection.interrupt(id, turnId)
        }
    }

    private fun handleEvent(event: JSONObject) {
        if (event.has("localError")) {
            (running.keys.toList() + listOfNotNull(activeId)).distinct().forEach { target ->
                reportChatError(event.text("localError"), "SSH 连接中断", target, running[target].orEmpty(), "connectionError:$target")
            }
            cancelUpdates()
            client?.let { ConnectionKeepAliveService.clear(it) }
            client?.close()
            client = null
            connected = false
            busy = false
            responding = false
            running.clear()
            sending.clear()
            requests.clear()
            status = "SSH 已断开；返回应用时会尝试重新连接"
            error = event.text("localError")
            scheduleReconnect()
            return
        }
        val method = event.text("method")
        val params = event.optJSONObject("params") ?: JSONObject()
        val id = params.text("threadId")
        val eventTurnId = params.text("turnId").ifBlank {
            if (method == "turn/started" || method == "turn/completed")
                params.optJSONObject("turn")?.text("id").orEmpty()
            else ""
        }
        val eventItemId = params.text("itemId").ifBlank { params.optJSONObject("item")?.text("id").orEmpty() }
        if (id.isNotBlank() && (method.startsWith("item/") || method.startsWith("turn/") || method == "error" ||
                method == "thread/status/changed" || method == "thread/queue/changed")) {
            recordChatDiagnostic("server.notification", id, eventTurnId, JSONObject()
                .put("method", method).put("itemId", eventItemId)
                .put("activeTurnId", running[id].orEmpty())
                .put("latestTurnId", latestTurnIds[id].orEmpty()))
        }
        val turnScoped = method.startsWith("item/") || method.startsWith("turn/") || method == "error"
        val newestTurnId = latestTurnIds[id]
        val activeTurnId = running[id].orEmpty()
        if (id.isNotBlank() && turnScoped && newestTurnId == null &&
            !(method == "turn/started" && hasRecentMobileTurnRequest(id))) {
            recordChatDiagnostic("unverified_turn_notification", id, eventTurnId, JSONObject()
                .put("method", method).put("itemId", eventItemId))
            syncThreadTimeline(id, "unverified_turn_notification")
            return
        }
        if (id.isNotBlank() && method == "turn/started" && eventTurnId.isNotBlank() &&
            activeTurnId.isNotBlank() && activeTurnId != eventTurnId) {
            recordChatDiagnostic("stale_turn_started", id, eventTurnId, JSONObject().put("activeTurnId", activeTurnId))
            uploadChatDiagnostic("stale_turn_started", id)
            return
        }
        if (id.isNotBlank() && method == "turn/started" && eventTurnId.isNotBlank() &&
            newestTurnId != null && newestTurnId != eventTurnId && !hasRecentMobileTurnRequest(id)) {
            recordChatDiagnostic("unexpected_turn_started", id, eventTurnId, JSONObject()
                .put("latestTurnId", newestTurnId).put("activeTurnId", activeTurnId))
            syncThreadTimeline(id, "unexpected_turn_started")
            return
        }
        // A delayed notification from an earlier turn used to be appended at the
        // bottom of the chat. Re-read the server's ordered history so the item is
        // retained in its original turn without letting stale state affect this one.
        if (id.isNotBlank() && turnScoped && eventTurnId.isNotBlank() &&
            newestTurnId != null && newestTurnId != eventTurnId && method != "turn/started") {
            recordChatDiagnostic("stale_turn_notification", id, eventTurnId, JSONObject()
                .put("method", method).put("itemId", eventItemId)
                .put("activeTurnId", activeTurnId).put("latestTurnId", newestTurnId))
            if (method.startsWith("item/")) syncThreadTimeline(id, "stale_turn_notification")
            else uploadChatDiagnostic("stale_turn_notification", id)
            return
        }
        if (event.has("id")) {
            when (method) {
                "item/commandExecution/requestApproval", "item/fileChange/requestApproval",
                "item/permissions/requestApproval", "item/tool/requestUserInput",
                "execCommandApproval", "applyPatchApproval" -> {
                    if (requests.none { it.get("id").toString() == event.get("id").toString() }) requests += event
                    if (id.isNotBlank()) messages[id] = upsertChat(messages[id].orEmpty(), requestChatLine(event))
                }
                "mcpServer/elicitation/request" -> {
                    reportChatError("MCP 表单暂不支持，已拒绝本次请求", "表单请求未处理", id)
                    val connection = client ?: return
                    viewModelScope.launch {
                        runCatching { connection.respondToServerRequest(event.get("id"), JSONObject().put("action", "decline")) }
                            .onFailure { reportChatError(it.message.orEmpty(), "拒绝表单失败", id) }
                    }
                }
                "currentTime/read" -> {
                    val connection = client ?: return
                    viewModelScope.launch {
                        runCatching { connection.respondToServerRequest(event.get("id"),
                            JSONObject().put("currentTimeAt", System.currentTimeMillis() / 1000)) }
                            .onFailure { reportChatError(it.message.orEmpty(), "回复时间请求失败", id) }
                    }
                }
                else -> replyUnsupported(event, "当前客户端暂不支持服务器请求：" + method)
            }
            return
        }
        if (id.isNotBlank()) {
            val current = messages[id].orEmpty()
            val updated = applyChatNotification(current, method, params)
            if (updated != current) messages[id] = updated
            if (method in setOf("item/started", "item/completed", "item/agentMessage/delta")) {
                val item = params.optJSONObject("item")
                promoteThread(id)
            }
        }
        when (method) {
            "serverRequest/resolved" -> {
                val requestId = params.opt("requestId")?.toString().orEmpty()
                requests.firstOrNull { it.get("id").toString() == requestId }?.let { request ->
                    val threadId = request.optJSONObject("params")?.text("threadId").orEmpty()
                    if (threadId.isNotBlank()) messages[threadId] = upsertChat(messages[threadId].orEmpty(), requestChatLine(request).copy(status = "resolved"))
                }
                requests.removeAll { it.get("id").toString() == requestId }
            }
            "thread/started" -> params.optJSONObject("thread")?.let { upsert(parseThread(it)) }
            "thread/name/updated" -> {
                val name = params.text("threadName")
                if (name.isBlank()) return
                threadNames[id] = name.take(120)
                threads.firstOrNull { it.id == id }?.let { upsert(it.copy(title = name.take(120))) }
            }
            "thread/settings/updated" -> params.optJSONObject("threadSettings")?.let { updated ->
                val fallback = threadExecution[id] ?: savedExecution(id) ?: ExecutionOptions(cwd, defaultMode, defaultReasoningEffort)
                threadExecution[id] = executionOptions(updated, fallback)
            }
            "thread/status/changed" -> if (params.optJSONObject("status")?.text("type") == "active") {
                if (id !in running) {
                    running[id] = ""
                    syncThreadTimeline(id)
                }
            } else if (running[id].isNullOrBlank()) running.remove(id)
            "thread/queue/changed" -> if (id.isNotBlank()) {
                client?.let { connection -> viewModelScope.launch {
                    runCatching { refreshQueue(id, connection) }
                        .onFailure { if (it !is CancellationException) reportChatError(it.message.orEmpty(), "同步 Codex 队列失败", id) }
                } }
            }
            "turn/started" -> {
                val turnId = params.optJSONObject("turn")?.text("id").orEmpty().ifBlank { eventTurnId }
                val hasRequest = hasRecentMobileTurnRequest(id)
                recordChatDiagnostic("turn.started", id, turnId)
                running[id] = turnId
                if (turnId.isNotBlank()) latestTurnIds[id] = turnId
                if (!hasRequest) uploadChatDiagnostic("turn_started_without_mobile_request", id)
                promoteThread(id)
                clearTaskStatus(id)
            }
            "turn/completed" -> {
                val stage = activity[id]
                recordChatDiagnostic("turn.completed", id, params.optJSONObject("turn")?.text("id").orEmpty(),
                    JSONObject().put("status", params.optJSONObject("turn")?.text("status").orEmpty()))
                running.remove(id)
                activity.remove(id)
                val turn = params.optJSONObject("turn")
                val failed = turn?.text("status") == "failed"
                val status = turn?.text("status").orEmpty()
                val completedTurnId = turn?.text("id").orEmpty()
                if (status == "completed") maybeGenerateThreadTitle(id)
                if (status in setOf("completed", "failed", "interrupted", "cancelled")) {
                    if (activeId == id) clearTaskStatus(id) else {
                        taskStatuses[id] = status
                        settings.edit().putString("thread.$id.lastTaskStatus", status).apply()
                    }
                    if (completedTurnId.isBlank() || settings.getString("thread.$id.lastNotifiedTurnId", null) != completedTurnId) {
                        if (completedTurnId.isNotBlank()) settings.edit().putString("thread.$id.lastNotifiedTurnId", completedTurnId).apply()
                        val threadTitle = threads.firstOrNull { it.id == id }?.title ?: "Andri 任务"
                        val project = projectFor(id)
                        val alertTitle = if (failed) "任务执行失败" else if (status in setOf("interrupted", "cancelled")) "任务已中断" else "任务已完成"
                        val detail = turn?.optJSONObject("error")?.text("message").orEmpty().take(180)
                        if (inForeground) {
                            taskCompletionAlerts.add(TaskCompletionAlert(
                                id = "$id:$completedTurnId", threadId = id, threadTitle = threadTitle,
                                project = project, title = alertTitle, detail = detail))
                        } else {
                            val notification = TaskNotifications.show(getApplication(), id, threadTitle, project, alertTitle, detail)
                            if (!notification.sent) {
                                val diagnosticClient = client
                                if (diagnosticClient?.isAlive() == true) viewModelScope.launch {
                                    runCatching {
                                        diagnosticClient.reportDiagnostic(JSONObject()
                                            .put("type", "task_notification_failed")
                                            .put("time", System.currentTimeMillis())
                                            .put("stage", notification.stage)
                                            .put("reason", notification.reason.take(300))
                                            .put("appVersion", BuildConfig.VERSION_NAME)
                                            .put("androidApi", android.os.Build.VERSION.SDK_INT))
                                    }.onFailure {
                                        android.util.Log.w("MobileDiagnostics", "Unable to upload notification diagnostic", it)
                                        error = "任务通知发送失败（${notification.stage}）；诊断回传也失败：${it.message ?: "SSH/SFTP 不可用"}"
                                    }
                                } else error = "任务通知发送失败（${notification.stage}）：${notification.reason}；当前没有可用的 SSH 连接回传诊断。"
                            }
                        }
                    }
                }
                if (failed) reportChatError(
                    turn?.optJSONObject("error")?.text("message").orEmpty().ifBlank { "Andri 任务失败" },
                    "任务执行失败" + if (stage.isNullOrBlank()) "" else " · " + stage.take(60), id, turn?.text("id").orEmpty(), "turnError:" + turn?.text("id").orEmpty())
                if (id.isNotBlank()) startNativeQueueIfIdle(id)
            }
            "item/started", "item/completed" -> params.optJSONObject("item")?.let { item ->
                if (item.text("type") !in listOf("userMessage", "agentMessage")) {
                    toolItems[item.text("id")] = item
                    activity[id] = if (method == "item/started") {
                        item.text("command").ifBlank { "正在执行：" + item.text("type") }.take(180)
                    } else "工具执行完成，等待 Andri 继续…"
                }
            }
            "error" -> {
                val turnId = params.text("turnId")
                val retry = params.optBoolean("willRetry")
                reportChatError(params.optJSONObject("error")?.text("message").orEmpty().ifBlank { params.text("message") },
                    if (retry) "请求异常，正在重试" else "任务执行异常", id, turnId,
                    if (retry || turnId.isBlank()) "" else "turnError:$turnId")
            }
        }
    }

    private fun replyUnsupported(event: JSONObject, explanation: String) {
        val target = event.optJSONObject("params")?.text("threadId")
        reportChatError(explanation, "请求暂不支持", target)
        val connection = client ?: return
        viewModelScope.launch {
            runCatching { connection.rejectUnsupportedRequest(event.get("id"), event.text("method")) }
                .onFailure { reportChatError(it.message.orEmpty(), "回复请求失败", target) }
        }
    }

    fun respond(request: JSONObject, approved: Boolean, answers: Map<String, String> = emptyMap()) {
        if (responding) return
        val connection = client ?: return
        responding = true
        requestError = ""
        viewModelScope.launch {
            try {
                val method = request.text("method")
                val result = when (method) {
                    "item/tool/requestUserInput" -> JSONObject().put("answers", JSONObject().apply {
                        answers.forEach { (id, value) -> put(id, JSONObject().put("answers", JSONArray().put(value))) }
                    })
                    "item/permissions/requestApproval" -> JSONObject().put("scope", "turn")
                        .put("permissions", if (approved) request.getJSONObject("params").getJSONObject("permissions") else JSONObject())
                    "execCommandApproval", "applyPatchApproval" -> JSONObject().put("decision", if (approved) "approved" else "denied")
                    else -> JSONObject().put("decision", if (approved) "accept" else "decline")
                }
                connection.respondToServerRequest(request.get("id"), result)
                val threadId = request.optJSONObject("params")?.text("threadId").orEmpty()
                if (threadId.isNotBlank()) {
                    val line = requestChatLine(request)
                    val summary = if (method == "item/tool/requestUserInput") {
                        val questions = request.optJSONObject("params")?.optJSONArray("questions") ?: JSONArray()
                        (0 until questions.length()).mapNotNull { i -> questions.optJSONObject(i)?.let { question ->
                            question.text("question") + "\n" + if (question.optBoolean("isSecret")) "已提交保密回答" else answers[question.text("id")].orEmpty()
                        } }.joinToString("\n\n")
                    } else line.text
                    messages[threadId] = upsertChat(messages[threadId].orEmpty(), line.copy(text = summary,
                        status = if (method == "item/tool/requestUserInput") "answered" else if (approved) "approved" else "declined"))
                }
                requests.removeAll { it.get("id").toString() == request.get("id").toString() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                requestError = "回复服务器请求失败：" + e.message
                reportChatError(e.message.orEmpty(), "回复审批失败", request.optJSONObject("params")?.text("threadId"))
            } finally { responding = false }
        }
    }

    fun sendQuestionAnswers(line: ChatLine, answers: List<String>) {
        val id = activeId ?: return
        val connection = client ?: return
        if (!connected || busy || id in sending || line.id in answeredQuestionItems ||
            answers.size != line.questions.size || answers.any { it.isBlank() }) return
        val current = messages[id].orEmpty()
        val index = current.indexOfFirst { it.id == line.id }
        if (index < 0 || current.drop(index + 1).any { it.role == "user" }) return
        val prompt = line.questions.zip(answers).joinToString("\n\n") { (question, answer) -> question.title + "\n我的选择：" + answer }
        val selectedModel = model
        val options = currentExecution
        sending += id
        viewModelScope.launch {
            try {
                val turnId = running[id]
                if (!turnId.isNullOrBlank()) connection.steerTurn(id, turnId, prompt)
                else {
                    check(id !in running) { "正在同步任务状态，请稍后提交" }
                    connection.sendTurn(id, selectedModel, prompt, options = options)
                }
                answeredQuestionItems += line.id
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reportChatError(e.message.orEmpty(), "提交选择失败", id)
            } finally { sending.remove(id) }
        }
    }

    override fun onCleared() {
        reconnectJob?.cancel()
        connectionJob?.cancel()
        client?.setServerMessageHandler { }
    }
}

internal fun upsertChat(current: List<ChatLine>, line: ChatLine): List<ChatLine> {
    val index = current.indexOfFirst { it.id == line.id && it.id.isNotBlank() }
    return current.toMutableList().apply { if (index < 0) add(line) else set(index, line) }
}

internal fun mergeHistory(
    history: List<ChatLine>,
    current: List<ChatLine>,
    latestPage: Boolean = false,
    activeTurnId: String = ""
): List<ChatLine> {
    if (history.isEmpty()) return current
    if ((history + current).any { it.role == "error" }) {
        val merged = mergeHistory(
            history.filter { it.role != "error" }, current.filter { it.role != "error" }, latestPage, activeTurnId
        ).toMutableList()
        val failures = (history + current).filter { it.role == "error" }.associateBy { it.id }.values
        failures.toList().asReversed().forEach { failure ->
            val anchor = merged.indexOfLast { it.id == failure.afterId && failure.afterId.isNotBlank() }
            val turn = merged.indexOfLast { it.turnId == failure.turnId && failure.turnId.isNotBlank() }
            merged.add(if (anchor >= 0) anchor + 1 else if (turn >= 0) turn + 1 else merged.size, failure)
        }
        return merged
    }
    val firstOverlap = current.indexOfFirst { live -> history.any { it.id == live.id } }
    val prefix = if (firstOverlap < 0) emptyList() else current.take(firstOverlap)
    val merged = history.map { saved ->
        current.firstOrNull { it.id == saved.id }?.let { live ->
            // History is authoritative after reconnect. Preserve a genuinely longer
            // live prefix; equal text must take the saved completed status.
            when {
                live.text.startsWith(saved.text) && live.text != saved.text -> live
                else -> saved
            }
        } ?: saved
    }
    val remaining = current.drop(if (firstOverlap < 0) 0 else firstOverlap)
        .filter { live -> history.none { it.id == live.id } }
    if (!latestPage) return prefix + merged + remaining

    // A latest-page response can omit older cached turns. Appending every
    // unmatched cached line after the page moves old messages to the bottom.
    // Reinsert lines into a turn already present in the page, keep an active
    // turn at the end, and retain other omitted turns before the page.
    val orderedHistory = merged.toMutableList()
    val omittedOlderLines = mutableListOf<ChatLine>()
    val liveTail = mutableListOf<ChatLine>()
    remaining.forEach { line ->
        val turnLines = line.turnId.takeIf { it.isNotBlank() }
            ?.let { turnId -> orderedHistory.indices.filter { orderedHistory[it].turnId == turnId } }
            .orEmpty()
        when {
            turnLines.isNotEmpty() -> orderedHistory.add(turnLines.last() + 1, line)
            line.streaming || line.turnId.isBlank() || line.turnId == activeTurnId ||
                line.status in setOf("inProgress", "pending", "waiting") -> liveTail.add(line)
            else -> omittedOlderLines.add(line)
        }
    }
    return prefix + omittedOlderLines + orderedHistory + liveTail
}

internal fun chatFailure(current: List<ChatLine>, details: String, stage: String, turnId: String = "", eventId: String = ""): ChatLine {
    val anchor = current.lastOrNull { it.role != "error" && (turnId.isBlank() || it.turnId == turnId) }
        ?: current.lastOrNull { it.role != "error" }
    return ChatLine("error", details, eventId.ifBlank { "localError:" + java.util.UUID.randomUUID() }, turnId, stage, anchor?.id.orEmpty())
}
