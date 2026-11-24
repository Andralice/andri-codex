package com.codexserver.mobile

import org.junit.Assert.*
import org.junit.Test

class ScreenNavigationCheck {
    @Test fun edgeSwipeOpensSidebarAndRightSwipeDoesNotLeaveTheChat() {
        assertEquals(ChatSwipe.SIDEBAR, chatSwipeTarget(12f, 70f, 32f))
        assertNull(chatSwipeTarget(120f, 70f, 32f))
        assertEquals(ChatSwipe.WORKSPACE, chatSwipeTarget(348f, -70f, 32f))
        assertEquals(ChatSwipe.WORKSPACE, chatSwipeTarget(120f, -70f, 32f))
        assertNull(chatSwipeTarget(120f, 0f, 32f))
    }

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
        listOf("connection", "connecting", "empty-chat").forEach { destination ->
            assertEquals(destination, MainScreen(destination).key)
        }
        assertThrows(IllegalArgumentException::class.java) { MainScreen("chat") }
    }
}
