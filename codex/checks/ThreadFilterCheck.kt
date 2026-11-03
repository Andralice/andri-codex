package com.codexserver.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadFilterCheck {
    @Test fun searchAndRunningFilterKeepOrderAndUseLiveStatus() {
        val rows = listOf(CodexThread("a", "糖果熊维护", "gpt-6-luna"), CodexThread("b", "服务器存储", "gpt-6.1-sol", running = true), CodexThread("c", "安卓客户端", "gpt-6-luna"))
        assertEquals(listOf(rows[0], rows[2]), filterThreads(rows, setOf("a", "c"), " GPT-6-LUNA ", false))
        assertEquals(listOf(rows[0]), filterThreads(rows, setOf("a"), "糖果", true))
        assertEquals(emptyList<CodexThread>(), filterThreads(rows, setOf("a"), "服务器", true))
        assertEquals(rows, filterThreads(rows, emptySet(), "  ", false))
        assertEquals(emptyList<CodexThread>(), filterThreads(rows, emptySet(), "", true))
    }
}
