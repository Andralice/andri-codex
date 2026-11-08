package com.codexserver.mobile

private val previewFence = Regex("(?m)^\\s*(```|~~~)[^\\n]*$")
private val previewLink = Regex("!?\\[([^\\]]*)]\\([^\\s)]*(?:\\s+\"[^\"]*\")?\\)")
private val previewLinePrefix = Regex("(?m)^\\s*(?:#{1,6}\\s+|>\\s*|[-+*]\\s+|\\d+[.)]\\s+)")
private val previewEmphasis = Regex("(\\*\\*|__|~~)(.+?)\\1")
private val previewCode = Regex("`+([^`]+)`+")
private val previewWhitespace = Regex("\\s+")

/** A list excerpt, never a replacement for the original Markdown in the conversation. */
internal fun threadPreview(lines: List<ChatLine>): String {
    val text = lines.asReversed().firstOrNull {
        it.role in setOf("user", "assistant") && it.text.isNotBlank()
    }?.text.orEmpty()
    return text.replace(previewFence, "")
        .replace(previewLink) { it.groupValues[1] }
        .replace(previewLinePrefix, "")
        .replace(previewEmphasis) { it.groupValues[2] }
        .replace(previewCode) { it.groupValues[1] }
        .replace(previewWhitespace, " ").trim()
}
