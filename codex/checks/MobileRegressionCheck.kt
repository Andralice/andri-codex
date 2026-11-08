package com.codexserver.mobile

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class MobileRegressionCheck {
    @Test fun turnInputUsesAppServerUserInputTypes() {
        val text = userInput("你好")
        assertEquals(1, text.length())
        assertEquals("text", text.getJSONObject(0).getString("type"))
        assertEquals("你好", text.getJSONObject(0).getString("text"))
        val image = userInput("查看图片", RED_IMAGE)
        assertEquals(2, image.length())
        assertEquals("image", image.getJSONObject(1).getString("type"))
        assertEquals(RED_IMAGE, image.getJSONObject(1).getString("url"))
        assertFalse(image.getJSONObject(1).has("image_url"))
    }

    @Test fun realTextImageAndConversationResume() = runBlocking {
        val keyPath = System.getenv("CODEX_CHECK_KEY_FILE")
        assumeTrue("Set CODEX_CHECK_KEY_FILE for the SSH regression check", !keyPath.isNullOrBlank())
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        fun connection() = SshCodexClient("82.156.142.57", 22, "ubuntu", File(keyPath!!).readText(), "") {
            events.trySend(it)
        }
        var client = connection()
        var threadId = ""
        suspend fun awaitTurn(turnId: String) {
            withTimeout(180_000) {
                while (true) {
                    val event = events.receive()
                    check(!event.has("localError")) { event.optString("localError") }
                    val params = event.optJSONObject("params") ?: continue
                    if (params.optString("threadId") != threadId) continue
                    if (event.has("id")) error("Unexpected server request in no-tool protocol check: " + event.optString("method"))
                    if (event.optString("method") != "turn/completed") continue
                    val turn = params.getJSONObject("turn")
                    if (turn.optString("id") != turnId) continue
                    assertEquals("Unexpected turn error: " + turn.optJSONObject("error"), "completed", turn.optString("status"))
                    return@withTimeout
                }
            }
        }
        try {
            client.connect()
            threadId = client.startThread("/home/ubuntu/codex/mobile-client-check", "gpt-6-luna")
                .getJSONObject("thread").getString("id")
            println("MOBILE_PROTOCOL_018 thread: " + threadId)
            val textTurn = client.sendTurn(threadId, "gpt-6-luna",
                "MOBILE_PROTOCOL_018 regression check. Reply with exactly MOBILE_TEXT_018_OK. Do not use tools or access files.")
            awaitTurn(textTurn)
            var messages = parseMessages(client.readThread(threadId).getJSONObject("thread"))
            assertTrue("No real text model reply", messages.any { it.role == "assistant" && "MOBILE_TEXT_018_OK" in it.text })
            println("Real text turn passed; model reply persisted")
            val imageData = "data:image/png;base64," + Base64.getEncoder().encodeToString(File("codex/checks/protocol-image-018.png").readBytes())
            val imageTurn = client.sendTurn(threadId, "gpt-6-luna",
                "MOBILE_PROTOCOL_018 image check. Describe the shapes, their colors, and transcribe the text printed in the attached image. Do not use tools or access files.", listOf(imageData))
            awaitTurn(imageTurn)
            messages = parseMessages(client.readThread(threadId).getJSONObject("thread"))
            val imageReply = messages.last { it.role == "assistant" }.text
            println("Image reply: " + imageReply)
            assertTrue("Model did not read the image's printed text", imageReply.contains("LANTERN", ignoreCase = true) && imageReply.contains("4826"))
            println("Real image turn passed; model read the PNG's printed text")
            client.close()
            client = connection()
            client.connect()
            val restored = client.resumeThread(threadId)
            assertEquals(threadId, restored.getJSONObject("thread").getString("id"))
            messages = parseMessages(restored.getJSONObject("thread"))
            assertTrue(messages.any { it.role == "assistant" && "MOBILE_TEXT_018_OK" in it.text })
            assertEquals(imageReply, messages.last { it.role == "assistant" }.text)
            println("Fresh SSH connection restored the same text/image conversation")
        } finally {
            client.close()
            events.close()
            println("Regression conversation ID: " + threadId)
        }
    }

    companion object {
        private const val RED_IMAGE = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAYklEQVR4nO3PMQ0AIADAMMAA/l0iAREcDcmqYJtn7/GzpQNeNaA1oDWgNaA1oDWgNaA1oDWgNaA1oDWgNaA1oDWgNaA1oDWgNaA1oDWgNaA1oDWgNaA1oDWgNaA1oDWgNaA1oDWgNaBdJjYBjuRqZZsAAAAASUVORK5CYII="
    }
}
