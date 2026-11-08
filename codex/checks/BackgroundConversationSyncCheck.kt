package com.codexserver.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BackgroundConversationSyncCheck {
    @Test fun slowListDoesNotDelayTheConversationOnScreen() = runBlocking {
        val releaseList = CompletableDeferred<Unit>()
        val threadVisible = CompletableDeferred<Unit>()
        val sync = launch {
            syncConversationContent(
                syncList = { releaseList.await() },
                syncThread = { threadVisible.complete(Unit) },
                onFailure = { throw AssertionError(it) }
            )
        }
        try {
            withTimeout(2_000) { threadVisible.await() }
            assertTrue("The list is still loading, but the chat is already synchronized", sync.isActive)
            releaseList.complete(Unit)
            withTimeout(2_000) { sync.join() }
        } finally { sync.cancelAndJoin() }
    }

    @Test fun listFailureDoesNotCancelConversationRecovery() = runBlocking {
        val listFailed = CompletableDeferred<Unit>()
        var recovered = false
        val failure = IOException("temporary list failure")
        val failures = mutableListOf<Exception>()
        syncConversationContent(
            syncList = { throw failure },
            syncThread = { listFailed.await(); recovered = true },
            onFailure = { failures += it; listFailed.complete(Unit) }
        )
        assertTrue(recovered)
        assertEquals(listOf(failure), failures)
    }

    @Test fun replacedConnectionCancelsBothReadsWithoutReportingAnError() = runBlocking {
        val listStarted = CompletableDeferred<Unit>()
        val threadStarted = CompletableDeferred<Unit>()
        var cancelledReads = 0
        val failures = mutableListOf<Exception>()
        val sync = launch {
            syncConversationContent(
                syncList = {
                    listStarted.complete(Unit)
                    try { awaitCancellation() } finally { cancelledReads++ }
                },
                syncThread = {
                    threadStarted.complete(Unit)
                    try { awaitCancellation() } finally { cancelledReads++ }
                },
                onFailure = { failures += it }
            )
        }
        withTimeout(2_000) { listStarted.await(); threadStarted.await() }
        sync.cancelAndJoin()
        assertEquals(2, cancelledReads)
        assertTrue(failures.isEmpty())
    }
}
