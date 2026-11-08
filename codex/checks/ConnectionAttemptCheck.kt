package com.codexserver.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConnectionAttemptCheck {
    @Test fun stalledBlockingHandshakeTimesOutWithoutWaitingForLibrary() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val aborted = CompletableDeferred<Unit>()
        val attempt = async {
            runCatching {
                awaitConnectionAttempt(timeoutMillis = 200, abort = { aborted.complete(Unit) }) {
                    entered.complete(Unit)
                    try { release.await(5, TimeUnit.SECONDS) } finally { exited.countDown() }
                }
            }
        }
        try {
            entered.await()
            val failure = withTimeout(2_000) { attempt.await().exceptionOrNull() }
            assertTrue(failure is TimeoutCancellationException)
            withTimeout(2_000) { aborted.await() }
            assertEquals("The UI deadline must not wait for blocked SSH code", 1L, exited.count)
        } finally { release.countDown() }
    }

    @Test fun userCanCancelBlockedHandshakeAndStartAnotherAttempt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val aborted = CompletableDeferred<Unit>()
        val attempt = launch {
            awaitConnectionAttempt(abort = { aborted.complete(Unit) }) {
                entered.complete(Unit)
                release.await(5, TimeUnit.SECONDS)
            }
        }
        try {
            entered.await()
            withTimeout(2_000) { attempt.cancelAndJoin(); aborted.await() }
            var connected = false
            awaitConnectionAttempt(abort = { fail("Successful connection was closed") }) { connected = true }
            assertTrue(connected)
        } finally { release.countDown() }
    }
}
