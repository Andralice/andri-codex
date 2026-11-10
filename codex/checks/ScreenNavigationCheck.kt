package com.codexserver.mobile

import org.junit.Assert.*
import org.junit.Test

class ScreenNavigationCheck {
    @Test fun conversationPagesHaveDistinctKeysForEachThreadAndDestination() {
        val pages = listOf("chat", "workspace", "thread-loading").flatMap { destination ->
            listOf("first", "second").map { id -> MainScreen(destination, CodexThread(id, id)) }
        }
        assertEquals(6, pages.map { it.key }.distinct().size)
    }

    @Test fun outgoingPageKeepsItsConversationWhenTheSelectionChanges() {
        var selection = CodexThread("first", "First conversation")
        val outgoing = MainScreen("chat", selection)
        selection = CodexThread("second", "Second conversation")
        val incoming = MainScreen("chat", selection)
        assertEquals("first", outgoing.thread!!.id)
        assertEquals("chat:first", outgoing.key)
        assertEquals("chat:second", incoming.key)
    }

    @Test fun connectionAndListPagesDoNotRequireAConversation() {
        listOf("connection", "connecting", "thread-list").forEach { destination ->
            assertEquals(destination, MainScreen(destination).key)
        }
        assertThrows(IllegalArgumentException::class.java) { MainScreen("chat") }
    }
}
