package com.codexserver.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-thread queue: arrivals during a suspended read join the same serial worker. */
internal class ConversationPreloadQueue<C : Any>(
    private val scope: CoroutineScope,
    private val isCurrent: (C) -> Boolean,
    private val load: suspend (C, String) -> Unit
) {
    private val pending = linkedSetOf<String>()
    private var connection: C? = null
    private var job: Job? = null

    fun enqueue(target: C, ids: Collection<String>) {
        if (!isCurrent(target)) return
        if (connection !== target) {
            cancel()
            connection = target
        }
        pending.addAll(ids.filter(String::isNotBlank))
        if (job?.isActive == true || pending.isEmpty()) return
        val next = scope.launch(start = CoroutineStart.LAZY) {
            while (isCurrent(target)) {
                val id = pending.firstOrNull() ?: break
                pending.remove(id)
                load(target, id)
            }
        }
        job = next
        next.invokeOnCompletion { if (job === next) job = null }
        next.start()
    }

    fun cancel() {
        job?.cancel()
        job = null
        connection = null
        pending.clear()
    }
}

/** All pages are scanned on the service heartbeat, with one scan in flight per connection. */
internal class BackgroundThreadDiscovery<C : Any>(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val isCurrent: (C) -> Boolean,
    private val intervalMillis: Long = 30_000L
) {
    private var connection: C? = null
    private var job: Job? = null
    private var attemptedAt: Long? = null

    fun request(target: C, force: Boolean = false, scan: suspend () -> Unit) {
        if (!isCurrent(target)) return
        if (connection !== target) {
            cancel()
            connection = target
        }
        if (job?.isActive == true) return
        val time = now()
        if (!force && attemptedAt?.let { time - it < intervalMillis } == true) return
        attemptedAt = time
        val next = scope.launch(start = CoroutineStart.LAZY) { if (isCurrent(target)) scan() }
        job = next
        next.invokeOnCompletion { if (job === next) job = null }
        next.start()
    }

    fun cancel() {
        job?.cancel()
        job = null
        connection = null
        attemptedAt = null
    }
}

/** Stored independently of the bounded chat cache; acknowledge only a fresh, idle snapshot. */
internal class RunningConversationRecovery(
    initial: Set<String>,
    lastRunningAtLeave: Set<String> = emptySet(),
    private val save: (Set<String>) -> Unit
) {
    private val pending = (initial + lastRunningAtLeave).filter(String::isNotBlank).toMutableSet()
    fun contains(id: String): Boolean = id in pending
    fun remember(ids: Collection<String>) {
        if (pending.addAll(ids.filter(String::isNotBlank))) save(pending.toSet())
    }
    fun forget(id: String) {
        if (pending.remove(id)) save(pending.toSet())
    }
    fun retainVisible(ids: Set<String>) {
        if (pending.retainAll(ids)) save(pending.toSet())
    }
    fun hydrated(id: String, running: Boolean) {
        if (running) remember(listOf(id)) else forget(id)
    }
    fun candidates(rows: List<CodexThread>, recentLimit: Int): List<String> {
        val visible = rows.mapTo(mutableSetOf()) { it.id }
        return (rows.filter { it.running }.map { it.id } + pending.filter { it in visible } +
            rows.take(recentLimit).map { it.id }).distinct()
    }
}
