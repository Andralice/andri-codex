package com.codexserver.mobile

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val MAX_GLOBAL_INSTRUCTION_BYTES = 32 * 1024

data class GlobalInstructionsDocument(val home: String, val path: String, val content: String, val exists: Boolean)

internal fun globalInstructionsHome(config: JSONObject): String {
    val layers = config.optJSONArray("layers") ?: error("服务器未返回配置位置，无法定位全局指令")
    val userFile = (0 until layers.length()).mapNotNull { layers.optJSONObject(it)?.optJSONObject("name") }
        .firstOrNull { it.text("type") == "user" }?.text("file").orEmpty()
    require(userFile.startsWith("/") && userFile.endsWith("/config.toml") && '\u0000' !in userFile &&
        userFile.split('/').none { it == "." || it == ".." }) { "服务器全局配置位置无效" }
    return userFile.removeSuffix("/config.toml").ifEmpty { "/" }
}

internal fun decodeGlobalInstructions(bytes: ByteArray): String {
    require(bytes.size <= MAX_GLOBAL_INSTRUCTION_BYTES) { "全局指令不能超过 32 KiB" }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}

internal fun encodeGlobalInstructions(text: String): ByteArray {
    require('\u0000' !in text) { "全局指令包含无效字符" }
    return text.toByteArray(Charsets.UTF_8).also {
        require(it.size <= MAX_GLOBAL_INSTRUCTION_BYTES) { "全局指令不能超过 32 KiB" }
    }
}

internal fun selectGlobalInstructions(home: String, override: String?, regular: String?): GlobalInstructionsDocument {
    val useOverride = !override.isNullOrBlank()
    val content = if (useOverride) override else regular
    return GlobalInstructionsDocument(home, home.trimEnd('/') + if (useOverride) "/AGENTS.override.md" else "/AGENTS.md",
        content.orEmpty(), content != null)
}

internal fun checkGlobalInstructionsUnchanged(original: GlobalInstructionsDocument, current: GlobalInstructionsDocument) {
    check(original == current) { "服务器指令已发生变化，请重新读取后再保存，以免覆盖其他修改" }
}
