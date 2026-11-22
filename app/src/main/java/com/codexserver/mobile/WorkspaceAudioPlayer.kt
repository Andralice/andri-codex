package com.codexserver.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.io.File
import kotlinx.coroutines.delay

/** One preview owns one player; it never starts on preparation or on focus gain. */
private class AudioPreviewPlayback(context: Context) {
    var ready by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var duration by mutableIntStateOf(0)
        private set
    var position by mutableIntStateOf(0)
        private set
    var error by mutableStateOf("")
        private set
    var notice by mutableStateOf("")
        private set

    private var released = false
    private var completed = false
    private val player = MediaPlayer()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change < 0) pause()
        }, Handler(Looper.getMainLooper())).build()

    fun prepare(file: File) {
        try {
            player.setAudioAttributes(attributes)
            player.setOnPreparedListener {
                if (!released && error.isBlank()) {
                    duration = it.duration.coerceAtLeast(0)
                    ready = true
                }
            }
            player.setOnCompletionListener {
                if (!released) {
                    playing = false
                    completed = true
                    position = duration
                    audioManager.abandonAudioFocusRequest(focus)
                }
            }
            player.setOnErrorListener { _, _, extra ->
                fail(if (extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED) "手机不支持这段音频的编码，可保存后用其他播放器打开。"
                    else "音频无法播放，请重新加载或用其他播放器打开。")
                true
            }
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        } catch (_: Exception) { fail("音频加载失败，请重新加载或用其他播放器打开。") }
    }

    fun toggle() {
        if (!ready || released || error.isNotBlank()) return
        if (playing) { pause(); return }
        try {
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                notice = "其他应用正在使用音频，请稍后再试"
                return
            }
            notice = ""
            if (completed) {
                player.seekTo(0L, MediaPlayer.SEEK_CLOSEST)
                position = 0
                completed = false
            }
            player.start()
            playing = true
        } catch (_: Exception) { fail("音频播放失败，请重新加载。") }
    }

    fun pause() {
        if (released) return
        try {
            if (playing) {
                player.pause()
                updatePosition()
            }
        } catch (_: Exception) { fail("音频播放中断，请重新加载。") }
        playing = false
        audioManager.abandonAudioFocusRequest(focus)
    }

    fun seek(milliseconds: Int) {
        if (!ready || released || error.isNotBlank()) return
        try {
            position = milliseconds.coerceIn(0, duration)
            player.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)
            completed = false
        } catch (_: Exception) { fail("无法调整播放进度，请重新加载。") }
    }

    fun updatePosition() {
        if (ready && !released) {
            runCatching { player.currentPosition }.onSuccess { position = it.coerceIn(0, duration) }
        }
    }

    private fun fail(message: String) {
        if (released) return
        error = message
        ready = false
        playing = false
        runCatching { player.reset() }
        audioManager.abandonAudioFocusRequest(focus)
    }

    fun release() {
        if (released) return
        released = true
        playing = false
        ready = false
        player.setOnPreparedListener(null)
        player.setOnCompletionListener(null)
        player.setOnErrorListener(null)
        player.release()
        audioManager.abandonAudioFocusRequest(focus)
    }
}

@Composable
internal fun WorkspaceAudioPlayer(file: File, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    val playback = remember(context, file) { AudioPreviewPlayback(context) }
    var seeking by remember(playback) { mutableStateOf<Float?>(null) }
    DisposableEffect(playback, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) playback.pause()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) playback.pause()
            }
        }
        lifecycle?.addObserver(observer)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        playback.prepare(file)
        onDispose {
            lifecycle?.removeObserver(observer)
            context.unregisterReceiver(receiver)
            playback.release()
        }
    }
    LaunchedEffect(playback, playback.playing) {
        while (playback.playing) {
            playback.updatePosition()
            delay(250)
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("音乐试听", style = MaterialTheme.typography.titleMedium)
        when {
            playback.error.isNotBlank() -> {
                Text(playback.error, color = MaterialTheme.colorScheme.error)
                TextButton(onRetry) { Text("重新加载音频") }
            }
            !playback.ready -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在准备音频…", fontSize = 13.sp)
            }
            else -> {
                Slider(value = seeking ?: playback.position.toFloat(), onValueChange = { seeking = it },
                    valueRange = 0f..playback.duration.coerceAtLeast(1).toFloat(), enabled = playback.duration > 0,
                    onValueChangeFinished = {
                        seeking?.let { playback.seek(it.toInt()) }
                        seeking = null
                    })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatAudioTime(seeking?.toInt() ?: playback.position), fontSize = 13.sp)
                    Text(formatAudioTime(playback.duration), fontSize = 13.sp)
                }
                Button(playback::toggle) { ActionLabel(if (playback.playing) "pause" else "play", if (playback.playing) "暂停" else "播放") }
                if (playback.notice.isNotBlank()) Text(playback.notice, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
}
