package com.codexserver.mobile

import org.junit.Assert.*
import org.junit.Test

class TaskCompletionCheck {
    @Test fun liveEventAndRecoveryOnlyDeliverOneNotification() {
        val live = TaskResultState().record("turn-1", "completed")
        assertEquals("completed", live.unreadStatus)
        assertTrue(live.needsNotification())
        val recovered = live.delivered().record("turn-1", "completed")
        assertFalse(recovered.needsNotification())
        assertEquals("completed", recovered.unreadStatus)
    }

    @Test fun readingTheConversationPreventsRecoveryFromRestoringItsBadge() {
        val read = TaskResultState().record("turn-1", "completed").delivered().acknowledge()
        val replay = read.record("turn-1", "completed")
        assertEquals("", replay.unreadStatus)
        assertFalse(replay.needsNotification())
        val nextTask = replay.record("turn-2", "completed")
        assertEquals("completed", nextTask.unreadStatus)
        assertTrue(nextTask.needsNotification())
    }

    @Test fun deniedNotificationDoesNotConsumeDeliveryOrLoseTheListBadge() {
        val denied = TaskResultState().record("turn-1", "failed")
        val recovery = denied.record("turn-1", "failed")
        assertTrue(recovery.needsNotification())
        assertEquals("failed", recovery.unreadStatus)
        assertFalse(recovery.delivered().needsNotification())
        // Opening the result before permission is restored must not alert for it later.
        assertFalse(denied.acknowledge().record("turn-1", "failed").needsNotification())
    }

    @Test fun successfulDeliveryKeepsResultUnreadUntilExplicitlyOpened() {
        terminalTaskStatuses.forEach { status ->
            val delivered = TaskResultState().record("turn-$status", status).delivered()
            assertEquals(status, delivered.unreadStatus)
            assertEquals("", delivered.acknowledge().unreadStatus)
        }
        assertEquals("任务已完成", taskResultTitle("completed"))
        assertEquals("任务执行失败", taskResultTitle("failed"))
        assertEquals("任务已中断", taskResultTitle("cancelled"))
    }

    @Test fun progressDoesNotReplaceAnUnreadTaskResult() {
        val unread = TaskResultState().record("turn-1", "completed")
        assertEquals(unread, unread.record("turn-2", "inProgress"))
        assertEquals(unread, unread.record("turn-2", ""))
    }
}
