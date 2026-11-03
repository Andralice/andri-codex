package com.codexserver.mobile

/** Main-thread gate: one connection attempt, stale callback rejection and bounded backoff. */
internal class ConnectionRecovery {
    private var generation = 0L
    private var connecting = false
    private var failures = 0
    private var connectedAt: Long? = null
    private var retryAt = 0L

    fun isCurrent(token: Long) = token == generation
    fun delayMillis(now: Long) = (retryAt - now).coerceAtLeast(0L)

    fun begin(now: Long, automatic: Boolean): Long? {
        if (connecting || automatic && delayMillis(now) > 0) return null
        connecting = true
        return ++generation
    }

    fun connected(token: Long, now: Long) {
        if (!isCurrent(token)) return
        connecting = false
        connectedAt = now
    }

    fun failed(token: Long, now: Long) {
        if (!isCurrent(token)) return
        connecting = false
        if (connectedAt?.let { now - it >= 60_000 } == true) failures = 0
        connectedAt = null
        failures = (failures + 1).coerceAtMost(5)
        retryAt = now + (1_000L shl failures).coerceAtMost(30_000L)
        generation++ // Discard any remaining events from the closed connection.
    }

    fun reset() {
        generation++
        connecting = false
        connectedAt = null
        failures = 0
        retryAt = 0L
    }
}
