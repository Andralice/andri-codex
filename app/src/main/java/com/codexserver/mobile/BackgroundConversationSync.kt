package com.codexserver.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** A slow or failed list refresh must not delay the conversation already on screen. */
internal suspend fun syncConversationContent(
    syncList: suspend () -> Unit,
    syncThread: suspend () -> Unit,
    onFailure: (Exception) -> Unit
) = supervisorScope {
    suspend fun sync(block: suspend () -> Unit) {
        try { block() }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            onFailure(e)
        }
    }
    launch { sync(syncList) }
    launch { sync(syncThread) }
    Unit
}
