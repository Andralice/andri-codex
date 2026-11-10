package com.codexserver.mobile

import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
import androidx.core.content.FileProvider
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.horizontalDrag
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first
import com.caverock.androidsvg.SVG
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.Base64
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import java.net.URL
import java.net.URI
import java.net.URLConnection
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.TextView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.text.method.LinkMovementMethod
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import kotlin.math.roundToInt

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5734), onPrimary = Color(0xFFF8EFE5),
    secondary = Color(0xFF5F7A56), onSecondary = Color.White,
    tertiary = Color(0xFFAE5A45), onTertiary = Color.White,
    background = Color(0xFFF3F1EB), onBackground = Color(0xFF28241E),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF28241E),
    surfaceVariant = Color(0xFFF7F6F1), onSurfaceVariant = Color(0xFF6E6960),
    primaryContainer = Color(0xFFF3EAE0), onPrimaryContainer = Color(0xFF754A2A),
    secondaryContainer = Color(0xFFEAF0E6), onSecondaryContainer = Color(0xFF3D5A34),
    error = Color(0xFFAE5A45), onError = Color.White,
    surfaceDim = Color(0xFFF7F6F1), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF3F1EB),
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFF7F6F1), surfaceTint = Color.Transparent,
    outline = Color(0xFFE6E2D7), outlineVariant = Color(0xFFEDEAE0)
)

class MainActivity : ComponentActivity() {
    private val vm by lazy { ConnectionSession.get(application) }
    private var notificationThreadId by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationThreadId = intent?.getStringExtra("threadId")
        setContent {
            MaterialTheme(colorScheme = LightColors) {
                Box(Modifier.fillMaxSize()) {
                val connectAfterNotificationPrompt = remember { mutableStateOf(false) }
                var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (connectAfterNotificationPrompt.value) {
                        connectAfterNotificationPrompt.value = false
                        vm.connect()
                    }
                    if (!granted) vm.error = "通知权限未开启，无法显示系统任务提醒；可从菜单的任务通知设置中开启。"
                }
                LaunchedEffect(vm.connected) {
                    if (vm.connected && !notificationPermissionRequested && Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermissionRequested = true
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                val connect = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        connectAfterNotificationPrompt.value = true
                        notificationPermissionRequested = true
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
                fun importImageUris(uris: List<Uri>) {
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
                val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
                    importImageUris(uris)
                }
                val legacyImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    result.data?.data?.let { importImageUris(listOf(it)) }
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
                var workspacePage by rememberSaveable(vm.activeId) { mutableStateOf(false) }
                val chatState = key(vm.activeId) { rememberSaveableStateHolder() }
                BackHandler(enabled = vm.activeId != null) {
                    if (workspacePage) workspacePage = false else vm.back()
                }
                val screenKey = when {
                    !vm.connected && vm.connecting && vm.privateKey.isNotBlank() && vm.activeThread == null && !vm.hasCachedSession -> "connecting"
                    !vm.connected && vm.activeThread == null && !vm.hasCachedSession -> "connection"
                    vm.activeThread == null -> "thread-list"
                    vm.openingThreadId != null || vm.threadOpenError.isNotBlank() -> "thread-loading:${vm.activeId}"
                    workspacePage -> "workspace:${vm.activeId}"
                    else -> "chat"
                }
                LaunchedEffect(screenKey, vm.foregroundGeneration, vm.connected, vm.threads.size) {
                    withFrameNanos { }
                    vm.recordScreenFrame(screenKey)
                }
                val notificationThreadCached = vm.threads.any { it.id == notificationThreadId }
                LaunchedEffect(vm.connected, notificationThreadCached, notificationThreadId) {
                    val threadId = notificationThreadId
                    if ((vm.connected || notificationThreadCached) && !threadId.isNullOrBlank()) {
                        vm.openThread(threadId)
                        notificationThreadId = null
                        intent.removeExtra("threadId")
                    }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                        AnimatedContent(
                            targetState = screenKey,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                val enteringChat = initialState == "thread-list" &&
                                    (targetState == "chat" || targetState.startsWith("thread-loading:"))
                                val leavingChat = targetState == "thread-list" &&
                                    (initialState == "chat" || initialState.startsWith("thread-loading:"))
                                val enteringWorkspace = initialState == "chat" && targetState.startsWith("workspace:")
                                val leavingWorkspace = targetState == "chat" && initialState.startsWith("workspace:")
                                if (enteringWorkspace || leavingWorkspace) {
                                    (fadeIn(tween(180)) + slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { if (enteringWorkspace) it else -it / 8 }) togetherWith
                                        (fadeOut(tween(160)) + slideOutHorizontally(tween(230, easing = FastOutSlowInEasing)) { if (enteringWorkspace) -it / 8 else it })
                                } else if (enteringChat || leavingChat) {
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
                                    Text("连接最多等待 45 秒", Modifier.padding(top = 8.dp), fontSize = 12.sp)
                                    TextButton(vm::cancelConnection) { ActionLabel("close", "取消连接 / 修改配置") }
                                    TextButton(vm::openHistory) { ActionLabel("history", "历史版本与恢复下载") }
                                }
                                screen == "connection" -> ConnectionScreen(vm, connect) { keyPicker.launch(arrayOf("*/*")) }
                                screen == "thread-list" -> ThreadListScreen(vm)
                                screen.startsWith("thread-loading:") -> ThreadLoadingScreen(vm)
                                screen.startsWith("workspace:") -> WorkspacePage(vm, onBack = { workspacePage = false })
                                else -> chatState.SaveableStateProvider(vm.activeId.orEmpty()) { ChatScreen(vm,
                                    workspaceOpen = workspacePage,
                                    openWorkspace = { workspacePage = true },
                                    addImage = {
                                        if (Build.VERSION.SDK_INT >= 33) {
                                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                        } else {
                                            legacyImagePicker.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                                                type = "image/*"
                                            })
                                        }
                                    },
                                    addVideo = { videoPicker.launch(arrayOf("video/*")) },
                                    addFiles = { filePicker.launch(arrayOf("*/*")) }) }
                            }
                        }

                    }
                }
                vm.requests.firstOrNull { it.get("id").toString() == vm.visibleRequestId }?.let { request -> key(request.get("id").toString()) { RequestDialog(vm, request) } }
                if (vm.historyDialog) ReleaseHistoryDialog(vm) { file -> installUpdate(this@MainActivity, file) }
                if (vm.updateDialog && !vm.historyDialog && vm.visibleRequestId == null && vm.connected) UpdateDialog(vm) { file ->
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
    Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape((size / 2.4f).dp)),
        contentAlignment = Alignment.Center) {
        Text("A", color = MaterialTheme.colorScheme.onPrimary, fontSize = (size * 0.5f).sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
    }
}

@Composable
private fun LineIcon(name: String, modifier: Modifier = Modifier.size(20.dp), color: Color = LocalContentColor.current) {
    val resource = when (name) {
        "paperclip" -> R.drawable.ic_material_attach_file
        "sliders" -> R.drawable.ic_material_tune
        "code" -> R.drawable.ic_material_code
        "chevron" -> R.drawable.ic_material_chevron_right
        "back" -> R.drawable.ic_material_arrow_back
        "sidebar" -> R.drawable.ic_material_menu_open
        "plus" -> R.drawable.ic_material_add
        "send", "paperplane" -> R.drawable.ic_material_north
        "north" -> R.drawable.ic_material_north
        "pause" -> R.drawable.ic_material_pause
        "play" -> R.drawable.ic_material_play_arrow
        "chevron-up" -> R.drawable.ic_material_expand_less
        "edit" -> R.drawable.ic_material_edit
        "folder" -> R.drawable.ic_material_folder
        "folder-plus" -> R.drawable.ic_material_add
        "file" -> R.drawable.ic_material_description
        "eye" -> R.drawable.ic_material_visibility
        "link" -> R.drawable.ic_material_link
        "more" -> R.drawable.ic_material_more_vert
        "search" -> R.drawable.ic_material_search
        "close" -> R.drawable.ic_material_close
        "stop" -> R.drawable.ic_material_stop
        "chat" -> R.drawable.ic_material_chat_bubble_outline
        "history" -> R.drawable.ic_material_history
        "refresh" -> R.drawable.ic_material_refresh
        "download" -> R.drawable.ic_material_download
        "upload" -> R.drawable.ic_material_upload
        "share" -> R.drawable.ic_material_share
        "check" -> R.drawable.ic_material_check
        "pin" -> R.drawable.ic_material_push_pin
        "archive" -> R.drawable.ic_material_archive
        "trash" -> R.drawable.ic_material_delete_outline
        "settings" -> R.drawable.ic_material_settings
        "arrow-up" -> R.drawable.ic_material_arrow_upward
        "arrow-right" -> R.drawable.ic_material_arrow_forward
        "save" -> R.drawable.ic_material_save
        "bolt" -> R.drawable.ic_material_bolt
        "info" -> R.drawable.ic_material_info_outline
        else -> R.drawable.ic_material_info_outline
    }
    Icon(painterResource(resource), contentDescription = null, modifier = modifier, tint = color)
}

@Composable
internal fun ActionLabel(icon: String?, label: String, color: Color = Color.Unspecified, iconSize: androidx.compose.ui.unit.Dp = 16.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    modifier: Modifier = Modifier) {
    val contentColor = if (color == Color.Unspecified) LocalContentColor.current else color
    if (icon == null) {
        Text(label, modifier, color = contentColor, fontSize = fontSize)
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LineIcon(icon, Modifier.size(iconSize), contentColor)
            Text(label, color = contentColor, fontSize = fontSize)
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
                    TextButton({ expanded = !expanded }) {
                        ActionLabel(if (expanded) "chevron-up" else "chevron", if (expanded) "收起" else "查看详情", iconSize = 14.dp, fontSize = 12.sp)
                    }
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
        Text("工作，随你而行。", fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, fontFamily = FontFamily.Serif)
        Text("从手机继续对话，让任务在服务器上运行。", color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp, lineHeight = 22.sp)
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MutedText("服务器连接")
                ConnectionField("服务器地址", vm.host, { vm.host = it }, !vm.connecting)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConnectionField("端口", vm.port, { vm.port = it }, !vm.connecting, Modifier.weight(1f), true)
                    ConnectionField("用户名", vm.username, { vm.username = it }, !vm.connecting, Modifier.weight(2f))
                }
                ConnectionField("工作目录", vm.cwd, { vm.cwd = it }, !vm.connecting)
            }
        }
        Surface(onClick = importKey, enabled = !vm.connecting, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
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
            shape = RoundedCornerShape(14.dp), enabled = !vm.connecting)
        Button(connect, enabled = vm.privateKey.isNotBlank() && !vm.connecting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) {
            if (vm.connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else { Text("连接工作区", fontSize = 16.sp); Spacer(Modifier.weight(1f)); Text("→", fontSize = 22.sp) }
        }
        if (vm.connecting) TextButton(vm::cancelConnection) { ActionLabel("close", "取消连接") }
        TextButton(vm::openHistory) { ActionLabel("history", "历史版本与恢复下载 · v" + BuildConfig.VERSION_NAME) }
        BackgroundConnectionSettingsButton()
        MutedText("连接前验证服务器身份 · 中转密钥留在服务器")
        ErrorText(vm.error)
        if (vm.connecting || vm.error.isNotBlank()) MutedText(vm.status)
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
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var workspaceDialog by remember { mutableStateOf(false) }
    var globalInstructionsDialog by remember { mutableStateOf(false) }
    var backgroundConnectionDialog by remember { mutableStateOf(false) }
    var renameDialog by remember(thread?.id) { mutableStateOf(false) }
    var filesDialog by remember(thread?.id) { mutableStateOf(false) }
    var diffDialog by remember(thread?.id) { mutableStateOf(false) }
    var titleDraft by remember(thread?.id) { mutableStateOf(thread?.title.orEmpty()) }
    Box {
        BadgedBox(badge = { if (vm.requests.isNotEmpty()) Badge { Text(vm.requests.size.toString()) } }) {
            IconButton({ expanded = true }, modifier = Modifier.semantics { contentDescription = "更多选项" }) { LineIcon("more") }
        }
        DropdownMenu(expanded, { expanded = false }, shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp,
            shadowElevation = 8.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            if (vm.requests.isNotEmpty()) DropdownMenuItem(text = { Text("待处理问答与审批 · ${vm.requests.size}") },
                onClick = { expanded = false; vm.openPendingRequest() })
            if (thread != null) DropdownMenuItem(leadingIcon = { LineIcon("edit") }, text = { Text("修改对话名称") }, enabled = true,
                onClick = { expanded = false; titleDraft = thread.title; renameDialog = true })
            if (thread != null) DropdownMenuItem(leadingIcon = { LineIcon("folder") }, text = { Text("项目文件") }, enabled = vm.connected ,
                onClick = { expanded = false; filesDialog = true; vm.loadProjectFiles(thread.cwd.ifBlank { vm.cwd }) })
            if (thread != null) DropdownMenuItem(leadingIcon = { LineIcon("code") }, text = { Text("审阅代码改动") }, enabled = vm.connected ,
                onClick = { expanded = false; diffDialog = true; vm.loadGitDiff() })
            DropdownMenuItem(leadingIcon = { LineIcon("refresh") }, text = { Text("刷新") }, enabled = vm.connected && !vm.operationPending("刷新对话", null), onClick = { expanded = false; vm.refresh() })
            DropdownMenuItem(leadingIcon = { LineIcon("settings") }, text = { Text("工作区与权限") }, enabled = true, onClick = { expanded = false; workspaceDialog = true })
            DropdownMenuItem(leadingIcon = { LineIcon("settings") }, text = { Text("后台连接设置") }, onClick = { expanded = false; backgroundConnectionDialog = true })
            DropdownMenuItem(leadingIcon = { LineIcon("settings") }, text = { Text("任务通知设置") }, onClick = {
                expanded = false
                runCatching { context.startActivity(TaskNotifications.settingsIntent(context)) }
                    .onFailure { vm.error = "无法打开通知设置，请在手机系统设置中开启 Andri 的通知和横幅提醒。" }
            })
            DropdownMenuItem(leadingIcon = { LineIcon("file") }, text = { Text("全局指令") }, onClick = { expanded = false; globalInstructionsDialog = true; if (vm.globalInstructionsDocument == null) vm.loadGlobalInstructions() })
            DropdownMenuItem(leadingIcon = { LineIcon("download") }, text = { Text("检查更新 · v" + BuildConfig.VERSION_NAME) }, onClick = { expanded = false; vm.openUpdates() })
            DropdownMenuItem(leadingIcon = { LineIcon("history") }, text = { Text("历史版本与恢复下载") }, onClick = { expanded = false; vm.openHistory() })
            DropdownMenuItem(leadingIcon = { LineIcon("close", color = MaterialTheme.colorScheme.error) },
                text = { Text("断开服务器", color = MaterialTheme.colorScheme.error) }, enabled = true, onClick = { expanded = false; vm.disconnect() })
        }
    }
    if (renameDialog && thread != null) AlertDialog(
        onDismissRequest = { renameDialog = false },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("修改对话名称", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(titleDraft, { titleDraft = it.take(120) }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("对话名称") }, shape = RoundedCornerShape(12.dp))
        },
        confirmButton = {
            Button(onClick = { vm.renameActiveThread(titleDraft); renameDialog = false },
                enabled = vm.connected && !vm.operationPending("修改对话名称", thread.id) && titleDraft.isNotBlank() && titleDraft.trim() != thread.title,
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary)) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { renameDialog = false }, enabled = true) { Text("取消") } }
    )
    if (filesDialog && thread != null) WorkspaceFilesDialog(vm) { filesDialog = false }
    if (diffDialog && thread != null) GitDiffDialog(vm) { diffDialog = false }
    if (workspaceDialog) ExecutionSettingsDialog(vm) { workspaceDialog = false }
    if (backgroundConnectionDialog) BackgroundConnectionSettingsDialog { backgroundConnectionDialog = false }
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
    val listing = vm.projectFilesListing
    AlertDialog(onDismissRequest = dismiss, shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("项目文件") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(vm.fileBrowserPath, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.loadProjectFiles(WORKSPACE_ROOT) }, enabled = vm.connected) { ActionLabel("folder", "项目根目录") }
                    TextButton(onClick = { vm.loadProjectFiles(vm.fileBrowserPath.substringBeforeLast('/').ifBlank { WORKSPACE_ROOT }) },
                        enabled = vm.connected && vm.fileBrowserPath != WORKSPACE_ROOT) { ActionLabel("arrow-up", "上一级") }
                }
                if (vm.projectFilesBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (vm.projectFilesError.isNotBlank()) Text(vm.projectFilesError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                listing?.let { directory ->
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        directory.children.forEach { child ->
                            TextButton(onClick = { vm.loadProjectFiles(directory.path.trimEnd('/') + "/" + child) },
                                modifier = Modifier.fillMaxWidth(), enabled = vm.connected) {
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
                            }, modifier = Modifier.fillMaxWidth(), enabled = vm.connected) {
                                LineIcon("file", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp)); Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(dismiss) { ActionLabel(null, "完成") } })

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
        containerColor = MaterialTheme.colorScheme.surface) {
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
                    ContextAction("添加手机图片", "每次选 1 张，可重复添加，最多 10 张") { onDismiss(); addImage() }
                    ContextAction("添加手机视频", "上传原画质视频") { onDismiss(); addVideo() }
                    ContextAction("添加网页或文字", "粘贴链接、选中文字或补充说明") { webDialog = true }
                }
                1 -> {
                    Text(vm.fileBrowserPath, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton({ vm.loadProjectFiles(WORKSPACE_ROOT) }, enabled = vm.connected) { ActionLabel("folder", "项目根目录") }
                        TextButton({ vm.loadProjectFiles(vm.fileBrowserPath.substringBeforeLast('/').ifBlank { WORKSPACE_ROOT }) },
                            enabled = vm.connected && vm.fileBrowserPath != WORKSPACE_ROOT) { ActionLabel("arrow-up", "上一级") }
                    }
                    if (vm.projectFilesBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (vm.projectFilesError.isNotBlank()) Text(vm.projectFilesError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    vm.projectFilesListing?.let { directory ->
                        directory.children.forEach { child ->
                            TextButton({ vm.loadProjectFiles(directory.path.trimEnd('/') + "/" + child) },
                                modifier = Modifier.fillMaxWidth(), enabled = vm.connected) {
                                LineIcon("folder", Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp)); Text(child, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        directory.files.forEach { name ->
                            val path = directory.path.trimEnd('/') + "/" + name
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { vm.addWorkspaceFileContext(path, name); selectedTab = 0 },
                                    modifier = Modifier.weight(1f), enabled = vm.connected) {
                                    LineIcon("file", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(8.dp)); Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("引用", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { scope.launch {
                                    runCatching { vm.cacheWorkspaceFile(path, directory.path) }
                                        .onSuccess { previewFile = it; previewName = name }
                                        .onFailure { vm.showWorkspaceError("读取文件失败：${it.message}") }
                                } }, enabled = vm.connected, modifier = Modifier.size(40.dp)) {
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
                        TextButton({ vm.loadGitDiff() }, enabled = !vm.gitDiffBusy) { ActionLabel("refresh", "刷新") }
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
            TextButton(onDismiss, modifier = Modifier.align(Alignment.End)) { ActionLabel(null, "完成") }
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
        }, enabled = sharedText.isNotBlank()) { ActionLabel("plus", "添加") } },
        dismissButton = { TextButton({ webDialog = false }) { ActionLabel(null, "取消") } })
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                WebView.startSafeBrowsing(context) { }
            }
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
        Surface(Modifier.fillMaxSize().padding(10.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (allowContextImport) "网页上下文" else "网页预览", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onDismiss) { ActionLabel("close", "关闭") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(address, { address = it }, Modifier.weight(1f), singleLine = true,
                        label = { Text("网页地址") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Button(onClick = {
                        val url = address.trim().let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it" }
                        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
                        if (scheme != "https") error = "网页上下文仅支持 HTTPS 地址"
                        else { error = ""; extractedPage = ""; currentUrl = url; pageView.loadUrl(url) }
                    }) { ActionLabel("link", "打开") }
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
                        ActionLabel("download", if (extracting) "读取中…" else "读取当前页面")
                    }
                    if (extractedPage.isNotBlank()) Button(onClick = {
                        onAdd(title.ifBlank { currentUrl }, extractedPage)
                    }) { ActionLabel("plus", "加入任务") }
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
            IconButton(onRemove, Modifier.size(40.dp)) { LineIcon("close") }
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

// Every produceState producer below assigns value; the current lint detector misses these assignments.
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
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
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(24.dp), containerColor = MaterialTheme.colorScheme.surface,
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
            TextButton(onShare) { ActionLabel("share", "分享") }
            TextButton(onSave) { ActionLabel("download", "保存到手机") }
            if (imageBitmap == null && !isText) TextButton(onClick = {
                runCatching {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }
            }) { ActionLabel("link", "打开") }
        } }, dismissButton = { TextButton(onDismiss) { ActionLabel(null, "关闭") } })
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
    if (file == null) AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(24.dp), containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("服务器文件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(path, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
                else Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { if (error.isNotBlank()) TextButton(onClick = { attempt++ }) { ActionLabel("refresh", "重试") } },
        dismissButton = { TextButton(onDismiss) { ActionLabel(null, "关闭") } })
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

@Composable
private fun ServerWorkspaceDirectoryLinkDialog(vm: MobileViewModel, path: String, onDismiss: () -> Unit) {
    LaunchedEffect(path) { vm.loadProjectFiles(path) }
    WorkspaceFilesDialog(vm, onDismiss)
}

@Composable
private fun GitDiffDialog(vm: MobileViewModel, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surface,
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
        }, confirmButton = { TextButton({ vm.loadGitDiff() }, enabled = !vm.gitDiffBusy) { ActionLabel("refresh", "刷新") } },
        dismissButton = { TextButton(dismiss) { ActionLabel(null, "关闭") } })
}

@Composable
private fun GlobalInstructionsDialog(vm: MobileViewModel, dismiss: () -> Unit) {
    val document = vm.globalInstructionsDocument
    AlertDialog(onDismissRequest = dismiss, shape = RoundedCornerShape(28.dp),
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
                        enabled = true, label = { Text("给助手的长期要求") },
                        placeholder = { Text("例如：默认使用中文；修改前先阅读相关代码。") },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp), shape = RoundedCornerShape(16.dp))
                    MutedText("保存前会检查其他修改，并备份已有指令。最多 32 KiB。")
                }
                if (vm.globalInstructionsError.isNotBlank()) {
                    Text(vm.globalInstructionsError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    TextButton(vm::loadGlobalInstructions, enabled = !vm.globalInstructionsBusy) { ActionLabel("refresh", "重新读取服务器内容") }
                }
                if (vm.globalInstructionsMessage.isNotBlank()) Text(vm.globalInstructionsMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = { TextButton(vm::saveGlobalInstructions, enabled = !vm.globalInstructionsBusy && document != null &&
            vm.globalInstructionsDraft != document.content) { ActionLabel("save", if (vm.globalInstructionsBusy) "处理中…" else "保存") } },
        dismissButton = { TextButton(dismiss) { ActionLabel(null, "关闭") } })
}

@Composable
private fun ExecutionSettingsDialog(vm: MobileViewModel, dismiss: () -> Unit) {
    val dialogScope = rememberCoroutineScope()
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
    AlertDialog(onDismissRequest = dismiss, shape = RoundedCornerShape(16.dp),
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
                        TextButton({ vm.browseWorkspace(root) }, enabled = vm.connected && root != (vm.workspaceListing?.path ?: root), contentPadding = PaddingValues(horizontal = 6.dp)) { ActionLabel("folder", "根目录") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ path = vm.workspaceListing?.path ?: path; selectedFile = null }, enabled = vm.workspaceListing != null) { ActionLabel("check", "选择此目录") }
                    TextButton({ creatingFolder = !creatingFolder; folderName = "" }, enabled = !vm.workspaceCreating) { ActionLabel(if (creatingFolder) "close" else "folder-plus", if (creatingFolder) "取消新建" else "新建项目") }
                }
                if (creatingFolder) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(folderName, { folderName = it }, Modifier.weight(1f), singleLine = true, enabled = vm.connected,
                        label = { Text("新项目文件夹名称") }, textStyle = MaterialTheme.typography.bodySmall)
                    TextButton({
                        val selectedAtStart = path
                        val nameAtStart = folderName
                        vm.createWorkspaceFolder(nameAtStart) { created ->
                            if (dialogScope.isActive && path == selectedAtStart && folderName == nameAtStart) {
                                path = created
                                selectedFile = null
                                creatingFolder = false
                            }
                        }
                    },
                        enabled = vm.connected && !vm.workspaceCreating && folderName.isNotBlank()) { ActionLabel("folder-plus", "创建") }
                }
                if (vm.workspaceBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.workspaceListing?.let { listing ->
                    Text("工作区内容", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    TextButton({ vm.browseWorkspace(listing.path.substringBeforeLast('/').ifBlank { "/" }) }, enabled = vm.connected && listing.path != "/") { ActionLabel("arrow-up", "上一级") }
                    Column(Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState())) {
                        if (listing.children.isEmpty() && listing.files.isEmpty()) MutedText("此目录为空，可以直接新建项目")
                        listing.children.forEach { child ->
                            val childPath = listing.path.trimEnd('/') + "/" + child
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton({ vm.browseWorkspace(childPath) }, enabled = vm.connected, modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    LineIcon("folder", Modifier.size(17.dp), MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(child, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton({ path = childPath; selectedFile = null }, enabled = vm.connected, contentPadding = PaddingValues(horizontal = 6.dp)) { ActionLabel(if (path == childPath) "check" else "folder", if (path == childPath) "已选择" else "选择") }
                            }
                        }
                        listing.files.forEach { file ->
                            TextButton({ path = listing.path; selectedFile = file }, enabled = vm.connected, modifier = Modifier.fillMaxWidth(),
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
                    Surface(onClick = { mode = option }, enabled = vm.connected, shape = RoundedCornerShape(12.dp),
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
                ReasoningEffortSlider(effort, effortOptions, vm.model, true) { effort = it }
                if (locked || (active != null && vm.hasPendingExecution(active))) MutedText("当前任务继续使用原设置；保存后从下一次发送或排队任务启动时生效。")
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (vm.workspaceError.isNotBlank()) Text(vm.workspaceError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                if (vm.workspaceStatus.isNotBlank()) MutedText(vm.workspaceStatus)
                TextButton({
                    val submitted = ExecutionOptions(path.trim(), mode, effort)
                    vm.saveExecution(submitted) {
                        if (dialogScope.isActive && vm.activeId == active && submitted == ExecutionOptions(path.trim(), mode, effort)) dismiss()
                    }
                },
                    enabled = vm.connected && (active ?: "__defaults__") !in vm.executionSaving && path.startsWith("/")) { ActionLabel("save", if ((active ?: "__defaults__") in vm.executionSaving) "保存中…" else "保存到下次执行") }
            }
        },
        dismissButton = { TextButton(dismiss) { ActionLabel(null, "取消") } })
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
                DropdownMenuItem(leadingIcon = { LineIcon("sliders") }, text = { Text(value + if (value == vm.model) "  ✓" else "") }, enabled = enabled,
                    onClick = { vm.selectModel(value); expanded = false })
            }
        }
    }
}

@Composable
private fun UpdateNotice(vm: MobileViewModel) {
    vm.updateInfo?.let { info ->
        TextButton(vm::openUpdates) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionLabel("download", "新版本 " + info.versionName + " 已就绪", MaterialTheme.colorScheme.secondary)
                LineIcon("arrow-right", Modifier.size(14.dp), MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun ThreadListScreen(vm: MobileViewModel) {
    val colors = MaterialTheme.colorScheme
    val canvas = MaterialTheme.colorScheme.background
    val selectedFilter = MaterialTheme.colorScheme.primaryContainer
    val neutralInk = MaterialTheme.colorScheme.onSurface
    val canCreate = vm.connected && !vm.operationPending("新建对话", null)
    var searchExpanded by rememberSaveable { mutableStateOf(vm.threadSearch.isNotBlank()) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val query = vm.threadSearch.trim()
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) searchFocus.requestFocus() else keyboard?.hide()
    }
    val matchingRows = vm.threads.filter { thread ->
        query.isBlank() || thread.title.contains(query, ignoreCase = true) || thread.model.contains(query, ignoreCase = true) ||
            vm.projectFor(thread.id).contains(query, ignoreCase = true) ||
            threadPreview(vm.messages[thread.id].orEmpty()).contains(query, ignoreCase = true)
    }.sortedWith(compareByDescending<CodexThread> { vm.isPinned(it.id) }.thenByDescending { it.updatedAt })
    val rows = if (vm.onlyRunningThreads) matchingRows.filter { it.id in vm.running } else matchingRows
    val layoutReported = remember(vm.foregroundGeneration, vm.connected, rows.size) {
        java.util.concurrent.atomic.AtomicBoolean(false)
    }
    val runningRows = rows.filter { it.id in vm.running }
    val recentRows = rows.filterNot { it.id in vm.running }

    Box(Modifier.fillMaxSize().background(canvas)) {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    LineIcon("code", Modifier.size(22.dp), colors.onPrimary)
                }
                Column(Modifier.padding(start = 10.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Andri", fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                    val connectionDot by animateColorAsState(
                        if (vm.connected) MaterialTheme.colorScheme.secondary else if (vm.connecting) Color(0xFFB18A54) else MaterialTheme.colorScheme.outline,
                        tween(220), label = "connection-indicator")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(6.dp).background(connectionDot, CircleShape))
                        Text(when {
                            vm.connected && vm.synchronizing -> "正在同步"
                            vm.connected -> "已连接"
                            vm.connecting -> "正在重连"
                            else -> "离线缓存"
                        }, fontSize = 11.sp, lineHeight = 14.sp, color = colors.onSurfaceVariant)
                    }
                }
                Surface(onClick = vm::newThread, enabled = canCreate,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "新建对话" },
                    shape = RoundedCornerShape(16.dp),
                    color = if (canCreate) colors.primary else colors.surfaceVariant,
                    contentColor = if (canCreate) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = .45f)) {
                    Box(contentAlignment = Alignment.Center) { LineIcon("plus", Modifier.size(22.dp)) }
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { AppMenu(vm) }
            }
            Surface(Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, colors.outlineVariant)) {
                Column {
                    AnimatedVisibility(searchExpanded, enter = fadeIn(tween(140)) + expandVertically(),
                        exit = fadeOut(tween(100)) + shrinkVertically()) {
                        TextField(vm.threadSearch, { vm.threadSearch = it },
                            modifier = Modifier.fillMaxWidth().height(56.dp).focusRequester(searchFocus),
                            placeholder = { Text("搜索对话或项目", fontSize = 14.sp) }, singleLine = true,
                            leadingIcon = { LineIcon("search", Modifier.size(19.dp), MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingIcon = { if (vm.threadSearch.isNotEmpty()) IconButton({ vm.threadSearch = "" },
                                modifier = Modifier.semantics { contentDescription = "清空搜索" }) { LineIcon("close", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant) } },
                            shape = RoundedCornerShape(18.dp), colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp),
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
                        }, modifier = Modifier.size(48.dp).semantics {
                            contentDescription = if (searchExpanded) "关闭搜索" else "搜索对话或项目"
                        }) {
                            LineIcon(if (searchExpanded) "close" else "search", Modifier.size(19.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            AnimatedVisibility(!vm.connected, enter = fadeIn(tween(160)) + expandVertically(),
                exit = fadeOut(tween(120)) + shrinkVertically()) {
                Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (vm.connecting) "正在后台恢复连接，可继续查看和编辑" else "离线缓存 · 可查看和编辑草稿",
                            Modifier.weight(1f), fontSize = 12.sp, color = Color(0xFF5D5954))
                        if (!vm.connecting && vm.privateKey.isNotBlank()) TextButton(vm::connect,
                            contentPadding = PaddingValues(horizontal = 8.dp)) { ActionLabel("refresh", "重连", neutralInk) }
                    }
                }
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

            LazyColumn(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned {
                if (layoutReported.compareAndSet(false, true)) vm.recordScreenFrame("thread-list-layout")
            }, contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rows.isEmpty()) item(key = "thread-empty-state") {
                    if (vm.busy) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        Surface(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
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
                        Text("正在进行", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                        Text(runningRows.size.toString(), fontSize = 11.sp, color = colors.onSurfaceVariant)
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
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
                        Text(recentRows.size.toString(), fontSize = 11.sp, color = colors.onSurfaceVariant)
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
        drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerContentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandMark(26)
                Text("Andri", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Serif)
                IconButton(onClick = onNew, enabled = vm.connected && !vm.operationPending("新建对话", null),
                    modifier = Modifier.size(44.dp).semantics { contentDescription = "新建对话" }) {
                    LineIcon("plus", color = MaterialTheme.colorScheme.primary)
                }
            }
            Surface(onClick = onNew, enabled = vm.connected && !vm.operationPending("新建对话", null),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
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
                    focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
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
                    Surface(onClick = { onSelect(item.id) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) Color(0xFFE9E8E4) else Color.Transparent) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val status = if (item.id in vm.running) "进行中" else
                                listOf(vm.lastTaskStatus(item.id), vm.projectFor(item.id)).filter { it.isNotBlank() }.joinToString(" · ")
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
    val colors = MaterialTheme.colorScheme
    var menuExpanded by remember(thread.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val project = vm.projectFor(thread.id)
    val projectColor = projectColor(project)
    val preview = threadPreview(vm.messages[thread.id].orEmpty())
    val pinned = vm.isPinned(thread.id)
    val canArchive = vm.connected && !active && thread.id !in vm.sending && thread.id !in vm.queueSubmitting && !vm.operationPending("归档对话", thread.id)
    val age = relativeThreadTime(thread.updatedAt)
    val startedAt = vm.runningSince[thread.id]
    var now by remember(thread.id, active) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active, startedAt) {
        if (active && startedAt != null) {
            while (true) {
                now = System.currentTimeMillis()
                delay(15_000)
            }
        }
    }
    val runningFor = startedAt?.let { formatElapsed(now - it) }
    val activity = vm.activity[thread.id].orEmpty().takeIf { it.isNotBlank() }
    val activeColor = MaterialTheme.colorScheme.secondary
    val taskStatus = vm.lastTaskStatus(thread.id)

    Surface(onClick = { vm.openThread(thread.id) },
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.secondaryContainer else colors.outline)) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(thread.title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF302F2C), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (active) {
                        Text(listOfNotNull(runningFor?.let { "已运行 $it" }, activity).joinToString(" · ").ifBlank { "正在执行" },
                            fontSize = 12.sp, lineHeight = 18.sp, color = activeColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else if (preview.isNotBlank()) {
                        Text(preview, fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.padding(start = 2.dp).size(48.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.semantics {
                        contentDescription = "对话操作：${thread.title}"
                    }) { LineIcon("more", Modifier.size(18.dp), colors.onSurfaceVariant) }
                    DropdownMenu(menuExpanded, { menuExpanded = false }, shape = RoundedCornerShape(16.dp),
                        containerColor = colors.surface, tonalElevation = 0.dp,
                        border = BorderStroke(1.dp, colors.outlineVariant)) {
                        DropdownMenuItem(leadingIcon = { LineIcon("pin") }, text = { Text(if (pinned) "取消置顶" else "置顶") },
                            onClick = { menuExpanded = false; vm.togglePinned(thread.id) })
                        DropdownMenuItem(leadingIcon = { LineIcon("archive") }, text = { Text("归档") }, enabled = canArchive,
                            onClick = { menuExpanded = false; vm.archiveThread(thread.id) })
                        HorizontalDivider(color = colors.outlineVariant)
                        DropdownMenuItem(leadingIcon = { LineIcon("trash", color = if (canArchive) Color(0xFF9B4F45) else colors.onSurface.copy(alpha = .38f)) }, text = { Text("删除", color = if (canArchive) Color(0xFF9B4F45)
                            else colors.onSurface.copy(alpha = .38f)) }, enabled = canArchive,
                            onClick = { menuExpanded = false; confirmDelete = true })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).background(projectColor, CircleShape))
                    Text(project, Modifier.weight(1f, fill = false), fontSize = 11.sp, lineHeight = 16.sp,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (pinned) Text("置顶", fontSize = 10.sp, color = colors.primary)
                if (active) {
                    Surface(color = Color(0xFFE4EDDF), shape = RoundedCornerShape(6.dp)) {
                        Text("进行中", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = activeColor)
                    }
                } else {
                    if (taskStatus.isNotBlank()) {
                        val resultColor = if (taskStatus == "最近任务已完成") colors.secondary else colors.error
                        Surface(color = resultColor.copy(alpha = .1f), shape = RoundedCornerShape(6.dp)) {
                            Text(taskStatus, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = resultColor)
                        }
                    }
                    age?.let { Text(it, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1) }
                }
            }
            if (active) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp).height(2.dp),
                    color = activeColor, trackColor = Color(0xFFDDE8DC))
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("删除这个会话？") },
        text = { Text("会话将从列表中移除，并在服务器上归档。") },
        confirmButton = { TextButton(enabled = canArchive,
            onClick = { confirmDelete = false; vm.archiveThread(thread.id, deleteLabel = true) }) { ActionLabel("trash", "删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { ActionLabel(null, "取消") } })
}

private val threadProjectColors = listOf(
    Color(0xFF8B776A), Color(0xFF6E897B), Color(0xFF7D7895), Color(0xFF9A8060), Color(0xFF70858A)
)

private fun projectColor(project: String): Color = threadProjectColors[
    (project.fold(17) { hash, char -> hash * 31 + char.code } and Int.MAX_VALUE) % threadProjectColors.size
]

private fun relativeThreadTime(updatedAt: Long): String? {
    if (updatedAt <= 0L) return null
    val elapsed = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        minutes < 48 * 60 -> "昨天"
        minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)} 天前"
        else -> SimpleDateFormat("MM月dd日", Locale.CHINA).format(Date(updatedAt))
    }
}

private fun formatElapsed(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    return when {
        seconds < 60 -> "${seconds}秒"
        seconds < 3_600 -> "${seconds / 60}分"
        else -> "${seconds / 3_600}小时${(seconds / 60) % 60}分"
    }
}

@Composable
private fun ThreadFilterChip(label: String, count: Int, selected: Boolean, selectedColor: Color, selectedText: Color,
    onClick: () -> Unit) {
    val background by animateColorAsState(if (selected) selectedColor else Color.Transparent, tween(200), label = "filter-chip-background")
    val foreground by animateColorAsState(if (selected) selectedText else MaterialTheme.colorScheme.onSurfaceVariant, tween(200), label = "filter-chip-foreground")
    val emphasis by animateFloatAsState(if (selected) 1f else .97f, tween(180), label = "filter-chip-emphasis")
    Surface(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).semantics {
        this.stateDescription = if (selected) "已选中" else "未选中"
    }.graphicsLayer {
        scaleX = emphasis
        scaleY = emphasis
    }, shape = RoundedCornerShape(12.dp),
        color = background,
        border = null) {
        Row(Modifier.padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = foreground)
            Text(count.toString(), fontSize = 11.sp, color = if (selected) selectedText.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Button(onClick = { vm.activeId?.let(vm::openThread) }) { ActionLabel("refresh", "重试") }
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(vm: MobileViewModel, workspaceOpen: Boolean, openWorkspace: () -> Unit,
    addImage: () -> Unit, addVideo: () -> Unit, addFiles: () -> Unit) {
    val thread = vm.activeThread ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val sidebarState = rememberDrawerState(DrawerValue.Closed)
    val sidebarScope = rememberCoroutineScope()
    var serverFileLinkPath by rememberSaveable(thread.id) { mutableStateOf<String?>(null) }
    var serverDirectoryLinkPath by rememberSaveable(thread.id) { mutableStateOf<String?>(null) }
    val running = thread.id in vm.running
    val sourceMessages = vm.messages[thread.id].orEmpty()
    val visibleMessages = remember(sourceMessages) { visibleChatLines(sourceMessages) }
    val activeTurnId = vm.running[thread.id]
    val messages = remember(visibleMessages, activeTurnId, running) {
        foldCompletedProcessLines(visibleMessages, activeTurnId, running)
    }
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
    var imageLinkToOpen by rememberSaveable(thread.id) { mutableStateOf<String?>(null) }
    fun openChatLink(url: String) {
        when (val target = chatLinkTarget(url, thread.cwd.ifBlank { vm.cwd })) {
            is ChatLinkTarget.Workspace -> {
                if (target.path.endsWith('/') || target.path == WORKSPACE_ROOT) {
                    serverDirectoryLinkPath = target.path.trimEnd('/')
                } else serverFileLinkPath = target.path
            }
            is ChatLinkTarget.Image -> imageLinkToOpen = target.url
            is ChatLinkTarget.Web -> webLinkToOpen = target.url
            null -> android.widget.Toast.makeText(context, "无法打开此链接", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    var leftBottom by rememberSaveable(thread.id) { mutableStateOf(false) }
    fun collapse() { expanded = false; focusComposer = false; keyboard?.hide() }
    BackHandler(expanded && !workspaceOpen) { collapse() }
    LaunchedEffect(expanded, focusComposer, workspaceOpen) {
        if (!workspaceOpen && expanded && focusComposer) { focus.requestFocus(); keyboard?.show() }
    }
    val enterWorkspace = {
        focusComposer = false
        keyboard?.hide()
        openWorkspace()
    }
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
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { sidebarScope.launch { sidebarState.open() } },
                modifier = Modifier.semantics { contentDescription = "打开对话侧栏" }) { LineIcon("sidebar") }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(thread.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val options = vm.currentExecution
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(5.dp).background(
                        if (vm.connected) Color(0xFF5F7A56) else Color(0xFFAE5A45), CircleShape))
                    Text("82 · " + options.cwd.trimEnd('/').substringAfterLast('/').ifBlank { "/" },
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("· " + options.mode.label + " · " + reasoningEffortLabel(options.effort),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(enterWorkspace, modifier = Modifier.semantics { contentDescription = "打开工作区" }) {
                LineIcon("folder")
            }
            AppMenu(vm, thread)
        }
        val syncingThread = thread.id in vm.syncingThreadIds || vm.openingThreadId == thread.id
        if (!vm.connected || !vm.canSendToThread(thread.id)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        syncingThread -> "正在同步当前对话… · 草稿会保留"
                        vm.connected -> "当前对话未同步，暂不能发送 · 草稿会保留"
                        vm.connecting -> "正在恢复连接 · 草稿会保留"
                        else -> "离线缓存 · 草稿会自动保存"
                    },
                    Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!vm.connected && !vm.connecting && vm.privateKey.isNotBlank()) {
                        TextButton(vm::connect, contentPadding = PaddingValues(horizontal = 8.dp)) { ActionLabel("refresh", "重连") }
                }
                if (vm.connected) {
                    TextButton(
                        onClick = { vm.retryThreadSync(thread.id) },
                        enabled = !syncingThread,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        if (syncingThread) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("同步中")
                        } else ActionLabel("refresh", "重新同步")
                    }
                }
            }
        }
        UpdateNotice(vm)
        ErrorText(vm.error)
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.requests.isNotEmpty()) TextButton({ vm.openPendingRequest() }) {
            Text("待处理问答与审批 · ${vm.requests.size} 项", fontSize = 12.sp)
        }
        val queuedTasks = vm.queuedTasks[thread.id].orEmpty()
        AnimatedVisibility(queuedTasks.isNotEmpty(), enter = fadeIn(tween(160)) + expandVertically(), exit = fadeOut(tween(120)) + shrinkVertically()) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
                                ActionLabel("play", "启动", iconSize = 13.dp, fontSize = 11.sp)
                            }
                            TextButton({ vm.removeQueuedTask(task.id) }, enabled = task.id !in vm.removingQueuedTasks,
                                contentPadding = PaddingValues(horizontal = 6.dp)) {
                                ActionLabel("close", if (task.id in vm.removingQueuedTasks) "移除中…" else "移除", iconSize = 13.dp, fontSize = 11.sp)
                            }
                        }
                    }
                    if (queuedTasks.size > 3) Text("另有 ${queuedTasks.size - 3} 条", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().workspaceSwipe(toWorkspace = true, onSwipe = enterWorkspace), state = list, reverseLayout = true,
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.Bottom) {
                // reverseLayout places the first item at the physical bottom. Keep a real
                // spacer here so the last message clears the floating composer.
                item("chatBottom") { Spacer(Modifier.height(chatBottomInset(composerHeight.value, expanded).dp)) }
                item("chatActivity") {
                    androidx.compose.animation.AnimatedVisibility(running || sending, enter = fadeIn(tween(180)) + expandVertically(), exit = fadeOut(tween(140)) + shrinkVertically()) {
                    Surface(Modifier.padding(top = 20.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
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
                                Surface(Modifier.widthIn(max = maxWidth * 0.88f), color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
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
                    androidx.compose.animation.AnimatedVisibility(messages.isEmpty() ,
                        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                        exit = fadeOut(tween(140)) + shrinkVertically(tween(180))) {
                        Column(Modifier.padding(top = 40.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            BrandMark(44)
                            Text("把想法，\r\n变成下一步。", fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.8).sp, fontFamily = FontFamily.Serif)
                            Text("写下你想完成的事。\r\nAndri 会在服务器上与你一起处理。", fontSize = 14.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item("historyHeader") {
                    if (!vm.historyCursors[thread.id].isNullOrBlank()) TextButton(vm::loadOlder, enabled = vm.connected && !vm.operationPending("加载历史")) { ActionLabel("arrow-up", "查看更早的消息", iconSize = 14.dp, fontSize = 12.sp) }
                }
            }
            // Only the rounded surface receives input; there is no opaque rectangular footer.
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp).padding(top = 18.dp, bottom = 18.dp)) {
                androidx.compose.animation.AnimatedVisibility(!expanded, modifier = Modifier.align(Alignment.BottomStart).offset(y = (-60).dp),
                    enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = .88f),
                    exit = fadeOut(tween(120)) + scaleOut(tween(180), targetScale = .88f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        Surface(onClick = { focusComposer = true; expanded = true }, modifier = Modifier.size(56.dp)
                            .semantics { contentDescription = "展开输入框" }, shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            shadowElevation = 2.dp) {
                            Box(contentAlignment = Alignment.Center) { LineIcon("north", Modifier.size(24.dp), MaterialTheme.colorScheme.onPrimary) }
                        }
                        if (draft.isNotBlank() || imageAttachments.isNotEmpty() || videoAttachment != null || sharedFiles.isNotEmpty() || references.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd).size(9.dp).background(MaterialTheme.colorScheme.surface, CircleShape))
                    }
                }
                }
                androidx.compose.animation.AnimatedVisibility(expanded, modifier = Modifier.align(Alignment.BottomStart),
                    enter = fadeIn(tween(180)) + slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 4 },
                    exit = fadeOut(tween(140)) + slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it / 4 }) {
                Surface(Modifier.fillMaxWidth().onSizeChanged { composerHeight = with(density) { it.height.toDp() } }, shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Column(Modifier.animateContentSize(tween(180)).padding(6.dp)) {
                        if (sending) Text("正在提交，后续编辑和新增附件会保留", Modifier.padding(horizontal = 10.dp),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (imageAttachments.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                imageAttachments.forEachIndexed { index, image ->
                                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 9.dp)) {
                                            Text(image.label, Modifier.widthIn(max = 140.dp), fontSize = 11.sp,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            IconButton({ vm.removeImage(index) }, Modifier.size(34.dp)
                                                .semantics { contentDescription = "移除${image.label}" }) { LineIcon("close") }
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
                                IconButton(vm::removeVideo, Modifier.size(44.dp).semantics { contentDescription = "移除视频" }) { LineIcon("close") }
                            }
                        }
                        sharedFiles.forEachIndexed { index, file ->
                            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                LineIcon("file", Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                                Text("${file.label} · ${formatVideoSize(file.sizeBytes)}", Modifier.weight(1f).padding(start = 8.dp),
                                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton({ vm.removeSharedFile(index) }, Modifier.size(44.dp).semantics { contentDescription = "移除${file.label}" }) { LineIcon("close") }
                            }
                        }
                        references.forEach { reference ->
                            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                LineIcon("file", Modifier.size(18.dp), MaterialTheme.colorScheme.secondary)
                                Text(reference.label, Modifier.weight(1f).padding(start = 8.dp),
                                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton({ vm.removeContextReference(reference.id) }, Modifier.size(44.dp)
                                    .semantics { contentDescription = "移除上下文 ${reference.label}" }) { LineIcon("close") }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            var tools by remember { mutableStateOf(false) }
                            Box {
                                IconButton({ tools = true }, Modifier.size(44.dp).semantics { contentDescription = "附件与模型" }) { LineIcon("plus") }
                                DropdownMenu(tools, { tools = false }, modifier = Modifier.width(300.dp), shape = RoundedCornerShape(24.dp),
                                    containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
                                    shadowElevation = 4.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Surface(onClick = { tools = false; addImage() }, enabled = true,
                                            color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                LineIcon("paperclip", Modifier.size(20.dp))
                                                Text("添加图片", fontSize = 14.sp)
                                            }
                                        }
                                        Surface(onClick = { tools = false; addVideo() }, enabled = true,
                                            color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                LineIcon("play", Modifier.size(20.dp))
                                                Text("添加视频（原画质）", fontSize = 14.sp)
                                            }
                                        }
                                        Surface(onClick = { tools = false; addFiles() }, enabled = true,
                                            color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                LineIcon("file", Modifier.size(20.dp))
                                                Text("添加文件（也可从 QQ / 微信分享）", fontSize = 14.sp)
                                            }
                                        }
                                        Surface(onClick = { tools = false; contextDrawerOpen = true }, enabled = true,
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
                                                Surface(onClick = { vm.selectModel(model) }, enabled = true,
                                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                                    Text(model, Modifier.padding(vertical = 12.dp), fontSize = 12.sp,
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                        ReasoningEffortSlider(vm.currentExecution.effort, vm.reasoningEfforts(), vm.model, true, vm::setReasoningEffort)
                                        MutedText("模型与推理强度用于下一次发送或排队")
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
                                enabled = true, minLines = 1, maxLines = 4,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, lineHeight = 24.sp),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { field ->
                                    if (draft.isEmpty()) Text("发消息…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                                    field()
                                })
                            if (running) IconButton(vm::stop,
                                enabled = vm.connected && !vm.operationPending("停止任务", thread.id),
                                modifier = Modifier.size(44.dp).semantics { contentDescription = "暂停当前任务" }) {
                                if (vm.operationPending("停止任务", thread.id)) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else LineIcon("pause", Modifier.size(21.dp))
                            }
                            FilledIconButton(if (running) vm::enqueueDraft else vm::send,
                                enabled = vm.canSendToThread(thread.id) && (draft.isNotBlank() || imageAttachments.isNotEmpty() || videoAttachment != null || sharedFiles.isNotEmpty() || references.isNotEmpty()) && !sending && thread.id !in vm.queueSubmitting,
                                modifier = Modifier.size(44.dp).semantics { contentDescription = if (running) "排队发送消息" else "发送消息" }, shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else LineIcon("north", Modifier.size(21.dp))
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
    imageLinkToOpen?.let { url ->
        Dialog(onDismissRequest = { imageLinkToOpen = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ChatImageSource(url, vm, thread.cwd.ifBlank { vm.cwd }, galleryPage = true)
                TextButton(onClick = { imageLinkToOpen = null }, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding()) {
                    ActionLabel("close", "关闭", Color.White)
                }
            }
        }
    }
    serverFileLinkPath?.let { path ->
        ServerWorkspaceFileLinkDialog(vm, path) { serverFileLinkPath = null }
    }
    serverDirectoryLinkPath?.let { path ->
        ServerWorkspaceDirectoryLinkDialog(vm, path) { serverDirectoryLinkPath = null }
    }
}

@Composable
private fun WorkspacePage(vm: MobileViewModel, onBack: () -> Unit) {
    val thread = vm.activeThread ?: return
    val cwd = vm.currentExecution.cwd.trimEnd('/').ifBlank { "/" }
    var selectedTab by rememberSaveable(thread.id) { mutableIntStateOf(0) }
    var previewPath by rememberSaveable(thread.id, cwd) { mutableStateOf<String?>(null) }
    var filesRequested by remember(cwd, vm.connected) { mutableStateOf(false) }
    var diffRequested by remember(cwd, vm.connected) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(cwd, vm.connected) {
        if (vm.connected) {
            vm.loadProjectFiles(cwd)
            filesRequested = true
        }
    }
    LaunchedEffect(selectedTab, cwd, vm.connected) {
        if (selectedTab == 1 && vm.connected) {
            vm.loadGitDiff()
            diffRequested = true
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .workspaceSwipe(toWorkspace = false, onSwipe = onBack)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, modifier = Modifier.semantics { contentDescription = "返回聊天" }) { LineIcon("back") }
            Column(Modifier.weight(1f)) {
                Text("工作区", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(vm.projectFor(thread.id), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = {
                if (selectedTab == 0) vm.loadProjectFiles(vm.fileBrowserPath.takeIf { filesRequested }.orEmpty().ifBlank { cwd })
                else vm.loadGitDiff()
            }, enabled = vm.connected && !(if (selectedTab == 0) vm.projectFilesBusy else vm.gitDiffBusy),
                modifier = Modifier.semantics { contentDescription = "刷新工作区" }) { LineIcon("refresh") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("文件", "改动").forEachIndexed { index, label ->
                FilterChip(selected = selectedTab == index, onClick = { selectedTab = index }, label = { Text(label) }, leadingIcon = {
                    LineIcon(if (index == 0) "folder" else "code", Modifier.size(17.dp))
                })
            }
        }
        when (selectedTab) {
            0 -> {
                val path = vm.fileBrowserPath.takeIf { filesRequested && it.isNotBlank() } ?: cwd
                val relativePath = if (path == cwd) "项目根目录" else path.removePrefix(cwd.trimEnd('/') + "/")
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(relativePath, Modifier.weight(1f), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { vm.loadProjectFiles(cwd) }, enabled = vm.connected && path != cwd,
                        contentPadding = PaddingValues(horizontal = 8.dp)) { ActionLabel("folder", "根目录", fontSize = 11.sp) }
                    TextButton(onClick = { vm.loadProjectFiles(path.substringBeforeLast('/').ifBlank { "/" }) },
                        enabled = vm.connected && path != cwd && path.startsWith(cwd.trimEnd('/') + "/"),
                        contentPadding = PaddingValues(horizontal = 8.dp)) { ActionLabel("arrow-up", "上级", fontSize = 11.sp) }
                }
                val listing = vm.projectFilesListing.takeIf { filesRequested && it?.path == path }
                when {
                    !vm.connected -> WorkspaceEmptyCard("连接服务器后查看项目文件。")
                    !filesRequested || vm.projectFilesBusy -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    vm.projectFilesError.isNotBlank() -> Text(vm.projectFilesError, Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    listing != null -> {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            if (listing.children.isEmpty() && listing.files.isEmpty()) item("empty-directory") {
                                WorkspaceEmptyCard("这个目录里没有文件。")
                            }
                            items(listing.children, key = { "dir:$it" }) { name ->
                                Surface(onClick = { vm.loadProjectFiles(listing.path.trimEnd('/') + "/" + name) },
                                    enabled = vm.connected, shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        LineIcon("folder", Modifier.size(19.dp), MaterialTheme.colorScheme.primary)
                                        Text(name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        LineIcon("chevron", Modifier.size(17.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            items(listing.files, key = { "file:$it" }) { name ->
                                val path = listing.path.trimEnd('/') + "/" + name
                                Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                    Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        LineIcon("file", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(name, Modifier.weight(1f).padding(start = 10.dp), fontSize = 13.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        IconButton(onClick = {
                                            vm.addWorkspaceFileContext(path, name)
                                            android.widget.Toast.makeText(context, "已加入聊天上下文", android.widget.Toast.LENGTH_SHORT).show()
                                        }, enabled = vm.connected,
                                            modifier = Modifier.semantics { contentDescription = "引用 $name" }) { LineIcon("plus", Modifier.size(18.dp), MaterialTheme.colorScheme.primary) }
                                        IconButton(onClick = { previewPath = path }, enabled = vm.connected,
                                            modifier = Modifier.semantics { contentDescription = "预览 $name" }) { LineIcon("eye", Modifier.size(18.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                when {
                    !vm.connected -> WorkspaceEmptyCard("连接服务器后查看代码改动。")
                    !diffRequested || vm.gitDiffBusy -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    vm.gitDiffError.isNotBlank() -> Text(vm.gitDiffError, Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    else -> {
                        val diff = vm.gitDiff
                        val hasChanges = remember(diff) { diff.lineSequence().any { it.isNotBlank() && !it.startsWith("--- ") } }
                        if (!hasChanges) WorkspaceEmptyCard("当前工作区没有 Git 改动。")
                        else SelectionContainer {
                            val displayedDiff = if (diff.length > 48_000) diff.take(48_000) + "\n\n… 内容过长，已截断" else diff
                            Text(displayedDiff, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                                .padding(horizontal = 18.dp, vertical = 10.dp), fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    previewPath?.let { ServerWorkspaceFileLinkDialog(vm, it) { previewPath = null } }
}

private fun Modifier.workspaceSwipe(toWorkspace: Boolean, onSwipe: () -> Unit): Modifier = pointerInput(toWorkspace) {
    val direction = if (toWorkspace) -1 else 1
    val threshold = 64.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var travel = 0f
        var wrongDirection = false
        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
            if (overSlop * direction <= 0f) wrongDirection = true
            if (!wrongDirection) {
                change.consume()
                travel = overSlop * direction
            }
        } ?: return@awaitEachGesture
        val completed = horizontalDrag(start.id) { change ->
            travel += change.positionChange().x * direction
            change.consume()
        }
        if (completed && travel >= threshold) onSwipe()
    }
}

@Composable
private fun WorkspaceEmptyCard(message: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Text(message, Modifier.padding(horizontal = 16.dp, vertical = 18.dp), fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SmoothAssistantMessage(line: ChatLine, vm: MobileViewModel, onOpenLink: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var visible by rememberSaveable(line.id) { mutableStateOf(if (line.streaming) "" else assistantAnswerText(line.text)) }
    var animate by remember(line.id) { mutableStateOf(line.streaming) }
    val answerText = assistantAnswerText(line.text)
    var copied by remember(line.id) { mutableStateOf(false) }
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
        if (!line.streaming && answerText.isNotBlank()) {
            TextButton(onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Andri 生成文字", answerText))
                copied = true
            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                ActionLabel(if (copied) "check" else "save", if (copied) "已复制" else "复制文字", iconSize = 14.dp, fontSize = 12.sp)
            }
        }
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
            key(index, source) {
                ChatImageSource(source, vm, baseDirectory, compact = true, onClick = { selectedIndex = index },
                    modifier = Modifier.size(76.dp))
            }
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
                    ActionLabel("close", "关闭", Color.White)
                }
                if (sources.size > 1) {
                    Text("${pagerState.currentPage + 1} / ${sources.size}",
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp), color = MaterialTheme.colorScheme.surface,
                        fontSize = 13.sp)
                }
            }
        }
    }
}

// Every produceState producer below assigns value; the current lint detector misses these assignments.
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun ChatImage(
    source: String,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    galleryPage: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val saveScope = rememberCoroutineScope()
    var retryCount by remember(source) { mutableIntStateOf(0) }
    val imageLoad by produceState<ChatImageLoad?>(null, source, retryCount) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
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
                check(bytes != null) { "图片来源格式不受支持" }
                val decoded = imageDecodeSemaphore.withPermit { decodeChatBitmap(bytes) }
                check(decoded != null) { "图片格式无法解码" }
                ChatImageLoad(bytes = bytes, bitmap = decoded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ChatImageLoad(error = error.message?.take(180).orEmpty().ifBlank { "图片读取失败" })
            }
        }
    }
    val originalBytes = imageLoad?.bytes
    val bitmap = imageLoad?.bitmap
    val loadError = imageLoad?.error
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
    var saving by remember(source) { mutableStateOf(false) }
    var pendingLegacySave by remember(source) { mutableStateOf<Triple<ByteArray, String, String>?>(null) }
    val mimeType = remember(source, originalBytes) { imageMimeType(source, originalBytes) }
    val suggestedName = remember(source, mimeType) { imageFileName(source, mimeType) }
    fun reportSaveResult(message: String) {
        saveStatus = message
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }
    fun saveToGallery(bytes: ByteArray, name: String, type: String, legacy: Boolean) {
        saving = true
        saveStatus = "正在保存到相册…"
        saveScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (legacy) saveImageToLegacyGallery(context, bytes, name, type)
                    else saveImageToGallery(context, bytes, name, type)
                }
                reportSaveResult("原图已保存到相册")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportSaveResult("保存失败：${error.message ?: "无法写入相册"}")
            } finally {
                saving = false
            }
        }
    }
    val legacyStoragePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingLegacySave
        pendingLegacySave = null
        if (granted && pending != null) saveToGallery(pending.first, pending.second, pending.third, legacy = true)
        else {
            saving = false
            if (!granted) reportSaveResult("需要存储权限才能保存到相册")
        }
    }
    fun saveOriginal() {
        if (saving) return
        val bytes = originalBytes
        if (bytes == null) reportSaveResult("图片尚未加载完成")
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToGallery(bytes, suggestedName, mimeType, legacy = false)
        } else if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingLegacySave = Triple(bytes, suggestedName, mimeType)
            saving = true
            saveStatus = "等待存储权限…"
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else saveToGallery(bytes, suggestedName, mimeType, legacy = true)
    }

    if (bitmap != null && galleryPage) {
        Box(Modifier.fillMaxSize()) {
            Image((previewBitmap ?: bitmap)!!.asImageBitmap(), contentDescription = "图片预览",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Column(Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = ::saveOriginal, enabled = !saving) {
                    ActionLabel("download", if (saving) "正在保存…" else "下载原图", Color.White)
                }
                if (saveStatus.isNotBlank()) ImageSaveFeedback(saveStatus)
            }
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
                    TextButton(onClick = ::openPreview) { ActionLabel("eye", "查看原图") }
                    TextButton(onClick = ::saveOriginal, enabled = !saving) {
                        ActionLabel("download", if (saving) "正在保存…" else "下载原图")
                    }
                }
                if (saveStatus.isNotBlank()) Text(saveStatus, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else if (source.isNotBlank() && imageLoad == null) {
        if (galleryPage) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (compact) {
            Box(modifier.size(76.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else Text("正在加载图片…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (source.isNotBlank()) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
        val retryAction: @Composable () -> Unit = {
            TextButton(onClick = { retryCount++ }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                ActionLabel("refresh", "重试加载", iconSize = 14.dp, fontSize = 12.sp)
            }
        }
        val copyLinkAction: @Composable () -> Unit = {
            if (source.startsWith("https://", true)) {
                TextButton(onClick = {
                    clipboard.setPrimaryClip(ClipData.newPlainText("图片链接", source))
                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    ActionLabel("link", "复制链接", iconSize = 14.dp, fontSize = 12.sp)
                }
            }
        }
        if (compact) {
            Column(modifier.size(76.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("加载失败", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                retryAction()
            }
        } else if (galleryPage) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("图片加载失败：${loadError.orEmpty()}", color = Color.White, fontSize = 13.sp)
                Row { retryAction(); copyLinkAction() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("图片加载失败：${loadError.orEmpty()}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row { retryAction(); copyLinkAction() }
            }
        }
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
                        ActionLabel("close", "关闭", Color.White)
                    }
                    Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (saveStatus.isNotBlank()) ImageSaveFeedback(saveStatus)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) { ActionLabel("refresh", "重置缩放", Color.White) }
                            TextButton(onClick = ::saveOriginal, enabled = !saving) {
                                ActionLabel("download", if (saving) "正在保存…" else "下载原图", Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageSaveFeedback(status: String) {
    Text(status, Modifier.background(Color.Black.copy(alpha = .7f), RoundedCornerShape(8.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp).semantics {
            liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
        }, fontSize = 13.sp, color = Color.White)
}

private fun saveImageToGallery(context: android.content.Context, bytes: ByteArray, name: String, mimeType: String) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/Andri")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建相册图片")
    try {
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("无法写入相册图片")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (error: Throwable) {
        resolver.delete(uri, null, null)
        throw error
    }
}

private fun saveVideoToGallery(context: android.content.Context, source: File, name: String) {
    val mimeType = videoMimeType(name)
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, mimeType)
        put(MediaStore.Video.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_MOVIES}/Andri")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建相册视频")
    try {
        resolver.openOutputStream(uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output, 128 * 1024) } }
            ?: error("无法写入相册视频")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
    } catch (error: Throwable) {
        resolver.delete(uri, null, null)
        throw error
    }
}

@Suppress("DEPRECATION")
private fun saveVideoToLegacyGallery(context: android.content.Context, source: File, name: String) {
    val directory = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "Andri")
    check(directory.exists() || directory.mkdirs()) { "无法创建相册目录" }
    val output = File(directory, name)
    source.inputStream().use { input -> output.outputStream().use { input.copyTo(it, 128 * 1024) } }
    android.media.MediaScannerConnection.scanFile(context, arrayOf(output.absolutePath), arrayOf(videoMimeType(name)), null)
}

private fun videoMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "m4v" -> "video/x-m4v"
    "mov" -> "video/quicktime"
    "3gp" -> "video/3gpp"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "avi" -> "video/x-msvideo"
    else -> "video/mp4"
}

@Suppress("DEPRECATION")
private fun saveImageToLegacyGallery(context: android.content.Context, bytes: ByteArray, name: String, mimeType: String) {
    val directory = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Andri")
    check(directory.exists() || directory.mkdirs()) { "无法创建相册目录" }
    val output = File(directory, name)
    output.outputStream().use { it.write(bytes) }
    android.media.MediaScannerConnection.scanFile(context, arrayOf(output.absolutePath), arrayOf(mimeType), null)
}

private fun imageMimeType(source: String, bytes: ByteArray?): String {
    if (source.startsWith("data:image/", true)) return source.substringAfter("data:").substringBefore(';')
    val path = runCatching { if (source.startsWith("file:", true)) URI(source).path else URI(source).path }.getOrNull().orEmpty()
    return when (path.substringAfterLast('.', "").lowercase()) {
        "svg" -> "image/svg+xml"
        "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
        "avif" -> "image/avif"; "heic" -> "image/heic"; "heif" -> "image/heif"
        else -> when {
            bytes?.getOrNull(0) == 0xFF.toByte() && bytes.getOrNull(1) == 0xD8.toByte() -> "image/jpeg"
            bytes != null && isPngImage(bytes) -> "image/png"
            bytes?.take(6)?.toByteArray()?.decodeToString()?.startsWith("GIF8") == true -> "image/gif"
            else -> "image/jpeg"
        }
    }
}

private fun imageFileName(source: String, mimeType: String): String {
    val extension = when (mimeType.lowercase()) {
        "image/svg+xml" -> "svg"
        "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; "image/bmp" -> "bmp"
        "image/avif" -> "avif"; "image/heic" -> "heic"; "image/heif" -> "heif"; else -> "jpg"
    }
    val candidate = runCatching { URI(source).path.orEmpty().substringAfterLast('/') }.getOrDefault("")
        .substringBefore('?').take(80)
    return candidate.takeIf { it.matches(Regex("[A-Za-z0-9._-]+\\.[A-Za-z0-9]{1,8}")) } ?: "andri-image.$extension"
}

private const val MAX_CHAT_IMAGE_BYTES = 10 * 1024 * 1024
private val remoteImageLoadSemaphore = Semaphore(permits = 4)
private val imageDecodeSemaphore = Semaphore(permits = 2)
private const val MAX_CHAT_IMAGE_SOURCE_PIXELS = 100_000_000L
private const val MAX_CHAT_IMAGE_DECODE_PIXELS = 2_500_000L
private const val MAX_CHAT_IMAGE_PREVIEW_PIXELS = 20_000_000L
private data class ChatImageLoad(val bytes: ByteArray? = null, val bitmap: android.graphics.Bitmap? = null, val error: String? = null)
private data class WorkspaceImageLoad(val uri: String? = null, val error: String? = null)

private fun decodeChatBitmap(bytes: ByteArray, maxPixels: Long = MAX_CHAT_IMAGE_DECODE_PIXELS): android.graphics.Bitmap? {
    if (bytes.isEmpty() || bytes.size > MAX_CHAT_IMAGE_BYTES) return null
    if (isSvgImage(bytes)) return decodeSvgBitmap(bytes, maxPixels)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodeWithImageDecoder(bytes, maxPixels)?.let { return it }
    }
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

@androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
private fun decodeWithImageDecoder(bytes: ByteArray, maxPixels: Long): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_CHAT_IMAGE_SOURCE_PIXELS) { "图片尺寸过大" }
        val scale = minOf(1.0, kotlin.math.sqrt(maxPixels.toDouble() / (width.toDouble() * height)), 8192.0 / maxOf(width, height))
        decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

private fun decodeSvgBitmap(bytes: ByteArray, maxPixels: Long): Bitmap? {
    return try {
        val svg = SVG.getFromString(bytes.toString(Charsets.UTF_8))
        val viewBox = svg.documentViewBox
        val width = svg.documentWidth.takeIf { it.isFinite() && it > 0f } ?: viewBox?.width()
        val height = svg.documentHeight.takeIf { it.isFinite() && it > 0f } ?: viewBox?.height()
        if (width == null || height == null || !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
        val scale = minOf(1f, kotlin.math.sqrt(maxPixels.toDouble() / (width.toDouble() * height)).toFloat(), 8192f / maxOf(width, height))
        val outWidth = (width * scale).toInt().coerceAtLeast(1)
        val outHeight = (height * scale).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.scale(outWidth / width, outHeight / height)
            svg.renderToCanvas(canvas)
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

// Every produceState producer below assigns value; the current lint detector misses these assignments.
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
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
        var retryCount by remember(serverPath, baseDirectory) { mutableIntStateOf(0) }
        val local by produceState<WorkspaceImageLoad?>(null, serverPath, baseDirectory, retryCount) {
            value = null
            value = try {
                WorkspaceImageLoad(uri = vm.cacheWorkspaceImage(serverPath, baseDirectory))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                WorkspaceImageLoad(error = error.message?.take(180).orEmpty().ifBlank { "服务器图片读取失败" })
            }
        }
        when {
            local == null -> Text("正在读取工作区图片…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            local!!.uri != null -> ChatImage(local!!.uri!!, compact, onClick, galleryPage, modifier)
            else -> {
                val errorModifier = if (galleryPage) Modifier.fillMaxSize() else Modifier.then(if (compact) modifier else Modifier)
                Column(errorModifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text("无法读取图片：${local!!.error}", fontSize = 11.sp,
                        color = if (galleryPage) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { retryCount++ }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        ActionLabel("refresh", "重试", iconSize = 14.dp, fontSize = 12.sp,
                            color = if (galleryPage) Color.White else Color.Unspecified)
                    }
                }
            }
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
        key(destination) { ChatImageSource(destination, vm, baseDirectory) }
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

// Every produceState producer below assigns value; the current lint detector misses these assignments.
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun ChatVideoSource(path: String, vm: MobileViewModel, baseDirectory: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
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
    val legacyStoragePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val file = videoFile
        if (granted && file?.isFile == true) scope.launch {
            status = "正在保存到相册…"
            val result = runCatching { withContext(Dispatchers.IO) { saveVideoToLegacyGallery(context, file, name) } }
            status = result.fold({ "已保存到相册" }, { "保存失败：${it.message ?: "无法写入相册"}" })
        } else if (!granted) status = "需要存储权限才能保存到相册"
    }
    fun saveVideo() {
        val file = videoFile
        if (file?.isFile != true) { status = "视频尚未下载完成"; return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            status = "正在保存到相册…"
            scope.launch {
                val result = runCatching { withContext(Dispatchers.IO) { saveVideoToGallery(context, file, name) } }
                status = result.fold({ "已保存到相册" }, { "保存失败：${it.message ?: "无法写入相册"}" })
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            status = "正在保存到相册…"
            scope.launch {
                val result = runCatching { withContext(Dispatchers.IO) { saveVideoToLegacyGallery(context, file, name) } }
                status = result.fold({ "已保存到相册" }, { "保存失败：${it.message ?: "无法写入相册"}" })
            }
        }
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
            TextButton(onClick = ::saveVideo, enabled = videoFile?.isFile == true) {
                ActionLabel("download", "保存视频到相册", MaterialTheme.colorScheme.primary)
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
    check(contentType.startsWith("image/") || pathExtension in chatImageExtensions) {
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
    // Item completion is not turn completion: keep the summary open until the whole task ends.
    LaunchedEffect(line.id, summaryActive) {
        if (line.isReasoningSummary) expanded = summaryActive
    }
    val pending = vm.requests.firstOrNull { "request:" + it.opt("id") == line.id }
    val status = when (line.status) {
        "pending" -> "待处理"; "inProgress" -> "进行中"; "failed" -> "失败"; "declined" -> "已拒绝"
        "answered" -> "已回答"; "approved" -> "已批准"; "interrupted" -> "已中断"; "resolved" -> "已处理"; else -> "已完成"
    }
    val running = line.status == "inProgress" || line.status == "pending"
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.fillMaxWidth()) {
            Surface(onClick = { expanded = !expanded }, color = Color.Transparent) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center) {
                        if (running) CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary)
                        else LineIcon("check", Modifier.size(13.dp), MaterialTheme.colorScheme.secondary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (line.processItems.size > 1) "${line.stage.takeIf { it.isNotBlank() } ?: "执行过程"} · ${line.processItems.size} 个步骤"
                            else line.stage.takeIf { it.isNotBlank() } ?: "执行过程",
                            fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (line.processItems.isNotEmpty()) Text(status + " · " + line.processItems.size + " 项",
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                        else Text(status, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                    }
                    Text(if (expanded) "⌃" else "⌄", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 13.dp, vertical = 8.dp)) {
                    if (line.processItems.isNotEmpty()) {
                        val summaries = line.processItems.filter { it.isReasoningSummary && it.text.isNotBlank() }
                        val details = line.processItems.filterNot { it.isReasoningSummary }
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            if (summaries.isEmpty()) {
                                Text("本轮没有可展示的摘要。", Modifier.fillMaxWidth(), fontSize = 12.sp,
                                    lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    summaries.forEach { MarkdownMessage(it.text, onOpenLink) }
                                }
                            }
                            if (details.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    details.forEachIndexed { index, item ->
                                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                                Text(String.format(Locale.ROOT, "%02d", index + 1), fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = if (item.status == "inProgress") MaterialTheme.colorScheme.primary
                                                        else if (item.status == "failed") MaterialTheme.colorScheme.error
                                                        else MaterialTheme.colorScheme.secondary)
                                                Text(item.stage, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f))
                                                if (item.status == "inProgress") Text("···", fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                                            }
                                            if (item.text.isNotBlank()) SelectionContainer {
                                                Text(truncateChatText(item.text) +
                                                    if (item.text.codePointCount(0, item.text.length) > MAX_MARKDOWN_RENDER_CHARS) "\r\n\r\n…内容过长，已截断显示。" else "",
                                                    Modifier.fillMaxWidth().padding(start = 22.dp), fontSize = 11.sp, lineHeight = 18.sp,
                                                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    if (pending != null) TextButton({ vm.openPendingRequest(pending.get("id").toString()) }) {
                        Text("处理此问答或审批", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionFields(title: String, options: List<Pair<String, String>>, value: String, enabled: Boolean,
    secret: Boolean = false, collapsibleCustomInput: Boolean = false, onChange: (String) -> Unit) {
    val hasCustomValue = options.isNotEmpty() && value.isNotBlank() && options.none { it.first == value }
    var customInputExpanded by rememberSaveable(title) { mutableStateOf(hasCustomValue) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 14.sp, lineHeight = 21.sp)
        if (options.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()) {
            options.forEach { (label, _) ->
                val selected = value == label
                Surface(onClick = { onChange(if (selected) "" else label) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                    Text(label, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                        fontSize = 13.5.sp, lineHeight = 20.sp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        if (options.isNotEmpty() && collapsibleCustomInput) {
            val expanded = customInputExpanded
            Row(Modifier.clickable(enabled = enabled) { customInputExpanded = !expanded }
                .padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (expanded) "▾" else "▸", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("自己填写", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(expanded) {
                OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), enabled = enabled,
                    label = { Text("你的回答") }, shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium, minLines = 1, maxLines = 4,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
            }
        } else {
            OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), enabled = enabled,
                label = { Text(if (options.isEmpty()) "你的回答" else "也可以自己填写") },
                shape = RoundedCornerShape(12.dp), textStyle = MaterialTheme.typography.bodyMedium,
                minLines = 1, maxLines = 4,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
        }
    }
}

@Composable
private fun AsyncQuestionCard(vm: MobileViewModel, line: ChatLine, latestQuestion: Boolean) {
    val answers = vm.questionAnswerDrafts[line.id].orEmpty()
    val answered = line.id in vm.answeredQuestionItems || !latestQuestion
    val enabled = !answered && vm.canSendToThread(vm.activeId.orEmpty()) && vm.activeId !in vm.sending && vm.activeId !in vm.queueSubmitting
    val allAnswered = line.questions.indices.all { !answers[it].isNullOrBlank() }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (!answered && line.questions.isNotEmpty()) Text("可以，确认几个小地方就开始。",
            fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface)
        line.questions.forEachIndexed { index, question ->
            QuestionFields(question.title, question.options.map { it to "" }, answers[index].orEmpty(), !answered,
                collapsibleCustomInput = true) { vm.questionAnswerDrafts[line.id] = answers + (index to it) }
        }
        if (!answered) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.sendQuestionAnswers(line, line.questions.indices.map { answers[it].orEmpty() }) },
                enabled = enabled && allAnswered, shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { ActionLabel("send", "按这些要求继续") }
            TextButton(onClick = { vm.sendQuestionAnswers(line, line.questions.indices.map { "" }, skip = true) },
                enabled = enabled) { Text("先出一版", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun MarkdownMessage(value: String, onOpenLink: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val color = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.primaryContainer
    val codeFg = MaterialTheme.colorScheme.primary
    val currentOpenLink by rememberUpdatedState(onOpenLink)
    val markwon = remember(context, codeBg, codeFg) {
        val serif = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { _, link -> currentOpenLink(link) }
                }
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .headingTypeface(serif)
                        .headingBreakHeight(0)
                        .blockQuoteColor(Color(0xFFE3D5C7).toArgb())
                        .blockQuoteWidth(2)
                        .codeTextColor(codeFg.toArgb())
                        .codeBackgroundColor(codeBg.toArgb())
                }
            })
            .build()
    }
    AndroidView(
        factory = { viewContext -> TextView(viewContext).apply {
            textSize = 15f
            setTextColor(color.toArgb())
            setLineSpacing(0f, 1.45f)
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
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
        .let { normalized ->
            // A space before the closing delimiter prevents CommonMark from recognizing bold text.
            // Move it outside the delimiters so generated prose such as `**text. **next` renders bold.
            Regex("(\\*\\*[^*\\n]+?)([ \\t]+)(\\*\\*)").replace(normalized) { match ->
                "${match.groupValues[1]}${match.groupValues[3]}${match.groupValues[2]}"
            }
        }



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
        Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(13.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Row(Modifier.padding(horizontal = 12.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(20.dp).background(Color(0xFFF6E4DE), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center) { Text("!", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Text(line.stage.ifBlank { "任务出现异常" }, Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun ReleaseHistoryDialog(vm: MobileViewModel, install: (java.io.File) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val exportBusy = vm.historyExportBusy
    val exportMessage = vm.historyExportMessage
    val saveApk = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        val downloaded = vm.historicalDownload
        if (uri != null && downloaded != null) vm.exportHistory(uri, downloaded)
    }
    AlertDialog(
        onDismissRequest = vm::dismissHistory,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("历史版本与恢复下载", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("当前版本 " + BuildConfig.VERSION_NAME)
                Text("通过独立 SSH 下载，不依赖 Codex 连接。", fontSize = 12.sp)
                if (vm.historyBusy || exportBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (vm.historyMessage.isNotBlank()) Text(vm.historyMessage, fontSize = 12.sp)
                ErrorText(vm.historyError)
                if (exportMessage.isNotBlank()) Text(exportMessage, fontSize = 12.sp)
                vm.historicalDownload?.let { downloaded ->
                    Text("已下载 " + downloaded.info.versionName, fontWeight = FontWeight.SemiBold)
                    if (downloaded.info.versionCode < BuildConfig.VERSION_CODE) {
                        Text("这是旧版原包。Android 通常禁止直接覆盖降级，系统可能拒绝安装。卸载会删除本机私钥配置和缓存；请先妥善备份，服务器对话仍会保留。", fontSize = 12.sp)
                    }
                    Row {
                        TextButton(enabled = !vm.historyBusy && !exportBusy, onClick = {
                            saveApk.launch("Andri-Android-" + downloaded.info.versionName + ".apk")
                        }) { ActionLabel("save", "保存 APK") }
                        TextButton(enabled = !vm.historyBusy && !exportBusy, onClick = { vm.installHistory(install) }) {
                            ActionLabel("download", "打开系统安装器")
                        }
                    }
                }
                vm.historyReleases.forEach { info ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(info.versionName + if (info.versionCode == BuildConfig.VERSION_CODE) " · 当前版本" else "",
                                fontWeight = FontWeight.Medium)
                            Text("${info.sizeBytes / 1024 / 1024} MB", fontSize = 12.sp)
                        }
                        TextButton(enabled = !vm.historyBusy && !exportBusy, onClick = {
                            vm.downloadHistory(info)
                        }) { ActionLabel("download", "下载") }
                    }
                    if (info.notes.isNotBlank()) Text(info.notes, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(vm::refreshHistory, enabled = !vm.historyBusy && !exportBusy) { ActionLabel("refresh", "刷新列表") } },
        dismissButton = { TextButton(vm::dismissHistory) { ActionLabel(null, if (vm.historyBusy) "取消" else "关闭") } }
    )
}

@Composable
private fun UpdateDialog(vm: MobileViewModel, install: (java.io.File) -> Unit) {
    AlertDialog(
        onDismissRequest = vm::dismissUpdate,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("应用更新", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前版本 " + BuildConfig.VERSION_NAME)
                TextButton(vm::openHistory) { ActionLabel("history", "历史版本与恢复下载") }
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
            }) { ActionLabel(if (vm.updateFile == null) "download" else "upload", if (vm.updateFile == null) "下载更新" else "安装更新") }
            else if (vm.updateError.isNotBlank()) TextButton(enabled = !vm.updateBusy && !vm.updateChecking,
                onClick = { vm.checkForUpdate(manual = true) }) { ActionLabel("refresh", "重新检查") }
            else TextButton(vm::dismissUpdate) { ActionLabel(null, "知道了") }
        },
        dismissButton = {
            if (vm.updateInfo != null) TextButton(vm::dismissUpdate) { ActionLabel(null, "稍后") }
        }
    )
}

@Composable
private fun RequestDialog(vm: MobileViewModel, request: JSONObject) {
    val params = request.optJSONObject("params") ?: JSONObject()
    val method = request.text("method")
    val userInput = method == "item/tool/requestUserInput"
    val requestId = request.get("id").toString()
    val answers = vm.requestAnswers[requestId].orEmpty()
    val responding = requestId in vm.respondingRequests
    val questions = params.optJSONArray("questions")
    val count = questions?.length() ?: 0
    val decisions = params.optJSONArray("availableDecisions")
    val canApprove = decisions == null || (0 until decisions.length()).any { decisions.opt(it) == "accept" }
    AlertDialog(
        onDismissRequest = vm::dismissPendingRequest,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (userInput) "需要你的回答" else "需要你的批准", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val threadId = params.text("threadId").ifBlank { params.text("conversationId") }
                Text(vm.threads.firstOrNull { it.id == threadId }?.title ?: threadId.take(12),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ErrorText(vm.requestErrors[requestId].orEmpty())
                if (userInput) {
                    for (i in 0 until count) {
                        val question = questions!!.getJSONObject(i)
                        val id = question.text("id")
                        val options = question.optJSONArray("options") ?: org.json.JSONArray()
                        QuestionFields(question.text("question"), (0 until options.length()).map { j ->
                            val option = options.getJSONObject(j)
                            option.text("label") to option.text("description")
                        }, answers[id].orEmpty(), !responding, question.optBoolean("isSecret")) { vm.setRequestAnswer(requestId, id, it) }
                    }
                } else {
                    params.text("command").ifBlank { params.text("cwd").ifBlank { null } }?.let { command ->
                        Surface(color = Color(0xFF2A2620), shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()) {
                            SelectionContainer {
                                Text(command, Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
                                    fontSize = 12.sp, lineHeight = 19.sp, color = Color(0xFFC9C2B2),
                                    fontFamily = FontFamily.Monospace, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    val reason = params.text("reason")
                    if (reason.isNotBlank()) Text(reason, fontSize = 12.sp, lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val detailKeys = listOf("grantRoot", "permissions", "additionalPermissions", "networkApprovalContext")
                    detailKeys.forEach { name ->
                        if (params.has(name) && !params.isNull(name)) SelectionContainer {
                            Text(name + "：\r\n" + params.opt(name)?.toString().orEmpty(), fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                        }
                    }
                    val item = vm.toolItems[params.text("itemId")]
                    val changes = item?.optJSONArray("changes")
                    if (changes != null) SelectionContainer { Text(changes.toString(2), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp) }
                    if (!canApprove) Text("服务器未提供单次批准选项，可拒绝本次请求。", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !responding && if (userInput) (0 until count).all { !answers[questions!!.getJSONObject(it).text("id")].isNullOrBlank() } else canApprove,
                onClick = { vm.respond(request, approved = true, answers = answers.toMap()) },
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary)
            ) { ActionLabel(if (userInput) "send" else "check", if (userInput) "提交回答" else "批准执行") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(vm::dismissPendingRequest) { Text("稍后处理") }
            if (!userInput) OutlinedButton(enabled = !responding, onClick = { vm.respond(request, approved = false) },
                shape = RoundedCornerShape(11.dp)) { Text("拒绝") }
            }
        }
    )
}
