package com.codexserver.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ClientCheck {
    @Test fun historyAndStreamingKeepMessagesSeparate() {
        val thread = JSONObject("""{"id":"t","name":null,"preview":"历史标题","status":{"type":"active"},"turns":[{"id":"turn","status":"inProgress","items":[{"id":"u","type":"userMessage","content":[{"type":"text","text":"你好"}]},{"id":"a","type":"agentMessage","text":"第一条"},{"id":"b","type":"agentMessage","text":"第二条"}]}]}""")
        assertEquals("历史标题", parseThread(thread).title)
        assertTrue(parseThread(thread).running)
        assertEquals("turn", parseThread(thread).turnId)
        val parsed = parseMessages(thread)
        assertEquals(listOf("你好", "第一条", "第二条"), parsed.map { it.text })
        val streamed = upsertChat(parsed, ChatLine("assistant", "第二条回复", "b"))
        assertEquals(3, streamed.size)
        assertEquals("第一条", streamed[1].text)
        assertEquals("第二条回复", mergeHistory(parsed, streamed)[2].text)
        assertEquals(4, upsertChat(streamed, ChatLine("assistant", "下一条", "c")).size)
        val older = ChatLine("user", "更早的消息", "old")
        assertEquals(listOf(older) + streamed, mergeHistory(parsed, listOf(older) + streamed))
        assertEquals(listOf(older) + parsed, mergeHistory(listOf(older), parsed))
        val page = JSONObject("""{"data":[{"id":"new"},{"id":"old"}],"nextCursor":"cursor"}""")
        assertEquals("old", chronologicalTurns(page).getJSONObject(0).getString("id"))
        assertEquals("cursor", hydratePage(JSONObject().put("thread", JSONObject()), page).getString("historyCursor"))
    }

    @Test fun latestPageMergeKeepsLongWindowOrderAndSameTurnDeltas() {
        val history = listOf(
            ChatLine("user", "第一轮问题", "u1", "t1"),
            ChatLine("assistant", "第一轮回答", "a1", "t1"),
            ChatLine("user", "第二轮问题", "u2", "t2"),
            ChatLine("assistant", "第二轮回答", "a2", "t2")
        )
        val older = ChatLine("user", "更早的历史", "old", "t0")
        val streamedAnswer = ChatLine("assistant", "第一轮回答仍在增长", "a1", "t1")
        val sameTurnDelta = ChatLine("tool", "新增工具事件", "tool-extra", "t1")
        val liveTail = ChatLine("assistant", "正在生成", "live-tail", "t2", streaming = true)
        val current = listOf(older, history[0], streamedAnswer, sameTurnDelta, history[2], history[3], liveTail)

        val merged = mergeHistory(history, current, latestPage = true, activeTurnId = "t2")

        assertEquals(listOf("old", "u1", "a1", "tool-extra", "u2", "a2", "live-tail"), merged.map { it.id })
        assertEquals("第一轮回答仍在增长", merged[2].text)
    }

    @Test fun androidEd25519ImplementationIsAvailable() {
        val signature = com.jcraft.jsch.bc.SignatureEd25519()
        signature.init()
        assertNotNull(Class.forName("org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters"))
    }

    @Test fun realServerParallelConversationsAndReconnect() = runBlocking {
        val keyPath = System.getenv("CODEX_CHECK_KEY_FILE")
        assumeTrue("Set CODEX_CHECK_KEY_FILE to run the real SSH check", !keyPath.isNullOrBlank())
        val key = File(keyPath!!).readText()
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        fun connection() = SshCodexClient("82.156.142.57", 22, "ubuntu", key, "") { events.trySend(it) }
        var client = connection()
        val created = mutableListOf<String>()
        try {
            withTimeout(60_000) { client.connect() }
            val existing = client.listThreads()
            println("SSH host key and app-server initialization passed; listed " + existing.size + " threads")
            for (model in listOf("gpt-6-luna", "gpt-6.1-sol")) {
                val result = client.startThread("/home/ubuntu/codex/mobile-client-check", model)
                val id = result.getJSONObject("thread").getString("id")
                assertEquals(model, result.getString("model"))
                created += id
            }
            coroutineScope {
                created.mapIndexed { index, id ->
                    launch { client.sendTurn(id, if (index == 0) "gpt-6-luna" else "gpt-6.1-sol",
                        "Reply with exactly MOBILE_CHECK_" + index + ". Do not use tools or access files.") }
                }.joinAll()
            }
            val done = mutableSetOf<String>()
            val deltas = mutableSetOf<String>()
            withTimeout(180_000) {
                while (done.size < 2) {
                    val event = events.receive()
                    check(!event.has("localError")) { event.text("localError") }
                    val params = event.optJSONObject("params") ?: continue
                    val id = params.text("threadId")
                    if (id !in created) continue
                    if (event.text("method") == "item/agentMessage/delta") deltas += id
                    if (event.text("method") == "turn/completed") {
                        val turn = params.getJSONObject("turn")
                        check(turn.text("status") != "failed") { turn.optJSONObject("error")?.text("message").orEmpty() }
                        done += id
                    }
                }
            }
            assertEquals(created.toSet(), deltas)
            client.close()
            client = connection()
            client.connect()
            created.forEachIndexed { index, id ->
                val resumed = client.resumeThread(id)
                val history = parseMessages(resumed.getJSONObject("thread"))
                assertTrue(history.any { it.role == "user" })
                assertTrue("Server did not return expected model reply", history.any {
                    it.role == "assistant" && it.text.contains("MOBILE_CHECK_" + index)
                })
                assertTrue(client.listThreads().any { it.id == id })
                println("Model " + index + " streaming, durable history, list visibility and reconnect passed")
            }
        } finally {
            client.close()
            println("Check conversation IDs: " + created.joinToString(", "))
            events.close()
        }
    }

    @Test fun realServerApprovalBackgroundAndInterrupt() = runBlocking {
        val keyPath = System.getenv("CODEX_CHECK_KEY_FILE")
        assumeTrue(!keyPath.isNullOrBlank())
        val key = File(keyPath!!).readText()
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        fun connection() = SshCodexClient("82.156.142.57", 22, "ubuntu", key, "") { events.trySend(it) }
        var client = connection()
        val result = try {
            client.connect()
            client.startThread("/home/ubuntu/codex/mobile-client-check", "gpt-6-luna")
        } catch (e: Exception) { client.close(); throw e }
        val id = result.getJSONObject("thread").getString("id")
        try {
            client.sendTurn(id, "gpt-6-luna",
                "This is an authorized mobile client integration check. Use exec_command to run exactly " +
                "'printf MOBILE_BACKGROUND_STARTED; sleep 12; printf MOBILE_BACKGROUND_FINISHED' " +
                "with sandbox_permissions=require_escalated and a justification asking permission for this harmless check. " +
                "Do not use other commands or edit files. After it finishes reply MOBILE_BACKGROUND_OK.")
            var approved = false
            var commandStarted = false
            withTimeout(120_000) {
                while (true) {
                    val event = events.receive()
                    check(!event.has("localError")) { event.text("localError") }
                    val params = event.optJSONObject("params") ?: continue
                    if (params.text("threadId") != id) continue
                    println("Tool check event: " + event.text("method"))
                    when (event.text("method")) {
                        "item/commandExecution/requestApproval" -> {
                            check(params.text("command").contains("MOBILE_BACKGROUND_STARTED")) { "Unexpected command in approval" }
                            client.respondToServerRequest(event.get("id"), JSONObject().put("decision", "accept"))
                            approved = true
                        }
                        "item/started" -> if (params.optJSONObject("item")?.text("type") == "commandExecution") commandStarted = true
                        "turn/completed" -> error("Turn ended before the expected command output")
                    }
                    if (approved && commandStarted) break
                }
            }
            assertTrue("No approval request was received", approved)
            client.close()
            client = connection()
            client.connect()
            val resumed = client.resumeThread(id)
            assertTrue("Running task did not survive SSH disconnect", parseThread(resumed.getJSONObject("thread")).running)
            println("Single-command approval and background survival across SSH disconnect passed")
            withTimeout(120_000) {
                while (true) {
                    val snapshot = client.readThread(id)
                    val lines = parseMessages(snapshot.getJSONObject("thread"))
                    if (lines.any { it.role == "assistant" && it.text.contains("MOBILE_BACKGROUND_OK") }) break
                    delay(1500)
                }
            }
            val turnId = client.sendTurn(id, "gpt-6-luna",
                "For the mobile stop-button check, use exec_command to run exactly 'sleep 40; printf MOBILE_STOP_FAILED' " +
                "with sandbox_permissions=require_escalated. Do not edit files or use other commands.")
            withTimeout(120_000) {
                while (true) {
                    val event = events.receive()
                    val params = event.optJSONObject("params") ?: continue
                    if (params.text("threadId") != id) continue
                    if (event.text("method") == "item/commandExecution/requestApproval") {
                        check(params.text("command").contains("MOBILE_STOP_FAILED"))
                        client.respondToServerRequest(event.get("id"), JSONObject().put("decision", "accept"))
                    }
                    if (event.text("method") == "item/started" &&
                        params.optJSONObject("item")?.text("command")?.contains("MOBILE_STOP_FAILED") == true) break
                }
            }
            client.interrupt(id, turnId)
            withTimeout(20_000) {
                while (true) {
                    val event = events.receive()
                    val params = event.optJSONObject("params") ?: continue
                    if (params.text("threadId") != id || event.text("method") != "turn/completed") continue
                    if (params.getJSONObject("turn").text("id") != turnId) continue
                    assertEquals("interrupted", params.getJSONObject("turn").text("status"))
                    break
                }
            }
            println("turn/interrupt stopped the running turn")
        } finally {
            client.close()
            events.close()
            println("Tool check thread: " + id)
        }
    }
}
