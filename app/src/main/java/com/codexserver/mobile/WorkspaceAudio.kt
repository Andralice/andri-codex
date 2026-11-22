package com.codexserver.mobile

internal val workspaceAudioMimeTypes = mapOf(
    "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "m4b" to "audio/mp4", "aac" to "audio/aac",
    "wav" to "audio/wav", "flac" to "audio/flac", "ogg" to "audio/ogg", "oga" to "audio/ogg",
    "opus" to "audio/ogg", "amr" to "audio/amr"
)

internal fun formatAudioTime(milliseconds: Int): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
}
