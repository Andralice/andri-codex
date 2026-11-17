package com.codexserver.mobile

import org.junit.Assert.*
import org.junit.Test

class ProjectSelectionCheck {
    private val defaults = ExecutionOptions(WORKSPACE_ROOT)
    private val selected = ExecutionOptions("$WORKSPACE_ROOT/andri-codex", ExecutionMode.FULL, "high")

    @Test fun restoredSettingsOverrideUnassignedCachedThreadAndOlderLocalSettings() {
        for (saved in listOf(null, defaults, defaults.copy(cwd = "$WORKSPACE_ROOT/old-project"))) {
            val actual = resolveThreadExecution(selected, saved, WORKSPACE_ROOT, defaults)
            assertEquals(selected, actual)
            assertEquals("andri-codex", projectNameForPath(actual.cwd))
        }
    }

    @Test fun pendingProjectSelectionKeepsItsDirectoryAndExecutionPreferences() {
        val pending = selected.copy(cwd = "$WORKSPACE_ROOT/自媒体/分镜")
        val actual = resolveThreadExecution(pending, selected, selected.cwd, defaults)
        assertEquals(pending, actual)
        assertEquals("自媒体", projectNameForPath(actual.cwd))
    }

    @Test fun cachedAndNewConversationsUseTheSameFallbackAsTheirWorkspace() {
        assertEquals(selected, resolveThreadExecution(null, selected, WORKSPACE_ROOT, defaults))
        assertEquals("andri-codex", projectNameForPath(resolveThreadExecution(null, null, selected.cwd, defaults).cwd))
        for (threadCwd in listOf(null, "", " ")) {
            assertEquals(selected, resolveThreadExecution(null, null, threadCwd, selected))
        }
        assertEquals("未指定项目", projectNameForPath(resolveThreadExecution(null, null, WORKSPACE_ROOT, defaults).cwd))
    }
}
