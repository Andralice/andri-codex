package com.codexserver.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadFilterCheck {
    @Test fun listExcerptRemovesMarkdownWithoutChangingConversationText() {
        val text = "## 更新完成\n- 已发布 **Andri 0.1.40**，查看 [更新说明](https://example.com/release)。\n`versionCode` 为 __41__。"
        val lines = listOf(ChatLine(role = "assistant", text = text),
            ChatLine(role = "tool", text = "不应显示工具记录"), ChatLine(role = "assistant", text = "  "))
        assertEquals("更新完成 已发布 Andri 0.1.40，查看 更新说明。 versionCode 为 41。", threadPreview(lines))
        assertEquals(text, lines.first().text)
        assertEquals("路径 /repo/a_b，计算 2 * 3", threadPreview(listOf(ChatLine("user", "路径 /repo/a_b，计算 2 * 3"))))
        assertEquals("val answer = 42", threadPreview(listOf(ChatLine("assistant", "```kotlin\nval answer = 42\n```"))))
        assertEquals("", threadPreview(emptyList()))
    }

    @Test fun searchAndRunningFilterKeepOrderAndUseLiveStatus() {
        val rows = listOf(CodexThread("a", "糖果熊维护", "gpt-6-luna"), CodexThread("b", "服务器存储", "gpt-6.1-sol", running = true), CodexThread("c", "安卓客户端", "gpt-6-luna"))
        assertEquals(listOf(rows[0], rows[2]), filterThreads(rows, setOf("a", "c"), " GPT-6-LUNA ", false))
        assertEquals(listOf(rows[0]), filterThreads(rows, setOf("a"), "糖果", true))
        assertEquals(emptyList<CodexThread>(), filterThreads(rows, setOf("a"), "服务器", true))
        assertEquals(rows, filterThreads(rows, emptySet(), "  ", false))
        assertEquals(emptyList<CodexThread>(), filterThreads(rows, emptySet(), "", true))
    }
}
