package com.codexserver.mobile

import android.os.Bundle
import android.net.Uri
import android.content.Intent
import androidx.core.content.FileProvider
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import android.graphics.BitmapFactory
import android.util.Base64
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import java.net.URL
import java.net.URI
import java.net.URLConnection
import java.security.MessageDigest
import android.widget.TextView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.text.method.LinkMovementMethod
import android.text.Spannable
import android.text.Selection
import android.text.style.URLSpan
import android.view.MotionEvent
import io.noties.markwon.Markwon
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import kotlin.math.roundToInt

private val LightColors = lightColorScheme(
    primary = Color(0xFF805B48), onPrimary = Color.White,
    secondary = Color(0xFF397463), onSecondary = Color.White,
    background = Color(0xFFFFFDFC), onBackground = Color(0xFF302D2B),
    surface = Color.White, onSurface = Color(0xFF302D2B),
    surfaceVariant = Color(0xFFFAF7F4), onSurfaceVariant = Color(0xFF756D67),
    primaryContainer = Color(0xFFF8EEEA), onPrimaryContainer = Color(0xFF493A32),
    secondaryContainer = Color(0xFFEAF0E9), onSecondaryContainer = Color(0xFF283C31),
    surfaceDim = Color(0xFFFAF7F4), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFDFC),
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFFAF7F4), surfaceTint = Color.Transparent,
    outline = Color(0xFFD8CEC7), outlineVariant = Color(0xFFEDE8E3)
)

class MainActivity : ComponentActivity() {
    private val vm by viewModels<MobileViewModel>()
    private var notificationThreadId by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationThreadId = intent?.getStringExtra("threadId")
        setContent {
            MaterialTheme(colorScheme = LightColors) {
                Box(Modifier.fillMaxSize()) {
                val connectAfterNotificationPrompt = remember { mutableStateOf(false) }
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (connectAfterNotificationPrompt.value) {
                        connectAfterNotificationPrompt.value = false
                        vm.connect()
                        if (!granted) vm.error = "通知权限未开启，应用进入后台时无法收到任务提醒；可在系统设置中为 Andri 开启通知。"
                    }
                }
                val connect = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        connectAfterNotificationPrompt.value = true
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else vm.connect()
                }
                val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) lifecycleScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(uri)?.use { stream ->
                                    val data = ByteArray(65_537)
                                    var size = 0
                                    while (size < data.size) {
                                        val read = stream.read(data, size, data.size - size)
                                        if (read < 0) break
                                        size += read
                                    }
                                    check(size <= 65_536) { "私钥文件过大，请选择 SSH 私钥" }
                                    String(data, 0, size, Charsets.UTF_8)
                                }.orEmpty()
                            }
                        }.onSuccess(vm::importKey).onFailure { vm.error = "私钥导入失败：" + it.message }
                    }
                }
                val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
                    if (uris.isNotEmpty()) lifecycleScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                check(uris.size <= 10) { "一次最多选择 10 张图片" }
                                var totalBytes = 0L
                                uris.map { uri ->
                                    val image = contentResolver.openInputStream(uri)?.use { stream ->
                                        val data = ByteArrayOutputStream()
                                        val buffer = ByteArray(64 * 1024)
                                        var size = 0
                                        while (true) {
                                            val read = stream.read(buffer)
                                            if (read < 0) break
                                            size += read
                                            totalBytes += read
                                            check(size <= 10 * 1024 * 1024) { "每张图片需小于 10 MB" }
                                            check(totalBytes <= 30L * 1024 * 1024) { "本次所选图片总大小需小于 30 MB" }
                                            data.write(buffer, 0, read)
                                        }
                                        IncomingImage(data.toByteArray(), contentResolver.getType(uri).orEmpty(),
                                            uri.lastPathSegment ?: "图片")
                                    } ?: error("无法读取图片")
                                    image
                                }
                            }
                        }.onSuccess(vm::importImages)
                            .onFailure { vm.reportChatError(it.message.orEmpty(), "图片添加失败") }
                    }
                }
                val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) lifecycleScope.launch {
                        var staged: File? = null
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                                    ?.takeIf { it.isNotBlank() }
                                    ?: uri.lastPathSegment.orEmpty().substringAfterLast(':').ifBlank { "video.mp4" }
                                check(displayName.substringAfterLast('.', "").lowercase() in SshCodexClient.VIDEO_EXTENSIONS) {
                                    "请选择 MP4、MOV、M4V、3GP、WebM、MKV 或 AVI 视频"
                                }
                                val directory = File(cacheDir, "video-attachments").apply { mkdirs() }
                                val file = File(directory, "${java.util.UUID.randomUUID()}-${displayName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")}")
                                staged = file
                                contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                                    val buffer = ByteArray(128 * 1024)
                                    var total = 0L
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        check(total <= SshCodexClient.MAX_CHAT_VIDEO_BYTES) { "视频超过 1 GB" }
                                        output.write(buffer, 0, count)
                                    }
                                    check(total > 0) { "视频文件为空" }
                                } } ?: error("无法读取视频")
                                file to displayName
                            }
                        }.onSuccess { (file, name) -> runCatching { vm.importVideo(file, name) }
                            .onFailure { file.delete(); vm.reportChatError(it.message.orEmpty(), "视频添加失败") } }
                            .onFailure { staged?.delete(); vm.reportChatError(it.message.orEmpty(), "视频添加失败") }
                    }
                }
                val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) receiveSharedUris(listOf(uri))
                }
                BackHandler(enabled = vm.activeId != null && vm.requests.isEmpty()) { vm.back() }
                val screenKey = when {
                    !vm.connected && vm.busy && vm.privateKey.isNotBlank() && vm.activeThread == null && !vm.hasCachedSession -> "connecting"
                    !vm.connected && vm.activeThread == null && !vm.hasCachedSession -> "connection"
                    vm.activeThread == null -> "thread-list"
                    vm.openingThreadId != null || vm.threadOpenError.isNotBlank() -> "thread-loading:${vm.activeId}"
                    else -> "chat"
                }
                LaunchedEffect(vm.connected, vm.hasCachedSession, notificationThreadId) {
                    val threadId = notificationThreadId
                    if ((vm.connected || vm.hasCachedSession) && !threadId.isNullOrBlank()) {
                        notificationThreadId = null
                        vm.openThread(threadId)
                    }
                }
                val completionAlert = vm.taskCompletionAlerts.firstOrNull()
                val showCompletionAlert = completionAlert != null && vm.requests.isEmpty() && !vm.updateDialog
                LaunchedEffect(completionAlert?.id, showCompletionAlert) {
                    if (completionAlert != null && showCompletionAlert) {
                        delay(4_500)
                        if (vm.taskCompletionAlerts.firstOrNull()?.id == completionAlert.id) {
                            vm.dismissTaskCompletionAlert()
                        }
                    }
                }
                val bannerSpace by animateDpAsState(
                    targetValue = if (showCompletionAlert) 60.dp else 0.dp,
                    animationSpec = tween(180), label = "taskBannerSpace"
                )
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                        AnimatedContent(
                            targetState = screenKey,
                            modifier = Modifier.fillMaxSize().padding(top = bannerSpace),
                            transitionSpec = {
                                val enteringChat = initialState == "thread-list" &&
                                    (targetState == "chat" || targetState.startsWith("thread-loading:"))
                                val leavingChat = targetState == "thread-list" &&
                                    (initialState == "chat" || initialState.startsWith("thread-loading:"))
                                if (enteringChat || leavingChat) {
                                    val enterOffset: (Int) -> Int = { width ->
                                        if (enteringChat) width / 10 else -width / 24
                                    }
                                    val exitOffset: (Int) -> Int = { width ->
                                        if (enteringChat) -width / 18 else width / 3
                                    }
                                    (fadeIn(tween(180)) + slideInHorizontally(
                                        tween(260, easing = FastOutSlowInEasing), enterOffset
                                    )) togetherWith
                                        (fadeOut(tween(180)) + slideOutHorizontally(
                                            tween(260, easing = FastOutSlowInEasing), exitOffset
                                        ))
                                } else {
                                    val enterOffset: (Int) -> Int = { distance -> -distance / 18 }
                                    val exitOffset: (Int) -> Int = { distance -> distance / 24 }
                                    (fadeIn(tween(170)) + slideInVertically(tween(200, easing = FastOutSlowInEasing), enterOffset)) togetherWith
                                        (fadeOut(tween(130)) + slideOutVertically(tween(160, easing = FastOutSlowInEasing), exitOffset))
                                }
                            },
                            label = "main-screen-transition"
                        ) { screen ->
                            when {
                                screen == "connecting" -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center) {
                                    BrandMark(44)
                                    Spacer(Modifier.height(24.dp))
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    Text(vm.status, Modifier.padding(top = 16.dp), fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                screen == "connection" -> ConnectionScreen(vm, connect) { keyPicker.launch(arrayOf("*/*")) }
                                screen == "thread-list" -> ThreadListScreen(vm)
                                screen.startsWith("thread-loading:") -> ThreadLoadingScreen(vm)
                                else -> key(vm.activeId) { ChatScreen(vm,
                                    addImage = {
                                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                    addVideo = { videoPicker.launch(arrayOf("video/*")) },
                                    addFiles = { filePicker.launch(arrayOf("*/*")) }) }
                            }
                        }
                        AnimatedVisibility(
                            visible = showCompletionAlert,
                            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                            enter = slideInVertically(initialOffsetY = { -it / 2 }) + fadeIn(tween(160)),
                            exit = slideOutVertically(targetOffsetY = { -it / 2 }, animationSpec = tween(180)) + fadeOut(tween(140))
                        ) {
                            completionAlert?.let { alert ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(15.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    tonalElevation = 3.dp,
                                    shadowElevation = 2.dp
                                ) {
                                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Box(Modifier.size(8.dp).background(
                                            if (alert.title == "任务已完成") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                                            CircleShape))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                            Text(alert.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                            Text("${alert.project} · ${alert.threadTitle}", fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                vm.requests.firstOrNull()?.let { request -> key(request.get("id").toString()) { RequestDialog(vm, request) } }
                if (vm.updateDialog && vm.requests.isEmpty() && vm.connected) UpdateDialog(vm) { file ->
                    runCatching { installUpdate(this@MainActivity, file) }
                        .onFailure { vm.reportInstallError("无法打开系统安装器：" + it.message) }
                }
            }
        }
        }
        receiveSharedFiles(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationThreadId = intent.getStringExtra("threadId")
        receiveSharedFiles(intent)
    }

    private fun receiveSharedFiles(intent: Intent?) {
        if (intent == null || intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val sharedTitle = intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty()
        val uris = linkedSetOf<Uri>()
        intent.data?.let(uris::add)
        intent.clipData?.let { clip -> for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(uris::add) }
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.forEach(uris::add)
        } else {
            @Suppress("DEPRECATION")
            (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()).forEach(uris::add)
        }
        if (intent.action == Intent.ACTION_SEND) {
            runCatching { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()?.let(uris::add)
        }
        if (sharedText.isNotBlank()) runCatching { vm.importSharedText(sharedText, sharedTitle) }
            .onFailure { vm.error = "添加网页或文字上下文失败：${it.message}" }
        if (uris.isEmpty()) {
            if (sharedText.isBlank()) vm.error = "分享内容中没有文件、网页链接或文字"
            else setIntent(Intent(this@MainActivity, MainActivity::class.java))
            return
        }
        receiveSharedUris(uris.toList())
    }

    private fun receiveSharedUris(uris: List<Uri>) {
        lifecycleScope.launch {
            for (uri in uris.take(20)) {
                var staged: File? = null
                runCatching {
                    withContext(Dispatchers.IO) {
                        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                            ?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty().substringAfterLast(':').ifBlank { "shared-file" }
                        val safeName = name.substringAfterLast('/').substringAfterLast('\\')
                            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "shared-file" }
                        val directory = File(cacheDir, "shared-files").apply { mkdirs() }
                        val file = File(directory, "${java.util.UUID.randomUUID()}-$safeName")
                        staged = file
                        var total = 0L
                        contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= SshCodexClient.MAX_SHARED_FILE_BYTES) { "文件超过 512 MB" }
                                output.write(buffer, 0, count)
                            }
                        } } ?: error("无法读取分享的文件")
                        check(total > 0) { "分享的文件为空" }
                        Triple(file, name, contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" })
                    }
                }.onSuccess { (file, name, mime) ->
                    runCatching { vm.importSharedFile(file, name, mime) }
                        .onFailure { file.delete(); vm.error = "添加分享文件失败：${it.message}" }
                }.onFailure { staged?.delete(); vm.error = "接收分享文件失败：${it.message}" }
            }
            if (uris.size > 20) vm.error = "一次最多接收 20 个文件，已忽略其余文件"
            setIntent(Intent(this@MainActivity, MainActivity::class.java))
        }
    }
    override fun onResume() { super.onResume(); vm.onForeground() }
    override fun onStop() { vm.onBackground(); super.onStop() }
}

@Composable
private fun BrandMark(size: Int = 40) {
    Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape((size / 3).dp)),
        contentAlignment = Alignment.Center) {
        LineIcon("code", Modifier.size((size * 0.62f).dp), MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun LineIcon(name: String, modifier: Modifier = Modifier.size(20.dp), color: Color = LocalContentColor.current) {
    Canvas(modifier) {
        val unit = size.width / 24f
        val stroke = 1.7f * unit
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), stroke, StrokeCap.Round)
        when (name) {
            "paperclip" -> { line(8f, 13f, 15f, 6f); line(15f, 6f, 19f, 10f); line(19f, 10f, 12f, 17f); line(12f, 17f, 8f, 13f); line(11f, 10f, 15f, 6f) }
            "sliders" -> { line(4f, 7f, 20f, 7f); line(4f, 17f, 20f, 17f); drawCircle(color, 2f * unit, Offset(9f * unit, 7f * unit)); drawCircle(color, 2f * unit, Offset(15f * unit, 17f * unit)) }
            "code" -> { line(8f, 6f, 2f, 12f); line(2f, 12f, 8f, 18f); line(16f, 6f, 22f, 12f); line(22f, 12f, 16f, 18f); line(14f, 4f, 10f, 20f) }
            "chevron" -> { line(9f, 6f, 15f, 12f); line(15f, 12f, 9f, 18f) }
            "back" -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
            "sidebar" -> { line(4f, 5f, 20f, 5f); line(4f, 12f, 20f, 12f); line(4f, 19f, 20f, 19f); line(8f, 4f, 8f, 20f) }
            "plus" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            "send" -> { line(12f, 19f, 12f, 5f); line(6f, 11f, 12f, 5f); line(12f, 5f, 18f, 11f) }
            "paperplane" -> { line(3f, 11f, 21f, 4f); line(21f, 4f, 15f, 20f); line(15f, 20f, 11f, 14f); line(11f, 14f, 3f, 11f); line(11f, 14f, 21f, 4f) }
            "pause" -> { drawRoundRect(color, Offset(6f * unit, 5f * unit), androidx.compose.ui.geometry.Size(4f * unit, 14f * unit), CornerRadius(1f * unit)); drawRoundRect(color, Offset(14f * unit, 5f * unit), androidx.compose.ui.geometry.Size(4f * unit, 14f * unit), CornerRadius(1f * unit)) }
            "play" -> drawPath(Path().apply { moveTo(7f * unit, 4f * unit); lineTo(20f * unit, 12f * unit); lineTo(7f * unit, 20f * unit); close() }, color)
            "chevron-up" -> { line(6f, 15f, 12f, 9f); line(12f, 9f, 18f, 15f) }
            "edit" -> { line(5f, 19f, 9f, 18f); line(9f, 18f, 19f, 8f); line(19f, 8f, 16f, 5f); line(16f, 5f, 6f, 15f); line(6f, 15f, 5f, 19f); line(14f, 7f, 17f, 10f) }
            "folder" -> { line(3f, 8f, 9f, 8f); line(9f, 8f, 11f, 10f); line(11f, 10f, 21f, 10f); line(21f, 10f, 19f, 19f); line(19f, 19f, 5f, 19f); line(5f, 19f, 3f, 8f) }
            "file" -> { line(6f, 3f, 15f, 3f); line(15f, 3f, 19f, 7f); line(19f, 7f, 19f, 21f); line(6f, 21f, 6f, 3f); line(15f, 3f, 15f, 7f); line(15f, 7f, 19f, 7f) }
            "eye" -> { drawOval(color, Offset(2f * unit, 6f * unit), androidx.compose.ui.geometry.Size(20f * unit, 12f * unit), style = Stroke(stroke)); drawCircle(color, 2.5f * unit, Offset(12f * unit, 12f * unit)) }
            "link" -> { drawRoundRect(color, Offset(3f * unit, 7f * unit), androidx.compose.ui.geometry.Size(11f * unit, 7f * unit), CornerRadius(3.5f * unit), style = Stroke(stroke)); drawRoundRect(color, Offset(10f * unit, 10f * unit), androidx.compose.ui.geometry.Size(11f * unit, 7f * unit), CornerRadius(3.5f * unit), style = Stroke(stroke)); line(8f, 14f, 16f, 10f) }
            "more" -> listOf(5f, 12f, 19f).forEach { drawCircle(color, 1.5f * unit, Offset(it * unit, 12f * unit)) }
            "search" -> { drawCircle(color, 6f * unit, Offset(10.5f * unit, 10.5f * unit), style = Stroke(stroke)); line(15f, 15f, 20f, 20f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            "stop" -> drawRoundRect(color, Offset(6f * unit, 6f * unit), androidx.compose.ui.geometry.Size(12f * unit, 12f * unit), CornerRadius(2f * unit))
            "chat" -> {
                val path = Path().apply { moveTo(5f * unit, 4f * unit); lineTo(19f * unit, 4f * unit); quadraticBezierTo(21f * unit, 4f * unit, 21f * unit, 6f * unit); lineTo(21f * unit, 16f * unit); quadraticBezierTo(21f * unit, 18f * unit, 19f * unit, 18f * unit); lineTo(8f * unit, 18f * unit); lineTo(3f * unit, 21f * unit); lineTo(3f * unit, 6f * unit); quadraticBezierTo(3f * unit, 4f * unit, 5f * unit, 4f * unit); close() }
                drawPath(path, color, style = Stroke(stroke))
                line(7f, 9f, 17f, 9f); line(7f, 13f, 14f, 13f)
            }
        }
    }
}

@Composable
private fun MutedText(value: String, modifier: Modifier = Modifier) {
    Text(value, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ErrorText(error: String) {
    var expanded by remember(error) { mutableStateOf(false) }
    AnimatedVisibility(error.isNotBlank(), enter = fadeIn(tween(150)) + expandVertically(),
        exit = fadeOut(tween(120)) + shrinkVertically()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.animateContentSize(tween(180))) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                    Text("操作未完成", Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "查看详情", fontSize = 12.sp) }
                }
                AnimatedVisibility(expanded, enter = fadeIn(tween(140)) + expandVertically(),
                    exit = fadeOut(tween(100)) + shrinkVertically()) {
                    SelectionContainer { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ConnectionScreen(vm: MobileViewModel, connect: () -> Unit, importKey: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandMark()
            Text("Andri", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Text("工作，随你而行。", fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp)
        Text("从手机继续对话，让任务在服务器上运行。", color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp, lineHeight = 22.sp)
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MutedText("服务器连接")
                ConnectionField("服务器地址", vm.host, { vm.host = it }, !vm.busy)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConnectionField("端口", vm.port, { vm.port = it }, !vm.busy, Modifier.weight(1f), true)
                    ConnectionField("用户名", vm.username, { vm.username = it }, !vm.busy, Modifier.weight(2f))
                }
                ConnectionField("工作目录", vm.cwd, { vm.cwd = it }, !vm.busy)
            }
        }
        Surface(onClick = importKey, enabled = !vm.busy, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center) { Text("SSH", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f)) {
                    Text(if (vm.privateKey.isBlank()) "导入 SSH 私钥" else "SSH 私钥已就绪", fontWeight = FontWeight.Medium)
                    MutedText(if (vm.privateKey.isBlank()) "选择手机中的私钥文件" else "点击更换私钥")
                }
                Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (vm.privateKey.isNotBlank()) OutlinedTextField(vm.passphrase, { vm.passphrase = it }, label = { Text("私钥口令（如有）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(14.dp), enabled = !vm.busy)
        Button(connect, enabled = vm.privateKey.isNotBlank() && !vm.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else { Text("连接工作区", fontSize = 16.sp); Spacer(Modifier.weight(1f)); Text("→", fontSize = 22.sp) }
        }
        MutedText("连接前验证服务器身份 · 中转密钥留在服务器")
        ErrorText(vm.error)
        if (vm.busy || vm.error.isNotBlank()) MutedText(vm.status)
    }
}

@Composable
private fun ConnectionField(label: String, value: String, change: (String) -> Unit, enabled: Boolean,
    modifier: Modifier = Modifier, numeric: Boolean = false) {
    TextField(value, change, modifier.fillMaxWidth(), label = { Text(label, fontSize = 12.sp) }, singleLine = true,
        enabled = enabled, shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent))
}

@Composable
private fun ServerStatus() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
        MutedText("82 · 已连接")
    }
}

@Composable
private fun AppMenu(vm: MobileViewModel, thread: CodexThread? = null) {
    var expanded by remember { mutableStateOf(false) }
    var workspaceDialog by remember { mutableStateOf(false) }
    var globalInstructionsDialog by remember { mutableStateOf(false) }
    var renameDialog by remember(thread?.id) { mutableStateOf(false) }
    var filesDialog by remember(thread?.id) { mutableStateOf(false) }
    var diffDialog by remember(thread?.id) { mutableStateOf(false) }
    var titleDraft by remember(thread?.id) { mutableStateOf(thread?.title.orEmpty()) }
    Box {
        IconButton({ expanded = true }, modifier = Modifier.semantics { contentDescription = "更多选项" }) { LineIcon("more") }
        DropdownMenu(expanded, { expanded = false }, shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp,
            shadowElevation = 8.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            if (thread != null) DropdownMenuItem(text = { Text("修改对话名称") }, enabled = !vm.busy,
                onClick = { expanded = false; titleDraft = thread.title; renameDialog = true })
            if (thread != null) DropdownMenuItem(text = { Text("项目文件") }, enabled = vm.connected && !vm.busy,
                onClick = { expanded = false; filesDialog = true; vm.loadProjectFiles(thread.cwd.ifBlank { vm.cwd }) })
            if (thread != null) DropdownMenuItem(text = { Text("审阅代码改动") }, enabled = vm.connected && !vm.busy,
                onClick = { expanded = false; diffDialog = true; vm.loadGitDiff() })
            DropdownMenuItem(text = { Text("刷新") }, enabled = !vm.busy, onClick = { expanded = false; vm.refresh() })
            DropdownMenuItem(text = { Text("工作区与权限") }, enabled = !vm.busy, onClick = { expanded = false; workspaceDialog = true })
            DropdownMenuItem(text = { Text("全局指令") }, onClick = { expanded = false; globalInstructionsDialog = true; vm.loadGlobalInstructions() })
            DropdownMenuItem(text = { Text("检查更新 · v" + BuildConfig.VERSION_NAME) }, onClick = { expanded = false; vm.openUpdates() })
            DropdownMenuItem(text = { Text("断开服务器") }, enabled = !vm.busy, onClick = { expanded = false; vm.disconnect() })
        }
    }
    if (renameDialog && thread != null) AlertDialog(
        onDismissRequest = { if (!vm.busy) renameDialog = false },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("修改对话名称") },
        text = {
            OutlinedTextField(titleDraft, { titleDraft = it.take(120) }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("对话名称") }, shape = RoundedCornerShape(14.dp))
        },
        confirmButton = {
            TextButton(onClick = { vm.renameActiveThread(titleDraft); renameDialog = false },
                enabled = !vm.busy && titleDraft.isNotBlank() && titleDraft.trim() != thread.title) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { renameDialog = false }, enabled = !vm.busy) { Text("取消") } }
    )
    if (filesDialog && thread != null) WorkspaceFilesDialog(vm) { filesDialog = false }
    if (diffDialog && thread != null) GitDiffDialog(vm) { diffDialog = false }
    if (workspaceDialog) ExecutionSettingsDialog(vm) { workspaceDialog = false }
    if (globalInstructionsDialog) GlobalInstructionsDialog(vm) { globalInstructionsDialog = false }
}

@Composable
private fun WorkspaceFilesDialog(vm: MobileViewModel, dismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var previewFile by remember { mutableStateOf<File?>(null) }
    var previewName by remember { mutableStateOf("") }
    var fileToSave by remember { mutableStateOf<File?>(null) }
    var fileToShare by remember { mutableStateOf<File?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val source = fileToSave
        if (uri != null && source?.isFile == true) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "w")!!.use { output -> source.inputStream().use { it.copyTo(output) } }
            } }.onFailure { vm.showWorkspaceError("保存文件失败：${it.message}") }
        }
    }
    val listing = vm.workspaceListing
    AlertDialog(onDismissRequest = dismiss, shape = RoundedCornerShape(26.dp), containerColor = Color.White,
        title = { Text("项目文件") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(vm.fileBrowserPath, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.loadProjectFiles(WORKSPACE_ROOT) }, enabled = !vm.workspaceBusy) { Text("项目根目录") }
                    TextButton(onClick = { vm.loadProjectFiles(vm.fileBrowserPath.substringBeforeLast('/').ifBlank { WORKSPACE_ROOT }) },
                        enabled = !vm.workspaceBusy && vm.fileBrowserPath != WORKSPACE_ROOT) { Text("上一级") }
                }
                if (vm.workspaceBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (vm.workspaceError.isNotBlank()) Text(vm.workspaceError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                listing?.let { directory ->
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        directory.children.forEach { child ->
                            TextButton(onClick = { vm.loadProjectFiles(directory.path.trimEnd('/') + "/" + child) },
                                modifier = Modifier.fillMaxWidth(), enabled = !vm.workspaceBusy) {
                                LineIcon("folder", Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp)); Text(child, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        directory.files.forEach { name ->
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { vm.cacheWorkspaceFile(directory.path.trimEnd('/') + "/" + name, directory.path) }
                                        .onSuccess { previewFile = it; previewName = name }
                                        .onFailure { vm.showWorkspaceError("读取文件失败：${it.message}") }
                                }
                            }, modifier = Modifier.fillMaxWidth(), enabled = !vm.workspaceBusy) {
                                LineIcon("file", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp)); Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("完成") } })

    previewFile?.let { file -> WorkspaceFilePreview(file, previewName,
        onDismiss = { previewFile = null },
        onSave = { fileToSave = file; saveLauncher.launch(previewName) },
        onShare = { fileToShare = file }) }
    fileToShare?.let { file ->
        LaunchedEffect(file) {
            runCatching {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "分享文件"))
            }.onFailure { vm.showWorkspaceError("无法分享文件：${it.message}") }
            fileToShare = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextDrawer(
    vm: MobileViewModel,
    cwd: String,
    addImage: () -> Unit,
    addVideo: () -> Unit,
    addFiles: () -> Unit,
    onDismiss: () -> Unit
) {
    val id = vm.activeId ?: return
    var webDialog by remember { mutableStateOf(false) }
    var browserDialog by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var sharedText by remember { mutableStateOf("") }
    var sharedTitle by remember { mutableStateOf("") }
    var webAddress by rememberSaveable { mutableStateOf("") }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var previewName by remember { mutableStateOf("") }
    var fileToSave by remember { mutableStateOf<File?>(null) }
    var fileToShare by remember { mutableStateOf<File?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val references = vm.contextFor(id)
    val sharedUrl = references.asSequence().mapNotNull {
        Regex("https://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE).find(it.prompt)?.value?.trimEnd('.', ',', ')', ']')
    }.firstOrNull().orEmpty()
    LaunchedEffect(sharedUrl) { if (webAddress.isBlank() && sharedUrl.isNotBlank()) webAddress = sharedUrl }
    val files = vm.sharedFilesFor(id)
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val source = fileToSave
        if (uri != null && source?.isFile == true) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "w")!!.use { output -> source.inputStream().use { it.copyTo(output) } }
            } }.onFailure { vm.showWorkspaceError("保存文件失败：${it.message}") }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("任务工作区", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    MutedText("集中管理本次任务的网页、文件、附件和代码改动。")
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("上下文", "服务器文件", "网页", "代码改动").forEachIndexed { index, label ->
                    FilterChip(selected = selectedTab == index, onClick = {
                        selectedTab = index
                        if (index == 1) vm.loadProjectFiles(cwd)
                        if (index == 3) vm.loadGitDiff()
                    }, label = { Text(label) })
                }
            }
            when (selectedTab) {
                0 -> {
                    if (references.isEmpty() && files.isEmpty() && vm.imageAttachments[id].isNullOrEmpty() && vm.videoAttachments[id] == null)
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                            Text("当前没有额外上下文", Modifier.fillMaxWidth().padding(14.dp), fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    references.forEach { reference ->
                        val url = Regex("https://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE).find(reference.prompt)?.value
                            ?.trimEnd('.', ',', ')', ']')
                        ContextRow(reference.label, reference.prompt.take(180), onRemove = { vm.removeContextReference(reference.id) },
                            onOpen = url?.let { { webAddress = it; selectedTab = 2; browserDialog = true } })
                    }
                    files.forEachIndexed { index, file ->
                        ContextRow(file.label, "手机文件 · ${formatVideoSize(file.sizeBytes)}", onRemove = { vm.removeSharedFile(index) })
                    }
                    vm.imageAttachments[id].orEmpty().forEachIndexed { index, image ->
                        ContextRow(image.label, "手机图片", onRemove = { vm.removeImage(index) })
                    }
                    vm.videoAttachments[id]?.let { ContextRow(it.label, "手机视频 · ${formatVideoSize(it.sizeBytes)}", onRemove = vm::removeVideo) }
                    HorizontalDivider()
                    ContextAction("从手机添加文件", "支持从 QQ、微信分享文件") { onDismiss(); addFiles() }
                    ContextAction("添加手机图片", "一次可多选最多 10 张") { onDismiss(); addImage() }
                    ContextAction("添加手机视频", "上传原画质视频") { onDismiss(); addVideo() }
                    ContextAction("添加网页或文字", "粘贴链接、选中文字或补充说明") { webDialog = true }
                }
                1 -> {
                    Text(vm.fileBrowserPath, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton({ vm.loadProjectFiles(WORKSPACE_ROOT) }, enabled = !vm.workspaceBusy) { Text("项目根目录") }
                        TextButton({ vm.loadProjectFiles(vm.fileBrowserPath.substringBeforeLast('/').ifBlank { WORKSPACE_ROOT }) },
                            enabled = !vm.workspaceBusy && vm.fileBrowserPath != WORKSPACE_ROOT) { Text("上一级") }
                    }
                    if (vm.workspaceBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (vm.workspaceError.isNotBlank()) Text(vm.workspaceError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    vm.workspaceListing?.let { directory ->
                        directory.children.forEach { child ->
                            TextButton({ vm.loadProjectFiles(directory.path.trimEnd('/') + "/" + child) },
                                modifier = Modifier.fillMaxWidth(), enabled = !vm.workspaceBusy) {
                                LineIcon("folder", Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp)); Text(child, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        directory.files.forEach { name ->
                            val path = directory.path.trimEnd('/') + "/" + name
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { vm.addWorkspaceFileContext(path, name); selectedTab = 0 },
                                    modifier = Modifier.weight(1f), enabled = !vm.workspaceBusy) {
                                    LineIcon("file", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(8.dp)); Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("引用", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { scope.launch {
                                    runCatching { vm.cacheWorkspaceFile(path, directory.path) }
                                        .onSuccess { previewFile = it; previewName = name }
                                        .onFailure { vm.showWorkspaceError("读取文件失败：${it.message}") }
                                } }, enabled = !vm.workspaceBusy, modifier = Modifier.size(40.dp)) {
                                    LineIcon("eye", Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                2 -> {
                    OutlinedTextField(webAddress, { webAddress = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("网页地址") }, placeholder = { Text("https://…") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    ContextAction("在工作区内打开", "浏览页面后可读取正文并加入当前任务") {
                        browserDialog = true
                    }
                    references.filter { Regex("https://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE).containsMatchIn(it.prompt) }
                        .forEach { reference ->
                            val url = Regex("https://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE).find(reference.prompt)?.value
                                ?.trimEnd('.', ',', ')', ']') ?: return@forEach
                            ContextRow(reference.label, url, onRemove = { vm.removeContextReference(reference.id) },
                                onOpen = { webAddress = url; browserDialog = true })
                        }
                }
                else -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("当前工作区的 Git 差异", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        TextButton({ vm.loadGitDiff() }, enabled = !vm.gitDiffBusy) { Text("刷新") }
                    }
                    if (!vm.connected) Text("连接服务器后可查看改动。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (vm.gitDiffBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (vm.gitDiffError.isNotBlank()) Text(vm.gitDiffError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    else if (!vm.gitDiffBusy) {
                        val diff = vm.gitDiff
                        Text(if (diff.isBlank()) "当前没有可显示的 Git 改动。" else diff.take(48_000),
                            Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TextButton(onDismiss, modifier = Modifier.align(Alignment.End)) { Text("完成") }
        }
    }
    if (webDialog) AlertDialog(onDismissRequest = { webDialog = false },
        title = { Text("添加网页或文字上下文") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sharedTitle, { sharedTitle = it }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text("名称（可选）") })
                OutlinedTextField(sharedText, { sharedText = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    label = { Text("网页 URL 或文字") }, placeholder = { Text("https://… 或粘贴网页选中的内容") })
            }
        }, confirmButton = { TextButton(onClick = {
            runCatching { vm.importSharedText(sharedText, sharedTitle) }
                .onFailure { vm.showWorkspaceError(it.message.orEmpty()) }
            sharedText = ""; sharedTitle = ""; webDialog = false
        }, enabled = sharedText.isNotBlank()) { Text("添加") } },
        dismissButton = { TextButton({ webDialog = false }) { Text("取消") } })
    if (browserDialog) WebPageContextDialog(webAddress.ifBlank { sharedUrl }, onDismiss = { browserDialog = false }, onAdd = { title, prompt ->
        vm.addContextReference(title, prompt)
        browserDialog = false
    })
    previewFile?.let { file -> WorkspaceFilePreview(file, previewName,
        onDismiss = { previewFile = null },
        onSave = { fileToSave = file; saveLauncher.launch(previewName) },
        onShare = { fileToShare = file }) }
    fileToShare?.let { file ->
        LaunchedEffect(file) {
            runCatching {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "分享文件"))
            }.onFailure { vm.showWorkspaceError("无法分享文件：${it.message}") }
            fileToShare = null
        }
    }
}

@Composable
private fun WebPageContextDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
    allowContextImport: Boolean = true
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var address by remember(initialUrl) { mutableStateOf(initialUrl) }
    var currentUrl by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var extracting by remember { mutableStateOf(false) }
    var extractedPage by remember { mutableStateOf("") }
    val pageView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            WebView.startSafeBrowsing(context) { }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme != "https") return true
                    currentUrl = request.url.toString()
                    address = currentUrl
                    return false
                }
                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url
                    address = url
                    title = view.title.orEmpty()
                    error = ""
                }
            }
            webChromeClient = WebChromeClient()
        }
    }
    DisposableEffect(pageView) { onDispose { pageView.stopLoading(); pageView.destroy() } }
    LaunchedEffect(pageView, initialUrl) {
        if (initialUrl.startsWith("https://", true)) {
            currentUrl = initialUrl
            pageView.loadUrl(initialUrl)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(10.dp), shape = RoundedCornerShape(24.dp), color = Color.White) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (allowContextImport) "网页上下文" else "网页预览", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onDismiss) { Text("关闭") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(address, { address = it }, Modifier.weight(1f), singleLine = true,
                        label = { Text("网页地址") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Button(onClick = {
                        val url = address.trim().let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it" }
                        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
                        if (scheme != "https") error = "网页上下文仅支持 HTTPS 地址"
                        else { error = ""; extractedPage = ""; currentUrl = url; pageView.loadUrl(url) }
                    }) { Text("打开") }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                AndroidView(factory = { pageView }, modifier = Modifier.weight(1f).fillMaxWidth())
                if (allowContextImport) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title.ifBlank { currentUrl }, Modifier.weight(1f), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Button(onClick = {
                        extracting = true
                        pageView.evaluateJavascript("(function(){return JSON.stringify({title:document.title,url:location.href,text:document.body?document.body.innerText:''})})()") { raw ->
                            extracting = false
                            runCatching {
                                val json = JSONTokener(raw ?: "null").nextValue() as? String ?: error("页面没有返回可读内容")
                                JSONObject(json)
                            }.onSuccess { page ->
                                val text = page.text("text").take(24_000)
                                if (text.isBlank()) error = "页面没有可读取的正文"
                                else {
                                    title = page.text("title").ifBlank { page.text("url") }
                                    extractedPage = "网页标题：${page.text("title")}\n网页地址：${page.text("url")}\n页面文字：\n$text"
                                }
                            }.onFailure { error = "读取页面失败：${it.message}" }
                        }
                    }, enabled = !extracting && currentUrl.isNotBlank() && extractedPage.isBlank()) {
                        Text(if (extracting) "读取中…" else "读取当前页面")
                    }
                    if (extractedPage.isNotBlank()) Button(onClick = {
                        onAdd(title.ifBlank { currentUrl }, extractedPage)
                    }) { Text("加入任务") }
                } else Text(title.ifBlank { currentUrl }, Modifier.fillMaxWidth(), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ContextRow(title: String, detail: String, onRemove: () -> Unit, onOpen: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(13.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineIcon("file", Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onOpen != null) IconButton(onOpen, Modifier.size(40.dp)) { LineIcon("link", Modifier.size(17.dp), MaterialTheme.colorScheme.primary) }
            IconButton(onRemove, Modifier.size(40.dp)) { Text("×") }
        }
    }
}

@Composable
private fun ContextAction(title: String, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkspaceFilePreview(file: File, name: String, onDismiss: () -> Unit, onSave: () -> Unit, onShare: () -> Unit) {
    val extension = name.substringAfterLast('.', "").lowercase()
    val isText = extension in setOf("txt", "md", "markdown", "json", "xml", "yaml", "yml", "toml", "kt", "kts", "java", "js", "ts", "tsx", "jsx", "py", "sh", "html", "css", "csv", "log", "diff", "patch")
    val context = androidx.compose.ui.platform.LocalContext.current
    val text by produceState("", file) {
        value = if (isText && file.length() <= 2L * 1024 * 1024) withContext(Dispatchers.IO) {
            runCatching { file.readText(Charsets.UTF_8) }.getOrElse { "无法按 UTF-8 预览该文件：${it.message}" }
        } else if (isText) "文件超过 2 MB，请保存到手机后用其他应用查看。" else "此格式可通过其他应用打开，或保存到手机。"
    }
    val imageBitmap by produceState<android.graphics.Bitmap?>(null, file) {
        value = if (extension in setOf("png", "jpg", "jpeg", "webp", "bmp")) withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        } else null
    }
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(24.dp), containerColor = Color.White,
        title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${formatVideoSize(file.length())} · ${file.absolutePath.substringAfterLast('/')}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                when {
                    imageBitmap != null -> Image(imageBitmap!!.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
                    isText -> SelectionContainer { Text(text, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp) }
                    else -> Text("保存文件后可在手机上的兼容应用中查看或编辑。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { Row {
            TextButton(onShare) { Text("分享") }
            TextButton(onSave) { Text("保存到手机") }
            if (imageBitmap == null && !isText) TextButton(onClick = {
                runCatching {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }
            }) { Text("打开") }
        } }, dismissButton = { TextButton(onDismiss) { Text("关闭") } })
}

@Composable
private fun ServerWorkspaceFileLinkDialog(vm: MobileViewModel, path: String, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember(path) { mutableStateOf<File?>(null) }
    var fileName by remember(path) { mutableStateOf(path.substringAfterLast('/')) }
    var error by remember(path) { mutableStateOf("") }
    var attempt by remember(path) { mutableIntStateOf(0) }
    var fileToShare by remember { mutableStateOf<File?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val source = file
        if (uri != null && source?.isFile == true) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "w")!!.use { output -> source.inputStream().use { it.copyTo(output) } }
            } }.onFailure { error = "保存文件失败：${it.message}" }
        }
    }
    LaunchedEffect(path, attempt) {
        error = ""
        runCatching { vm.cacheWorkspaceFile(path, WORKSPACE_ROOT) }
            .onSuccess { file = it; fileName = vm.downloadedWorkspaceFileName }
            .onFailure { error = it.message ?: "读取服务器文件失败" }
    }
    file?.let { cached -> WorkspaceFilePreview(cached, fileName, onDismiss,
        onSave = { saveLauncher.launch(fileName) }, onShare = { fileToShare = cached }) }
    if (file == null) AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(24.dp), containerColor = Color.White,
        title = { Text("服务器文件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(path, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
                else Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { if (error.isNotBlank()) TextButton(onClick = { attempt++ }) { Text("重试") } },
        dismissButton = { TextButton(onDismiss) { Text("关闭") } })
    fileToShare?.let { shared -> LaunchedEffect(shared) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", shared)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = URLConnection.guessContentTypeFromName(shared.name) ?: "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享文件"))
        }.onFailure { error = "分享文件失败：${it.message}" }
        fileToShare = null
    } }
}

private fun serverWorkspacePathFromLink(value: String): String? = runCatching {
    val uri = URI(value)
    val path = when {
        uri.host.equals("home", ignoreCase = true) -> "/home" + uri.path.orEmpty()
        uri.scheme.equals("file", ignoreCase = true) -> uri.path.orEmpty()
        uri.scheme == null && uri.path?.startsWith("$WORKSPACE_ROOT/") == true -> uri.path
        else -> return@runCatching null
    }
    path.takeIf { it.startsWith("$WORKSPACE_ROOT/") && '\u0000' !in it }
}.getOrNull()

private fun serverWorkspaceLinkIsDirectory(value: String): Boolean = runCatching {
    URI(value).path?.endsWith('/') == true
}.getOrDefault(false)

@Composable
private fun ServerWorkspaceDirectoryLinkDialog(vm: MobileViewModel, path: String, onDismiss: () -> Unit) {
    LaunchedEffect(path) { vm.loadProjectFiles(path) }
    WorkspaceFilesDialog(vm, onDismiss)
}

@Composable
private fun GitDiffDialog(vm: MobileViewModel, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, shape = RoundedCornerShape(24.dp), containerColor = Color.White,
        title = { Text("审阅代码改动") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.gitDiffBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (vm.gitDiffError.isNotBlank()) Text(vm.gitDiffError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                else if (!vm.gitDiffBusy) {
                    if (vm.gitDiff.isBlank() || ("diff --git" !in vm.gitDiff && vm.gitDiff.substringBefore("--- 已暂存的改动 ---").isBlank()))
                        Text("工作区没有已暂存或未暂存的改动。", fontSize = 13.sp)
                    else SelectionContainer {
                        Text(vm.gitDiff, Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                    }
                }
            }
        }, confirmButton = { TextButton({ vm.loadGitDiff() }, enabled = !vm.gitDiffBusy) { Text("刷新") } },
        dismissButton = { TextButton(dismiss) { Text("关闭") } })
}

@Composable
private fun GlobalInstructionsDialog(vm: MobileViewModel, dismiss: () -> Unit) {
    val document = vm.globalInstructionsDocument
    AlertDialog(onDismissRequest = { if (!vm.globalInstructionsBusy) dismiss() }, shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp,
        title = { Text("全局指令") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MutedText("统一设置回答习惯和工作要求，适用于此服务器账号下的新对话。已有任务不会重启。")
                if (vm.globalInstructionsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                document?.let {
                    Text(it.path, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (it.path.endsWith("/AGENTS.override.md")) MutedText("正在编辑优先使用的覆盖文件；清空后会恢复 AGENTS.md 中的指令。")
                    OutlinedTextField(vm.globalInstructionsDraft, vm::editGlobalInstructions,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 280.dp),
                        enabled = !vm.globalInstructionsBusy, label = { Text("给助手的长期要求") },
                        placeholder = { Text("例如：默认使用中文；修改前先阅读相关代码。") },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp), shape = RoundedCornerShape(16.dp))
                    MutedText("保存前会检查其他修改，并备份已有指令。最多 32 KiB。")
                }
                if (vm.globalInstructionsError.isNotBlank()) {
                    Text(vm.globalInstructionsError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    TextButton(vm::loadGlobalInstructions, enabled = !vm.globalInstructionsBusy) { Text("重新读取服务器内容") }
                }
                if (vm.globalInstructionsMessage.isNotBlank()) Text(vm.globalInstructionsMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = { TextButton(vm::saveGlobalInstructions, enabled = !vm.globalInstructionsBusy && document != null &&
            vm.globalInstructionsDraft != document.content) { Text(if (vm.globalInstructionsBusy) "处理中…" else "保存") } },
        dismissButton = { TextButton(dismiss, enabled = !vm.globalInstructionsBusy) { Text("关闭") } })
}

@Composable
private fun ExecutionSettingsDialog(vm: MobileViewModel, dismiss: () -> Unit) {
    val original = vm.currentExecution
    val root = WORKSPACE_ROOT
    var path by remember { mutableStateOf(if (original.cwd.trimEnd('/') == "/home/ubuntu") root else original.cwd) }
    var mode by remember { mutableStateOf(original.mode) }
    var effort by remember { mutableStateOf(original.effort) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    val active = vm.activeId
    val locked = active != null && (active in vm.running || active in vm.sending)
    LaunchedEffect(Unit) { vm.browseWorkspace(root) }
    AlertDialog(onDismissRequest = { if (!vm.workspaceBusy) dismiss() }, shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp,
        title = { Text("工作区与权限") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MutedText(if (active == null) "新对话的默认设置" else "只应用到当前对话")
                Text("项目归属：${projectNameForPath(path)}（根据所选工作区自动设置）",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LineIcon("folder", Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                        Text(vm.workspaceListing?.path ?: root, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton({ vm.browseWorkspace(root) }, enabled = !vm.workspaceBusy && root != (vm.workspaceListing?.path ?: root), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("根目录") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ path = vm.workspaceListing?.path ?: path; selectedFile = null }, enabled = vm.workspaceListing != null && !vm.workspaceBusy && !locked) { Text("选择此目录") }
                    TextButton({ creatingFolder = !creatingFolder; folderName = "" }, enabled = !vm.workspaceBusy && !locked) { Text(if (creatingFolder) "取消新建" else "新建项目") }
                }
                if (creatingFolder) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(folderName, { folderName = it }, Modifier.weight(1f), singleLine = true, enabled = !vm.workspaceBusy,
                        label = { Text("新项目文件夹名称") }, textStyle = MaterialTheme.typography.bodySmall)
                    TextButton({ vm.createWorkspaceFolder(folderName) { path = it; selectedFile = null; creatingFolder = false } },
                        enabled = !vm.workspaceBusy && folderName.isNotBlank()) { Text("创建") }
                }
                if (vm.workspaceBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.workspaceListing?.let { listing ->
                    Text("工作区内容", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    TextButton({ vm.browseWorkspace(listing.path.substringBeforeLast('/').ifBlank { "/" }) }, enabled = !vm.workspaceBusy && listing.path != "/") { Text("↑ 上一级") }
                    Column(Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState())) {
                        if (listing.children.isEmpty() && listing.files.isEmpty()) MutedText("此目录为空，可以直接新建项目")
                        listing.children.forEach { child ->
                            val childPath = listing.path.trimEnd('/') + "/" + child
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton({ vm.browseWorkspace(childPath) }, enabled = !vm.workspaceBusy, modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    LineIcon("folder", Modifier.size(17.dp), MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(child, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton({ path = childPath; selectedFile = null }, enabled = !vm.workspaceBusy && !locked, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (path == childPath) "已选择 ✓" else "选择") }
                            }
                        }
                        listing.files.forEach { file ->
                            TextButton({ path = listing.path; selectedFile = file }, enabled = !vm.workspaceBusy && !locked, modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 8.dp)) {
                                LineIcon("file", Modifier.size(17.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(file, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Text("已选工作区：$path", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                selectedFile?.let { MutedText("已选择文件：$it\r\n将使用它所在的目录作为工作区") }
                HorizontalDivider()
                Text("执行模式", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                ExecutionMode.entries.forEach { option ->
                    Surface(onClick = { mode = option }, enabled = !vm.workspaceBusy && !locked, shape = RoundedCornerShape(12.dp),
                        color = if (mode == option) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent) {
                        Row(Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(mode == option, onClick = null, modifier = Modifier.padding(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(option.label, fontSize = 14.sp)
                                Text(option.description, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                val effortOptions = vm.reasoningEfforts(vm.model)
                ReasoningEffortSlider(effort, effortOptions, vm.model, !vm.workspaceBusy && !locked) { effort = it }
                if (locked) MutedText("任务运行中，可浏览目录；任务完成后才能保存设置。")
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (vm.workspaceError.isNotBlank()) Text(vm.workspaceError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                if (vm.workspaceBusy && vm.workspaceStatus.isNotBlank()) MutedText(vm.workspaceStatus)
                TextButton({ vm.saveExecution(ExecutionOptions(path.trim(), mode, effort)) { dismiss() } },
                    enabled = !locked && !vm.workspaceBusy && !vm.busy && path.startsWith("/")) { Text(if (vm.workspaceBusy) "请稍候…" else "保存") }
            }
        },
        dismissButton = { TextButton(dismiss, enabled = !vm.workspaceBusy) { Text("取消") } })
}

private fun reasoningEffortLabel(value: String): String = when (value) {
    "low" -> "低"
    "medium" -> "标准"
    "high" -> "高"
    "xhigh" -> "很高"
    "max" -> "极高"
    "ultra" -> "Ultra"
    else -> value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningEffortSlider(value: String, options: List<String>, model: String, enabled: Boolean, onChange: (String) -> Unit) {
    val index = options.indexOf(value).coerceAtLeast(0)
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    var position by remember(model, options) { mutableFloatStateOf(index.toFloat()) }
    val visualPosition by animateFloatAsState(if (dragging) position else index.toFloat(),
        animationSpec = if (dragging) snap() else tween(220, easing = FastOutSlowInEasing), label = "effort-position")
    val thumbScale by animateFloatAsState(if (dragging) 1.08f else 1f, tween(120), label = "effort-thumb")
    fun selectPosition(target: Float) {
        position = target.coerceIn(0f, options.lastIndex.toFloat())
        onChange(options[position.roundToInt()])
    }
    LaunchedEffect(index, dragging) { if (!dragging) position = index.toFloat() }
    Surface(shape = RoundedCornerShape(20.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 6.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 44.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(reasoningEffortLabel(value), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = colors.primary)
                    Text(model, Modifier.padding(top = 2.dp), fontSize = 13.sp, lineHeight = 18.sp,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onChange(if ("medium" in options) "medium" else options.first()) },
                    enabled = enabled, modifier = Modifier.align(Alignment.TopEnd).size(44.dp).semantics { contentDescription = "恢复默认推理强度" }) {
                    Canvas(Modifier.size(18.dp)) {
                        val unit = size.width / 24f
                        val color = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else .45f)
                        drawArc(color, 215f, 300f, false, Offset(5f * unit, 5f * unit),
                            androidx.compose.ui.geometry.Size(14f * unit, 14f * unit), style = Stroke(1.5f * unit, cap = StrokeCap.Round))
                        drawPath(Path().apply { moveTo(4f * unit, 9f * unit); lineTo(4f * unit, 4f * unit); lineTo(9f * unit, 4f * unit) },
                            color, style = Stroke(1.5f * unit, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    }
                }
            }
            Slider(value = visualPosition, onValueChange = ::selectPosition,
                onValueChangeFinished = { position = index.toFloat() }, interactionSource = interaction,
                valueRange = 0f..options.lastIndex.toFloat(), enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onPreviewKeyEvent { event ->
                    if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val target = when (event.key) {
                        Key.DirectionRight, Key.DirectionUp -> index + 1
                        Key.DirectionLeft, Key.DirectionDown -> index - 1
                        Key.MoveHome -> 0
                        Key.MoveEnd -> options.lastIndex
                        else -> return@onPreviewKeyEvent false
                    }
                    selectPosition(target.toFloat()); true
                }.semantics {
                    contentDescription = "模型推理强度滑块"
                    stateDescription = reasoningEffortLabel(value)
                    progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..options.lastIndex.toFloat(), options.size - 2)
                    setProgress { target ->
                        if (!enabled) false else { selectPosition(target.roundToInt().toFloat()); true }
                    }
                },
                thumb = {
                    Box(Modifier.size(34.dp).graphicsLayer {
                        alpha = if (enabled) 1f else .45f
                        scaleX = thumbScale; scaleY = thumbScale
                    }
                        .shadow(2.dp, CircleShape).background(colors.surface, CircleShape)
                        .border(1.dp, Color(0xFFF0EEEB), CircleShape))
                },
                track = {
                    Canvas(Modifier.fillMaxWidth().height(30.dp).graphicsLayer { alpha = if (enabled) 1f else .45f }) {
                        val centerY = size.height / 2f
                        drawLine(Color(0xFFF0E8E1), Offset(0f, centerY), Offset(size.width, centerY), size.height, StrokeCap.Round)
                        drawLine(Color(0xFFB88365),
                            Offset(0f, centerY), Offset(size.width * visualPosition / options.lastIndex, centerY), size.height, StrokeCap.Round)
                        options.indices.forEach { tick ->
                            drawCircle(if (tick <= visualPosition) Color(0xFFE4C8B6) else Color(0xFFC3B3A7), 2.5.dp.toPx(),
                                Offset(size.width * tick / options.lastIndex, centerY))
                        }
                    }
                })
        }
    }
}

@Composable
private fun ModelPicker(vm: MobileViewModel, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { expanded = true }, enabled = enabled, shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.padding(horizontal = 12.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(vm.model, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded, { expanded = false }, shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp,
            shadowElevation = 8.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            (listOf("gpt-6-luna", "gpt-6.1-sol") + vm.model).distinct().forEach { value ->
                DropdownMenuItem(text = { Text(value + if (value == vm.model) "  ✓" else "") }, enabled = enabled,
                    onClick = { vm.selectModel(value); expanded = false })
            }
        }
    }
}

@Composable
private fun UpdateNotice(vm: MobileViewModel) {
    vm.updateInfo?.let { info ->
        TextButton(vm::openUpdates) { Text("新版本 " + info.versionName + " 已就绪  →", color = MaterialTheme.colorScheme.secondary) }
    }
}

@Composable
private fun ThreadListScreen(vm: MobileViewModel) {
    val colors = MaterialTheme.colorScheme
    val canvas = Color(0xFFFAF9F7)
    val selectedFilter = Color(0xFFE9E8E4)
    val neutralInk = Color(0xFF343330)
    var searchExpanded by rememberSaveable { mutableStateOf(vm.threadSearch.isNotBlank()) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val query = vm.threadSearch.trim()
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) searchFocus.requestFocus() else keyboard?.hide()
    }
    val matchingRows = vm.threads.filter { thread ->
        query.isBlank() || thread.title.contains(query, ignoreCase = true) || thread.model.contains(query, ignoreCase = true) ||
            vm.projectFor(thread.id).contains(query, ignoreCase = true)
    }
    val rows = if (vm.onlyRunningThreads) matchingRows.filter { it.id in vm.running } else matchingRows
    val runningRows = rows.filter { it.id in vm.running }
    val recentRows = rows.filterNot { it.id in vm.running }

    Box(Modifier.fillMaxSize().background(canvas)) {
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(Color(0xFFF0EFEC), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    LineIcon("code", Modifier.size(22.dp), Color(0xFF575550))
                }
                Text("Andri", Modifier.padding(start = 9.dp).weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    val connectionDot by animateColorAsState(
                        if (vm.connected) Color(0xFF6F9B7D) else if (vm.busy) Color(0xFFB18A54) else Color(0xFFB2A9B7),
                        tween(220), label = "connection-indicator")
                    Box(Modifier.size(7.dp).background(connectionDot, CircleShape))
                    AnimatedContent(
                        targetState = if (vm.connected) "82 · 已连接" else if (vm.busy) "正在连接" else "未连接",
                        transitionSpec = {
                            (fadeIn(tween(140)) + slideInVertically(tween(170)) { it / 3 }) togetherWith
                                (fadeOut(tween(100)) + slideOutVertically(tween(130)) { -it / 3 })
                        }, label = "connection-label") { label ->
                        Text(label, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                }
                Surface(onClick = vm::newThread, enabled = vm.connected && !vm.busy,
                    modifier = Modifier.size(44.dp).semantics { contentDescription = "新建对话" },
                    shape = CircleShape, color = Color(0xFFE9E8E4), contentColor = neutralInk) {
                    Box(contentAlignment = Alignment.Center) { LineIcon("plus", Modifier.size(20.dp), neutralInk) }
                }
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { AppMenu(vm) }
            }
            Surface(Modifier.fillMaxWidth().padding(bottom = 10.dp),
                shape = RoundedCornerShape(20.dp), color = Color.White) {
                Column {
                    AnimatedVisibility(searchExpanded, enter = fadeIn(tween(140)) + expandVertically(),
                        exit = fadeOut(tween(100)) + shrinkVertically()) {
                        TextField(vm.threadSearch, { vm.threadSearch = it },
                            modifier = Modifier.fillMaxWidth().height(56.dp).focusRequester(searchFocus),
                            placeholder = { Text("搜索对话或项目", fontSize = 14.sp) }, singleLine = true,
                            leadingIcon = { LineIcon("search", Modifier.size(19.dp), Color(0xFF807D78)) },
                            trailingIcon = { if (vm.threadSearch.isNotEmpty()) IconButton({ vm.threadSearch = "" },
                                modifier = Modifier.semantics { contentDescription = "清空搜索" }) { Text("×", fontSize = 22.sp, color = Color(0xFF625F5B)) } },
                            shape = RoundedCornerShape(18.dp), colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                focusedLeadingIconColor = Color(0xFF95899D), unfocusedLeadingIconColor = Color(0xFF95899D)))
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            ThreadFilterChip("全部", matchingRows.size, !vm.onlyRunningThreads, selectedFilter, neutralInk) { vm.onlyRunningThreads = false }
                            ThreadFilterChip("进行中", matchingRows.count { it.id in vm.running }, vm.onlyRunningThreads,
                                selectedFilter, neutralInk) { vm.onlyRunningThreads = true }
                        }
                        IconButton(onClick = {
                            if (searchExpanded) {
                                vm.threadSearch = ""
                                searchExpanded = false
                            } else searchExpanded = true
                        }, modifier = Modifier.size(44.dp).semantics {
                            contentDescription = if (searchExpanded) "关闭搜索" else "搜索对话或项目"
                        }) {
                            LineIcon(if (searchExpanded) "close" else "search", Modifier.size(19.dp), Color(0xFF807D78))
                        }
                        AnimatedContent(rows.size, transitionSpec = {
                            (fadeIn(tween(120)) + slideInVertically(tween(160)) { it / 2 }) togetherWith
                                (fadeOut(tween(90)) + slideOutVertically(tween(130)) { -it / 2 })
                        }, label = "thread-count") { count ->
                            Text("$count 个", Modifier.padding(start = 2.dp, end = 7.dp),
                                fontSize = 12.sp, color = Color(0xFF85827C), maxLines = 1)
                        }
                    }
                }
            }
            AnimatedVisibility(!vm.connected, enter = fadeIn(tween(160)) + expandVertically(),
                exit = fadeOut(tween(120)) + shrinkVertically()) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (vm.busy) "正在恢复连接，先显示本地内容。" else "离线缓存 · 发送和同步需要连接。",
                        Modifier.weight(1f), fontSize = 12.sp, color = colors.onSurfaceVariant)
                    if (!vm.busy && vm.privateKey.isNotBlank()) TextButton(vm::connect,
                        contentPadding = PaddingValues(horizontal = 8.dp)) { Text("重连", color = neutralInk) }
                }
            }
            AnimatedVisibility(vm.busy, enter = fadeIn(tween(120)) + expandVertically(),
                exit = fadeOut(tween(100)) + shrinkVertically()) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF77746F), trackColor = Color(0xFFEAE9E5))
            }
            ErrorText(vm.error)
            val pendingSharedFiles = vm.sharedFilesFor(MobileViewModel.PENDING_SHARED_FILES)
            val pendingContexts = vm.contextFor(MobileViewModel.PENDING_SHARED_FILES)
            if (pendingSharedFiles.isNotEmpty() || pendingContexts.isNotEmpty()) Surface(Modifier.fillMaxWidth().padding(top = 6.dp),
                color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp)) {
                Text("已收到 ${pendingSharedFiles.size} 个文件和 ${pendingContexts.size} 项网页/文字上下文。打开或新建对话后即可使用。",
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "thread-empty-state") {
                    AnimatedVisibility(rows.isEmpty() && !vm.busy, enter = fadeIn(tween(180)) + expandVertically(),
                        exit = fadeOut(tween(120)) + shrinkVertically()) {
                        Surface(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(22.dp), color = Color.White) {
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(if (vm.threadSearch.isNotBlank()) "没有找到匹配的对话" else if (vm.onlyRunningThreads) "目前没有进行中的任务" else "从一个想法开始",
                                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (vm.threadSearch.isNotBlank()) "换个关键词，或清空搜索。" else if (vm.onlyRunningThreads) "切换到「全部」可继续已有对话。" else "创建新对话，把你想做的事告诉 Andri。",
                                    fontSize = 13.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (runningRows.isNotEmpty()) item(key = "running-header") {
                    Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 3.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("正在进行", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF4A4844))
                        Text(runningRows.size.toString(), fontSize = 12.sp, color = Color(0xFF77746F))
                    }
                }
                items(runningRows, key = { "running-" + it.id }) { thread ->
                    ThreadListRow(thread, vm, active = true, modifier = Modifier.animateItem(
                        fadeInSpec = tween(160), placementSpec = tween(210, easing = FastOutSlowInEasing),
                        fadeOutSpec = tween(120)))
                }
                if (recentRows.isNotEmpty()) item(key = "recent-header") {
                    Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = if (runningRows.isEmpty()) 5.dp else 10.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (runningRows.isEmpty()) "所有对话" else "最近对话", Modifier.weight(1f),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF4A4844))
                        Text(recentRows.size.toString() + " 个", fontSize = 12.sp, color = Color(0xFF8C8984))
                    }
                }
                items(recentRows, key = { it.id }) { thread ->
                    ThreadListRow(thread, vm, active = false, modifier = Modifier.animateItem(
                        fadeInSpec = tween(160), placementSpec = tween(210, easing = FastOutSlowInEasing),
                        fadeOutSpec = tween(120)))
                }
            }
        }

    }
}

@Composable
private fun ThreadSidebar(vm: MobileViewModel, activeId: String, onSelect: (String) -> Unit, onNew: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val matching = vm.threads.filter { thread ->
        query.isBlank() || thread.title.contains(query.trim(), ignoreCase = true) ||
            thread.model.contains(query.trim(), ignoreCase = true) ||
            vm.projectFor(thread.id).contains(query.trim(), ignoreCase = true)
    }
    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight().width(320.dp),
        drawerContainerColor = Color(0xFFFAF9F7),
        drawerContentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Codex", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onNew, enabled = vm.connected && !vm.busy,
                    modifier = Modifier.size(44.dp).semantics { contentDescription = "新建对话" }) {
                    LineIcon("plus", color = MaterialTheme.colorScheme.primary)
                }
            }
            Surface(onClick = onNew, enabled = vm.connected && !vm.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                shape = RoundedCornerShape(14.dp), color = Color.White,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LineIcon("plus", Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                    Text("新建对话", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            TextField(query, { query = it }, modifier = Modifier.fillMaxWidth().height(54.dp),
                placeholder = { Text("搜索对话或项目", fontSize = 13.sp) }, singleLine = true,
                leadingIcon = { LineIcon("search", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { LineIcon("close", Modifier.size(16.dp)) } },
                shape = RoundedCornerShape(14.dp), colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("最近对话", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${matching.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp),
                contentPadding = PaddingValues(bottom = 12.dp)) {
                items(matching, key = { "sidebar-${it.id}" }) { item ->
                    val selected = item.id == activeId
                    Surface(onClick = { onSelect(item.id) }, enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) Color(0xFFE9E8E4) else Color.Transparent) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val status = if (item.id in vm.running) "进行中" else vm.projectFor(item.id)
                            Text(status, fontSize = 11.sp, color = if (item.id in vm.running) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (matching.isEmpty()) item {
                    Text(if (vm.threads.isEmpty()) "还没有对话" else "没有匹配的对话",
                        Modifier.fillMaxWidth().padding(vertical = 20.dp), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!vm.connected) Text("离线时只能查看缓存对话", Modifier.padding(top = 6.dp), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThreadListRow(thread: CodexThread, vm: MobileViewModel, active: Boolean, modifier: Modifier = Modifier) {
    val status = vm.lastTaskStatus(thread.id)
    val idleLabel = status.ifBlank {
        thread.model.ifBlank { if (thread.title == "新对话") "尚未执行" else "继续对话" }
    }
    val activeColor = Color(0xFF618168)
    Column(modifier.fillMaxWidth()) {
        Surface(onClick = { vm.openThread(thread.id) }, enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            shape = RoundedCornerShape(8.dp), color = Color.Transparent) {
            Column(Modifier.fillMaxWidth().padding(vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(thread.title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                    color = Color(0xFF302F2C), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (active) {
                        Box(Modifier.size(6.dp).background(activeColor, CircleShape))
                        Text("正在执行", fontSize = 12.sp, color = activeColor)
                        Text("·", fontSize = 12.sp, color = Color(0xFF8C8984))
                        Text(vm.projectFor(thread.id), fontSize = 12.sp, color = Color(0xFF77746F),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        val statusColor = if ("失败" in status) MaterialTheme.colorScheme.error else Color(0xFF77746F)
                        Text(vm.projectFor(thread.id) + "  ·  " + idleLabel, fontSize = 12.sp, color = statusColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFFE7E5E1), thickness = 0.8.dp)
    }
}

@Composable
private fun ThreadFilterChip(label: String, count: Int, selected: Boolean, selectedColor: Color, selectedText: Color,
    onClick: () -> Unit) {
    val background by animateColorAsState(if (selected) selectedColor else Color.Transparent, tween(200), label = "filter-chip-background")
    val foreground by animateColorAsState(if (selected) selectedText else Color(0xFF625F5B), tween(200), label = "filter-chip-foreground")
    val emphasis by animateFloatAsState(if (selected) 1f else .97f, tween(180), label = "filter-chip-emphasis")
    Surface(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).graphicsLayer {
        scaleX = emphasis
        scaleY = emphasis
    }, shape = CircleShape,
        color = background,
        border = null) {
        Row(Modifier.padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = foreground)
            Text(count.toString(), fontSize = 11.sp, color = if (selected) selectedText.copy(alpha = 0.78f) else Color(0xFF77746F))
        }
    }
}

@Composable
private fun ThreadLoadingScreen(vm: MobileViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(vm::back, modifier = Modifier.semantics { contentDescription = "返回对话列表" }) { LineIcon("back") }
            Text("对话", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(32.dp)) {
                if (vm.openingThreadId != null) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                    Text("正在加载对话…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(vm.threadOpenError, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.activeId?.let(vm::openThread) }) { Text("重试") }
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(vm: MobileViewModel, addImage: () -> Unit, addVideo: () -> Unit, addFiles: () -> Unit) {
    val thread = vm.activeThread ?: return
    val sidebarState = rememberDrawerState(DrawerValue.Closed)
    val sidebarScope = rememberCoroutineScope()
    var serverFileLinkPath by rememberSaveable(thread.id) { mutableStateOf<String?>(null) }
    var serverDirectoryLinkPath by rememberSaveable(thread.id) { mutableStateOf<String?>(null) }
    val running = thread.id in vm.running
    val messages = foldCompletedProcessLines(visibleChatLines(vm.messages[thread.id].orEmpty()),
        vm.running[thread.id], running)
    val keyedMessages = remember(messages) {
        val used = mutableSetOf<String>()
        messages.asReversed().mapIndexed { index, line ->
            var key = line.id.takeIf { it.isNotBlank() } ?: "chat-row-$index"
            while (!used.add(key)) key = "${line.id.ifBlank { "chat-row" }}-$index-${used.size}"
            line to key
        }
    }
    val sending = thread.id in vm.sending
    val list = rememberLazyListState()
    var expanded by rememberSaveable(thread.id) { mutableStateOf(messages.isEmpty()) }
    var focusComposer by rememberSaveable(thread.id) { mutableStateOf(messages.isEmpty()) }
    var composerHeight by remember { mutableStateOf(64.dp) }
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val draft = vm.drafts[thread.id].orEmpty()
    var composerValue by rememberSaveable(thread.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
    }
    LaunchedEffect(thread.id, draft) {
        if (composerValue.text != draft) composerValue = TextFieldValue(draft, TextRange(draft.length))
    }
    val imageAttachments = vm.imageAttachments[thread.id].orEmpty()
    val videoAttachment = vm.videoAttachments[thread.id]
    val sharedFiles = vm.sharedFilesFor(thread.id)
    val references = vm.contextFor(thread.id)
    var contextDrawerOpen by rememberSaveable(thread.id) { mutableStateOf(false) }
    var webLinkToOpen by rememberSaveable(thread.id) { mutableStateOf<String?>(null) }
    fun openChatLink(url: String) {
        val serverPath = serverWorkspacePathFromLink(url)
        if (serverPath != null) {
            if (serverWorkspaceLinkIsDirectory(url)) serverDirectoryLinkPath = serverPath.trimEnd('/')
            else serverFileLinkPath = serverPath
        } else webLinkToOpen = url
    }
    var leftBottom by rememberSaveable(thread.id) { mutableStateOf(false) }
    fun collapse() { expanded = false; focusComposer = false; keyboard?.hide() }
    BackHandler(expanded) { collapse() }
    LaunchedEffect(expanded, focusComposer) { if (expanded && focusComposer) { focus.requestFocus(); keyboard?.show() } }
    LaunchedEffect(list) {
        snapshotFlow {
            Triple(list.isScrollInProgress, list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0, expanded)
        }.collect { (scrolling, atBottom, isExpanded) ->
            if (scrolling && !atBottom) {
                leftBottom = true
                if (isExpanded) collapse()
            } else if (!scrolling && atBottom && leftBottom) {
                leftBottom = false
                focusComposer = false
                expanded = true
            }
        }
    }
    ModalNavigationDrawer(
        drawerState = sidebarState,
        gesturesEnabled = true,
        scrimColor = Color(0x66000000),
        drawerContent = {
            ThreadSidebar(vm, thread.id,
                onSelect = { id ->
                    sidebarScope.launch {
                        sidebarState.close()
                        if (id != thread.id) vm.openThread(id)
                    }
                },
                onNew = {
                    sidebarScope.launch {
                        sidebarState.close()
                        vm.newThread()
                    }
                })
        }
    ) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { sidebarScope.launch { sidebarState.open() } },
                modifier = Modifier.semantics { contentDescription = "打开对话侧栏" }) { LineIcon("sidebar") }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(thread.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val options = vm.currentExecution
                Text("82 · " + options.cwd.trimEnd('/').substringAfterLast('/').ifBlank { "/" } + " · " + options.mode.label,
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AppMenu(vm, thread)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (!vm.connected) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (vm.busy) "正在连接服务器 · 显示本地内容" else "离线缓存 · 连接后同步最新内容",
                    Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!vm.busy && vm.privateKey.isNotBlank()) {
                    TextButton(vm::connect, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("重连") }
                }
            }
        }
        UpdateNotice(vm)
        ErrorText(vm.error)
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val queuedTasks = vm.queuedTasks[thread.id].orEmpty()
        AnimatedVisibility(queuedTasks.isNotEmpty(), enter = fadeIn(tween(160)) + expandVertically(), exit = fadeOut(tween(120)) + shrinkVertically()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                    val hasUnconfirmed = queuedTasks.any { !it.autoStartAllowed }
                    Text(if (hasUnconfirmed) "有旧排队消息需要确认；已确认消息会在任务结束后自动发送"
                        else "排队中 · ${queuedTasks.size} 条消息将在当前任务结束后发送", fontSize = 12.sp,
                        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.secondary)
                    queuedTasks.take(3).forEachIndexed { index, task ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}. ${if (task.autoStartAllowed) "" else "待确认 · "}${task.prompt.replace("\r\n", " ").take(64)}",
                                Modifier.weight(1f), fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!task.autoStartAllowed) TextButton({ vm.startQueuedTask(task.id) },
                                enabled = index == 0 && !running, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text("启动", fontSize = 11.sp)
                            }
                            TextButton({ vm.removeQueuedTask(task.id) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("移除", fontSize = 11.sp) }
                        }
                    }
                    if (queuedTasks.size > 3) Text("另有 ${queuedTasks.size - 3} 条", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true,
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.Bottom) {
                // reverseLayout places the first item at the physical bottom. Keep a real
                // spacer here so the last message clears the floating composer.
                item("chatBottom") { Spacer(Modifier.height(chatBottomInset(composerHeight.value, expanded).dp)) }
                item("chatActivity") {
                    androidx.compose.animation.AnimatedVisibility(running || sending, enter = fadeIn(tween(180)) + expandVertically(), exit = fadeOut(tween(140)) + shrinkVertically()) {
                    Surface(Modifier.padding(top = 20.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                        Text("●  " + (vm.activity[thread.id] ?: "Andri 正在处理…"), Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    }
                }
                items(keyedMessages, key = { it.second }) { (line, _) ->
                    Column(Modifier.animateItem(
                        fadeInSpec = tween(190), placementSpec = tween(230, easing = FastOutSlowInEasing),
                        fadeOutSpec = tween(130)).fillMaxWidth().padding(top = 24.dp)) {
                        when (line.role) {
                            "error" -> TimelineError(line)
                            "thought" -> TimelineOutput(line, vm, ::openChatLink)
                            "tool", "request" -> TimelineOutput(line, vm, ::openChatLink)
                            "user" -> BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                Surface(Modifier.widthIn(max = maxWidth * 0.88f), color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(22.dp)) {
                                    Column(Modifier.padding(horizontal = 17.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (line.text.isNotBlank()) ChatMarkdown(line.text, vm, thread.cwd.ifBlank { vm.cwd }, ::openChatLink)
                                        if (line.images.isNotEmpty()) ChatImageGallery(line.images, vm, thread.cwd.ifBlank { vm.cwd })
                                    }
                                }
                            }
                            else -> {
                                SmoothAssistantMessage(line, vm, ::openChatLink)
                                if (line.questions.isNotEmpty()) AsyncQuestionCard(vm, line,
                                    messages.dropWhile { it.id != line.id }.drop(1).none { it.role == "user" })
                            }
                        }
                    }
                }
                item(key = "chat-welcome") {
                    androidx.compose.animation.AnimatedVisibility(messages.isEmpty() && !vm.busy,
                        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                        exit = fadeOut(tween(140)) + shrinkVertically(tween(180))) {
                        Column(Modifier.padding(top = 40.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            BrandMark(44)
                            Text("把想法，\r\n变成下一步。", fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp)
                            Text("写下你想完成的事。\r\nAndri 会在服务器上与你一起处理。", fontSize = 14.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item("historyHeader") {
                    if (!vm.historyCursors[thread.id].isNullOrBlank()) TextButton(vm::loadOlder, enabled = !vm.busy) { Text("查看更早的消息", fontSize = 12.sp) }
                }
            }
            // Only the rounded surface receives input; there is no opaque rectangular footer.
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp).padding(top = 18.dp, bottom = 18.dp)) {
                androidx.compose.animation.AnimatedVisibility(!expanded, modifier = Modifier.align(Alignment.BottomStart).offset(y = (-60).dp),
                    enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = .88f),
                    exit = fadeOut(tween(120)) + scaleOut(tween(180), targetScale = .88f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        Surface(onClick = { focusComposer = true; expanded = true }, modifier = Modifier.size(58.dp)
                            .semantics { contentDescription = "展开输入框" }, shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 2.dp) {
                            Box(contentAlignment = Alignment.Center) { LineIcon("edit", Modifier.size(24.dp), MaterialTheme.colorScheme.primary) }
                        }
                        if (draft.isNotBlank() || imageAttachments.isNotEmpty() || videoAttachment != null || sharedFiles.isNotEmpty() || references.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd).size(9.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    }
                }
                }
                androidx.compose.animation.AnimatedVisibility(expanded, modifier = Modifier.align(Alignment.BottomStart),
                    enter = fadeIn(tween(180)) + slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 4 },
                    exit = fadeOut(tween(140)) + slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it / 4 }) {
                Surface(Modifier.fillMaxWidth().onSizeChanged { composerHeight = with(density) { it.height.toDp() } }, shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.animateContentSize(tween(180)).padding(6.dp)) {
                        if (imageAttachments.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                imageAttachments.forEachIndexed { index, image ->
                                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 9.dp)) {
                                            Text(image.label, Modifier.widthIn(max = 140.dp), fontSize = 11.sp,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            IconButton({ vm.removeImage(index) }, Modifier.size(34.dp)
                                                .semantics { contentDescription = "移除${image.label}" }) { Text("×") }
                                        }
                                    }
                                }
                            }
                        }
                        videoAttachment?.let {
                            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                LineIcon("play", Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                                Text("${it.label} · ${formatVideoSize(it.sizeBytes)} · 原画质", Modifier.weight(1f).padding(start = 8.dp),
                                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(vm::removeVideo, Modifier.size(44.dp).semantics { contentDescription = "移除视频" }) { Text("×") }
                            }
                        }
                        sharedFiles.forEachIndexed { index, file ->
                            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                LineIcon("file", Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                                Text("${file.label} · ${formatVideoSize(file.sizeBytes)}", Modifier.weight(1f).padding(start = 8.dp),
                                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton({ vm.removeSharedFile(index) }, Modifier.size(44.dp).semantics { contentDescription = "移除${file.label}" }) { Text("×") }
                            }
                        }
                        references.forEach { reference ->
                            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                LineIcon("file", Modifier.size(18.dp), MaterialTheme.colorScheme.secondary)
                                Text(reference.label, Modifier.weight(1f).padding(start = 8.dp),
                                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton({ vm.removeContextReference(reference.id) }, Modifier.size(44.dp)
                                    .semantics { contentDescription = "移除上下文 ${reference.label}" }) { Text("×") }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            var tools by remember { mutableStateOf(false) }
                            Box {
                                IconButton({ tools = true }, Modifier.size(44.dp).semantics { contentDescription = "附件与模型" }) { LineIcon("plus") }
                                DropdownMenu(tools, { tools = false }, modifier = Modifier.width(300.dp), shape = RoundedCornerShape(24.dp),
                                    containerColor = Color.White, tonalElevation = 0.dp,
                                    shadowElevation = 4.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Surface(onClick = { tools = false; addImage() }, enabled = !sending,
                                            color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                LineIcon("paperclip", Modifier.size(20.dp))
                                                Text("添加图片", fontSize = 14.sp)
                                            }
                                        }
                                        Surface(onClick = { tools = false; addVideo() }, enabled = !sending,
                                            color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                LineIcon("play", Modifier.size(20.dp))
                                                Text("添加视频（原画质）", fontSize = 14.sp)
                                            }
                                        }
                                        Surface(onClick = { tools = false; addFiles() }, enabled = !sending,
                                            color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                LineIcon("file", Modifier.size(20.dp))
                                                Text("添加文件（也可从 QQ / 微信分享）", fontSize = 14.sp)
                                            }
                                        }
                                        Surface(onClick = { tools = false; contextDrawerOpen = true }, enabled = !sending,
                                            color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                LineIcon("sliders", Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                                                Text("打开任务工作区${if (references.isNotEmpty()) " · ${references.size}" else ""}", fontSize = 14.sp,
                                                    color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf("gpt-6-luna", "gpt-6.1-sol").forEach { model ->
                                                val selected = vm.model == model
                                                Surface(onClick = { vm.selectModel(model) }, enabled = !running && !sending,
                                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.White,
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                                    Text(model, Modifier.padding(vertical = 12.dp), fontSize = 12.sp,
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                        ReasoningEffortSlider(vm.currentExecution.effort, vm.reasoningEfforts(), vm.model, !running && !sending, vm::setReasoningEffort)
                                    }
                                }
                            }
                            BasicTextField(composerValue, { value ->
                                composerValue = value
                                vm.updateDraft(thread.id, value.text)
                            },
                                Modifier.weight(1f).padding(vertical = 10.dp, horizontal = 4.dp).focusRequester(focus)
                                    .pointerInput(thread.id, composerValue.text) {
                                        detectTapGestures(onTap = {
                                            composerValue = composerValue.copy(selection = TextRange(composerValue.text.length))
                                            focus.requestFocus()
                                        })
                                    }
                                    .onFocusChanged { state -> if (state.isFocused) composerValue = composerValue.copy(selection = TextRange(composerValue.text.length)) },
                                enabled = !sending, minLines = 1, maxLines = 4,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, lineHeight = 24.sp),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { field ->
                                    if (draft.isEmpty()) Text("发消息…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                                    field()
                                })
                            if (running) {
                                val hasQueuedContent = draft.isNotBlank() || imageAttachments.isNotEmpty() || videoAttachment != null || sharedFiles.isNotEmpty() || references.isNotEmpty()
                                FilledIconButton(
                                    onClick = if (hasQueuedContent) vm::enqueueDraft else vm::stop,
                                    enabled = vm.connected && (if (hasQueuedContent) thread.id !in vm.queueSubmitting && !vm.busy else !vm.busy),
                                    modifier = Modifier.size(44.dp).semantics {
                                        contentDescription = if (hasQueuedContent) "排队发送消息" else "暂停当前任务"
                                    },
                                    shape = CircleShape
                                ) {
                                    if (thread.id in vm.queueSubmitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else LineIcon(if (hasQueuedContent) "paperplane" else "pause", Modifier.size(21.dp))
                                }
                            } else FilledIconButton(vm::send,
                                enabled = vm.connected && (draft.isNotBlank() || imageAttachments.isNotEmpty() || videoAttachment != null || sharedFiles.isNotEmpty() || references.isNotEmpty()) && !sending && !vm.busy,
                                modifier = Modifier.size(44.dp).semantics { contentDescription = "发送消息" }, shape = CircleShape) {
                                if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else LineIcon("paperplane", Modifier.size(21.dp))
                            }
                        }
                    }
                }
                }
            }
        }
    }
    }
    if (contextDrawerOpen) ContextDrawer(vm, thread.cwd.ifBlank { vm.cwd }, addImage, addVideo, addFiles,
        onDismiss = { contextDrawerOpen = false })
    webLinkToOpen?.let { url ->
        WebPageContextDialog(url, onDismiss = { webLinkToOpen = null }, onAdd = { title, prompt ->
            vm.addContextReference(title, prompt)
            webLinkToOpen = null
        }, allowContextImport = false)
    }
    serverFileLinkPath?.let { path ->
        ServerWorkspaceFileLinkDialog(vm, path) { serverFileLinkPath = null }
    }
    serverDirectoryLinkPath?.let { path ->
        ServerWorkspaceDirectoryLinkDialog(vm, path) { serverDirectoryLinkPath = null }
    }
}

@Composable
private fun SmoothAssistantMessage(line: ChatLine, vm: MobileViewModel, onOpenLink: (String) -> Unit) {
    var visible by rememberSaveable(line.id) { mutableStateOf(if (line.streaming) "" else assistantAnswerText(line.text)) }
    var animate by remember(line.id) { mutableStateOf(line.streaming) }
    val answerText = assistantAnswerText(line.text)
    // The ViewModel replaces the line for each delta. Keying by the actual target
    // makes each replacement observable while keeping the already revealed prefix.
    LaunchedEffect(line.id, answerText, line.streaming) {
        animate = animate || line.streaming
        if (!animate) {
            visible = answerText
            return@LaunchedEffect
        }
        while (visible != answerText) {
            withFrameNanos { }
            visible = nextStreamText(visible, answerText)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (visible.isNotEmpty()) ChatMarkdown(visible, vm, vm.activeThread?.cwd?.ifBlank { vm.cwd } ?: vm.cwd, onOpenLink)
        if (line.images.isNotEmpty()) ChatImageGallery(line.images, vm, vm.activeThread?.cwd?.ifBlank { vm.cwd } ?: vm.cwd)
    }
}

private fun assistantAnswerText(text: String): String {
    val marker = Regex("(?m)^[ \\t]*(?:改动见|改动位于|修改见|修改位于|相关文件见|文件改动见|代码改动见)\\s*[:：]?").find(text)
    return if (marker != null && text.substring(0, marker.range.first).isNotBlank())
        text.substring(0, marker.range.first).trimEnd() else text
}

@Composable
private fun ChatImageGallery(sources: List<String>, vm: MobileViewModel, baseDirectory: String) {
    var selectedIndex by remember(sources) { mutableIntStateOf(-1) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sources.forEachIndexed { index, source ->
            ChatImageSource(source, vm, baseDirectory, compact = true, onClick = { selectedIndex = index },
                modifier = Modifier.size(76.dp))
        }
    }
    if (selectedIndex >= 0) {
        val pagerState = rememberPagerState(initialPage = selectedIndex) { sources.size }
        Dialog(onDismissRequest = { selectedIndex = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    ChatImageSource(sources[page], vm, baseDirectory, galleryPage = true)
                }
                TextButton(onClick = { selectedIndex = -1 }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    Text("关闭", color = Color.White)
                }
                if (sources.size > 1) {
                    Text("${pagerState.currentPage + 1} / ${sources.size}",
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp), color = Color.White,
                        fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ChatImage(
    source: String,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    galleryPage: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var originalBytes by remember(source) { mutableStateOf<ByteArray?>(null) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, source) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = when {
                    source.startsWith("data:image/", true) -> {
                        val encoded = source.substringAfter(",", "")
                        check(encoded.length <= MAX_CHAT_IMAGE_BYTES * 2) { "图片超过 10 MB" }
                        Base64.decode(encoded, Base64.DEFAULT).also {
                            check(it.size <= MAX_CHAT_IMAGE_BYTES) { "图片超过 10 MB" }
                        }
                    }
                    source.startsWith("https://", true) ->
                        remoteImageLoadSemaphore.withPermit { loadCachedRemoteImage(source, context.cacheDir) }
                    source.startsWith("file:") -> {
                        val file = File(URI(source)).canonicalFile
                        val allowedFolders = listOf("markdown-images", "linked-images").map {
                            File(context.cacheDir, it).canonicalPath + "/"
                        }
                        check(allowedFolders.any(file.path::startsWith)) { "图片缓存路径无效" }
                        check(file.length() in 1..MAX_CHAT_IMAGE_BYTES.toLong()) { "图片超过 10 MB" }
                        file.readBytes()
                    }
                    else -> null
                }
                originalBytes = bytes
                bytes?.let(::decodeChatBitmap)
            }.getOrNull()
        }
    }
    var previewOpen by remember(source) { mutableStateOf(false) }
    val previewTransition = remember(source) { MutableTransitionState(false) }
    val previewScope = rememberCoroutineScope()
    fun openPreview() {
        previewOpen = true
        previewTransition.targetState = true
    }
    fun closePreview() {
        if (!previewOpen || !previewTransition.targetState) return
        previewTransition.targetState = false
        previewScope.launch {
            delay(220)
            if (!previewTransition.targetState) previewOpen = false
        }
    }
    val previewBitmap by produceState<android.graphics.Bitmap?>(null, source, previewOpen, originalBytes) {
        value = if (!previewOpen && !galleryPage) null else withContext(Dispatchers.IO) {
            originalBytes?.let { decodeChatBitmap(it, MAX_CHAT_IMAGE_PREVIEW_PIXELS) }
        }
    }
    var saveStatus by remember(source) { mutableStateOf("") }
    val mimeType = remember(source, originalBytes) { imageMimeType(source, originalBytes) }
    val suggestedName = remember(source, mimeType) { imageFileName(source, mimeType) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = originalBytes
        saveStatus = runCatching {
            check(bytes != null) { "图片尚未加载完成" }
            context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            "原图已保存"
        }.getOrElse { "保存失败：${it.message ?: "无法写入文件"}" }
    }
    fun saveOriginal() {
        if (originalBytes == null) saveStatus = "图片尚未加载完成"
        else saveLauncher.launch(suggestedName)
    }

    if (bitmap != null && galleryPage) {
        Box(Modifier.fillMaxSize()) {
            Image((previewBitmap ?: bitmap)!!.asImageBitmap(), contentDescription = "图片预览",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            TextButton(onClick = ::saveOriginal, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text("下载原图", color = Color.White)
            }
            if (saveStatus.isNotBlank()) Text(saveStatus, Modifier.align(Alignment.BottomEnd).padding(24.dp),
                fontSize = 12.sp, color = Color.White)
        }
    } else if (bitmap != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Image(bitmap!!.asImageBitmap(), contentDescription = if (compact) "点按放大图片" else "点按查看原图",
                modifier = (if (compact) modifier else modifier.fillMaxWidth().heightIn(max = 360.dp))
                    .clip(RoundedCornerShape(if (compact) 10.dp else 14.dp))
                    .clickable { onClick?.invoke() ?: openPreview() },
                contentScale = if (compact) ContentScale.Crop else ContentScale.Fit)
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::openPreview) { Text("查看原图") }
                    TextButton(onClick = ::saveOriginal) { Text("下载原图") }
                    if (saveStatus.isNotBlank()) Text(saveStatus, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else if (source.isNotBlank()) {
        if (compact) {
            Box(modifier.size(76.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text("图片", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Text("图片暂时无法显示", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (previewOpen && bitmap != null) {
        val zoomBitmap = previewBitmap ?: bitmap
        var scale by remember(source) { mutableFloatStateOf(1f) }
        var offsetX by remember(source) { mutableFloatStateOf(0f) }
        var offsetY by remember(source) { mutableFloatStateOf(0f) }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = ::closePreview,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            AnimatedVisibility(visibleState = previewTransition,
                enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = .96f),
                exit = fadeOut(tween(150)) + scaleOut(tween(180), targetScale = .98f)) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Image(zoomBitmap!!.asImageBitmap(), contentDescription = "原图预览，可双指缩放并拖动",
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(source) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    if (scale <= 1f) { offsetX = 0f; offsetY = 0f }
                                    else { offsetX += pan.x; offsetY += pan.y }
                                }
                            }
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
                        contentScale = ContentScale.Fit)
                    TextButton(onClick = ::closePreview, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                        Text("关闭", color = Color.White)
                    }
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) { Text("重置缩放", color = Color.White) }
                        TextButton(onClick = ::saveOriginal) { Text("下载原图", color = Color.White) }
                    }
                }
            }
        }
    }
}

private fun imageMimeType(source: String, bytes: ByteArray?): String {
    if (source.startsWith("data:image/", true)) return source.substringAfter("data:").substringBefore(';')
    val path = runCatching { if (source.startsWith("file:", true)) URI(source).path else URI(source).path }.getOrNull().orEmpty()
    return when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
        "avif" -> "image/avif"; "heic" -> "image/heic"; "heif" -> "image/heif"
        else -> when {
            bytes?.getOrNull(0) == 0xFF.toByte() && bytes.getOrNull(1) == 0xD8.toByte() -> "image/jpeg"
            bytes?.take(8)?.toByteArray()?.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) == true -> "image/png"
            bytes?.take(6)?.toByteArray()?.decodeToString()?.startsWith("GIF8") == true -> "image/gif"
            else -> "image/jpeg"
        }
    }
}

private fun imageFileName(source: String, mimeType: String): String {
    val extension = when (mimeType.lowercase()) {
        "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; "image/bmp" -> "bmp"
        "image/avif" -> "avif"; "image/heic" -> "heic"; "image/heif" -> "heif"; else -> "jpg"
    }
    val candidate = runCatching { URI(source).path.orEmpty().substringAfterLast('/') }.getOrDefault("")
        .substringBefore('?').take(80)
    return candidate.takeIf { it.matches(Regex("[A-Za-z0-9._-]+\\.[A-Za-z0-9]{1,8}")) } ?: "andri-image.$extension"
}

private const val MAX_CHAT_IMAGE_BYTES = 10 * 1024 * 1024
private val remoteImageLoadSemaphore = Semaphore(permits = 4)
private const val MAX_CHAT_IMAGE_SOURCE_PIXELS = 100_000_000L
private const val MAX_CHAT_IMAGE_DECODE_PIXELS = 2_500_000L
private const val MAX_CHAT_IMAGE_PREVIEW_PIXELS = 20_000_000L

private fun decodeChatBitmap(bytes: ByteArray, maxPixels: Long = MAX_CHAT_IMAGE_DECODE_PIXELS): android.graphics.Bitmap? {
    if (bytes.isEmpty() || bytes.size > MAX_CHAT_IMAGE_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_CHAT_IMAGE_SOURCE_PIXELS) return null
    var sample = 1
    while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels ||
        maxOf(width, height) / sample > 8192) sample *= 2
    return try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
        })
    } catch (_: OutOfMemoryError) {
        null
    }
}

@Composable
private fun ChatImageSource(
    source: String,
    vm: MobileViewModel,
    baseDirectory: String,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    galleryPage: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cachedFile = source.startsWith("file:") && runCatching {
        File(URI(source)).canonicalPath.startsWith(File(context.cacheDir, "markdown-images").canonicalPath + "/")
    }.getOrDefault(false)
    if (source.startsWith("http://", true)) {
        Text("图片链接不安全，已跳过", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (source.startsWith("https://", true) || source.startsWith("data:image/", true) || cachedFile) {
        ChatImage(source, compact, onClick, galleryPage, modifier)
    } else {
        val serverPath = if (source.startsWith("file:", true)) runCatching { URI(source).path }.getOrNull() ?: source else source
        val local by produceState<String?>(null, serverPath, baseDirectory) {
            value = runCatching { vm.cacheWorkspaceImage(serverPath, baseDirectory) }.getOrNull() ?: ""
        }
        when {
            local == null -> Text("正在读取工作区图片…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            local!!.isBlank() -> Text("无法读取工作区图片", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> ChatImage(local!!, compact, onClick, galleryPage, modifier)
        }
    }
}

@Composable
private fun ChatMarkdown(markdown: String, vm: MobileViewModel, baseDirectory: String, onOpenLink: (String) -> Unit = {}) {
    val bounded = truncateChatText(markdown)
    MarkdownMessage(bounded, onOpenLink)
    if (markdown.codePointCount(0, markdown.length) > MAX_MARKDOWN_RENDER_CHARS) {
        Text("内容较长，仅展示前 ${MAX_MARKDOWN_RENDER_CHARS / 1000} 千字符。", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    markdownImageDestinations(bounded).distinct().forEach { destination ->
        ChatImageSource(destination, vm, baseDirectory)
    }
    videoAttachmentPaths(bounded).forEach { path -> ChatVideoSource(path, vm, baseDirectory) }
}

private fun videoAttachmentPaths(text: String): List<String> {
    val candidates = buildList {
        addAll(Regex("(?:视频附件（原画质文件）：)?((?:/|\\./|codex/)[^\\s<>\\\"'`]+)").findAll(text).map { it.groupValues[1] })
        addAll(Regex("`([^`]+\\.(?:mp4|m4v|mov|3gp|webm|mkv|avi))`", RegexOption.IGNORE_CASE).findAll(text).map { it.groupValues[1] })
        addAll(Regex("""\]\(<?([^)>\s]+\.(?:mp4|m4v|mov|3gp|webm|mkv|avi))>?\)""", RegexOption.IGNORE_CASE).findAll(text).map { it.groupValues[1] })
    }
    return candidates.map { it.trimEnd('.', ',', ';', ')', ']', '}', '`') }
        .filter { it.substringAfterLast('/').substringAfterLast('.').lowercase() in SshCodexClient.VIDEO_EXTENSIONS }
        .distinct()
}

@Composable
private fun ChatVideoSource(path: String, vm: MobileViewModel, baseDirectory: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val videoResult by produceState<Result<File>?>(null, path, baseDirectory, vm.connected) {
        value = try {
            Result.success(vm.cacheWorkspaceVideo(path, baseDirectory))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    val videoFile = videoResult?.getOrNull()
    val videoAspectRatio by produceState(16f / 9f, videoFile) {
        val file = videoFile
        value = if (file == null) 16f / 9f else withContext(Dispatchers.IO) {
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    var width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull()
                        ?: error("无法读取视频宽度")
                    var height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull()
                        ?: error("无法读取视频高度")
                    val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rotation == 90 || rotation == 270) { val oldWidth = width; width = height; height = oldWidth }
                    (width / height).coerceIn(0.1f, 10f)
                } finally { retriever.release() }
            }.getOrDefault(16f / 9f)
        }
    }
    var status by remember(path) { mutableStateOf("") }
    val name = path.substringAfterLast('/').substringAfterLast('\\').take(100).ifBlank { "andri-video.mp4" }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/*")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val file = videoFile
        status = runCatching {
            check(file?.isFile == true) { "视频尚未下载完成" }
            context.contentResolver.openOutputStream(uri, "w")!!.use { output -> file.inputStream().use { it.copyTo(output, 128 * 1024) } }
            "原画质视频已保存"
        }.getOrElse { "保存失败：${it.message ?: "无法写入文件"}" }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when {
            videoResult == null -> Text("正在下载原画质视频…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            videoFile?.isFile != true -> Text("视频暂时无法读取：${videoResult?.exceptionOrNull()?.message ?: "请检查 SSH 连接"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> AndroidView(
                modifier = Modifier.fillMaxWidth().aspectRatio(videoAspectRatio).clip(RoundedCornerShape(14.dp)),
                factory = { viewContext -> android.widget.VideoView(viewContext).apply {
                    tag = videoFile!!.absolutePath
                    setVideoPath(videoFile!!.absolutePath)
                    setMediaController(android.widget.MediaController(viewContext).also { it.setAnchorView(this) })
                } },
                update = { view -> if (view.tag != videoFile!!.absolutePath) {
                    view.tag = videoFile!!.absolutePath
                    view.setVideoPath(videoFile!!.absolutePath)
                    view.setMediaController(android.widget.MediaController(context).also { it.setAnchorView(view) })
                } }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (videoFile?.isFile == true) saveLauncher.launch(name) }) {
                Text("下载原画质视频", color = MaterialTheme.colorScheme.primary)
            }
            if (status.isNotBlank()) Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatVideoSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
}

private const val MAX_MARKDOWN_RENDER_CHARS = 48_000
private fun truncateChatText(value: String): String =
    if (value.codePointCount(0, value.length) <= MAX_MARKDOWN_RENDER_CHARS) value
    else value.substring(0, value.offsetByCodePoints(0, MAX_MARKDOWN_RENDER_CHARS))

private fun markdownImageDestinations(markdown: String): List<String> {
    val definitions = Regex("""(?im)^ {0,3}\[([^]]+)]\s*:\s*(?:<([^>]+)>|(\S+?))(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\s*$""")
        .findAll(markdown).associate { match ->
            match.groupValues[1].trim().replace(Regex("\\s+"), " ").lowercase() to
                (match.groups[2]?.value ?: match.groupValues[3]).trim()
        }
    val inline = Regex("!\\[([^]]*)]\\s*\\(\\s*(?:<([^>]+)>|((?:\\\\.|[^\\s)])+))(?:\\s+(?:\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?\\s*\\)")
        .findAll(markdown).map { it.groupValues[2].ifBlank { it.groupValues[3] } }.toList()
    val reference = Regex("!\\[([^]]*)]\\s*(?:\\[([^]]*)])?").findAll(markdown).mapNotNull { match ->
        val key = (match.groupValues[2].ifBlank { match.groupValues[1] }).trim().replace(Regex("\\s+"), " ").lowercase()
        definitions[key]
    }.toList()
    val markdownImages = (inline + reference)
    // Also treat direct HTTPS links ending in a known image extension as images.
    // Query strings are allowed, which covers common signed CDN links.
    val directImageLinks = Regex("https://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
        .findAll(markdown)
        .map { it.value.trimEnd('.', ',', ';', ':', ')', ']', '}') }
        .filter { raw ->
            val path = runCatching { URI(raw).path.orEmpty() }.getOrDefault("")
            path.substringAfterLast('.', "").lowercase() in
                setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "heic", "heif")
        }
        .toList()
    return (markdownImages + directImageLinks).distinct().mapNotNull { raw ->
        val value = if (raw.startsWith("file:", true)) runCatching { URI(raw).path }.getOrNull() ?: raw else raw
        value.takeIf { it.isNotBlank() && !it.contains('\u0000') }
    }
}

private fun loadCachedRemoteImage(url: String, cacheDir: File): ByteArray {
    val folder = File(cacheDir, "linked-images").apply { mkdirs() }
    val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val cached = File(folder, "$key.img")
    if (cached.isFile && cached.length() in 1..MAX_CHAT_IMAGE_BYTES.toLong()) {
        cached.setLastModified(System.currentTimeMillis())
        return cached.readBytes()
    }

    val connection = URL(url).openConnection().apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
        setRequestProperty("User-Agent", "Andri/Android")
    }
    val contentType = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
    val pathExtension = runCatching { URI(url).path.orEmpty().substringAfterLast('.', "").lowercase() }
        .getOrDefault("")
    check(contentType.startsWith("image/") || pathExtension in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "heic", "heif")) {
        "链接返回的不是图片（${contentType.ifBlank { "未知类型" }}）"
    }
    val bytes = connection.getInputStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= MAX_CHAT_IMAGE_BYTES) { "图片超过 10 MB" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    check(bytes.isNotEmpty()) { "图片为空" }
    val staged = File(folder, "$key-${java.util.UUID.randomUUID()}.tmp")
    staged.writeBytes(bytes)
    check(staged.renameTo(cached)) { "无法缓存图片" }
    trimLinkedImageCache(folder)
    CacheMaintenance.trim(cacheDir)
    return bytes
}

private fun trimLinkedImageCache(folder: File) {
    val files = folder.listFiles()?.filter { it.isFile && it.extension == "img" }
        ?.sortedBy { it.lastModified() } ?: return
    var total = files.sumOf { it.length() }
    for (file in files) {
        if (total <= MAX_LINKED_IMAGE_CACHE_BYTES) break
        total -= file.length()
        file.delete()
    }
}

private const val MAX_LINKED_IMAGE_CACHE_BYTES = 100L * 1024 * 1024

@Composable
private fun TimelineOutput(line: ChatLine, vm: MobileViewModel, onOpenLink: (String) -> Unit) {
    val summaryActive = reasoningSummaryActive(line, vm.running[vm.activeId])
    var expanded by rememberSaveable(line.id) { mutableStateOf(summaryActive) }
    var detailsExpanded by rememberSaveable(line.id) { mutableStateOf(false) }
    // Item completion is not turn completion: keep the summary open until the whole task ends.
    LaunchedEffect(line.id, summaryActive) {
        if (line.isReasoningSummary) expanded = summaryActive
    }
    val pending = vm.requests.firstOrNull { "request:" + it.opt("id") == line.id }
    val status = when (line.status) {
        "pending" -> "待处理"; "inProgress" -> "进行中"; "failed" -> "失败"; "declined" -> "已拒绝"
        "answered" -> "已回答"; "approved" -> "已批准"; "interrupted" -> "已中断"; "resolved" -> "已处理"; else -> "已完成"
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Surface(onClick = { expanded = !expanded }, color = Color.Transparent) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LineIcon(if (line.role == "request") "sliders" else "code", Modifier.size(16.dp), MaterialTheme.colorScheme.secondary)
                    Text(line.stage, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                    Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (expanded) "⌃" else "⌄", fontSize = 14.sp)
                }
            }
            AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    if (line.processItems.isNotEmpty()) {
                        val summaries = line.processItems.filter { it.isReasoningSummary && it.text.isNotBlank() }
                        val details = line.processItems.filterNot { it.isReasoningSummary }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Codex 汇报摘要", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.secondary)
                            if (summaries.isEmpty()) {
                                Text("本轮没有可展示的摘要。", Modifier.fillMaxWidth(), fontSize = 12.sp,
                                    lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    summaries.forEach { MarkdownMessage(it.text, onOpenLink) }
                                }
                            }
                            if (details.isNotEmpty()) {
                                Surface(onClick = { detailsExpanded = !detailsExpanded },
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(10.dp)) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        LineIcon("code", Modifier.size(14.dp), MaterialTheme.colorScheme.secondary)
                                        Text("脚本、命令与执行明细（${details.size} 项）", Modifier.weight(1f), fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(if (detailsExpanded) "⌃" else "⌄", fontSize = 13.sp)
                                    }
                                }
                                AnimatedVisibility(detailsExpanded, enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.padding(top = 2.dp)) {
                                        details.forEach { item ->
                                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text(item.stage, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.secondary)
                                                if (item.text.isNotBlank()) SelectionContainer {
                                                    Text(truncateChatText(item.text) +
                                                        if (item.text.codePointCount(0, item.text.length) > MAX_MARKDOWN_RENDER_CHARS) "\r\n\r\n…内容过长，已截断显示。" else "",
                                                        Modifier.fillMaxWidth(), fontSize = 12.sp, lineHeight = 19.sp,
                                                        fontFamily = FontFamily.Monospace)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (line.text.isNotBlank()) SelectionContainer {
                        if (line.isReasoningSummary) MarkdownMessage(line.text, onOpenLink)
                        else Text(truncateChatText(line.text) +
                            if (line.text.codePointCount(0, line.text.length) > MAX_MARKDOWN_RENDER_CHARS) "\r\n\r\n…内容过长，已截断显示。" else "",
                            Modifier.fillMaxWidth(), fontSize = 12.sp, lineHeight = 20.sp,
                            fontFamily = if (line.role == "tool") FontFamily.Monospace else FontFamily.Default)
                    }
                    if (pending != null) Text("请在当前问答或审批窗口中处理。", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun QuestionFields(title: String, options: List<Pair<String, String>>, value: String, enabled: Boolean,
    secret: Boolean = false, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 21.sp)
        options.forEach { (label, description) ->
            val selected = value == label
            Surface(onClick = { onChange(label) }, enabled = enabled, shape = RoundedCornerShape(14.dp),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 54.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top) {
                    RadioButton(selected = selected, onClick = null, enabled = enabled,
                        modifier = Modifier.padding(end = 8.dp))
                    Column(Modifier.weight(1f).padding(top = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label, fontSize = 14.sp, lineHeight = 20.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                        if (description.isNotBlank()) Text(description, fontSize = 12.sp, lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text(if (options.isEmpty()) "你的回答" else "也可以填写自己的回答") },
            shape = RoundedCornerShape(12.dp), textStyle = MaterialTheme.typography.bodyMedium,
            minLines = 2, maxLines = 4,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
    }
}

@Composable
private fun AsyncQuestionCard(vm: MobileViewModel, line: ChatLine, latestQuestion: Boolean) {
    val answers = remember(line.id) { mutableStateMapOf<Int, String>() }
    val answered = line.id in vm.answeredQuestionItems || !latestQuestion
    Surface(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (answered) "问题记录" else "需要你的选择", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            line.questions.forEachIndexed { index, question ->
                QuestionFields(question.title, question.options.map { it to "" }, answers[index].orEmpty(), !answered && vm.connected && !vm.busy && vm.activeId !in vm.sending) { answers[index] = it }
            }
            if (!answered) Button(onClick = { vm.sendQuestionAnswers(line, line.questions.indices.map { answers[it].orEmpty() }) },
                enabled = vm.connected && !vm.busy && vm.activeId !in vm.sending && line.questions.indices.all { !answers[it].isNullOrBlank() },
                shape = RoundedCornerShape(12.dp)) { Text("提交回答") }
        }
    }
}

@Composable
private fun MarkdownMessage(value: String, onOpenLink: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val color = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .build()
    }
    AndroidView(
        factory = { viewContext -> TextView(viewContext).apply {
            textSize = 15f
            setTextColor(color.toArgb())
            setLineSpacing(0f, 1.45f)
            setTextIsSelectable(true)
            movementMethod = object : LinkMovementMethod() {
                override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
                    if (event.action != MotionEvent.ACTION_UP && event.action != MotionEvent.ACTION_DOWN) {
                        return super.onTouchEvent(widget, buffer, event)
                    }
                    val x = (event.x - widget.totalPaddingLeft + widget.scrollX).toInt()
                    val y = (event.y - widget.totalPaddingTop + widget.scrollY).toInt()
                    val layout = widget.layout ?: return false
                    if (x < 0 || y < 0 || y > layout.height) return false
                    val line = layout.getLineForVertical(y)
                    val offset = layout.getOffsetForHorizontal(line, x.toFloat())
                    val span = buffer.getSpans(offset, (offset + 1).coerceAtMost(buffer.length), URLSpan::class.java).firstOrNull()
                    if (span != null) {
                        if (event.action == MotionEvent.ACTION_UP) onOpenLink(span.url)
                        else Selection.setSelection(buffer, buffer.getSpanStart(span), buffer.getSpanEnd(span))
                        return true
                    }
                    return super.onTouchEvent(widget, buffer, event)
                }
            }
            setLinkTextColor(linkColor.toArgb())
        } },
        update = { textView ->
            textView.setTextColor(color.toArgb())
            textView.setLinkTextColor(linkColor.toArgb())
            markwon.setMarkdown(textView, filterGfmDisallowedHtml(repairCommonMarkdownSpacing(truncateChatText(value))))
        }
    )
}

/** Recover generated role labels that put whitespace before `**` and no separating space after it. */
private fun repairCommonMarkdownSpacing(markdown: String): String =
    Regex("(\\*\\*[^*\\n]+?[：:])[ \\t]+\\*\\*(?=\\S)").replace(markdown, "$1** ")

private val disallowedGfmHtmlTag = Regex(
    "(?i)</?(?:title|textarea|style|xmp|iframe|noembed|noframes|script|plaintext)(?:\\s[^<>]*?)?\\s*/?>"
)

/** GFM tagfilter: keep prohibited raw tags visible as text instead of handing them to HTML rendering. */
private fun filterGfmDisallowedHtml(markdown: String): String {
    val fenced = Regex("^ {0,3}(`{3,}|~{3,})")
    var fenceChar: Char? = null
    var fenceSize = 0
    return markdown.lineSequence().map { line ->
        val start = fenced.find(line)
        if (fenceChar == null && start != null) {
            fenceChar = start.groupValues[1].first()
            fenceSize = start.groupValues[1].length
            line
        } else if (fenceChar != null) {
            val marker = fenceChar!!
            val closing = line.trimStart().takeWhile { it == marker }.length >= fenceSize &&
                line.trimStart().firstOrNull() == marker && line.trimStart().dropWhile { it == marker }.isBlank()
            if (closing) { fenceChar = null; fenceSize = 0 }
            line
        } else {
            // Inline code is opaque to HTML parsing, so leave any tag-like text inside it untouched.
            val codeSpans = Regex("(`+)(.*?)\\1").findAll(line).toList()
            if (codeSpans.isEmpty()) escapeDisallowedHtmlTags(line)
            else buildString {
                var cursor = 0
                for (span in codeSpans) {
                    append(escapeDisallowedHtmlTags(line.substring(cursor, span.range.first)))
                    append(span.value)
                    cursor = span.range.last + 1
                }
                append(escapeDisallowedHtmlTags(line.substring(cursor)))
            }
        }
    }.joinToString("\r\n")
}

private fun escapeDisallowedHtmlTags(value: String): String = disallowedGfmHtmlTag.replace(value) { match ->
    "&lt;" + match.value.substring(1, match.value.length - 1) + "&gt;"
}

@Composable
private fun TimelineError(line: ChatLine) {
    var expanded by remember(line.id) { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.animateContentSize(tween(180))) {
        Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.padding(horizontal = 12.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("!", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Text(line.stage.ifBlank { "任务出现异常" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (expanded) "⌃" else "⌄", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(expanded, enter = fadeIn(tween(140)) + expandVertically(),
            exit = fadeOut(tween(100)) + shrinkVertically()) {
            SelectionContainer {
                Text(line.text, Modifier.fillMaxWidth().padding(horizontal = 4.dp), fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UpdateDialog(vm: MobileViewModel, install: (java.io.File) -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!vm.updateBusy) vm.dismissUpdate() },
        title = { Text("应用更新") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前版本 " + BuildConfig.VERSION_NAME)
                if (vm.updateChecking) Text("正在通过 82 的 SSH 检查版本…")
                else Text(vm.updateMessage)
                vm.updateInfo?.let { info ->
                    Text(info.notes)
                    Text("安装包约 " + (info.sizeBytes / 1024 / 1024) + " MB，通过 SSH 下载。")
                    Text("首次安装需允许此来源安装应用；授权后返回这里，再点「安装更新」。系统会要求你确认安装。")
                }
                if (vm.updateBusy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在确认最新版本并校验更新包…") }
                if (vm.updateFile != null) Text("版本 " + vm.updateInfo?.versionName.orEmpty() + " 下载完成，已验证包名、版本、SHA-256 和应用签名。")
                ErrorText(vm.updateError)
            }
        },
        confirmButton = {
            if (vm.updateInfo != null) TextButton(enabled = !vm.updateBusy && !vm.updateChecking, onClick = {
                val file = vm.updateFile
                if (file == null) vm.downloadUpdate() else vm.prepareUpdateInstall(install)
            }) { Text(if (vm.updateFile == null) "下载更新" else "安装更新") }
            else if (vm.updateError.isNotBlank()) TextButton(enabled = !vm.updateBusy && !vm.updateChecking,
                onClick = { vm.checkForUpdate(manual = true) }) { Text("重新检查") }
            else TextButton(vm::dismissUpdate) { Text("知道了") }
        },
        dismissButton = {
            if (vm.updateInfo != null) TextButton(vm::dismissUpdate, enabled = !vm.updateBusy) { Text("稍后") }
        }
    )
}

@Composable
private fun RequestDialog(vm: MobileViewModel, request: JSONObject) {
    val params = request.optJSONObject("params") ?: JSONObject()
    val method = request.text("method")
    val userInput = method == "item/tool/requestUserInput"
    val answers = remember(request.get("id").toString()) { mutableStateMapOf<String, String>() }
    val questions = params.optJSONArray("questions")
    val count = questions?.length() ?: 0
    val decisions = params.optJSONArray("availableDecisions")
    val canApprove = decisions == null || (0 until decisions.length()).any { decisions.opt(it) == "accept" }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (userInput) "Andri 需要你的回答" else "Andri 请求批准（待处理 " + vm.requests.size + "）") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val threadId = params.text("threadId").ifBlank { params.text("conversationId") }
                Text(vm.threads.firstOrNull { it.id == threadId }?.title ?: threadId.take(12))
                ErrorText(vm.requestError)
                if (userInput) {
                    for (i in 0 until count) {
                        val question = questions!!.getJSONObject(i)
                        val id = question.text("id")
                        val options = question.optJSONArray("options") ?: org.json.JSONArray()
                        QuestionFields(question.text("question"), (0 until options.length()).map { j ->
                            val option = options.getJSONObject(j)
                            option.text("label") to option.text("description")
                        }, answers[id].orEmpty(), !vm.responding, question.optBoolean("isSecret")) { answers[id] = it }
                    }
                } else {
                    val detailKeys = listOf("command", "cwd", "reason", "grantRoot", "permissions", "additionalPermissions", "networkApprovalContext")
                    detailKeys.forEach { name ->
                        if (params.has(name) && !params.isNull(name)) SelectionContainer { Text(name + "：\r\n" + params.opt(name)?.toString().orEmpty()) }
                    }
                    val item = vm.toolItems[params.text("itemId")]
                    val changes = item?.optJSONArray("changes")
                    if (changes != null) SelectionContainer { Text(changes.toString(2)) }
                    if (!canApprove) Text("服务器未提供单次批准选项，可拒绝本次请求。")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !vm.responding && if (userInput) (0 until count).all { !answers[questions!!.getJSONObject(it).text("id")].isNullOrBlank() } else canApprove,
                onClick = { vm.respond(request, approved = true, answers = answers.toMap()) }
            ) { Text(if (userInput) "提交回答" else "批准这一次") }
        },
        dismissButton = {
            if (!userInput) TextButton(enabled = !vm.responding, onClick = { vm.respond(request, approved = false) }) { Text("拒绝") }
        }
    )
}
