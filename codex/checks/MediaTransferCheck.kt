package com.codexserver.mobile

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaTransferCheck {
    private fun fixture(block: (File) -> Unit) {
        val root = File("codex/check-fixtures").apply { mkdirs() }
        val directory = Files.createTempDirectory(root.toPath(), "media-resume-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedReadSurvivesRecreationAndOnlyFetchesRemainingBytes() = fixture { directory -> runBlocking {
        val expected = ByteArray(1_048_576) { (it % 251).toByte() }
        val part = File(directory, "video.part")
        val firstOffset = prepareMediaPartial(part, "source/1:mtime=10", expected.size.toLong())
        var read = 0
        val interrupted = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (read >= 524_288) throw IOException("simulated lost connection")
                val count = minOf(length, 524_288 - read)
                expected.copyInto(buffer, offset, read, read + count)
                read += count
                return count
            }
        }
        try {
            appendMediaPartial(part, expected.size.toLong(), firstOffset, interrupted) { }
            fail("Interrupted read must fail")
        } catch (_: IOException) { }
        assertEquals(524_288L, part.length())
        // No in-memory transfer state is reused, as after restarting the process.
        val restored = prepareMediaPartial(File(directory, "video.part"), "source/1:mtime=10", expected.size.toLong())
        assertEquals(524_288L, restored)
        val updates = mutableListOf<MediaTransferProgress>()
        ByteArrayInputStream(expected, restored.toInt(), expected.size - restored.toInt()).use {
            appendMediaPartial(part, expected.size.toLong(), restored, it) { update -> updates += update }
        }
        assertArrayEquals(expected, part.readBytes())
        assertEquals(restored, updates.first().downloaded)
        assertEquals(1f, updates.last().fraction!!, 0f)
    } }

    @Test fun replacementWithSameLengthDoesNotAppendToAnOlderVersion() = fixture { directory ->
        val part = File(directory, "video.part")
        prepareMediaPartial(part, "file:size=8:mtime=10", 8)
        part.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertEquals(0, prepareMediaPartial(part, "file:size=8:mtime=11", 8))
        assertFalse(part.exists())
    }

    @Test fun unknownOrOverlongPartialIsDiscardedBeforeAppending() = fixture { directory ->
        val part = File(directory, "video.part")
        part.writeBytes(ByteArray(20))
        assertEquals(0, prepareMediaPartial(part, "new", 10))
        part.writeBytes(ByteArray(11))
        assertEquals(0, prepareMediaPartial(part, "new", 10))
    }

    @Test fun cancellationKeepsBytesForNextWorker() = fixture { directory -> runBlocking {
        val part = File(directory, "video.part")
        prepareMediaPartial(part, "same", 4)
        try { appendMediaPartial(part, 4, 0, ByteArrayInputStream(byteArrayOf(1, 2))) { } }
        catch (_: IOException) { }
        try {
            appendMediaPartial(part, 4, 2, ByteArrayInputStream(byteArrayOf(3, 4))) { throw CancellationException("worker stopped") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(2L, prepareMediaPartial(part, "same", 4))
    } }

    @Test fun unconfirmedTailAfterAbruptPowerLossIsNotUsedAsAResumeOffset() = fixture { directory -> runBlocking {
        val part = File(directory, "video.part")
        prepareMediaPartial(part, "same", 6)
        try { appendMediaPartial(part, 6, 0, ByteArrayInputStream(byteArrayOf(1, 2))) { } }
        catch (_: IOException) { }
        // Simulate file length advancing before the next sync/checkpoint completed.
        java.io.FileOutputStream(part, true).use { it.write(byteArrayOf(99, 99)) }
        assertEquals(4L, part.length())
        assertEquals(2L, prepareMediaPartial(part, "same", 6))
        assertArrayEquals(byteArrayOf(1, 2), part.readBytes())
        appendMediaPartial(part, 6, 2, ByteArrayInputStream(byteArrayOf(3, 4, 5, 6))) { }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), part.readBytes())
    } }

    @Test fun earlyEofIsNotPromotedToACompletedVideo() = fixture { directory -> runBlocking {
        val part = File(directory, "video.part")
        prepareMediaPartial(part, "same", 4)
        try {
            appendMediaPartial(part, 4, 0, ByteArrayInputStream(byteArrayOf(1, 2))) { }
            fail("Incomplete file must not be marked complete")
        } catch (_: IOException) { }
        assertEquals(2L, part.length())
    } }

    @Test fun httpRangeMustStartAtTheStoredOffset() {
        assertEquals(100L, mediaRangeTotal("bytes 20-99/100", 20))
        for (header in listOf("bytes 0-99/100", "bytes 20-100/100", "bytes */100", "")) {
            try { mediaRangeTotal(header, 20); fail("Invalid range: $header") } catch (_: IOException) { }
        }
    }

    @Test fun progressHandlesLargeFilesAndMissingTotals() {
        val large = 4L * 1024 * 1024 * 1024
        assertEquals(.5f, MediaTransferProgress("正在下载", large / 2, large).fraction!!, 0f)
        assertTrue(MediaTransferProgress("正在下载", large / 2, large, 1024).description.contains("50%"))
        assertNull(MediaTransferProgress(downloaded = 100).fraction)
        assertTrue(MediaTransferProgress(downloaded = 100).description.contains("已保留"))
    }

    @Test fun cacheCleanupNeverDeletesPartsOrAnActiveWorkersCompleteFile() = fixture { directory ->
        val active = File(directory, "active").apply { mkdirs() }
        val old = File(directory, "old").apply { mkdirs() }
        val partial = File(old, "video.part").apply { writeBytes(ByteArray(20)) }
        val activeComplete = File(active, "complete.mp4").apply { writeBytes(ByteArray(20)) }
        val oldComplete = File(old, "complete.mp4").apply { writeBytes(ByteArray(20)) }
        trimCompletedVideos(directory, setOf("active"), maxBytes = 0)
        assertTrue(partial.isFile)
        assertTrue(activeComplete.isFile)
        assertFalse(oldComplete.exists())
    }
}
