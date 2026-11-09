package com.codexserver.mobile

/** A read may merge history, but must not replace activity changed since it began. */
internal class ThreadReadState {
    private val revisions = mutableMapOf<String, Long>()
    private val archived = mutableSetOf<String>()

    fun revision(id: String): Long = revisions[id] ?: 0L
    fun snapshot(): Map<String, Long> = revisions.toMap()
    fun changed(id: String) { revisions[id] = revision(id) + 1L }
    fun archive(id: String) { archived += id; changed(id) }
    fun isArchived(id: String): Boolean = id in archived
    fun accepts(id: String, revision: Long): Boolean = !isArchived(id) && this.revision(id) == revision
}
