package com.codexserver.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundConnectionCheck {
    @Test fun failedReconnectsKeepRetryingUntilConnected() = runBlocking {
        val recovery = ConnectionRecovery()
        var now = 0L
        var connected = false
        var attempts = 0
        val waits = mutableListOf<Long>()
        withTimeout(2_000) {
            retryConnection(
                shouldRetry = { !connected },
                delayMillis = { 1L },
                onWaiting = {
                    // Advance a fake monotonic clock, without waiting through real backoff.
                    val wait = recovery.delayMillis(now)
                    waits += wait
                    now += wait
                },
                attempt = {
                    val token = checkNotNull(recovery.begin(now, automatic = true))
                    attempts++
                    if (attempts < 4) recovery.failed(token, now)
                    else { recovery.connected(token, now); connected = true }
                }
            )
        }
        assertEquals(4, attempts)
        assertEquals(listOf(0L, 2_000L, 4_000L, 8_000L), waits)
    }

    @Test fun manualDisconnectDuringBackoffPreventsAnotherAttempt() = runBlocking {
        var enabled = true
        var attempts = 0
        retryConnection(
            shouldRetry = { enabled },
            delayMillis = { 1L },
            onWaiting = { enabled = false },
            attempt = { attempts++ }
        )
        assertEquals(0, attempts)
    }

    @Test fun cancellingRetryLoopCancelsTheInFlightAttempt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var stopped = false
        val job = launch {
            retryConnection(
                shouldRetry = { true },
                delayMillis = { 1L },
                onWaiting = {},
                attempt = {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped = true }
                }
            )
        }
        withTimeout(2_000) { started.await() }
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(stopped)
    }

    @Test fun replacedNetworkRejectsOldSocketEventsAndResetsBackoff() {
        val recovery = ConnectionRecovery()
        val stale = checkNotNull(recovery.begin(0L, automatic = false))
        recovery.connected(stale, 0L)
        recovery.reset()
        val replacement = checkNotNull(recovery.begin(1L, automatic = true))
        recovery.failed(stale, 2L)
        assertTrue(recovery.isCurrent(replacement))
        assertEquals(0L, recovery.delayMillis(2L))
    }
}
