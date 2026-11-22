package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.install.ApkDownloader
import io.github.edrock200.appdownloader.install.ExpectedApk
import io.github.edrock200.appdownloader.install.InstallRestoreStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class VerifiedDownloadTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun verifiesBytesAndUsesUniqueFilesForTheSameEntry() = runBlocking {
        val bytes = "PK signed APK fixture".toByteArray()
        val source = temp.newFile().apply { writeBytes(bytes) }
        val sha256 = InstallRestoreStore.sha256(source)
        val dir = temp.newFolder()
        serve(bytes) { url ->
            val expected = ExpectedApk(bytes.size.toLong(), "sha256:$sha256")
            val first = ApkDownloader.download(url, dir, "silora", expected) {}
            val second = ApkDownloader.download(url, dir, "silora", expected) {}
            assertNotEquals(first.file, second.file)
            assertArrayEquals(bytes, first.file.readBytes())
            assertEquals(sha256, second.sha256)
            assertTrue(dir.listFiles()!!.none { it.extension == "part" })
        }
    }

    @Test fun sizeOrDigestMismatchLeavesNoStagedArtifact() = runBlocking {
        val bytes = "PK truncated APK fixture".toByteArray()
        val dir = temp.newFolder()
        serve(bytes) { url ->
            for (expected in listOf(ExpectedApk(bytes.size + 1L, null),
                ExpectedApk(bytes.size.toLong(), "sha256:" + "0".repeat(64)))) {
                val failure = runCatching { ApkDownloader.download(url, dir, "silora", expected) {} }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertTrue(dir.listFiles()!!.isEmpty())
            }
        }
    }

    private suspend fun serve(bytes: ByteArray, test: suspend (String) -> Unit) {
        ServerSocket().use { server ->
            server.bind(InetSocketAddress("127.0.0.1", 0))
            val responder = thread(isDaemon = true, name = "apk-test-server") {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    client.use { socket ->
                        socket.soTimeout = 2_000
                        val request = socket.getInputStream().bufferedReader()
                        while (!request.readLine().isNullOrEmpty()) { /* Drain request headers. */ }
                        val headers = "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(headers.toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
            try {
                test("http://127.0.0.1:${server.localPort}/fixture.apk")
            } finally {
                server.close()
                responder.join(2_000)
            }
        }
    }
}
