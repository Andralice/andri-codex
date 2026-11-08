package com.codexserver.mobile

import kotlinx.coroutines.delay

/** Keep retrying after a failed handshake, until connected or explicitly stopped. */
internal suspend fun retryConnection(
    shouldRetry: () -> Boolean,
    delayMillis: () -> Long,
    onWaiting: (Long) -> Unit,
    attempt: suspend () -> Unit
) {
    while (shouldRetry()) {
        val wait = delayMillis()
        onWaiting(wait)
        delay(wait)
        if (shouldRetry()) attempt()
    }
}
