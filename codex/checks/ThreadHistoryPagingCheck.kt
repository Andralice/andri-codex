package com.codexserver.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ThreadHistoryPagingCheck {
    @Test fun timesOutOnFullTurnsPageThenRetriesCompactPage() = runBlocking {
        var fullCalls = 0
        var compactCalls = 0
        val result = retryCompactTurnsPage(
            fullPage = {
                fullCalls++
                throw IOException("thread/turns/list 等待响应超时，请刷新状态后再操作")
            },
            compactPage = {
                compactCalls++
                "compact page"
            }
        )
        assertEquals("compact page", result)
        assertEquals(1, fullCalls)
        assertEquals(1, compactCalls)
        assertEquals(8, INITIAL_THREAD_TURNS_LIMIT)
        assertEquals(10, THREAD_TURNS_PAGE_LIMIT)
        assertEquals(3, THREAD_TURNS_COMPACT_PAGE_LIMIT)
    }

    @Test fun retriesOnlyTheSpecificTurnsListTimeout() = runBlocking {
        var compactCalls = 0
        val result = retryCompactTurnsPage(
            fullPage = { "normal page" },
            compactPage = { compactCalls++; "compact page" }
        )
        assertEquals("normal page", result)
        assertEquals(0, compactCalls)

        val unrelated = runCatching {
            retryCompactTurnsPage(
                fullPage = { throw IOException("thread/resume 等待响应超时") },
                compactPage = { compactCalls++; "compact page" }
            )
        }
        assertTrue(unrelated.isFailure)
        assertEquals(0, compactCalls)
    }
}
