package com.codexserver.mobile

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit local-sshd fixture only; no production user key or model requests. */
class VideoSftpResumeCheck {
    @Test fun dedicatedSftpResumesAfterTheTransferJobIsStopped() = runBlocking {
        val keyFile = System.getenv("CODEX_CHECK_VIDEO_SFTP_KEY_FILE")
        assumeTrue(!keyFile.isNullOrBlank())
        val source = File(System.getenv("CODEX_CHECK_VIDEO_SFTP_SOURCE"))
        val part = File(System.getenv("CODEX_CHECK_VIDEO_SFTP_PART"))
        val port = System.getenv("CODEX_CHECK_VIDEO_SFTP_PORT").toInt()
        val username = System.getenv("CODEX_CHECK_VIDEO_SFTP_USER")
        val key = File(keyFile!!).readText()
        val first = SshCodexClient("127.0.0.1", port, username, key, "") { }
        val transfer = launch(Dispatchers.IO) { first.downloadWorkspaceVideo(source.path, WORKSPACE_ROOT, part) }
        try {
            withTimeout(30_000) {
                while (part.length() == 0L && transfer.isActive) delay(1)
            }
            transfer.cancel()
            withTimeout(10_000) { transfer.join() }
            val retained = part.length()
            assertTrue("Transfer stop must retain a genuine partial file", retained in 1 until source.length())
            assertTrue(File(part.path + ".source").isFile)

            val resumed = SshCodexClient("127.0.0.1", port, username, key, "") { }
            var resumedOffset = -1L
            try {
                withTimeout(90_000) {
                    resumed.downloadWorkspaceVideo(source.path, WORKSPACE_ROOT, part) {
                        if (it.stage == "正在下载" && resumedOffset < 0) resumedOffset = it.downloaded
                    }
                }
                assertEquals(retained, resumedOffset)
                fun digest(file: File): ByteArray {
                    val hash = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            hash.update(buffer, 0, count)
                        }
                    }
                    return hash.digest()
                }
                assertArrayEquals(digest(source), digest(part))
                assertFalse("Dedicated video download does not require an app-server connection", resumed.isAlive())
                println("Dedicated SFTP cancellation retained bytes; a fresh client resumed at the exact offset and matched SHA-256")
            } finally { resumed.close() }
        } finally { transfer.cancel(); first.close() }
    }
}
