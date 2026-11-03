package com.codexserver.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ConnectionRecoveryCheck {
    @Test fun automaticRetriesAreSerializedAndBackedOff() {
        val state = ConnectionRecovery()
        val first = state.begin(0, automatic = false)
        assertNotNull(first)
        assertNull(state.begin(0, automatic = true))
        state.failed(first!!, 0)
        assertTrue(state.delayMillis(0) > 0)
        assertNull(state.begin(1, automatic = true))
        assertNotNull(state.begin(state.delayMillis(0) + 1, automatic = true))
    }

    @Test fun realIdleConnectionStaysUsable() = runBlocking {
        val keyPath = System.getenv("CODEX_CHECK_IDLE_KEY_FILE")
        assumeTrue("Set CODEX_CHECK_IDLE_KEY_FILE for the read-only idle check", !keyPath.isNullOrBlank())
        val failures = Channel<String>(Channel.UNLIMITED)
        val client = SshCodexClient("82.156.142.57", 22, "ubuntu", File(keyPath!!).readText(), "") {
            if (it.has("localError")) failures.trySend(it.optString("localError"))
        }
        try {
            client.connect()
            println("Idle SSH/WebSocket check connected; no model calls or new conversations")
            repeat(6) { index ->
                val failure = withTimeoutOrNull(15_000) { failures.receive() }
                assertNull("Connection failed after ${(index + 1) * 15}s: $failure", failure)
                assertTrue("Transport closed without an error event", client.isAlive())
                println("Idle connection alive after ${(index + 1) * 15}s")
            }
            client.listThreads()
            println("Read-only RPC succeeded after 90 seconds idle")
        } finally { client.close(); failures.close() }
    }
}
