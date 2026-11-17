package com.codexserver.mobile

import org.junit.Assert.*
import org.junit.Test

class MobileUiPolicyCheck {
    @Test fun reminderHistorySurvivesRecreatedPolicyAndOlderVersions() {
        var stored = emptySet<String>()
        fun policy() = UpdateReminderPolicy({ stored }, { stored = it })
        val first = policy()
        assertTrue(first.shouldRemind(68))
        first.markReminded(68)
        assertFalse(first.shouldRemind(68))
        val afterRestart = policy()
        assertFalse(afterRestart.shouldRemind(68))
        assertTrue(afterRestart.shouldRemind(69))
        afterRestart.markReminded(69)
        assertFalse(policy().shouldRemind(68))
        assertFalse(policy().shouldRemind(69))
        assertTrue(policy().shouldRemind(70))
    }

    @Test fun automaticNetworkRecoveryStaysQuietButActionableFailuresRemainVisible() {
        val timeout = java.net.SocketTimeoutException("timed out")
        val wrapped = java.io.IOException("SSH transport", java.net.SocketException("connection reset"))
        assertFalse(connectionFailureNeedsAttention(true, timeout))
        assertFalse(connectionFailureNeedsAttention(true, wrapped))
        assertFalse(connectionFailureNeedsAttention(true, java.net.UnknownHostException("offline")))
        assertTrue(connectionFailureNeedsAttention(false, timeout))
        assertTrue(connectionFailureNeedsAttention(true, IllegalStateException("authentication failed")))
        assertTrue(connectionFailureNeedsAttention(true, java.io.IOException("host identity changed")))
    }

    @Test fun patchesKeepBothStagesFullPathsAndActualAdditionCounts() {
        val diff = """
            --- Git 仓库：/project ---
             M app/手机 界面.kt
            ?? new.md

            --- 已暂存的改动 ---
            diff --git a/app/手机 界面.kt b/app/手机 界面.kt
            --- a/app/手机 界面.kt
            +++ b/app/手机 界面.kt
            @@ -1 +1 @@
            -old
            +new

            --- 未暂存的改动 ---
            diff --git a/app/手机 界面.kt b/app/手机 界面.kt
            --- a/app/手机 界面.kt
            +++ b/app/手机 界面.kt
            @@ -1 +1,2 @@
             new
            +next
        """.trimIndent()
        val rows = workspaceDiffSections(diff)
        assertEquals(3, rows.size)
        assertTrue(rows[0].lines.contains("?? new.md"))
        assertEquals("app/手机 界面.kt · 已暂存", rows[1].title)
        assertEquals("app/手机 界面.kt · 未暂存", rows[2].title)
        assertEquals(3, rows.map { it.id }.distinct().size)
        assertEquals(1, rows[1].additions)
        assertEquals(1, rows[1].deletions)
        assertEquals(1, rows[2].additions)
        assertEquals(0, rows[2].deletions)
        assertTrue(rows[2].lines.contains("+next"))
    }

    @Test fun emptyBinaryDeletedAndUntrackedChangesRemainReadable() {
        assertTrue(workspaceDiffSections("--- Git 仓库：/p ---\n\n--- 已暂存的改动 ---\n\n--- 未暂存的改动 ---\n").isEmpty())
        assertEquals(listOf("?? new.png"), workspaceDiffSections("?? new.png").single().lines)
        val binary = workspaceDiffSections("diff --git a/a.png b/a.png\nBinary files a/a.png and b/a.png differ").single()
        assertTrue(binary.title.startsWith("a.png"))
        assertEquals(0, binary.additions)
        val deleted = workspaceDiffSections("diff --git a/old.md b/old.md\n--- a/old.md\n+++ /dev/null\n-old").single()
        assertTrue(deleted.title.startsWith("old.md"))
        assertEquals(1, deleted.deletions)
    }

    @Test fun foldedFailuresKeepTheirStatusAndDetails() {
        val failed = ChatLine("tool", "failure details", "failed", "old", status = "failed")
        val folded = foldCompletedProcessLines(listOf(failed), null, false).single()
        assertEquals("failed", folded.status)
        assertEquals(listOf(failed), folded.processItems)
    }

    @Test fun activeExecutionDetailsFoldWithoutHidingAnswerOrApproval() {
        val tools = listOf(ChatLine("tool", "output", "cmd", "active", stage = "命令执行", status = "inProgress"),
            ChatLine("tool", "plan", "plan", "active", stage = "执行计划"))
        val question = ChatLine("request", "approve?", "request", "active", status = "pending")
        val answer = ChatLine("assistant", "answer", "answer", "active")
        val rows = foldCompletedProcessLines(tools + question + answer, "active", true)
        assertEquals(listOf("thought", "request", "assistant"), rows.map { it.role })
        assertEquals(tools, rows.first().processItems)
        assertEquals("inProgress", rows.first().status)
        assertEquals(question, rows[1])
        val done = foldCompletedProcessLines(tools + answer, null, false)
        assertEquals("completed", done.first().status)
        assertEquals(answer, done.last())
    }
}
