package com.codexserver.mobile

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class UpdateCheck {
    @Test fun manifestRejectsUnsafeAndMalformedUpdates() {
        fun manifest() = JSONObject().put("versionCode", 3).put("versionName", "0.1.2")
            .put("apkPath", SshCodexClient.RELEASE_ROOT + "codex-server-0.1.2.apk")
            .put("sha256", "a".repeat(64)).put("sizeBytes", 14_000_000)
        assertEquals(3, parseUpdateInfo(manifest()).versionCode)
        for ((field, value) in listOf(
            "apkPath" to "/tmp/other.apk",
            "apkPath" to SshCodexClient.RELEASE_ROOT + "../other.apk",
            "versionName" to "../../file",
            "sha256" to "bad-hash",
            "sizeBytes" to 0,
            "sizeBytes" to SshCodexClient.MAX_UPDATE_BYTES + 1,
            "versionCode" to -1
        )) {
            assertTrue("Manifest accepted invalid " + field, runCatching { parseUpdateInfo(manifest().put(field, value)) }.isFailure)
        }
    }

    @Test fun realSftpManifestAndApkDownload() = runBlocking {
        val keyPath = System.getenv("CODEX_CHECK_KEY_FILE")
        assumeTrue(!keyPath.isNullOrBlank())
        val client = SshCodexClient("82.156.142.57", 22, "ubuntu", File(keyPath!!).readText(), "") {}
        try {
            client.connect()
            val info = client.readUpdateInfo(SshCodexClient.RELEASE_ROOT + "latest.json")
            assertEquals(BuildConfig.VERSION_CODE, info.versionCode)
            assertEquals(BuildConfig.VERSION_NAME, info.versionName)
            val output = ByteArrayOutputStream()
            client.downloadUpdate(info, output)
            val bytes = output.toByteArray()
            assertEquals(info.sizeBytes, bytes.size.toLong())
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(info.sha256, digest)
            val localApk = File("app/build/outputs/apk/debug/app-debug.apk")
            assertArrayEquals(localApk.readBytes(), bytes)
            println("SSH/SFTP manifest, full APK download, version, size and SHA-256 matched the local APK")
        } finally { client.close() }
    }
}
