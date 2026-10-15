package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.PlayWriteFailure
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class BoundedPlayTransportTest {
    private fun exchange(path: String, bytes: ByteArray, chunked: Boolean = false): Result<PlayRpcResponse> = runBlocking {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val calls = AtomicInteger()
        val worker = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader()
                    input.readLine(); var length = 0
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt() }
                    repeat(length) { input.read() }; calls.incrementAndGet()
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 200 OK\r\nConnection: close\r\n" +
                        if (chunked) "Transfer-Encoding: chunked\r\n\r\n${bytes.size.toString(16)}\r\n" else "Content-Length: ${bytes.size}\r\n\r\n").toByteArray())
                    output.write(bytes)
                    if (chunked) output.write("\r\n0\r\n\r\n".toByteArray())
                    output.flush()
                }
            } catch (_: IOException) { /* A bounded client may close before the oversized response completes. */ }
        }.apply { isDaemon = true; start() }
        val transport = NoRetryPlayRpcTransport()
        try {
            runCatching { transport.send(Request.Builder().url("http://127.0.0.1:${server.localPort}/rest/v1/rpc/$path")
                .post("{}".toRequestBody()).build()) }.also { assertEquals(1, calls.get()) }
        } finally { transport.close(); server.close(); worker.join(3500) }
    }
    @Test fun `receipt accepts exact 32KiB boundary and rejects the next byte without retries`() {
        assertEquals(32768, exchange("create_play_v1", ByteArray(32768) { 32 }).getOrThrow().body.length)
        assertTrue(exchange("create_play_v1", ByteArray(32769) { 32 }).exceptionOrNull() is PlayWriteFailure)
    }
    @Test fun `getter accepts exact 4MiB boundary while chunked receipt cannot evade its smaller cap`() {
        assertEquals(4 * 1048576, exchange("get_play_editor_v1", ByteArray(4 * 1048576) { 32 }).getOrThrow().body.length)
        assertTrue(exchange("get_play_editor_v1", ByteArray(4 * 1048576 + 1) { 32 }).exceptionOrNull() is PlayWriteFailure)
        assertTrue(exchange("update_play_v1", ByteArray(32769) { 32 }, chunked = true).exceptionOrNull() is PlayWriteFailure)
    }
    @Test fun `invalid UTF8 never becomes a silently repaired receipt`() {
        assertTrue(exchange("delete_play_v1", byteArrayOf(0xc3.toByte(), 0x28)).exceptionOrNull() is PlayWriteFailure)
    }
}
