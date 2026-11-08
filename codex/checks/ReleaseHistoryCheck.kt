package com.codexserver.mobile

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ReleaseHistoryCheck {
    private fun release(code: Int) = UpdateInfo(code, "0.1.$code", SshCodexClient.RELEASE_ROOT + "release-$code.apk",
        "a".repeat(64), "", 3)
    private fun json(info: UpdateInfo) = JSONObject().put("versionCode", info.versionCode).put("versionName", info.versionName)
        .put("apkPath", info.apkPath).put("sha256", info.sha256).put("sizeBytes", info.sizeBytes)

    @Test fun historyRejectsUnsafePathsAndConflictingVersions() {
        assertEquals(listOf(9, 3), parseReleaseHistory(JSONObject().put("releases",
            JSONArray().put(json(release(3))).put(json(release(9))))).map { it.versionCode })
        assertTrue(runCatching { parseReleaseHistory(JSONObject().put("releases", JSONArray()
            .put(json(release(3)).put("apkPath", "/tmp/evil.apk")))) }.isFailure)
        assertTrue(runCatching { parseReleaseHistory(JSONObject().put("releases", JSONArray()
            .put(json(release(3))).put(json(release(3)).put("sha256", "b".repeat(64))))) }.isFailure)
    }

    @Test fun selectedOldReleaseSurvivesNewLatestAndTamperingDeletesDownload() = runBlocking {
        val directory = Files.createTempDirectory(File("codex").toPath(), "history-check-").toFile()
        try {
            val selected = release(3)
            var catalog = listOf(selected, release(9))
            val downloaded = downloadHistoricalRelease(directory, selected, { catalog }, { info, file ->
                assertEquals(3, info.versionCode)
                file.writeText("APK")
                catalog = listOf(release(10)) + catalog
            }, { file, _ -> check(file.readText() == "APK") })
            assertEquals(selected, downloaded.info)
            downloaded.file.delete()
            assertTrue(runCatching {
                downloadHistoricalRelease(directory, selected, { catalog }, { _, file ->
                    file.writeText("APK")
                    catalog = listOf(selected.copy(sha256 = "b".repeat(64)))
                }, { _, _ -> })
            }.isFailure)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun readOnlyServerConnectionAndSshOnlyRecoveryDownload() = runBlocking {
        val keyPath = System.getenv("CODEX_CHECK_READONLY_KEY_FILE")
        assumeTrue(!keyPath.isNullOrBlank())
        val key = File(keyPath!!).readText()
        val connection = SshCodexClient("82.156.142.57", 22, "ubuntu", key, "") {}
        try {
            awaitConnectionAttempt(abort = connection::close) { connection.connect() }
            val threads = connection.listThreads(firstPageOnly = true)
            println("Real pinned SSH + WebSocket + initialize + thread/list passed (${threads.size} rows)")
        } finally { connection.close() }
        val recovery = SshCodexClient("82.156.142.57", 22, "ubuntu", key, "") {}
        val directory = Files.createTempDirectory(File("codex").toPath(), "real-history-").toFile()
        try {
            awaitConnectionAttempt(abort = recovery::close) { recovery.connectSshOnly() }
            assertFalse("Recovery must not initialize app-server", recovery.isAlive())
            val catalog = recovery.readReleaseHistory()
            val selected = catalog.last()
            val downloaded = downloadHistoricalRelease(directory, selected, { recovery.readReleaseHistory() }, { info, file ->
                file.outputStream().use { recovery.downloadUpdate(info, it) }
            }, { file, info ->
                assertEquals(info.sizeBytes, file.length())
                assertEquals(info.sha256, MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) })
            })
            assertEquals(selected.versionCode, downloaded.info.versionCode)
            println("SSH-only history (${catalog.size} releases) + selected original APK + size/SHA-256 passed")
        } finally { recovery.close(); directory.deleteRecursively() }
    }
}
