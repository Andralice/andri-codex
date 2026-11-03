package com.codexserver.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class UpdateFlowCheck {
    private fun payload(code: Int) = "APK-$code".toByteArray()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun release(code: Int) = UpdateInfo(code, "0.1.$code", SshCodexClient.RELEASE_ROOT + "test-$code.apk",
        digest(payload(code)), "release $code", payload(code).size.toLong())
    private fun verify(file: File, info: UpdateInfo) {
        check(file.length() == info.sizeBytes && digest(file.readBytes()) == info.sha256) { "Invalid APK" }
    }
    private fun directory() = Files.createTempDirectory(File("codex").toPath(), "update-flow-").toFile()

    @Test fun downloadUsesLiveReleaseInsteadOfPreviouslyDisplayedVersion() = runBlocking {
        val directory = directory()
        try {
            var server = release(7) // The dialog originally displayed this release.
            val displayed = server
            server = release(8) // A new release was published before tapping Download.
            var reads = 0
            val downloaded = downloadCurrentUpdate(directory, { reads++; server }, { info, file ->
                assertEquals(8, info.versionCode)
                file.writeBytes(payload(info.versionCode))
            }, ::verify)!!
            assertEquals(7, displayed.versionCode)
            assertEquals(8, downloaded.info.versionCode)
            assertEquals(2, reads)
            assertEquals("update-8-${server.sha256}.apk", downloaded.file.name)
            assertArrayEquals(payload(8), downloaded.file.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun releasePublishedDuringDownloadDiscardsOldPackage() = runBlocking {
        val directory = directory()
        try {
            var server = release(7)
            val downloaded = downloadCurrentUpdate(directory, { server }, { info, file ->
                file.writeBytes(payload(info.versionCode))
                server = release(8)
            }, ::verify)
            assertNull(downloaded)
            assertEquals(0, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun installChecksLiveReleaseAndRejectsAlreadyDownloadedOldPackage() = runBlocking {
        val directory = directory()
        try {
            var server = release(7)
            val downloaded = downloadCurrentUpdate(directory, { server }, { info, file -> file.writeBytes(payload(info.versionCode)) }, ::verify)!!
            server = release(8)
            var installerOpened = false
            assertFalse(installCurrentUpdate(downloaded, { server }, ::verify) { installerOpened = true })
            assertFalse(installerOpened)
            assertFalse(downloaded.file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedLatestCheckNeverDownloadsOrOpensInstaller() = runBlocking {
        val directory = directory()
        try {
            var downloadCalled = false
            val failedDownload = runCatching { downloadCurrentUpdate(directory, { error("SFTP unavailable") },
                { _, _ -> downloadCalled = true }, ::verify) }
            assertTrue(failedDownload.isFailure)
            assertFalse(downloadCalled)
            val downloaded = downloadCurrentUpdate(directory, { release(7) }, { info, file -> file.writeBytes(payload(info.versionCode)) }, ::verify)!!
            var installerOpened = false
            val failedInstall = runCatching { installCurrentUpdate(downloaded, { error("SFTP unavailable") }, ::verify) { installerOpened = true } }
            assertTrue(failedInstall.isFailure)
            assertFalse(installerOpened)
        } finally { directory.deleteRecursively() }
    }

    @Test fun installRechecksPackageBytesAndBindsManifestToDownloadedFile() = runBlocking {
        val directory = directory()
        try {
            val info = release(8)
            val downloaded = downloadCurrentUpdate(directory, { info }, { _, file -> file.writeBytes(payload(8)) }, ::verify)!!
            var installerOpened = false
            downloaded.file.writeText("corrupted")
            val failure = runCatching { installCurrentUpdate(downloaded, { info }, ::verify) { installerOpened = true } }
            assertTrue(failure.isFailure)
            assertFalse(installerOpened)
            downloaded.file.writeBytes(payload(8))
            assertTrue(installCurrentUpdate(downloaded, { info.copy(notes = "Updated release notes") }, ::verify) {
                installerOpened = true
                assertEquals(downloaded.file, it)
            })
            assertTrue(installerOpened)
        } finally { directory.deleteRecursively() }
    }

    @Test fun sameVersionWithReplacedPackageCannotInstallOldBytes() = runBlocking {
        val directory = directory()
        try {
            val info = release(8)
            val downloaded = downloadCurrentUpdate(directory, { info }, { _, file -> file.writeBytes(payload(8)) }, ::verify)!!
            var installerOpened = false
            val replacement = info.copy(sha256 = digest("REPLACED".toByteArray()))
            assertFalse(installCurrentUpdate(downloaded, { replacement }, ::verify) { installerOpened = true })
            assertFalse(installerOpened)
        } finally { directory.deleteRecursively() }
    }

    @Test fun noNewerReleaseNeverDownloadsOrInstallsCachedPackage() = runBlocking {
        val directory = directory()
        try {
            assertNull(downloadCurrentUpdate(directory, { null }, { _, _ -> fail("Unexpected download") }, ::verify))
            val downloaded = downloadCurrentUpdate(directory, { release(8) }, { info, file -> file.writeBytes(payload(info.versionCode)) }, ::verify)!!
            assertFalse(installCurrentUpdate(downloaded, { null }, ::verify) { fail("Unexpected install") })
            assertFalse(downloaded.file.exists())
        } finally { directory.deleteRecursively() }
    }
}
