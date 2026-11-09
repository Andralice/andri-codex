package com.codexserver.mobile

import org.junit.Assert.*
import org.junit.Test

class RuntimeInteractionCheck {
    @Test fun uploadAcknowledgementKeepsNewDraftAndNewEqualAttachments() {
        val sentImage = ImageAttachment("data:image/png;base64,AAA=", "图")
        val newImage = sentImage.copy()
        val sentReference = ContextReference("old", "旧引用", "内容")
        val newReference = ContextReference("new", "新引用", "内容")

        assertEquals("下一条消息", acceptedDraftText("下一条消息", 2, 1))
        assertEquals(listOf(newImage), unsentItems(listOf(sentImage, newImage), listOf(sentImage)))
        assertSame(newImage, unsentItems(listOf(sentImage, newImage), listOf(sentImage)).single())
        assertEquals(listOf(newReference), unsentItems(listOf(sentReference, newReference), listOf(sentReference)))
        // A second confirmation (turn/started followed by the RPC response) must also preserve new edits.
        assertEquals("下一条消息", acceptedDraftText("下一条消息", 2, 1))
        assertSame(newImage, unsentItems(listOf(newImage), listOf(sentImage)).single())
    }

    @Test fun retypingIdenticalTextIsStillANewDraftButUneditedTextIsCleared() {
        assertEquals("同一段文字", acceptedDraftText("同一段文字", 3, 1))
        assertEquals("", acceptedDraftText("原提交内容", 1, 1))
        assertEquals("", acceptedDraftText("", 2, 1))
    }

    @Test fun delayedRefreshCannotReplaceNewerActivityAndDoesNotBlockOtherThreads() {
        val reads = ThreadReadState()
        val listRevision = reads.snapshot()
        val threadRevision = reads.revision("a")
        reads.changed("a") // A turn starts after the list and history reads begin.
        assertFalse(reads.accepts("a", listRevision["a"] ?: 0L))
        assertFalse(reads.accepts("a", threadRevision))
        assertTrue(reads.accepts("b", listRevision["b"] ?: 0L))
        assertTrue(reads.accepts("a", reads.revision("a")))
        val runningRevision = reads.revision("a")
        reads.changed("a") // A turn finishes before an old running snapshot arrives.
        assertFalse(reads.accepts("a", runningRevision))
    }

    @Test fun confirmedArchiveRejectsOldAndNewReadResultsForThatThreadOnly() {
        val reads = ThreadReadState()
        val beforeArchive = reads.revision("a")
        reads.archive("a")
        assertTrue(reads.isArchived("a"))
        assertFalse(reads.accepts("a", beforeArchive))
        assertFalse(reads.accepts("a", reads.revision("a")))
        assertTrue(reads.accepts("b", reads.revision("b")))
    }


    @Test fun historyReadStartedBeforeCompletionCannotRestoreStreamingOrEraseLaterItems() {
        val oldRead = listOf(ChatLine("assistant", "最终回复", "reply", "turn", streaming = true, status = "inProgress"))
        val completed = oldRead.single().copy(streaming = false, status = "completed")
        val nextPrompt = ChatLine("user", "下一条", "next", "turn-next")
        val merged = mergeHistory(oldRead, listOf(completed, nextPrompt), latestPage = true,
            activeTurnId = "turn-next", preferCurrent = true)
        assertEquals(listOf("reply", "next"), merged.map { it.id })
        assertEquals("completed", merged.first().status)
        assertFalse(merged.first().streaming)
    }

}
