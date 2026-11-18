package com.codexserver.mobile

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.util.Collections
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real HTTPS reads against a local fixture; never contacts an upstream provider. */
class VideoHttpDownloadCheck {
    private class State {
        @Volatile var running = true
        @Volatile var payload = ByteArray(1_048_576) { (it % 251).toByte() }
        @Volatile var etag = "\"v1\""
        @Volatile var interrupt = true
        @Volatile var ignoreRanges = false
        @Volatile var forbidden = false
        @Volatile var failure: Throwable? = null
        val offsets: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    }

    @Test fun interruptedHttpsResumesAndHandlesCompleteRangeOrChangedSource() = runBlocking {
        val fixtures = File("codex/check-fixtures").apply { mkdirs() }
        val directory = Files.createTempDirectory(fixtures.toPath(), "https-video-").toFile()
        var server: SSLServerSocket? = null
        var serverThread: Thread? = null
        val state = State()
        val originalFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        try {
            val store = File(directory, "fixture.p12")
            val password = "local-fixture-only"
            val command = File(System.getProperty("java.home"), "bin/keytool").absolutePath
            val generated = ProcessBuilder(command, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
                "-keysize", "2048", "-storetype", "PKCS12", "-keystore", store.absolutePath,
                "-storepass", password, "-keypass", password, "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "1", "-noprompt")
                .redirectErrorStream(true).start()
            generated.inputStream.readBytes()
            assertEquals("Local test certificate generation", 0, generated.waitFor())
            val keys = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, password.toCharArray()) } }
            val keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password.toCharArray()) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            val tls = SSLContext.getInstance("TLS").apply { init(keyManager.keyManagers, trust.trustManagers, null) }
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            val listener = tls.serverSocketFactory.createServerSocket(0, 10, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            server = listener
            serverThread = thread(isDaemon = true, name = "video-https-fixture") {
                while (state.running) {
                    try {
                        listener.accept().use { socket ->
                            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            check(input.readLine().startsWith("GET "))
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isBlank()) break
                                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                            }
                            val offset = headers["range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toLong() ?: 0L
                            state.offsets += offset
                            val payload = state.payload
                            var status = 200
                            var start = 0
                            var count = payload.size
                            var declaredSize = payload.size
                            var range = ""
                            if (state.forbidden) { status = 403; count = 0; declaredSize = 0 }
                            else if (offset > 0 && headers["if-range"] == state.etag && !state.ignoreRanges) {
                                if (offset == payload.size.toLong()) {
                                    status = 416; count = 0; declaredSize = 0; range = "Content-Range: bytes */${payload.size}\r\n"
                                } else {
                                    status = 206; start = offset.toInt(); count = payload.size - start; declaredSize = count
                                    range = "Content-Range: bytes $start-${payload.size - 1}/${payload.size}\r\n"
                                }
                            } else if (state.interrupt) { count = payload.size / 2; state.interrupt = false }
                            val output = socket.getOutputStream()
                            output.write(("HTTP/1.1 $status Fixture\r\nConnection: close\r\n" +
                                "Content-Length: $declaredSize\r\nETag: ${state.etag}\r\n$range\r\n").toByteArray(Charsets.US_ASCII))
                            if (count > 0) output.write(payload, start, count)
                            output.flush()
                        }
                    } catch (failure: Throwable) {
                        if (state.running) { state.failure = failure; break }
                    }
                }
            }
            val url = "https://localhost:${listener.localPort}/video.mp4"
            val part = File(directory, "video.part")
            try { downloadResumableVideoUrl(url, part) { }; fail("First connection should be interrupted") }
            catch (_: IOException) { }
            assertEquals(state.payload.size / 2L, part.length())
            downloadResumableVideoUrl(url, part) { }
            assertArrayEquals(state.payload, part.readBytes())
            assertEquals(state.payload.size / 2L, state.offsets.last())

            downloadResumableVideoUrl(url, part) { }
            assertEquals(state.payload.size.toLong(), state.offsets.last())
            assertArrayEquals(state.payload, part.readBytes())

            state.etag = "\"v2\""
            state.payload = ByteArray(state.payload.size) { 7 }
            downloadResumableVideoUrl(url, part) { }
            assertArrayEquals(state.payload, part.readBytes())

            java.io.RandomAccessFile(part, "rw").use { it.setLength(state.payload.size / 2L) }
            state.ignoreRanges = true
            downloadResumableVideoUrl(url, part) { }
            assertEquals(state.payload.size / 2L, state.offsets.last())
            assertArrayEquals(state.payload, part.readBytes())

            state.forbidden = true
            try { downloadResumableVideoUrl(url, part) { }; fail("Forbidden URLs must surface as permanent errors") }
            catch (_: PermanentMediaDownloadException) { }
            assertNull(state.failure)
        } finally {
            state.running = false
            server?.close()
            serverThread?.join(1_000)
            HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory)
            directory.deleteRecursively()
        }
    }
}
