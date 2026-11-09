package com.codexserver.mobile

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
data class IncomingImage(val bytes: ByteArray, val mimeType: String, val label: String)
data class VideoAttachment(val file: File, val label: String, val sizeBytes: Long)
data class SharedFileAttachment(val file: File, val label: String, val sizeBytes: Long, val mimeType: String)
data class ContextReference(val id: String, val label: String, val prompt: String)
data class QueuedTask(val id: String, val clientUserMessageId: String, val prompt: String,
    val autoStartAllowed: Boolean = false)
data class TaskCompletionAlert(val id: String, val threadId: String, val threadTitle: String,
    val project: String, val title: String, val detail: String)

class MobileViewModel(application: Application) : AndroidViewModel(application) {
    private data class BufferedAgentDelta(val turnId: String, val text: StringBuilder)

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
    private val threadReads = ThreadReadState()
    private val lastHydratedUpdatedAt = mutableMapOf<String, Long>()
    private val bufferedAgentDeltas = mutableMapOf<String, LinkedHashMap<String, BufferedAgentDelta>>()
    private val agentDeltaFlushJobs = mutableMapOf<String, Job>()
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
    var workspaceCreating by mutableStateOf(false)
        private set
    val executionSaving = mutableStateListOf<String>()
    private var workspaceReadJob: Job? = null
    private var workspaceReadGeneration = 0
    var projectFilesListing by mutableStateOf<RemoteDirectory?>(null)
        private set
    var projectFilesBusy by mutableStateOf(false)
        private set
    var projectFilesError by mutableStateOf("")
        private set
    private var projectFilesJob: Job? = null
    private var projectFilesGeneration = 0
    private var gitDiffJob: Job? = null
    private var gitDiffGeneration = 0
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
    private val operations = mutableStateMapOf<String, Any>()
    val busy: Boolean get() = operations.isNotEmpty()
    fun operationPending(stage: String, id: String? = activeId): Boolean = "$stage:${id.orEmpty()}" in operations
    private var actions = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
    private val draftRevisions = mutableMapOf<String, Long>()
    private val submittingComposers = mutableMapOf<String, ComposerSubmission>()
    private val startingComposers = mutableSetOf<String>()
    private val queueLocks = mutableMapOf<String, Mutex>()
    val removingQueuedTasks = mutableStateListOf<String>()
    private val pendingExecutionIds = mutableStateListOf<String>()
    var connecting by mutableStateOf(false)
        private set
    var synchronizing by mutableStateOf(false)
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
    val respondingRequests = mutableStateListOf<String>()
    val requestAnswers = mutableStateMapOf<String, Map<String, String>>()
    val requestErrors = mutableStateMapOf<String, String>()
    var visibleRequestId by mutableStateOf<String?>(null)
        private set
    fun openPendingRequest(id: String? = null) {
        visibleRequestId = requests.firstOrNull { id == null || it.get("id").toString() == id }?.get("id")?.toString()
    }
    fun dismissPendingRequest() { visibleRequestId = null }
    fun setRequestAnswer(requestId: String, questionId: String, value: String) {
        requestAnswers[requestId] = requestAnswers[requestId].orEmpty() + (questionId to value)
    }
    val threads = mutableStateListOf<CodexThread>()
    val syncingThreadIds = mutableStateListOf<String>()
    private val localEmptyThreads = mutableSetOf<String>()
    private val localThreadIds = mutableSetOf<String>()
    private var listedThreadIds = emptySet<String>()
    val running = mutableStateMapOf<String, String>()
    val runningSince = mutableStateMapOf<String, Long>()
    private val pinnedThreadIds = mutableStateMapOf<String, Boolean>()
    val sending = mutableStateListOf<String>()
    private val mobileTurnStartedDuringSend = mutableSetOf<String>()
    private val readyThreadIds = mutableStateListOf<String>()
    fun canSendToThread(id: String): Boolean = connected && id in readyThreadIds && !operationPending("归档对话", id)
    val messages = mutableStateMapOf<String, List<ChatLine>>()
    val queuedTasks = mutableStateMapOf<String, List<QueuedTask>>()
    val historyCursors = mutableStateMapOf<String, String>()
    val drafts = mutableStateMapOf<String, String>()
    val imageAttachments = mutableStateMapOf<String, List<ImageAttachment>>()
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
    val questionAnswerDrafts = mutableStateMapOf<String, Map<Int, String>>()
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
    var historyDialog by mutableStateOf(false)
        private set
    var historyReleases by mutableStateOf<List<UpdateInfo>>(emptyList())
        private set
    var historyBusy by mutableStateOf(false)
        private set
    var historyError by mutableStateOf("")
        private set
    var historyMessage by mutableStateOf("")
        private set
    var historyExportBusy by mutableStateOf(false)
        private set
    var historyExportMessage by mutableStateOf("")
        private set
    internal var historicalDownload by mutableStateOf<DownloadedUpdate?>(null)
        private set
    private var historyJob: Job? = null
    private var historyClient: SshCodexClient? = null
    private var connectionStage = "connect-start"

    private var client: SshCodexClient? = null
    private val recovery = ConnectionRecovery()
    private var connectionJob: Job? = null
    private var synchronizationJob: Job? = null
    private var lastForegroundSyncAt = Long.MIN_VALUE
    private var threadOpenJob: Job? = null
    private var reconnectJob: Job? = null
    private var inForeground = false
    var foregroundGeneration by mutableIntStateOf(0)
        private set
    private var foregroundStartedAt = System.nanoTime()
    private var uploadedConnectionTimingId: String? = null
    private fun connectionTime() = System.nanoTime() / 1_000_000
    private var reconnectOnForeground = settings.getBoolean("autoReconnect", privateKey.isNotBlank())
    val queueSubmitting = mutableStateListOf<String>()
    val activeThread: CodexThread? get() = threads.firstOrNull { it.id == activeId }

    init {
        settings.all.filter { (key, value) -> key.startsWith("thread.") && key.endsWith(".pendingExecution") && value == true }
            .keys.forEach { pendingExecutionIds += it.removePrefix("thread.").removeSuffix(".pendingExecution") }
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
            cached.threads.filter { it.running }.forEach { thread ->
                runningSince[thread.id] = System.currentTimeMillis()
                TaskRecoveryScheduler.enqueue(application, thread.id, thread.cwd.ifBlank { cwd })
            }
        }
        viewModelScope.launch(Dispatchers.IO) { CacheMaintenance.trim(application.cacheDir) }
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

    fun isPinned(id: String): Boolean = pinnedThreadIds[id]
        ?: settings.getBoolean("thread.$id.pinned", false)

    fun togglePinned(id: String) {
        val next = !isPinned(id)
        pinnedThreadIds[id] = next
        settings.edit().putBoolean("thread.$id.pinned", next).apply()
    }

    /** The app-server exposes archive for user threads; the list removes it immediately after success. */
    fun archiveThread(id: String, deleteLabel: Boolean = false) {
        if (id in running || id in sending || id in queueSubmitting) {
            reportChatError("请先停止当前任务再归档会话", "归档对话", id)
            return
        }
        operation("归档对话", id) { connection ->
            connection.archiveThread(id)
            requireCurrent(connection)
            threadReads.archive(id)
            listedThreadIds = listedThreadIds - id
            localThreadIds.remove(id)
            localEmptyThreads.remove(id)
            readyThreadIds.remove(id)
            threads.removeAll { it.id == id }
            messages.remove(id)
            running.remove(id)
            runningSince.remove(id)
            pinnedThreadIds.remove(id)
            settings.edit().remove("thread.$id.pinned")
                .remove("thread.$id.localThread").remove("thread.$id.localEmpty").apply()
            if (activeId == id) back()
        }
    }
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
        get() = activeId?.let { threadExecution[it] ?: savedExecution(it) } ?: ExecutionOptions(activeThread?.cwd?.ifBlank { cwd } ?: cwd, defaultMode, defaultReasoningEffort)
    val approvalPolicy: String get() = currentExecution.mode.policy
    val approvalsReviewer: String get() = currentExecution.mode.reviewer
    val sandbox: String get() = currentExecution.mode.sandbox

    fun reasoningEfforts(forModel: String = model): List<String> = when (forModel) {
        "gpt-6.1-sol" -> listOf("low", "medium", "high", "xhigh", "max", "ultra")
        else -> listOf("low", "medium", "high", "xhigh", "max")
    }

    fun selectModel(value: String) {
        model = value
        activeId?.let { settings.edit().putString("thread.$it.nextModel", value).apply() }
        if (currentExecution.effort !in reasoningEfforts(value)) setReasoningEffort(reasoningEfforts(value).first())
    }

    fun setReasoningEffort(value: String) {
        if (value !in reasoningEfforts()) return
        val id = activeId
        if (id != null) {
            val current = threadExecution[id] ?: currentExecution
            markExecutionPending(id, current.copy(effort = value))
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

    fun hasPendingExecution(id: String): Boolean = id in pendingExecutionIds

    private fun markExecutionPending(id: String, options: ExecutionOptions) {
        rememberExecution(id, options)
        if (id !in pendingExecutionIds) pendingExecutionIds += id
        settings.edit().putBoolean("thread.$id.pendingExecution", true).apply()
    }

    private fun executionApplied(id: String, options: ExecutionOptions) {
        threads.firstOrNull { it.id == id }?.let { upsert(it.copy(cwd = options.cwd)) }
        if ((threadExecution[id] ?: savedExecution(id)) == options) {
            pendingExecutionIds.remove(id)
            settings.edit().remove("thread.$id.pendingExecution").apply()
        }
    }

    private fun requireCurrent(connection: SshCodexClient) {
        check(client === connection && connected) { "连接已变更，本次结果已忽略" }
    }

    private fun resetActions() {
        actions.cancel()
        actions = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        operations.clear()
        queueSubmitting.clear()
        removingQueuedTasks.clear()
        respondingRequests.clear()
        startingComposers.clear()
        mobileTurnStartedDuringSend.clear()
        requestAnswers.clear()
        requestErrors.clear()
        executionSaving.clear()
        workspaceReadGeneration++
        projectFilesGeneration++
        gitDiffGeneration++
        workspaceBusy = false
        workspaceCreating = false
        projectFilesBusy = false
        gitDiffBusy = false
        globalInstructionsBusy = false
        visibleRequestId = null
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
        if (connectionJob?.isActive == true) return
        resetActions()
        flushAllAgentDeltas()
        val validPort = port.toIntOrNull()
        if (host.isBlank() || username.isBlank() || validPort == null || validPort !in 1..65535) {
            error = "请填写有效的服务器地址、用户名和端口"
            return
        }
        val token = recovery.begin(connectionTime(), automatic) ?: return
        if (!automatic) reconnectJob?.cancel()
        connecting = true
        synchronizationJob?.cancel()
        synchronizing = false
        error = ""
        // A foreground reconnect replaces the dead SSH socket, but must not make an
        // update the user already started disappear from the UI.
        cancelUpdates(preserveState = preserveUpdateState)
        connected = false
        readyThreadIds.clear()
        client?.let { previous ->
            previous.close()
        }
        sending.clear()
        requests.clear()
        settings.edit().putString("host", host.trim()).putString("port", port)
            .putString("username", username.trim()).putString("cwd", cwd)
            .putString("executionMode", defaultMode.name).apply()
        runCatching { secrets?.put("passphrase", passphrase) }
        status = "正在建立 SSH 安全连接…"
        lateinit var connection: SshCodexClient
        connectionStage = "connect-start"
        connection = SshCodexClient(host.trim(), validPort, username.trim(), privateKey, passphrase,
            onConnectionStage = { stage -> viewModelScope.launch {
                if (client === connection && recovery.isCurrent(token) && connecting) {
                    connectionStage = stage
                    status = connectionStageLabel(stage)
                }
            } }) { event ->
            viewModelScope.launch {
                if (client === connection && recovery.isCurrent(token)) {
                    if (event.has("localError")) recovery.failed(token, connectionTime())
                    handleEvent(event)
                }
            }
        }
        client = connection
        connectionJob = viewModelScope.launch {
            try {
                ConnectionKeepAliveService.hold(getApplication(), connection)
                awaitConnectionAttempt(abort = connection::close) { connection.connect() }
                if (client !== connection || !recovery.isCurrent(token)) return@launch
                connected = true
                connecting = false
                recovery.connected(token, connectionTime())
                reconnectOnForeground = true
                settings.edit().putBoolean("autoReconnect", true).apply()
                status = "已通过 SSH 连接到 82"
                synchronizeInBackground(connection, restoreSubscription = true)
                flushPendingChatDiagnostics()
                checkForUpdate()
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                viewModelScope.launch(Dispatchers.IO) { connection.close() }
                if (client === connection && recovery.isCurrent(token)) {
                    ConnectionKeepAliveService.clear(connection)
                    recovery.failed(token, connectionTime())
                    client = null
                    connected = false
                    error = if (e is TimeoutCancellationException)
                        "连接超时（45 秒）：" + connectionStageLabel(connectionStage).removeSuffix("…") + "。可重试或打开历史版本。"
                        else e.message ?: "连接失败"
                    status = "连接失败，可重试或查看历史版本"
                }
            } finally {
                if (client === connection || client == null) {
                    connecting = false
                    connectionJob = null
                    if (!connected) {
                        if (reconnectOnForeground) scheduleReconnect()
                        else ConnectionKeepAliveService.release(getApplication())
                    }
                }
            }
        }
    }

    fun cancelConnection() {
        reconnectJob?.cancel()
        reconnectJob = null
        recovery.reset()
        connectionJob?.cancel()
        connectionJob = null
        val previous = client
        client = null
        previous?.let {
            ConnectionKeepAliveService.clear(it)
            viewModelScope.launch(Dispatchers.IO) { it.close() }
        }
        connecting = false
        connected = false
        reconnectOnForeground = false
        settings.edit().putBoolean("autoReconnect", false).apply()
        ConnectionKeepAliveService.release(getApplication())
        status = "连接已取消，可修改配置或查看历史版本"
    }

    /** Transport recovery and history synchronization never acquire the UI operation lock. */
    private fun synchronizeInBackground(connection: SshCodexClient, restoreSubscription: Boolean = false) {
        if (synchronizationJob?.isActive == true) return
        lastForegroundSyncAt = connectionTime()
        val restore = activeId
        val selectionGeneration = threadSelectionGeneration
        synchronizing = true
        synchronizationJob = viewModelScope.launch {
            try {
                // On an already-live connection, thread/list is enough to tell
                // whether the selected conversation changed. Avoid another
                // turns/list read when its server timestamp is unchanged.
                val listedActiveThread = CompletableDeferred<CodexThread?>()
                syncConversationContent(
                    syncList = {
                        try {
                            val revisions = threadReads.snapshot()
                            val rows = connection.listThreads(firstPageOnly = true)
                            listedActiveThread.complete(rows.firstOrNull { it.id == restore })
                            if (client === connection && connected) {
                                // A first page is partial: keep older cached conversations available.
                                replaceThreads(rows, partial = true, revisions = revisions)
                                updateRunningThreads(rows, revisions)
                                // A reconnect needs a full reconciliation and task recovery.
                                // A foreground refresh on the same live socket only needs the
                                // first page; the full scan and serial resumes add no new data.
                                if (restoreSubscription) {
                                    refreshThreadListInBackground(connection, skipHydration = setOfNotNull(restore))
                                }
                            }
                        } finally {
                            // A failed list read must fall back to fetching the conversation.
                            listedActiveThread.complete(null)
                        }
                    },
                    syncThread = {
                        if (restore != null) {
                            if (restoreSubscription) loadThread(restore, selectionGeneration)
                            else if (restore !in localEmptyThreads) {
                                val listed = listedActiveThread.await()
                                val knownUpdatedAt = lastHydratedUpdatedAt[restore] ?: 0L
                                val unchanged = listed?.updatedAt?.let { it > 0L && it == knownUpdatedAt } == true
                                if (!unchanged) {
                                    val revision = threadReads.revision(restore)
                                    val snapshot = connection.readThread(restore)
                                    if (client === connection && connected) applySnapshot(snapshot, revision)
                                }
                            }
                        }
                    },
                    onFailure = { e ->
                        if (client === connection) error = "同步暂未完成，可继续操作：" + (e.message ?: "未知错误")
                    }
                )
            } finally {
                if (client === connection) synchronizing = false
            }
        }
    }

    private fun updateRunningThreads(rows: List<CodexThread>, revisions: Map<String, Long>) {
        rows.forEach {
            if (!threadReads.accepts(it.id, revisions[it.id] ?: 0L)) return@forEach
            val wasRunning = it.id in running
            val previousTurnId = running[it.id]
            if (it.running) {
                running[it.id] = running[it.id] ?: it.turnId
                runningSince.putIfAbsent(it.id, System.currentTimeMillis())
                if (it.turnId.isNotBlank()) latestTurnIds[it.id] = it.turnId
            } else {
                running.remove(it.id)
                runningSince.remove(it.id)
            }
            if (wasRunning != (it.id in running) || previousTurnId != running[it.id]) threadReads.changed(it.id)
        }
    }

    private fun refreshThreadListInBackground(connection: SshCodexClient, skipHydration: Set<String> = emptySet()) {
        viewModelScope.launch {
            val revisions = threadReads.snapshot()
            runCatching { connection.listThreads() }
                .onSuccess { rows ->
                    if (client === connection && connected) {
                        replaceThreads(rows, revisions = revisions)
                        updateRunningThreads(rows, revisions)
                        preloadRunningThreads(connection, rows.mapNotNull { row ->
                            row.id.takeIf { row.running && it !in skipHydration }
                        })
                    }
                }
                .onFailure { if (it !is CancellationException) android.util.Log.w("MobileSync", "后台刷新对话列表失败", it) }
        }
    }

    /** Hydrate active task windows serially so a busy app-server is not flooded with resumes. */
    private suspend fun preloadRunningThreads(connection: SshCodexClient, ids: List<String>) {
        ids.distinct().forEach { id ->
            if (client !== connection || !connected || !connection.isAlive()) return
            if (id in readyThreadIds || openingThreadId == id) return@forEach
            try {
                val saved = savedExecution(id)
                val revision = threadReads.revision(id)
                val result = connection.resumeThread(id, if (hasPendingExecution(id)) null else saved)
                if (client !== connection || !connected || threadReads.isArchived(id)) return
                if (id !in readyThreadIds) readyThreadIds += id
                val fallback = saved ?: ExecutionOptions(cwd, defaultMode, defaultReasoningEffort)
                if (!hasPendingExecution(id)) threadExecution[id] = executionOptions(result, fallback)
                applySnapshot(result, revision)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.w("MobileSync", "后台预加载进行中任务失败", e)
            }
        }
    }

    private fun scheduleReconnect() {
        if ((!inForeground && !ConnectionKeepAliveService.isActive()) || !reconnectOnForeground || reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            retryConnection(
                shouldRetry = { (inForeground || ConnectionKeepAliveService.isActive()) && reconnectOnForeground && !connected },
                delayMillis = { recovery.delayMillis(connectionTime()).coerceAtLeast(2_000) },
                onWaiting = { wait -> status = "连接中断，${wait / 1000} 秒后自动重连" },
                attempt = {
                    if (connectionJob?.isActive != true) {
                        connect(preserveUpdateState = true, automatic = true)
                    }
                    connectionJob?.join()
                }
            )
        }
    }

    /** Called on the main thread by the service, including after process recreation. */
    fun maintainBackgroundConnection(networkChanged: Boolean = false) {
        if (!reconnectOnForeground) return
        if (networkChanged || connected && client?.isAlive() != true) {
            reconnectJob?.cancel()
            reconnectJob = null
            recovery.reset() // Reject events from the socket tied to the previous network.
            connectionJob?.cancel()
            connectionJob = null
            handleEvent(JSONObject().put("localError", if (networkChanged) "网络已切换，正在恢复连接" else "连接已失效，正在恢复连接"))
        } else if (!connected && connectionJob?.isActive != true) {
            scheduleReconnect()
        }
    }

    fun disconnect() {
        resetActions()
        flushAllAgentDeltas()
        reconnectJob?.cancel()
        reconnectJob = null
        recovery.reset()
        connectionJob?.cancel()
        synchronizationJob?.cancel()
        connecting = false
        synchronizing = false
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
        readyThreadIds.clear()
        running.clear()
        runningSince.clear()
        sending.clear()
        requests.clear()
        status = "已断开连接"
    }

    private fun upsert(thread: CodexThread) {
        if (threadReads.isArchived(thread.id)) return
        val named = threadNames[thread.id]
        val index = threads.indexOfFirst { it.id == thread.id }
        val previous = threads.getOrNull(index)
        val title = named?.takeIf { it.isNotBlank() }?.take(120) ?: thread.title
        val incoming = thread.copy(title = title)
        if (index < 0) threads.add(0, incoming) else threads[index] = incoming.copy(
            model = incoming.model.ifBlank { previous?.model.orEmpty() },
            cwd = incoming.cwd.ifBlank { previous?.cwd.orEmpty() },
            updatedAt = incoming.updatedAt.takeIf { it > 0L } ?: previous?.updatedAt ?: 0L)
    }

    private fun promoteThread(id: String) {
        val index = threads.indexOfFirst { it.id == id }
        if (index > 0) threads.add(0, threads.removeAt(index))
    }

    private fun applySnapshot(result: JSONObject, revision: Long? = null) {
        val thread = result.optJSONObject("thread") ?: return
        val row = parseThread(thread, result.text("model"))
        if (row.id.isBlank() || threadReads.isArchived(row.id)) return
        val applyActivity = revision == null || threadReads.accepts(row.id, revision)
        if (applyActivity && row.updatedAt > 0L) lastHydratedUpdatedAt[row.id] = row.updatedAt
        val old = messages[row.id].orEmpty()
        val history = parseMessages(thread)
        val merged = mergeHistory(history, old, latestPage = true,
            activeTurnId = if (applyActivity) row.turnId else running[row.id].orEmpty(), preferCurrent = !applyActivity)
        val hasServerTitle = thread.text("name").isNotBlank() || thread.text("title").isNotBlank()
        val firstPrompt = merged.firstOrNull { it.role == "user" && it.text.isNotBlank() }?.text.orEmpty()
        val previewMatchesFirstPrompt = row.id == activeId && row.turnId.isBlank() && !hasServerTitle &&
            firstPrompt.isNotBlank() && row.title == firstPrompt.replace(Regex("\\s+"), " ").trim().take(120)
        val displayRow = if (previewMatchesFirstPrompt) row.copy(title = "新对话") else row
        upsert(if (applyActivity) displayRow else displayRow.copy(running = row.id in running, turnId = running[row.id].orEmpty()))
        val wasRunning = row.id in running
        val previousTurnId = running[row.id]
        if (applyActivity && row.running) {
            running[row.id] = row.turnId
            runningSince.putIfAbsent(row.id, System.currentTimeMillis())
        } else if (applyActivity) {
            running.remove(row.id)
            runningSince.remove(row.id)
        }
        if (applyActivity && (wasRunning != (row.id in running) || previousTurnId != running[row.id])) threadReads.changed(row.id)
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
        if (applyActivity && activeId == row.id && displayRow.turnId.isBlank()) maybeGenerateThreadTitle(row.id)
        if (applyActivity) {
            (history.lastOrNull { it.turnId.isNotBlank() }?.turnId
                ?: old.lastOrNull { it.turnId.isNotBlank() }?.turnId)
                ?.let { latestTurnIds[row.id] = it }
            if (row.running && row.turnId.isNotBlank()) latestTurnIds[row.id] = row.turnId
        }
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
                val revision = threadReads.revision(id)
                val result = connection.readThread(id)
                if (client === connection) {
                    applySnapshot(result, revision)
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
        if (threadReads.isArchived(id)) return
        clearTaskStatus(id)
        if (maySelectThread()) settings.getString("thread.$id.nextModel", null)?.let { model = it }
        if (!drafts.containsKey(id)) drafts[id] = settings.getString("draft.$id", "").orEmpty()
        val connection = checkNotNull(client) { "SSH 已断开" }
        if (id in localEmptyThreads && id !in listedThreadIds) {
            if (id !in readyThreadIds) readyThreadIds += id
            if (maySelectThread()) activeId = id
            threadExecution[id] = savedExecution(id) ?: ExecutionOptions(cwd, defaultMode, defaultReasoningEffort)
            if (maySelectThread()) settings.edit().putString("lastThreadId", id).apply()
            return
        }
        // Queue state is secondary to opening the conversation. Fetch it in parallel
        // so a slow queue/list request does not hold up thread/resume and the chat UI.
        refreshQueueInBackground(id, connection)
        val saved = savedExecution(id)
        val revision = threadReads.revision(id)
        var result = try {
            connection.resumeThread(id, if (hasPendingExecution(id)) null else saved)
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
        if (client !== connection || !connected || threadReads.isArchived(id)) return
        if (saved != null && !hasPendingExecution(id) && actual != saved && threadReads.accepts(id, revision) &&
            id !in running && id !in sending && id !in queueSubmitting && !parseThread(result.getJSONObject("thread")).running) {
            result = connection.updateThreadSettings(id, saved)
        }
        if (client !== connection || !connected || threadReads.isArchived(id)) return
        if (id !in readyThreadIds) readyThreadIds += id
        applySnapshot(result, revision)
        threadExecution[id] = if (hasPendingExecution(id)) threadExecution[id] ?: savedExecution(id) ?: actual
            else executionOptions(result, saved ?: ExecutionOptions(cwd, defaultMode, defaultReasoningEffort))
        if (maySelectThread()) {
            activeId = id
            model = settings.getString("thread.$id.nextModel", null)
                ?: result.text("model").ifBlank { model }
            settings.edit().putString("lastThreadId", id).apply()
            if (connected) startNativeQueueIfIdle(id)
        }
    }

    fun openThread(id: String) {
        if (threadReads.isArchived(id)) return
        model = settings.getString("thread.$id.nextModel", null)
            ?: threads.firstOrNull { it.id == id }?.model?.takeIf { it.isNotBlank() } ?: model
        val selectionGeneration = ++threadSelectionGeneration
        threadOpenJob?.cancel()
        threadOpenJob = null
        if (!drafts.containsKey(id)) drafts[id] = settings.getString("draft.$id", "").orEmpty()
        bindPendingSharedFiles(id)
        clearTaskStatus(id)
        if (messages.containsKey(id)) {
            activeId = id
            openingThreadId = null
            threadOpenError = ""
            settings.edit().putString("lastThreadId", id).apply()
            if (connected) retryThreadSync(id, selectionGeneration)
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
        val openStartedAt = System.nanoTime()
        recordChatDiagnostic("thread_open_started", id, "", JSONObject()
            .put("cached", messages.containsKey(id)))
        threadOpenJob = viewModelScope.launch {
            try {
                loadThread(id, selectionGeneration)
                recordChatDiagnostic("thread_open_completed", id, "", JSONObject()
                    .put("elapsedMs", (System.nanoTime() - openStartedAt) / 1_000_000))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val message = e.message?.takeIf { it.isNotBlank() } ?: "加载对话失败"
                recordChatDiagnostic("thread_open_failed", id, "", JSONObject()
                    .put("elapsedMs", (System.nanoTime() - openStartedAt) / 1_000_000)
                    .put("reason", message.take(200)))
                uploadChatDiagnostic("thread_open_failed", id)
                if (openingThreadId == id && selectionGeneration == threadSelectionGeneration)
                    threadOpenError = message
            } finally {
                if (openingThreadId == id && selectionGeneration == threadSelectionGeneration) {
                    openingThreadId = null
                }
            }
        }
    }

    fun retryThreadSync(id: String) {
        retryThreadSync(id, threadSelectionGeneration)
    }

    private fun retryThreadSync(id: String, selectionGeneration: Int) {
        if (id in syncingThreadIds || openingThreadId == id) return
        val connection = client
        if (!connected || connection?.isAlive() != true) {
            recordChatDiagnostic("thread_sync_retry_reconnect", id, "")
            status = "连接已中断，正在重新连接…"
            connect(automatic = false)
            return
        }
        threadOpenJob?.cancel()
        syncingThreadIds += id
        error = ""
        status = "正在重新同步当前对话…"
        recordChatDiagnostic("thread_sync_retry_started", id, "")
        threadOpenJob = viewModelScope.launch {
            try {
                loadThread(id, selectionGeneration)
                recordChatDiagnostic("thread_sync_retry_completed", id, "")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val message = e.message?.takeIf { it.isNotBlank() } ?: "同步对话失败"
                recordChatDiagnostic("thread_sync_retry_failed", id, "", JSONObject().put("reason", message.take(200)))
                reportChatError(message, "同步对话失败", id)
            } finally {
                syncingThreadIds.remove(id)
            }
        }
    }

    fun newThread() {
        if (operationPending("新建对话", null)) return
        val selection = ++threadSelectionGeneration
        val options = ExecutionOptions(cwd, defaultMode, defaultReasoningEffort)
        val selectedModel = model
        operation("新建对话", null) { connection ->
            val result = connection.startThread(options, selectedModel)
            requireCurrent(connection)
            applySnapshot(result)
            val id = result.getJSONObject("thread").getString("id")
            if (id !in readyThreadIds) readyThreadIds += id
            localEmptyThreads += id
            localThreadIds += id
            settings.edit().putBoolean("thread.$id.localThread", true)
                .putBoolean("thread.$id.localEmpty", true)
                .putString("thread.$id.title", "新对话")
                .putString("thread.$id.model", selectedModel)
                .putString("thread.$id.nextModel", selectedModel)
                .putString("thread.$id.cwd", options.cwd).apply()
            rememberExecution(id, executionOptions(result, options))
            if (selection == threadSelectionGeneration) {
                activeId = id
                bindPendingSharedFiles(id)
                settings.edit().putString("lastThreadId", id).apply()
            }
        }
    }

    fun renameActiveThread(name: String) {
        val id = activeId ?: return
        val cleaned = name.trim().take(120)
        if (cleaned.isBlank() || cleaned == activeThread?.title) return
        operation("修改对话名称", id) { connection ->
            connection.renameThread(id, cleaned)
            requireCurrent(connection)
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
        actions.launch {
            try {
                val document = connection.readGlobalInstructions()
                check(client === connection && connected) { "SSH 连接已变更，请重新读取" }
                globalInstructionsDocument = document
                globalInstructionsDraft = document.content
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                globalInstructionsError = "读取失败：" + (e.message ?: "未知错误")
            } finally { if (client === connection) globalInstructionsBusy = false }
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
        actions.launch {
            try {
                check(client === connection && connected) { "SSH 连接已变更，请重新读取" }
                val actual = connection.saveGlobalInstructions(original, text)
                check(client === connection && connected) { "SSH 连接已变更，请重新读取以确认保存结果" }
                globalInstructionsDocument = actual
                if (globalInstructionsDraft == text) globalInstructionsDraft = actual.content
                globalInstructionsMessage = "已保存到服务器。请新建对话使用更新后的指令。"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                globalInstructionsError = "保存失败：" + (e.message ?: "未知错误")
            } finally { if (client === connection) globalInstructionsBusy = false }
        }
    }

    fun browseWorkspace(path: String = currentExecution.cwd) {
        val connection = client ?: run { workspaceError = "SSH 已断开，请重新连接后浏览"; return }
        val generation = ++workspaceReadGeneration
        workspaceReadJob?.cancel()
        workspaceBusy = true
        workspaceError = ""
        workspaceReadJob = actions.launch {
            try {
                val listing = connection.listDirectory(path)
                requireCurrent(connection)
                if (generation == workspaceReadGeneration) workspaceListing = listing
            }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == workspaceReadGeneration) workspaceError = "无法读取工作区：" + (e.message ?: "未知错误")
            }
            finally { if (generation == workspaceReadGeneration) workspaceBusy = false }
        }
    }

    fun showWorkspaceError(message: String) {
        workspaceError = message
        projectFilesError = message
    }

    fun createWorkspaceFolder(name: String, done: (String) -> Unit = {}) {
        val connection = client ?: return
        val parent = workspaceListing?.path ?: currentExecution.cwd
        if (workspaceCreating) return
        workspaceCreating = true
        workspaceError = ""
        val generation = workspaceReadGeneration
        actions.launch {
            try {
                val created = connection.createDirectory(parent, name.trim())
                requireCurrent(connection)
                if (generation == workspaceReadGeneration) {
                    workspaceReadGeneration++
                    workspaceReadJob?.cancel()
                    workspaceBusy = false
                    workspaceListing = created
                }
                done(created.path)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                workspaceError = "新建项目失败：" + (e.message ?: "未知错误")
            } finally { if (client === connection) workspaceCreating = false }
        }
    }

    fun saveExecution(options: ExecutionOptions, done: () -> Unit) {
        val connection = client ?: run { workspaceError = "SSH 已断开，请重新连接后保存"; return }
        val id = activeId
        val key = id ?: "__defaults__"
        if (key in executionSaving) return
        executionSaving += key
        workspaceError = ""
        actions.launch {
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
                    workspaceStatus = "已保存新对话的默认设置"
                } else {
                    check(!threadReads.isArchived(id)) { "该会话已归档，请选择其他会话" }
                    markExecutionPending(id, selected)
                    // Running turns retain their execution boundary. The next turn/queue start applies this choice.
                    workspaceStatus = "已保存，将在下一次发送或排队任务启动时生效"
                }
                done()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                workspaceError = "保存失败：" + (e.message ?: "未知错误")
            } finally { if (client === connection) executionSaving.remove(key) }
        }
    }

    fun reportChatError(value: String, stage: String, id: String? = activeId, turnId: String = "", eventId: String = "") {
        if (value.isBlank()) return
        if (id.isNullOrBlank()) { error = "$stage：$value"; return }
        val current = messages[id].orEmpty()
        if (current.any { it.role == "error" && it.stage == stage && it.turnId == turnId && it.text == value }) return
        messages[id] = upsertChat(current, chatFailure(current, value, stage, turnId, eventId))
    }

    fun importImages(images: List<IncomingImage>) {
        val id = activeId ?: return
        check(images.isNotEmpty()) { "请选择图片" }
        val current = imageAttachments[id].orEmpty()
        check(current.size + images.size <= 10) { "一次最多添加 10 张图片" }
        check(images.sumOf { it.bytes.size.toLong() } <= 30L * 1024 * 1024) { "本次所选图片总大小需小于 30 MB" }
        val additions = images.map { image ->
            check(image.bytes.isNotEmpty() && image.bytes.size <= 10 * 1024 * 1024) { "每张图片需小于 10 MB" }
            check(image.mimeType.startsWith("image/")) { "请选择图片文件" }
            val encoded = Base64.encodeToString(image.bytes, Base64.NO_WRAP)
            ImageAttachment("data:${image.mimeType};base64,$encoded", image.label.take(80))
        }
        val estimatedTotalBytes = (current + additions).sumOf { attachment ->
            attachment.dataUrl.substringAfter(',', "").length * 3L / 4L
        }
        check(estimatedTotalBytes <= 30L * 1024 * 1024) { "本次图片总大小需小于 30 MB" }
        imageAttachments[id] = current + additions
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
        rows.getOrNull(index)?.file?.let { file ->
            if (submittingComposers.values.none { pending -> pending.files.any { it.file == file } }) file.delete()
        }
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
        loadProjectFiles(path)
    }

    fun loadGitDiff() {
        val connection = client ?: return
        val path = currentExecution.cwd
        val generation = ++gitDiffGeneration
        gitDiffJob?.cancel()
        gitDiffBusy = true
        gitDiffError = ""
        gitDiff = ""
        gitDiffJob = actions.launch {
            try {
                val diff = connection.readGitDiff(path)
                requireCurrent(connection)
                if (generation == gitDiffGeneration) gitDiff = diff
            }
            catch (e: Exception) { if (e !is CancellationException && generation == gitDiffGeneration) gitDiffError = e.message.orEmpty() }
            finally { if (generation == gitDiffGeneration) gitDiffBusy = false }
        }
    }

    fun loadProjectFiles(path: String = currentExecution.cwd) {
        val connection = client ?: run { projectFilesError = "SSH 已断开，请重新连接后浏览"; return }
        val generation = ++projectFilesGeneration
        projectFilesJob?.cancel()
        fileBrowserPath = path
        projectFilesBusy = true
        projectFilesError = ""
        projectFilesJob = actions.launch {
            try {
                val listing = connection.listDirectory(path)
                requireCurrent(connection)
                if (generation == projectFilesGeneration) {
                    projectFilesListing = listing
                    fileBrowserPath = listing.path
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == projectFilesGeneration) projectFilesError = e.message.orEmpty()
            } finally { if (generation == projectFilesGeneration) projectFilesBusy = false }
        }
    }

    fun clearDownloadedWorkspaceFile() {
        downloadedWorkspaceFile?.delete()
        downloadedWorkspaceFile = null
        downloadedWorkspaceFileName = ""
    }

    fun removeImage(index: Int? = null) {
        val id = activeId ?: return
        if (index == null) imageAttachments.remove(id)
        else {
            val remaining = imageAttachments[id].orEmpty().filterIndexed { itemIndex, _ -> itemIndex != index }
            if (remaining.isEmpty()) imageAttachments.remove(id) else imageAttachments[id] = remaining
        }
    }

    suspend fun cacheWorkspaceImage(path: String, baseDirectory: String): String = withContext(Dispatchers.IO) {
        val connection = client ?: error("SSH 已断开，无法读取服务器图片")
        val folder = File(getApplication<Application>().cacheDir, "markdown-images").apply { mkdirs() }
        connection.cacheWorkspaceImage(path, baseDirectory, folder).toURI().toString()
    }

    suspend fun cacheWorkspaceVideo(path: String, baseDirectory: String): File = withContext(Dispatchers.IO) {
        val folder = File(getApplication<Application>().cacheDir, "workspace-videos").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-256").digest((baseDirectory + "\n" + path).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val target = File(folder, "$key.${path.substringAfterLast('.', "mp4").lowercase()}")
        if (target.isFile && target.length() > 0L) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }
        val download = workspaceVideoJobs[key] ?: run {
            val created = viewModelScope.async(Dispatchers.IO) {
                if (target.isFile && target.length() > 0L) return@async target
                val connection = client ?: error("SSH 已断开，无法读取服务器视频")
                val staged = File(folder, "$key-${java.util.UUID.randomUUID()}.tmp")
                try {
                    connection.downloadWorkspaceVideo(path, baseDirectory, staged)
                    check(staged.renameTo(target)) { "无法缓存服务器视频" }
                    CacheMaintenance.trim(getApplication<Application>().cacheDir)
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

    fun refresh() = operation("刷新对话", null) { connection ->
        val selectedId = activeId
        val revisions = threadReads.snapshot()
        val rows = connection.listThreads()
        if (client !== connection || !connected) return@operation
        replaceThreads(rows, revisions = revisions)
        updateRunningThreads(rows, revisions)
        selectedId?.takeIf { it in listedThreadIds }?.let {
            val revision = threadReads.revision(it)
            val result = connection.readThread(it)
            requireCurrent(connection)
            applySnapshot(result, revision)
        }
    }

    private fun replaceThreads(rows: List<CodexThread>, partial: Boolean = false, revisions: Map<String, Long>) {
        val previousUpdatedAt = threads.associate { it.id to it.updatedAt }
        val mergedRows = rows.filterNot { threadReads.isArchived(it.id) }.map { row ->
            val incoming = row.copy(updatedAt = row.updatedAt.takeIf { it > 0L } ?: previousUpdatedAt[row.id] ?: 0L)
            if (threadReads.accepts(row.id, revisions[row.id] ?: 0L)) incoming
            else incoming.copy(running = row.id in running, turnId = running[row.id].orEmpty())
        }
        val serverIds = mergedRows.mapTo(mutableSetOf()) { it.id }
        listedThreadIds = if (partial) listedThreadIds + serverIds else serverIds
        val retainedRows = threads.filter { it.id !in serverIds && !threadReads.isArchived(it.id) &&
            (partial || !threadReads.accepts(it.id, revisions[it.id] ?: 0L)) }
        val prefs = settings.all
        val localIds = prefs.keys.asSequence()
            .filter { it.startsWith("thread.") && it.endsWith(".localThread") }
            .filter { prefs[it] == true }
            .map { it.removePrefix("thread.").removeSuffix(".localThread") }
            .filterNot { threadReads.isArchived(it) }
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
        threads.addAll((mergedRows + retainedRows + localRows).distinctBy { it.id })
    }

    fun onForeground() {
        inForeground = true
        foregroundStartedAt = System.nanoTime()
        foregroundGeneration++
        if (connectionJob?.isActive == true) return
        if (!connected) {
            val retained = ConnectionKeepAliveService.currentClient()
            if (retained != null) {
                client = retained
                retained.setServerMessageHandler { event ->
                    viewModelScope.launch { if (client === retained) handleEvent(event) }
                }
                connected = true
                connecting = false
                status = "已通过 SSH 连接到 82"
                synchronizeInBackground(retained, restoreSubscription = true)
                flushPendingChatDiagnostics()
                checkForUpdate()
                return
            }
        }
        val connection = client
        if (connected && (connection == null || !connection.isAlive())) {
            synchronizationJob?.cancel()
            synchronizing = false
            connection?.let {
                ConnectionKeepAliveService.clear(it)
                it.close()
            }
            client = null
            connected = false
            sending.clear()
            requests.clear()
            status = "正在后台恢复连接，可继续查看和编辑"
        } else if (connected && connection != null) {
            // Returning from a picker or a brief app switch should not refresh every page
            // or lock the UI. Keep the existing subscription and reconcile in the background.
            if (lastForegroundSyncAt == Long.MIN_VALUE || connectionTime() - lastForegroundSyncAt >= 15_000) {
                synchronizeInBackground(connection)
            }
            checkForUpdate()
            return
        }
        if (reconnectOnForeground) {
            if (recovery.delayMillis(connectionTime()) > 0) scheduleReconnect()
            else connect(preserveUpdateState = true, automatic = true)
        }
    }

    fun recordScreenFrame(screen: String) {
        val elapsed = (System.nanoTime() - foregroundStartedAt) / 1_000_000
        val currentClient = client
        val screenName = screen.substringBefore(':')
        android.util.Log.d("AndriConnection", "[${currentClient?.diagnosticId ?: "cache"}] screen=$screenName " +
            "frame-ready=${elapsed}ms connected=$connected threads=${threads.size}")
        if (connected && currentClient != null) {
            currentClient.recordConnectionTiming("screen-$screenName", elapsed,
                JSONObject().put("threadCount", threads.size))
            if (uploadedConnectionTimingId != currentClient.diagnosticId) {
                uploadedConnectionTimingId = currentClient.diagnosticId
                val report = currentClient.connectionTimingReport()
                viewModelScope.launch {
                    runCatching { currentClient.reportDiagnostic(report) }
                        .onFailure {
                            if (uploadedConnectionTimingId == currentClient.diagnosticId) uploadedConnectionTimingId = null
                            android.util.Log.w("MobileDiagnostics", "Unable to upload connection timing", it)
                        }
                }
            }
        }
    }

    fun onBackground() {
        inForeground = false
        if (!ConnectionKeepAliveService.isActive()) {
            reconnectJob?.cancel()
            reconnectJob = null
        }
        // The process session and foreground service continue recovery after the UI stops.
    }

    fun openHistory() {
        historyDialog = true
        refreshHistory()
    }

    private fun runHistoryAction(action: suspend (SshCodexClient) -> Unit) {
        if (historyBusy) return
        val validPort = port.toIntOrNull()
        if (privateKey.isBlank() || validPort == null) {
            historyError = "请先导入 SSH 私钥并填写有效连接配置"
            return
        }
        historyBusy = true
        historyError = ""
        historyMessage = "正在通过独立 SSH 读取版本，不需要连接 Codex…"
        val connection = SshCodexClient(host.trim(), validPort, username.trim(), privateKey, passphrase) {}
        historyClient = connection
        historyJob = viewModelScope.launch {
            try {
                awaitConnectionAttempt(abort = connection::close) { connection.connectSshOnly() }
                kotlinx.coroutines.withTimeout(180_000) { action(connection) }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                historyError = if (e is TimeoutCancellationException) "版本操作超时，请检查网络后重试" else e.message ?: "版本操作失败"
                historyMessage = ""
            } finally {
                if (historyClient === connection) {
                    historyClient = null
                    historyBusy = false
                }
                viewModelScope.launch(Dispatchers.IO) { connection.close() }
            }
        }
    }

    fun refreshHistory() = runHistoryAction { connection ->
        historyReleases = connection.readReleaseHistory()
        historyMessage = "共 " + historyReleases.size + " 个可下载版本"
    }

    fun downloadHistory(info: UpdateInfo) = if (historyExportBusy) Unit else runHistoryAction { connection ->
        historicalDownload?.file?.delete()
        historicalDownload = null
        historyMessage = "正在下载 " + info.versionName + "…"
        historicalDownload = downloadHistoricalRelease(
            File(getApplication<Application>().cacheDir, "updates"), info,
            readHistory = { connection.readReleaseHistory() },
            download = { release, file -> withContext(Dispatchers.IO) {
                file.outputStream().use { connection.downloadUpdate(release, it) }
            } },
            verify = { file, release -> verifyDownloadedUpdate(file, release, allowHistorical = true) }
        )
        historyMessage = info.versionName + " 下载完成，已验证包名、版本、大小、SHA-256 和签名"
    }

    fun installHistory(install: (File) -> Unit) {
        if (historyBusy) return
        val downloaded = historicalDownload ?: return
        historyBusy = true
        historyError = ""
        historyJob = viewModelScope.launch {
            try {
                // A verified local historical package remains usable when the server is unavailable.
                verifyDownloadedUpdate(downloaded.file, downloaded.info, allowHistorical = true)
                install(downloaded.file)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                historyError = e.message ?: "无法打开历史包"
            } finally { historyBusy = false }
        }
    }

    fun dismissHistory() {
        historyJob?.cancel()
        historyJob = null
        historyClient?.let { connection -> viewModelScope.launch(Dispatchers.IO) { connection.close() } }
        historyClient = null
        historyBusy = false
        historyDialog = false
    }

    internal fun exportHistory(uri: Uri, downloaded: DownloadedUpdate) {
        if (historyExportBusy) return
        historyExportBusy = true
        historyExportMessage = "正在保存安装包，可关闭窗口继续使用"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    downloaded.file.inputStream().use { input ->
                        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                            ?: error("无法保存到所选位置")
                    }
                }
                historyExportMessage = "安装包已保存到所选位置"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                historyExportMessage = "保存失败：" + e.message
            } finally { historyExportBusy = false }
        }
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
                    verify = { file, info -> verifyDownloadedUpdate(file, info) }
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
                    verify = { file, info -> verifyDownloadedUpdate(file, info) }, install = install)
                if (!installed && updateInfo != null) updateMessage = "版本已更新，请先下载 " + updateInfo!!.versionName
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (client === connection) failUpdate("更新安装检查失败：", e)
            } finally { if (client === connection) updateBusy = false }
        }
    }

    private suspend fun verifyDownloadedUpdate(file: File, info: UpdateInfo, allowHistorical: Boolean = false) {
        withContext(Dispatchers.IO) {
            check(file.length() == info.sizeBytes) { "更新包下载不完整" }
            check(sha256(file).equals(info.sha256, ignoreCase = true)) { "更新包校验失败" }
        }
        verifyUpdateApk(getApplication(), file, info, allowHistorical)
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
        operation("加载历史", id) { connection ->
            val page = connection.listTurns(id, cursor)
            requireCurrent(connection)
            if (threadReads.isArchived(id)) return@operation
            val history = parseMessages(JSONObject().put("turns", chronologicalTurns(page)))
            messages[id] = mergeHistory(history, messages[id].orEmpty())
            historyCursors[id] = page.text("nextCursor")
        }
    }

    private fun operation(stage: String = "操作", targetId: String? = activeId, block: suspend (SshCodexClient) -> Unit) {
        val key = "$stage:${targetId.orEmpty()}"
        if (key in operations) return
        if (!connected) {
            error = "连接恢复后可执行此操作；现在可以查看对话和编辑草稿"
            return
        }
        val connection = client ?: return
        val token = Any()
        operations[key] = token
        error = ""
        actions.launch {
            try { block(connection) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                if (client === connection) reportChatError(e.message ?: "未知错误", stage + "失败", targetId)
            } finally { if (operations[key] === token) operations.remove(key) }
        }
    }

    fun send() {
        val id = activeId ?: return
        val rawPrompt = drafts[id].orEmpty().trim()
        val prompt = rawPrompt
        val attachments = imageAttachments[id].orEmpty()
        val video = videoAttachments[id]
        val sharedFiles = sharedFileAttachments[id].orEmpty()
        val references = contextReferences[id].orEmpty()
        val connection = client ?: return
        if (!canSendToThread(id) || (rawPrompt.isBlank() && attachments.isEmpty() && video == null && sharedFiles.isEmpty() && references.isEmpty()) || id in sending || id in queueSubmitting) return
        if (id in running) { enqueueDraft(); return }
        val selectedModel = model
        sending += id
        mobileTurnStartedDuringSend.remove(id)
        error = ""
        val options = threadExecution[id] ?: savedExecution(id) ?: currentExecution
        val submission = captureComposer(id)
        actions.launch {
            try {
                val baseDirectory = options.cwd
                val remoteImages = connection.uploadImages(attachments.map { ImageUpload(it.dataUrl, it.label) }, baseDirectory)
                val videoPath = video?.let { connection.uploadWorkspaceVideo(it.file, it.label, baseDirectory) }
                val remoteFiles = sharedFiles.map { file ->
                    connection.uploadSharedFile(file.file, file.label, baseDirectory)
                }
                val fallback = when {
                    attachments.isNotEmpty() -> "请查看我发送的${attachments.size}张图片。"
                    video != null -> "请查看这个视频。"
                    sharedFiles.isNotEmpty() -> "请阅读我分享的文件并按内容处理。"
                    else -> "请根据我选定的上下文完成任务。"
                }
                val composedPrompt = listOf(prompt.ifBlank { fallback }, videoPath?.let { "视频附件（原画质文件）：$it" },
                    remoteImages.takeIf { it.isNotEmpty() }?.let { "手机图片（已保存至项目 codex/inbox）：\n" + it.joinToString("\n") },
                    remoteFiles.takeIf { it.isNotEmpty() }?.let { "手机分享的文件（位于项目 codex/inbox）：\n" + it.joinToString("\n") },
                    references.takeIf { it.isNotEmpty() }?.joinToString("\n\n") { "【上下文：${it.label}】\n${it.prompt}" })
                    .filterNotNull().filter(String::isNotBlank).joinToString("\n")
                recordChatDiagnostic("composer.turn.start.request", id, "", JSONObject()
                    .put("imageCount", attachments.size).put("hasVideo", video != null)
                    .put("sharedFileCount", sharedFiles.size).put("contextCount", references.size))
                startingComposers += id
                val startRevision = threadReads.revision(id)
                val turnId = connection.sendTurn(id, selectedModel, composedPrompt, attachments.map { it.dataUrl }, options)
                requireCurrent(connection)
                executionApplied(id, options)
                recordChatDiagnostic("composer.turn.start.accepted", id, turnId)
                TaskRecoveryScheduler.enqueue(getApplication(), id, baseDirectory)
                confirmStartedTurn(id, turnId, startRevision)
                promoteThread(id)
                clearComposerAfterAcceptance(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (id in mobileTurnStartedDuringSend) {
                    recordChatDiagnostic("composer.turn.start.response_missing_but_confirmed", id, running[id].orEmpty(),
                        JSONObject().put("reason", e.message.orEmpty().take(160)))
                    clearComposerAfterAcceptance(id)
                } else {
                    reportChatError(e.message ?: "未知错误", "发送消息失败", id)
                }
            } finally {
                releaseComposer(id, submission)
                if (client === connection) {
                    sending.remove(id)
                    mobileTurnStartedDuringSend.remove(id)
                    startingComposers.remove(id)
                    if (id !in running) startNativeQueueIfIdle(id)
                }
            }
        }
    }

    private fun confirmStartedTurn(id: String, turnId: String, startRevision: Long) {
        // A short turn may finish before the response. Newer notifications own its state.
        if (turnId.isNotBlank() && threadReads.accepts(id, startRevision)) {
            threadReads.changed(id)
            running[id] = turnId
            runningSince.putIfAbsent(id, System.currentTimeMillis())
            latestTurnIds[id] = turnId
        } else if (id in running && running[id].isNullOrBlank()) {
            running[id] = turnId
        }
    }

    private fun clearComposerAfterAcceptance(id: String) {
        val submitted = submittingComposers[id] ?: return
        drafts[id] = acceptedDraftText(drafts[id].orEmpty(), draftRevisions[id] ?: 0, submitted.revision)
        settings.edit().putString("draft.$id", drafts[id]).apply()
        localEmptyThreads.remove(id)
        settings.edit().remove("thread.$id.localEmpty").apply()
        imageAttachments[id] = unsentItems(imageAttachments[id].orEmpty(), submitted.images)
        if (videoAttachments[id] === submitted.video) videoAttachments.remove(id)
        sharedFileAttachments[id] = unsentItems(sharedFileAttachments[id].orEmpty(), submitted.files)
        persistSharedFiles()
        contextReferences[id] = unsentItems(contextReferences[id].orEmpty(), submitted.references)
        persistContextReferences()
    }

    private fun captureComposer(id: String): ComposerSubmission = ComposerSubmission(
        draftRevisions[id] ?: 0, imageAttachments[id].orEmpty().toList(), videoAttachments[id],
        sharedFileAttachments[id].orEmpty().toList(), contextReferences[id].orEmpty().toList()
    ).also { submittingComposers[id] = it }

    private fun releaseComposer(id: String, submitted: ComposerSubmission) {
        if (submittingComposers[id] === submitted) submittingComposers.remove(id)
        submitted.files.filter { old -> sharedFileAttachments.values.none { files -> files.any { it.file == old.file } } }
            .forEach { old ->
                if (submittingComposers.values.none { pending -> pending.files.any { it.file == old.file } }) old.file.delete()
            }
    }

    fun updateDraft(id: String, value: String) {
        draftRevisions[id] = (draftRevisions[id] ?: 0) + 1
        drafts[id] = value
        settings.edit().putString("draft.$id", value).apply()
    }

    fun enqueueDraft() {
        val id = activeId ?: return
        val raw = drafts[id].orEmpty().trim()
        val attachments = imageAttachments[id].orEmpty()
        val video = videoAttachments[id]
        val sharedFiles = sharedFileAttachments[id].orEmpty()
        val references = contextReferences[id].orEmpty()
        val connection = client ?: return
        if (!canSendToThread(id) || id !in running || (raw.isBlank() && attachments.isEmpty() && video == null && sharedFiles.isEmpty() && references.isEmpty()) || id in sending) return
        sending += id
        val selectedModel = model
        val options = currentExecution
        val submission = captureComposer(id)
        actions.launch {
            try {
                val baseDirectory = options.cwd
                val remoteImages = connection.uploadImages(attachments.map { ImageUpload(it.dataUrl, it.label) }, baseDirectory)
                val videoPath = video?.let { connection.uploadWorkspaceVideo(it.file, it.label, baseDirectory) }
                val remoteFiles = sharedFiles.map { file ->
                    connection.uploadSharedFile(file.file, file.label, baseDirectory)
                }
                val fallback = when {
                    attachments.isNotEmpty() -> "请查看我发送的${attachments.size}张图片。"
                    video != null -> "请查看这个视频。"
                    sharedFiles.isNotEmpty() -> "请阅读我分享的文件并按内容处理。"
                    else -> "请根据我选定的上下文完成任务。"
                }
                val queuedPrompt = listOf(raw.ifBlank { fallback }, videoPath?.let { "视频附件（原画质文件）：$it" },
                    remoteImages.takeIf { it.isNotEmpty() }?.let { "手机图片（已保存至项目 codex/inbox）：\n" + it.joinToString("\n") },
                    remoteFiles.takeIf { it.isNotEmpty() }?.let { "手机分享的文件（位于项目 codex/inbox）：\n" + it.joinToString("\n") },
                    references.takeIf { it.isNotEmpty() }?.joinToString("\n\n") { "【上下文：${it.label}】\n${it.prompt}" })
                    .filterNotNull().filter(String::isNotBlank).joinToString("\n")
                recordChatDiagnostic("composer.queue.add.request", id, "", JSONObject()
                    .put("imageCount", attachments.size).put("hasVideo", video != null)
                    .put("sharedFileCount", sharedFiles.size).put("contextCount", references.size))
                val added = withQueue(id, connection) {
                    connection.addQueuedSubmission(id, queuedPrompt, attachments.map { it.dataUrl })
                }
                requireCurrent(connection)
                settings.edit().putString("queue.${added.id}.execution", options.params().put("effort", options.effort).put("model", selectedModel).toString()).apply()
                allowQueueAutoStart(id, added.id)
                recordChatDiagnostic("composer.queue.add.accepted", id, "", JSONObject()
                    .put("queuedSubmissionId", added.id).put("clientUserMessageId", added.clientUserMessageId))
                val updated = queuedTasks[id].orEmpty().filterNot { it.id == added.id } +
                    QueuedTask(added.id, added.clientUserMessageId, added.text, autoStartAllowed = true)
                queuedTasks[id] = updated
                clearComposerAfterAcceptance(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reportChatError(e.message ?: "Codex 原生队列请求失败", "加入排队失败", id)
            } finally {
                releaseComposer(id, submission)
                if (client === connection) {
                    sending.remove(id)
                    if (id !in running) startNativeQueueIfIdle(id)
                }
            }
        }
    }

    fun removeQueuedTask(taskId: String) {
        val id = activeId ?: return
        val connection = client ?: return
        if (taskId in removingQueuedTasks) return
        removingQueuedTasks += taskId
        actions.launch {
            try {
                withQueue(id, connection) {
                    connection.deleteQueuedSubmission(id, taskId)
                    requireCurrent(connection)
                    consumeQueueAutoStart(id, taskId)
                    settings.edit().remove("queue.$taskId.execution").apply()
                    refreshQueue(id, connection)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reportChatError(e.message ?: "Codex 原生队列删除失败", "移除排队消息失败", id)
            } finally {
                if (client === connection) {
                    removingQueuedTasks.remove(taskId)
                    if (id !in running) startNativeQueueIfIdle(id)
                }
            }
        }
    }

    private suspend fun <T> withQueue(id: String, connection: SshCodexClient, block: suspend () -> T): T =
        queueLocks.getOrPut(id) { Mutex() }.withLock {
            requireCurrent(connection)
            if (id !in queueSubmitting) queueSubmitting += id
            try { block() }
            finally { if (client === connection) queueSubmitting.remove(id) }
        }

    private suspend fun prepareQueuedExecution(id: String, taskId: String, connection: SshCodexClient) {
        val stored = settings.getString("queue.$taskId.execution", null)?.let(::JSONObject)
        val fallback = threadExecution[id] ?: savedExecution(id) ?: ExecutionOptions(cwd, defaultMode, defaultReasoningEffort)
        val options = if (stored != null) executionOptions(stored, fallback) else fallback
        val selectedModel = stored?.text("model")?.takeIf { it.isNotBlank() }
            ?: settings.getString("thread.$id.nextModel", null) ?: threads.firstOrNull { it.id == id }?.model ?: model
        connection.updateThreadSettings(id, options, selectedModel)
        requireCurrent(connection)
        executionApplied(id, options)
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
        if (!canSendToThread(id) || id in running || id in sending || id in queueSubmitting) return
        val connection = client ?: return
        queueSubmitting += id
        actions.launch {
            try {
                withQueue(id, connection) {
                    val queued = connection.listQueuedSubmissions(id)
                    requireCurrent(connection)
                    if (id in running || id in sending) return@withQueue
                    queuedTasks[id] = queued.map { QueuedTask(it.id, it.clientUserMessageId, it.text,
                        autoStartAllowed = it.id in autoStartQueueIds(id)) }
                    val next = queued.firstOrNull() ?: return@withQueue
                    if (next.id !in autoStartQueueIds(id)) {
                        recordChatDiagnostic("queue.held_for_confirmation", id, "", JSONObject()
                            .put("queuedSubmissionId", next.id).put("clientUserMessageId", next.clientUserMessageId))
                        uploadChatDiagnostic("unrecognized_queued_submission", id)
                        return@withQueue
                    }
                    prepareQueuedExecution(id, next.id, connection)
                    check(id !in running && id !in sending) { "当前对话正在提交或执行任务，请稍后启动队列" }
                    recordChatDiagnostic("queue.start.request", id, "", JSONObject()
                        .put("queuedSubmissionId", next.id).put("clientUserMessageId", next.clientUserMessageId))
                    val startRevision = threadReads.revision(id)
                    val turnId = connection.startQueuedSubmission(id, next.id)
                    requireCurrent(connection)
                    confirmStartedTurn(id, turnId, startRevision)
                    check(turnId.isNotBlank()) { "Codex 没有返回排队任务的回合 ID" }
                    TaskRecoveryScheduler.enqueue(getApplication(), id, threads.firstOrNull { it.id == id }?.cwd ?: cwd)
                    consumeQueueAutoStart(id, next.id)
                    settings.edit().remove("queue.${next.id}.execution").apply()
                    recordChatDiagnostic("queue.start.accepted", id, "", JSONObject().put("queuedSubmissionId", next.id))
                    refreshQueue(id, connection)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reportChatError(e.message ?: "Codex 无法启动排队消息", "启动原生队列失败", id)
            } finally { if (client === connection) queueSubmitting.remove(id) }
        }
    }

    fun startQueuedTask(taskId: String) {
        val id = activeId ?: return
        val connection = client ?: return
        if (!canSendToThread(id) || id in running || id in sending || id in queueSubmitting) return
        queueSubmitting += id
        actions.launch {
            try {
                withQueue(id, connection) {
                    val queue = connection.listQueuedSubmissions(id)
                    requireCurrent(connection)
                    check(id !in running && id !in sending) { "当前对话正在执行或提交任务" }
                    check(queue.firstOrNull()?.id == taskId) { "请先处理排在前面的消息，保持队列顺序" }
                    prepareQueuedExecution(id, taskId, connection)
                    check(id !in running && id !in sending) { "当前对话正在执行或提交任务" }
                    recordChatDiagnostic("queue.start.manual.request", id, "", JSONObject()
                        .put("queuedSubmissionId", taskId)
                        .put("clientUserMessageId", queue.first().clientUserMessageId))
                    val startRevision = threadReads.revision(id)
                    val turnId = connection.startQueuedSubmission(id, taskId)
                    requireCurrent(connection)
                    confirmStartedTurn(id, turnId, startRevision)
                    check(turnId.isNotBlank()) { "Codex 没有返回排队任务的回合 ID" }
                    TaskRecoveryScheduler.enqueue(getApplication(), id, threads.firstOrNull { it.id == id }?.cwd ?: cwd)
                    consumeQueueAutoStart(id, taskId)
                    settings.edit().remove("queue.$taskId.execution").apply()
                    recordChatDiagnostic("queue.start.manual.accepted", id, turnId,
                        JSONObject().put("queuedSubmissionId", taskId))
                    refreshQueue(id, connection)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reportChatError(e.message ?: "Codex 无法启动排队消息", "启动排队消息失败", id)
            } finally { if (client === connection) queueSubmitting.remove(id) }
        }
    }

    fun stop() {
        val id = activeId ?: return
        operation("停止任务", id) { connection ->
            if (running[id].isNullOrBlank()) {
                val revision = threadReads.revision(id)
                val result = connection.readThread(id)
                requireCurrent(connection)
                applySnapshot(result, revision)
            }
            val turnId = running[id].orEmpty()
            check(turnId.isNotBlank()) { "没有找到运行中的回合，请刷新" }
            connection.interrupt(id, turnId)
        }
    }

    private fun flushAgentDeltas(threadId: String, cancelTimer: Boolean = true) {
        val flushJob = agentDeltaFlushJobs.remove(threadId)
        if (cancelTimer) flushJob?.cancel()
        val pending = bufferedAgentDeltas.remove(threadId) ?: return
        var changed = false
        pending.forEach { (itemId, delta) ->
            if (delta.text.isEmpty()) return@forEach
            val current = messages[threadId].orEmpty()
            val updated = applyChatNotification(current, "item/agentMessage/delta", JSONObject()
                .put("itemId", itemId).put("turnId", delta.turnId).put("delta", delta.text.toString()))
            if (updated !== current) {
                messages[threadId] = updated
                changed = true
            }
        }
        if (changed) promoteThread(threadId)
    }

    private fun flushAllAgentDeltas() {
        (bufferedAgentDeltas.keys + agentDeltaFlushJobs.keys).toSet().forEach(::flushAgentDeltas)
    }

    private fun handleEvent(event: JSONObject) {
        if (event.has("localError")) {
            resetActions()
            flushAllAgentDeltas()
            (running.keys.toList() + listOfNotNull(activeId)).distinct().forEach { target ->
                reportChatError(event.text("localError"), "SSH 连接中断", target, running[target].orEmpty(), "connectionError:$target")
            }
            cancelUpdates()
            synchronizationJob?.cancel()
            synchronizing = false
            connecting = false
            client?.let { ConnectionKeepAliveService.clear(it) }
            client?.close()
            client = null
            connected = false
            readyThreadIds.clear()
            running.clear()
            runningSince.clear()
            sending.clear()
            requests.clear()
            status = "SSH 已断开，正在自动恢复连接"
            error = event.text("localError")
            scheduleReconnect()
            return
        }
        val method = event.text("method")
        val params = event.optJSONObject("params") ?: JSONObject()
        val id = params.text("threadId")
        if (threadReads.isArchived(id)) return
        val eventTurnId = params.text("turnId").ifBlank {
            if (method == "turn/started" || method == "turn/completed")
                params.optJSONObject("turn")?.text("id").orEmpty()
            else ""
        }
        val eventItemId = params.text("itemId").ifBlank { params.optJSONObject("item")?.text("id").orEmpty() }
        if (method != "item/agentMessage/delta" && id.isNotBlank()) flushAgentDeltas(id)
        if (id.isNotBlank() && method != "item/agentMessage/delta" &&
            (method.startsWith("item/") || method.startsWith("turn/") || method == "error" ||
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
        if (id.isNotBlank() && method in setOf("turn/started", "turn/completed", "thread/status/changed")) {
            threadReads.changed(id)
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
        if (method == "item/agentMessage/delta" && id.isNotBlank() && eventItemId.isNotBlank()) {
            val pending = bufferedAgentDeltas.getOrPut(id) { linkedMapOf() }
            val delta = pending[eventItemId]
            if (delta == null) pending[eventItemId] = BufferedAgentDelta(eventTurnId, StringBuilder(params.text("delta")))
            else delta.text.append(params.text("delta"))
            if (agentDeltaFlushJobs[id]?.isActive != true) {
                agentDeltaFlushJobs[id] = viewModelScope.launch {
                    delay(32)
                    flushAgentDeltas(id, cancelTimer = false)
                }
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
                requestAnswers.remove(requestId)
                requestErrors.remove(requestId)
                if (visibleRequestId == requestId) visibleRequestId = null
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
                if (!hasPendingExecution(id)) threadExecution[id] = executionOptions(updated, fallback)
            }
            "thread/status/changed" -> if (params.optJSONObject("status")?.text("type") == "active") {
                if (id !in running) {
                    running[id] = ""
                    runningSince.putIfAbsent(id, System.currentTimeMillis())
                    syncThreadTimeline(id)
                }
            } else if (running[id].isNullOrBlank()) {
                running.remove(id)
                runningSince.remove(id)
            }
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
                if (id in startingComposers && hasRequest) {
                    mobileTurnStartedDuringSend += id
                    recordChatDiagnostic("composer.turn.start.confirmed_by_event", id, turnId)
                    clearComposerAfterAcceptance(id)
                }
                running[id] = turnId
                runningSince.putIfAbsent(id, System.currentTimeMillis())
                if (turnId.isNotBlank()) latestTurnIds[id] = turnId
                TaskRecoveryScheduler.enqueue(getApplication(), id, threads.firstOrNull { it.id == id }?.cwd ?: cwd)
                if (!hasRequest) uploadChatDiagnostic("turn_started_without_mobile_request", id)
                promoteThread(id)
                clearTaskStatus(id)
            }
            "turn/completed" -> {
                val stage = activity[id]
                recordChatDiagnostic("turn.completed", id, params.optJSONObject("turn")?.text("id").orEmpty(),
                    JSONObject().put("status", params.optJSONObject("turn")?.text("status").orEmpty()))
                running.remove(id)
                runningSince.remove(id)
                activity.remove(id)
                val turn = params.optJSONObject("turn")
                val failed = turn?.text("status") == "failed"
                val status = turn?.text("status").orEmpty()
                val completedTurnId = turn?.text("id").orEmpty()
                TaskRecoveryScheduler.enqueue(getApplication(), id, threads.firstOrNull { it.id == id }?.cwd ?: cwd, delaySeconds = 0)
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
        val requestId = request.get("id").toString()
        if (requestId in respondingRequests || requests.none { it.get("id").toString() == requestId }) return
        val connection = client ?: return
        respondingRequests += requestId
        requestErrors.remove(requestId)
        actions.launch {
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
                requireCurrent(connection)
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
                requestAnswers.remove(requestId)
                requestErrors.remove(requestId)
                if (visibleRequestId == requestId) visibleRequestId = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                requestErrors[requestId] = "回复服务器请求失败：" + e.message
                reportChatError(e.message.orEmpty(), "回复审批失败", request.optJSONObject("params")?.text("threadId"))
            } finally { if (client === connection) respondingRequests.remove(requestId) }
        }
    }

    fun sendQuestionAnswers(line: ChatLine, answers: List<String>, skip: Boolean = false) {
        val id = activeId ?: return
        val connection = client ?: return
        if (!canSendToThread(id) || id in sending || id in queueSubmitting || line.id in answeredQuestionItems ||
            answers.size != line.questions.size || (!skip && answers.any { it.isBlank() })) return
        val current = messages[id].orEmpty()
        val index = current.indexOfFirst { it.id == line.id }
        if (index < 0 || current.drop(index + 1).any { it.role == "user" }) return
        val prompt = if (skip) "先根据现有信息出一版草稿；尚未确定的问题请明确标注，不要替我作出关键决定。\n" + line.questions.joinToString("\n") { it.title }
            else line.questions.zip(answers).joinToString("\n\n") { (question, answer) -> question.title + "\n我的选择：" + answer }
        val selectedModel = model
        val options = currentExecution
        sending += id
        actions.launch {
            try {
                val turnId = running[id]
                if (!turnId.isNullOrBlank()) connection.steerTurn(id, turnId, prompt)
                else {
                    check(id !in running) { "正在同步任务状态，请稍后提交" }
                    val startRevision = threadReads.revision(id)
                    val startedTurnId = connection.sendTurn(id, selectedModel, prompt, options = options)
                    requireCurrent(connection)
                    confirmStartedTurn(id, startedTurnId, startRevision)
                    executionApplied(id, options)
                }
                requireCurrent(connection)
                answeredQuestionItems += line.id
                questionAnswerDrafts.remove(line.id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reportChatError(e.message.orEmpty(), "提交选择失败", id)
            } finally { if (client === connection) sending.remove(id) }
        }
    }

    override fun onCleared() {
        flushAllAgentDeltas()
        historyJob?.cancel()
        historyClient?.let { connection -> kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { connection.close() } }
        reconnectJob?.cancel()
        connectionJob?.cancel()
        synchronizationJob?.cancel()
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
    activeTurnId: String = "",
    preferCurrent: Boolean = false
): List<ChatLine> {
    if (history.isEmpty()) return current
    if ((history + current).any { it.role == "error" }) {
        val merged = mergeHistory(
            history.filter { it.role != "error" }, current.filter { it.role != "error" }, latestPage, activeTurnId, preferCurrent
        ).toMutableList()
        val failures = (history + current).filter { it.role == "error" }.associateBy { it.id }.values
        failures.toList().asReversed().forEach { failure ->
            val anchor = merged.indexOfLast { it.id == failure.afterId && failure.afterId.isNotBlank() }
            val turn = merged.indexOfLast { it.turnId == failure.turnId && failure.turnId.isNotBlank() }
            merged.add(if (anchor >= 0) anchor + 1 else if (turn >= 0) turn + 1 else merged.size, failure)
        }
        return merged
    }
    // A reconnect page is small, but `current` can contain a long locally loaded
    // conversation. Index IDs once so each incoming line does not rescan that
    // entire window. The old implementation made this path quadratic as history
    // grew, especially while restoring a long conversation.
    val historyIds = history.mapTo(HashSet(history.size)) { it.id }
    val currentById = current.asReversed().associateBy { it.id }
    val firstOverlap = current.indexOfFirst { it.id in historyIds }
    val prefix = if (firstOverlap < 0) emptyList() else current.take(firstOverlap)
    val merged = history.map { saved ->
        currentById[saved.id]?.let { live ->
            // History is authoritative after reconnect. Preserve a genuinely longer
            // live prefix; equal text must take the saved completed status.
            when {
                preferCurrent -> live
                live.text.startsWith(saved.text) && live.text != saved.text -> live
                else -> saved
            }
        } ?: saved
    }
    val remaining = current.drop(if (firstOverlap < 0) 0 else firstOverlap)
        .filter { live -> live.id !in historyIds }
    if (!latestPage) return prefix + merged + remaining

    // A latest-page response can omit older cached turns. Appending every
    // unmatched cached line after the page moves old messages to the bottom.
    // Reinsert lines into a turn already present in the page, keep an active
    // turn at the end, and retain other omitted turns before the page.
    val omittedOlderLines = mutableListOf<ChatLine>()
    val liveTail = mutableListOf<ChatLine>()
    val additionsByTurn = LinkedHashMap<String, MutableList<ChatLine>>()
    val mergedTurnIds = merged.mapNotNullTo(HashSet()) { it.turnId.takeIf(String::isNotBlank) }
    remaining.forEach { line ->
        val turnId = line.turnId
        when {
            turnId in mergedTurnIds ->
                additionsByTurn.getOrPut(turnId) { mutableListOf() }.add(line)
            line.streaming || line.turnId.isBlank() || line.turnId == activeTurnId ||
                line.status in setOf("inProgress", "pending", "waiting") -> liveTail.add(line)
            else -> omittedOlderLines.add(line)
        }
    }
    val lastIndexByTurn = merged.mapIndexedNotNull { index, line ->
        line.turnId.takeIf { it.isNotBlank() }?.let { it to index }
    }.toMap()
    val orderedHistory = buildList(merged.size + remaining.size) {
        merged.forEachIndexed { index, line ->
            add(line)
            if (lastIndexByTurn[line.turnId] == index) addAll(additionsByTurn[line.turnId].orEmpty())
        }
    }
    return prefix + omittedOlderLines + orderedHistory + liveTail
}

internal fun chatFailure(current: List<ChatLine>, details: String, stage: String, turnId: String = "", eventId: String = ""): ChatLine {
    val anchor = current.lastOrNull { it.role != "error" && (turnId.isBlank() || it.turnId == turnId) }
        ?: current.lastOrNull { it.role != "error" }
    return ChatLine("error", details, eventId.ifBlank { "localError:" + java.util.UUID.randomUUID() }, turnId, stage, anchor?.id.orEmpty())
}
