package com.codexserver.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal data class CachedConversationState(
    val threads: List<CodexThread>,
    val messages: Map<String, List<ChatLine>>,
    val lastThreadId: String?,
    val model: String
)

/** Small private app-data snapshot so the last workspace can render before SSH reconnects. */
internal class LocalConversationCache(context: Context) {
    private val file = File(context.filesDir, "conversation-cache.json")

    fun load(): CachedConversationState? = runCatching {
        if (!file.isFile || file.length() > MAX_CACHE_BYTES) return null
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val threadRows = root.optJSONArray("threads") ?: JSONArray()
        val threads = buildList {
            for (index in 0 until threadRows.length()) {
                threadRows.optJSONObject(index)?.let(::decodeThread)?.let(::add)
            }
        }
        val messageObject = root.optJSONObject("messages") ?: JSONObject()
        val messages = buildMap {
            val keys = messageObject.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val rows = messageObject.optJSONArray(id) ?: continue
                put(id, buildList {
                    for (index in 0 until rows.length()) {
                        rows.optJSONObject(index)?.let(::decodeLine)?.let(::add)
                    }
                })
            }
        }
        CachedConversationState(threads, messages,
            root.optString("lastThreadId").takeIf { it.isNotBlank() },
            root.optString("model", "gpt-6-luna").ifBlank { "gpt-6-luna" })
    }.getOrNull()

    fun save(state: CachedConversationState) {
        runCatching {
            val root = JSONObject().put("version", 1).put("lastThreadId", state.lastThreadId)
                .put("model", state.model)
            val threadRows = JSONArray()
            state.threads.take(MAX_THREADS).forEach { threadRows.put(encodeThread(it)) }
            root.put("threads", threadRows)

            val messageObject = JSONObject()
            val ids = buildList {
                state.lastThreadId?.let(::add)
                state.threads.take(MAX_CACHED_MESSAGE_THREADS).forEach { add(it.id) }
            }.distinct()
            ids.forEach { id ->
                val lines = state.messages[id].orEmpty().takeLast(MAX_LINES_PER_THREAD)
                if (lines.isNotEmpty()) messageObject.put(id, JSONArray().apply {
                    lines.forEach { put(encodeLine(it)) }
                })
            }
            root.put("messages", messageObject)

            var encoded = root.toString()
            // Keep app startup reads small; messages remain authoritative on the server.
            while (encoded.toByteArray(Charsets.UTF_8).size > MAX_CACHE_BYTES && messageObject.length() > 0) {
                val largestId = messageObject.keys().asSequence().maxByOrNull { messageObject.optJSONArray(it)?.length() ?: 0 }
                    ?: break
                val rows = messageObject.optJSONArray(largestId) ?: break
                if (rows.length() <= 1) messageObject.remove(largestId)
                else {
                    val trimmed = JSONArray()
                    for (index in (rows.length() * 3) / 4 until rows.length()) trimmed.put(rows.get(index))
                    messageObject.put(largestId, trimmed)
                }
                encoded = root.toString()
            }
            val temp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temp).use { output ->
                output.write(encoded.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temp.renameTo(file)) {
                file.delete()
                check(temp.renameTo(file)) { "无法保存本地对话缓存" }
            }
        }
    }

    private fun encodeThread(thread: CodexThread) = JSONObject()
        .put("id", thread.id).put("title", thread.title).put("model", thread.model)
        .put("running", thread.running).put("turnId", thread.turnId).put("cwd", thread.cwd)

    private fun decodeThread(value: JSONObject) = CodexThread(
        value.optString("id"), value.optString("title"), value.optString("model"),
        value.optBoolean("running"), value.optString("turnId"), value.optString("cwd")
    ).takeIf { it.id.isNotBlank() }

    private fun encodeLine(line: ChatLine): JSONObject = JSONObject()
        .put("role", line.role).put("text", line.text).put("id", line.id).put("turnId", line.turnId)
        .put("stage", line.stage).put("afterId", line.afterId).put("streaming", line.streaming)
        .put("status", line.status)
        .put("questions", JSONArray().apply {
            line.questions.forEach { put(JSONObject().put("title", it.title).put("options", JSONArray(it.options))) }
        })
        // Inline image data can be several megabytes; the server remains the source for images.
        .put("images", JSONArray(line.images.filterNot { it.startsWith("data:image/", ignoreCase = true) }))
        .put("processItems", JSONArray().apply { line.processItems.forEach { put(encodeLine(it)) } })

    private fun decodeLine(value: JSONObject): ChatLine {
        val questions = value.optJSONArray("questions") ?: JSONArray()
        val images = value.optJSONArray("images") ?: JSONArray()
        val processItems = value.optJSONArray("processItems") ?: JSONArray()
        return ChatLine(
            role = value.optString("role"), text = value.optString("text"), id = value.optString("id"),
            turnId = value.optString("turnId"), stage = value.optString("stage"),
            afterId = value.optString("afterId"), streaming = value.optBoolean("streaming"),
            questions = buildList {
                for (index in 0 until questions.length()) {
                    val question = questions.optJSONObject(index) ?: continue
                    val options = question.optJSONArray("options") ?: JSONArray()
                    add(ChatQuestion(question.optString("title"), buildList {
                        for (option in 0 until options.length()) add(options.optString(option))
                    }))
                }
            },
            status = value.optString("status"),
            images = buildList { for (index in 0 until images.length()) add(images.optString(index)) },
            processItems = buildList {
                for (index in 0 until processItems.length()) {
                    processItems.optJSONObject(index)?.let { add(decodeLine(it)) }
                }
            }
        )
    }

    private companion object {
        const val MAX_THREADS = 100
        const val MAX_CACHED_MESSAGE_THREADS = 12
        const val MAX_LINES_PER_THREAD = 250
        const val MAX_CACHE_BYTES = 1_500_000L
    }
}
