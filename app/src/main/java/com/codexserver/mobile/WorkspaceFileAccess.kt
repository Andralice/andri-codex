package com.codexserver.mobile

import java.nio.file.Paths

internal const val CODEX_STORAGE_ROOT = "/mnt/codex-storage"
internal val workspaceReadRoots = setOf(WORKSPACE_ROOT, CODEX_STORAGE_ROOT)

/** Shared by link routing and SFTP reads. SFTP callers must pass the server's realpath. */
internal fun isReadableWorkspacePath(path: String): Boolean {
    if (!path.startsWith('/') || '\u0000' in path) return false
    val normalized = runCatching { Paths.get(path).normalize().toString() }.getOrNull() ?: return false
    return workspaceReadRoots.any { root -> normalized == root || normalized.startsWith("$root/") }
}
