package app.kingscore.android.data.remote

import app.kingscore.android.core.auth.*
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.repository.*
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.net.ServerSocket
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kingscore.contracts.v1.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class CommentWritesTest {
    private val account = UUID(0, 1)
    private val play = UUID(0, 2)
    private val comment = UUID(0, 3)
    private val user = """{"id":"$account","aud":"authenticated","app_metadata":{"role":"admin"},"user_metadata":{"name":"Test"}}"""
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun row(content: String = "Ciao") = """[{"id":"$comment","play_id":"$play","user_id":"$account","author_name":"Server","content":"$content","created_at":"2026-10-13T12:00:00Z","updated_at":"2026-10-13T13:00:00Z"}]"""
    private class Network : NetworkMonitor { override val connected = MutableStateFlow(true) }

    private fun check(action: suspend (SupabaseAuthRepository, Network) -> Unit) = runBlocking {
        val client = createSupabaseClient("https://example.supabase.co", "public-test-key") {
            httpEngine = MockEngine { request ->
                if (request.url.encodedPath == "/auth/v1/token") respond("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"token_type":"bearer","user":$user}""", headers = jsonHeaders)
                else respond(user, headers = jsonHeaders)
            }
            defaultLogLevel = LogLevel.NONE
            install(Auth) {
                autoLoadFromStorage = false; alwaysAutoRefresh = false; enableLifecycleCallbacks = false
                sessionManager = MemorySessionManager(UserSession("user-access", "user-refresh", expiresIn = 3600, tokenType = "bearer"))
                codeVerifierCache = MemoryCodeVerifierCache()
            }
        }
        try { client.auth.loadFromStorage(autoRefresh = false); action(SupabaseAuthRepository(client), Network()) }
        finally { client.close() }
    }

    @Test fun `POST PATCH DELETE use verified bearer minimal payload and ownership filters`() = check { auth, network ->
        val requests = mutableListOf<Request>()
        val writer = SupabaseCommentWrites(auth, network, account, CommentTransport { requests += it; CommentHttpResponse(200, row()) })
        assertEquals("Server", writer.create(CreateComment(play, "Ciao")).author_name)
        writer.update(play, comment, UpdateComment("Ciao")); writer.delete(play, comment)
        assertEquals(listOf("POST", "PATCH", "DELETE"), requests.map { it.method })
        for (request in requests) {
            assertEquals("/rest/v1/play_comments", request.url.encodedPath)
            assertEquals("Bearer user-access", request.header("Authorization"))
            assertEquals("public-test-key", request.header("apikey"))
            assertEquals("return=representation", request.header("Prefer"))
            if (request.method != "POST") {
                assertEquals("eq.$account", request.url.queryParameter("user_id"))
                assertEquals("eq.$play", request.url.queryParameter("play_id"))
                assertEquals("eq.$comment", request.url.queryParameter("id"))
            }
        }
        fun body(index: Int) = Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8()
        assertEquals("""{"play_id":"$play","content":"Ciao"}""", body(0))
        assertEquals("""{"content":"Ciao"}""", body(1))
        assertNull(requests[2].body)
    }

    @Test fun `offline missing and wrong account never dispatch a write`() = check { auth, network ->
        var calls = 0
        val transport = CommentTransport { calls++; CommentHttpResponse(201, row()) }
        network.connected.value = false
        try { SupabaseCommentWrites(auth, network, account, transport).create(CreateComment(play, "Ciao")); fail() }
        catch (failure: AuthFailure) { assertEquals(AuthProblem.NETWORK_UNAVAILABLE, failure.problem) }
        network.connected.value = true
        try { SupabaseCommentWrites(auth, network, UUID(0, 99), transport).create(CreateComment(play, "Ciao")); fail() }
        catch (failure: AuthFailure) { assertEquals(AuthProblem.INVALID_CREDENTIALS, failure.problem) }
        auth.signOut()
        try { SupabaseCommentWrites(auth, network, account, transport).create(CreateComment(play, "Ciao")); fail() }
        catch (failure: AuthFailure) { assertEquals(AuthProblem.INVALID_CREDENTIALS, failure.problem) }
        assertEquals(0, calls)
    }

    @Test fun `zero rows are not success and invalid success representations are uncertain`() = check { auth, network ->
        for (response in listOf("[]", "{}", "[{}]", row().replace(account.toString(), UUID(0, 90).toString()), row("Changed"), row().replace(play.toString(), UUID(0, 91).toString()))) {
            var calls = 0
            val writer = SupabaseCommentWrites(auth, network, account, CommentTransport { calls++; CommentHttpResponse(200, response) })
            expect(CommentProblem.UNCERTAIN) { writer.create(CreateComment(play, "Ciao")) }
            assertEquals(1, calls)
        }
        val writer = SupabaseCommentWrites(auth, network, account, CommentTransport { CommentHttpResponse(200, "[]") })
        expect(CommentProblem.NOT_FOUND) { writer.update(play, comment, UpdateComment("Ciao")) }
        expect(CommentProblem.NOT_FOUND) { writer.delete(play, comment) }
    }

    @Test fun `HTTP failure and timeout never retry or expose remote bodies`() = check { auth, network ->
        for ((status, problem) in listOf(400 to CommentProblem.INVALID_CONTENT, 403 to CommentProblem.FORBIDDEN,
            404 to CommentProblem.NOT_FOUND, 409 to CommentProblem.INVALID_CONTENT, 422 to CommentProblem.INVALID_CONTENT,
            500 to CommentProblem.UNCERTAIN, 503 to CommentProblem.UNCERTAIN, 302 to CommentProblem.UNCERTAIN)) {
            var calls = 0
            val writer = SupabaseCommentWrites(auth, network, account, CommentTransport { calls++; CommentHttpResponse(status, "private-server-body") })
            expect(problem) { writer.create(CreateComment(play, "Ciao")) }
            assertEquals(1, calls)
        }
        var calls = 0
        val writer = SupabaseCommentWrites(auth, network, account, CommentTransport { calls++; throw IOException("private-server-body") })
        expect(CommentProblem.UNCERTAIN) { writer.create(CreateComment(play, "Ciao")) }
        assertEquals(1, calls)
    }

    @Test fun `explicit 401 refreshes once and retries with new bearer`() = check { auth, network ->
        val tokens = mutableListOf<String?>()
        val writer = SupabaseCommentWrites(auth, network, account, CommentTransport {
            tokens += it.header("Authorization")
            if (tokens.size == 1) CommentHttpResponse(401, "") else CommentHttpResponse(201, row())
        })
        writer.create(CreateComment(play, "Ciao"))
        assertEquals(listOf("Bearer user-access", "Bearer new-access"), tokens)
    }

    @Test fun `disconnect after dispatch discards the response and blocks access`() = check { auth, network ->
        var calls = 0
        val writer = SupabaseCommentWrites(auth, network, account, CommentTransport {
            calls++; network.connected.value = false; CommentHttpResponse(201, row())
        })
        try { writer.create(CreateComment(play, "Ciao")); fail() }
        catch (failure: AuthFailure) { assertEquals(AuthProblem.NETWORK_UNAVAILABLE, failure.problem) }
        assertEquals(1, calls)
    }

    @Test fun `transport blocks a 503 Retry-After replay before the second network exchange`() = runBlocking {
        val server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val calls = AtomicInteger()
        val worker = Thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = socket.getInputStream().bufferedReader()
                    if (input.readLine() != null) {
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { input.read() }
                        calls.incrementAndGet()
                        socket.getOutputStream().write("HTTP/1.1 503 Unavailable\r\nRetry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
            } catch (_: IOException) { /* Closing the fixture socket ends the bounded worker. */ }
        }.apply { isDaemon = true; start() }
        val transport = NoRetryCommentTransport()
        try {
            expect(CommentProblem.UNCERTAIN) { transport.send(Request.Builder().url("http://127.0.0.1:${server.localPort}/comment")
                .post("{}".toRequestBody()).build()) }
            assertEquals(1, calls.get())
        } finally { transport.close(); server.close(); worker.join(2500) }
    }

    @Test fun `validation counts emoji code points and rejects invalid PostgreSQL Unicode`() {
        UpdateComment("😀".repeat(1000))
        CreateComment(play, "prima\nseconda")
        for (text in listOf("😀".repeat(1001), "", "   ", " x ", "a\u0000b", "\uD800", "\uDC00")) {
            assertThrows(IllegalArgumentException::class.java) { UpdateComment(text) }
        }
    }

    @Test fun `uncertain outcomes survive writer disposal and prevent a second dispatch until reread`() = check { auth, network ->
        val outcomes = CommentOutcomes(account)
        var calls = 0
        val transport = CommentTransport { calls++; throw IOException("timeout after commit") }
        val writer = SupabaseCommentWrites(auth, network, account, transport, outcomes)
        expect(CommentProblem.UNCERTAIN) { writer.create(CreateComment(play, "Ciao")) }
        writer.close()
        val recreated = SupabaseCommentWrites(auth, network, account, transport, outcomes)
        assertFalse(recreated.hasUncertainOutcome(play))
        assertTrue(recreated.restoreUncertainOutcome(play))
        assertTrue(recreated.hasUncertainOutcome(play))
        expect(CommentProblem.UNCERTAIN) { recreated.create(CreateComment(play, "Ciao")) }
        assertEquals(1, calls)
        recreated.acknowledgeOutcome(play)
        assertFalse(recreated.hasUncertainOutcome(play))
    }

    @Test fun `outcome registry forgets memory but preserves actor records and locks`() = runBlocking {
        val registry = AccountCommentOutcomes()
        val first = registry.forAccount(account)
        first.begin(play)
        assertSame(first, registry.forAccount(account))
        val next = registry.forAccount(UUID(0, 99))
        assertFalse(first.has(play)); assertFalse(next.has(play))
        first.restore()
        assertTrue(first.has(play)); assertFalse(next.has(play))
        next.begin(play); registry.clear()
        assertFalse(next.has(play))
        assertFalse(registry.forAccount(account).has(play))
    }

    private suspend fun expect(problem: CommentProblem, action: suspend () -> Any?) {
        try { action(); fail("Expected $problem") } catch (failure: CommentFailure) {
            assertEquals(problem, failure.problem); assertFalse(failure.message.orEmpty().contains("private-server-body"))
        }
    }

    @Test fun `journal persists before HTTP and new client restores without dispatching`() = check { auth, network ->
        val store = MemoryCommentOutcomeStore(); var calls = 0
        val writer = SupabaseCommentWrites(auth, network, account, CommentTransport {
            calls++; assertTrue(play in store.load(account)); throw IOException()
        }, CommentOutcomes(account, store))
        expect(CommentProblem.UNCERTAIN) { writer.create(CreateComment(play, "Ciao")) }; writer.close()
        val restored = SupabaseCommentWrites(auth, network, account, CommentTransport { calls++; CommentHttpResponse(200, row()) }, CommentOutcomes(account, store))
        assertTrue(restored.restoreUncertainOutcome(play))
        expect(CommentProblem.UNCERTAIN) { restored.create(CreateComment(play, "Ciao")) }
        assertEquals(1, calls)
    }
    @Test fun `failed storage blocks HTTP before dispatch`() = check { auth, network ->
        var calls = 0
        val store = object : CommentOutcomeStore {
            override suspend fun load(accountId: UUID) = emptySet<UUID>()
            override suspend fun save(accountId: UUID, plays: Set<UUID>) { throw IOException() }
        }
        val writer = SupabaseCommentWrites(auth, network, account, CommentTransport { calls++; CommentHttpResponse(200, row()) }, CommentOutcomes(account, store))
        expect(CommentProblem.STORAGE) { writer.create(CreateComment(play, "Ciao")) }; assertEquals(0, calls)
    }
    @Test fun `offline different account and closed client never read persisted IDs`() = check { auth, network ->
        var loads = 0
        val store = object : CommentOutcomeStore {
            override suspend fun load(accountId: UUID): Set<UUID> { loads++; return setOf(play) }
            override suspend fun save(accountId: UUID, plays: Set<UUID>) { fail() }
        }
        val writer = SupabaseCommentWrites(auth, network, account, outcomes = CommentOutcomes(account, store))
        network.connected.value = false
        try { writer.restoreUncertainOutcome(play); fail() } catch (failure: AuthFailure) { assertEquals(AuthProblem.NETWORK_UNAVAILABLE, failure.problem) }
        network.connected.value = true
        val other = UUID(0, 99)
        val wrong = SupabaseCommentWrites(auth, network, other, outcomes = CommentOutcomes(other, store))
        try { wrong.restoreUncertainOutcome(play); fail() } catch (failure: AuthFailure) { assertEquals(AuthProblem.INVALID_CREDENTIALS, failure.problem) }
        writer.close()
        try { writer.restoreUncertainOutcome(play); fail() } catch (failure: AuthFailure) { assertEquals(AuthProblem.INVALID_CREDENTIALS, failure.problem) }
        assertEquals(0, loads); wrong.close()
    }
}
