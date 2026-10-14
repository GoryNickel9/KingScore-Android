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
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class PlayWritesTest {
    private val f = PlayWriteFixtures
    private class Network : NetworkMonitor { override val connected = MutableStateFlow(true) }
    private fun check(action: suspend (SupabaseAuthRepository, Network) -> Unit) = runBlocking {
        val user = """{"id":"${f.account}","aud":"authenticated","app_metadata":{"role":"admin"}}"""
        val client = createSupabaseClient("https://example.test", "public-test-key") {
            httpEngine = MockEngine { request -> respond(if (request.url.encodedPath == "/auth/v1/token")
                """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"token_type":"bearer","user":$user}""" else user,
                headers = headersOf(HttpHeaders.ContentType, "application/json")) }
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
    private fun body(request: Request) = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
    private suspend fun problem(expected: PlayWriteProblem, action: suspend () -> Any?) {
        try { action(); fail("Expected $expected") } catch (failure: PlayWriteFailure) { assertEquals(expected, failure.problem) }
    }
    private suspend fun authProblem(expected: AuthProblem, action: suspend () -> Any?) {
        try { action(); fail("Expected $expected") } catch (failure: AuthFailure) { assertEquals(expected, failure.problem) }
    }

    @Test fun `default disabled client performs no RPC`() = check { auth, network ->
        var calls = 0
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { calls++; PlayRpcResponse(200, f.saved()) })
        problem(PlayWriteProblem.UNAVAILABLE) { writer.editor(f.play) }
        problem(PlayWriteProblem.UNAVAILABLE) { writer.create(f.create()) }
        assertEquals(0, calls); assertNull(writer.pendingRequest); writer.close()
    }
    @Test fun `four allowlisted RPCs use verified bearer and exact request bodies`() = check { auth, network ->
        val requests = mutableListOf<Request>()
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport {
            requests += it
            PlayRpcResponse(200, when (it.url.pathSegments.last()) { "get_play_editor_v1" -> f.editor(); "delete_play_v1" -> f.deleted(); else -> f.saved() })
        }, enabled = true)
        val update = f.update().copy(request_id = UUID(0, 7))
        val delete = f.delete().copy(request_id = UUID(0, 8))
        writer.editor(f.play); writer.create(f.create()); writer.update(update); writer.delete(delete)
        assertEquals(listOf("get_play_editor_v1", "create_play_v1", "update_play_v1", "delete_play_v1"), requests.map { it.url.pathSegments.last() })
        requests.forEach {
            assertEquals("POST", it.method); assertEquals("Bearer user-access", it.header("Authorization"))
            assertEquals("public-test-key", it.header("apikey")); assertNull(it.url.query)
        }
        assertEquals(PlaySaveJson.createArguments(f.create()).toString(), body(requests[1]))
        assertEquals(PlaySaveJson.updateArguments(update).toString(), body(requests[2]))
        assertNull(writer.pendingRequest)
    }
    @Test fun `offline missing session and different account never send`() = check { auth, network ->
        var calls = 0
        val transport = PlayRpcTransport { calls++; PlayRpcResponse(200, f.saved()) }
        val writer = SupabasePlayWrites(auth, network, f.account, transport, true)
        network.connected.value = false
        authProblem(AuthProblem.NETWORK_UNAVAILABLE) { writer.create(f.create()) }
        network.connected.value = true
        authProblem(AuthProblem.INVALID_CREDENTIALS) { SupabasePlayWrites(auth, network, UUID(0, 99), transport, true).create(f.create()) }
        auth.signOut(); authProblem(AuthProblem.INVALID_CREDENTIALS) { writer.create(f.create()) }
        assertEquals(0, calls); assertNull(writer.pendingRequest)
    }
    @Test fun `confirmed HTTP rejection clears only a fresh request`() = check { auth, network ->
        for ((status, code, expected) in listOf(Triple(400, "PT400", PlayWriteProblem.INVALID_INPUT),
            Triple(403, "42501", PlayWriteProblem.FORBIDDEN), Triple(403, "PT403", PlayWriteProblem.FORBIDDEN),
            Triple(404, "PT404", PlayWriteProblem.NOT_FOUND), Triple(409, "PT409", PlayWriteProblem.CONFLICT))) {
            var calls = 0
            val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { calls++; PlayRpcResponse(status, """{"code":"$code"}""") }, true)
            problem(expected) { writer.create(f.create()) }
            assertEquals(1, calls); assertNull(writer.pendingRequest)
        }
    }
    @Test fun `unrecognized rejection retains the journal and blocks new writes`() = check { auth, network ->
        val responses = listOf(PlayRpcResponse(422, """{"code":"PT422"}"""), PlayRpcResponse(403, """{"code":"PGRST301"}""")) +
            listOf(400, 401, 403, 404, 409).flatMap { status ->
                listOf("", "<html>Gateway error</html>", "{}", """{"code":42501}""", """{"code":"PT500"}""")
                    .map { PlayRpcResponse(status, it) }
            }
        for (response in responses) {
            val store = MemoryPendingPlayStore()
            var calls = 0
            val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { calls++; response }, true,
                PlayWriteJournal(f.account, store))
            problem(PlayWriteProblem.UNCERTAIN) { writer.create(f.create()) }
            assertEquals(f.request, writer.pendingRequest!!.requestId)
            assertEquals(PlaySaveJson.createArguments(f.create()).toString(), store.load(f.account)!!.body)
            problem(PlayWriteProblem.UNCERTAIN) { writer.create(f.create().copy(request_id = UUID(0, 99))) }
            assertEquals(1, calls)
            writer.close()
            assertNotNull(store.load(f.account))
        }
    }
    @Test fun `ambiguous rejection survives restart and replays only the original request`() = check { auth, network ->
        val store = MemoryPendingPlayStore()
        val requests = mutableListOf<Request>()
        val first = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport {
            requests += it; PlayRpcResponse(409, "<html>Proxy conflict</html>")
        }, true, PlayWriteJournal(f.account, store))
        problem(PlayWriteProblem.UNCERTAIN) { first.create(f.create()) }
        first.close()
        val second = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport {
            requests += it; PlayRpcResponse(200, f.saved(true))
        }, true, PlayWriteJournal(f.account, store))
        assertEquals(f.request, second.restorePending()!!.requestId)
        assertTrue((second.retryPending() as PlayWriteResult.Saved).value.replayed)
        assertEquals(body(requests[0]), body(requests[1]))
        assertEquals(2, requests.size)
        assertNotNull(store.load(f.account)!!.confirmedResponse)
        second.close()
    }
    @Test fun `missing RPC differs from a missing play and never falls back`() = check { auth, network ->
        var calls = 0
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport {
            calls++; PlayRpcResponse(404, """{"code":"PGRST202","message":"RPC missing"}""")
        }, true)
        problem(PlayWriteProblem.UNAVAILABLE) { writer.editor(f.play) }
        problem(PlayWriteProblem.UNAVAILABLE) { writer.create(f.create()) }
        assertEquals(2, calls); assertNull(writer.pendingRequest)
    }
    @Test fun `ambiguous HTTP statuses and invalid success block new writes`() = check { auth, network ->
        for (response in listOf(PlayRpcResponse(500, "secret"), PlayRpcResponse(503, "secret"), PlayRpcResponse(302, ""),
            PlayRpcResponse(204, ""), PlayRpcResponse(200, "{}"), PlayRpcResponse(200, f.saved().replace(f.play.toString(), "bad-id")))) {
            var calls = 0
            val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { calls++; response }, true)
            problem(PlayWriteProblem.UNCERTAIN) { writer.create(f.create()) }
            assertEquals(f.request, writer.pendingRequest!!.requestId)
            problem(PlayWriteProblem.UNCERTAIN) { writer.create(f.create().copy(request_id = UUID(0, 99))) }
            assertEquals(1, calls)
        }
    }
    @Test fun `explicit retry freezes original payload and UUID after timeout`() = check { auth, network ->
        val rows = mutableListOf(f.score())
        val command = f.create().copy(payload = f.input(rows))
        val requests = mutableListOf<Request>()
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport {
            requests += it; if (requests.size == 1) throw IOException("secret timeout") else PlayRpcResponse(200, f.saved(true))
        }, true)
        problem(PlayWriteProblem.UNCERTAIN) { writer.create(command) }
        rows.clear(); rows += f.score("77")
        assertTrue((writer.retryPending() as PlayWriteResult.Saved).value.replayed)
        assertEquals(body(requests[0]), body(requests[1])); assertNull(writer.pendingRequest)
        assertTrue((writer.retryPending() as PlayWriteResult.Saved).restored)
        assertEquals(2, requests.size)
    }
    @Test fun `failed retry retains uncertain request even for confirmed rejection`() = check { auth, network ->
        for (status in listOf(400, 403, 404, 409)) {
            var calls = 0
            val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport {
                calls++; if (calls == 1) throw IOException() else PlayRpcResponse(status, """{"code":"PT$status"}""")
            }, true)
            problem(PlayWriteProblem.UNCERTAIN) { writer.create(f.create()) }
            try { writer.retryPending(); fail() } catch (_: PlayWriteFailure) { }
            assertEquals(f.request, writer.pendingRequest!!.requestId)
        }
    }
    @Test fun `explicit 401 refreshes once with identical payload and new bearer`() = check { auth, network ->
        val requests = mutableListOf<Request>()
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport {
            requests += it; if (requests.size == 1) PlayRpcResponse(401, """{"code":"PGRST301"}""") else PlayRpcResponse(200, f.saved())
        }, true)
        writer.create(f.create())
        assertEquals(listOf("Bearer user-access", "Bearer new-access"), requests.map { it.header("Authorization") })
        assertEquals(body(requests[0]), body(requests[1])); assertNull(writer.pendingRequest)
    }
    @Test fun `second 401 stops and an earlier ambiguous request survives`() = check { auth, network ->
        var calls = 0
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport {
            calls++; if (calls == 1) throw IOException() else PlayRpcResponse(401, """{"code":"PT401"}""")
        }, true)
        problem(PlayWriteProblem.UNCERTAIN) { writer.create(f.create()) }
        authProblem(AuthProblem.INVALID_CREDENTIALS) { writer.retryPending() }
        assertEquals(3, calls); assertEquals(f.request, writer.pendingRequest!!.requestId)
    }
    @Test fun `disconnect after dispatch blocks access and preserves uncertainty`() = check { auth, network ->
        var calls = 0
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport {
            calls++; network.connected.value = false; PlayRpcResponse(200, f.saved())
        }, true)
        authProblem(AuthProblem.NETWORK_UNAVAILABLE) { writer.create(f.create()) }
        assertNotNull(writer.pendingRequest); network.connected.value = true
        problem(PlayWriteProblem.UNCERTAIN) { writer.create(f.create()) }; assertEquals(1, calls)
    }
    @Test fun `close during dispatch discards response and never sends again`() = check { auth, network ->
        var calls = 0
        lateinit var writer: SupabasePlayWrites
        writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { calls++; writer.close(); PlayRpcResponse(200, f.saved()) }, true)
        problem(PlayWriteProblem.CLOSED) { writer.create(f.create()) }
        assertNull(writer.pendingRequest)
        problem(PlayWriteProblem.CLOSED) { writer.retryPending() }; assertEquals(1, calls)
    }
    @Test fun `concurrent second mutation is rejected before dispatch`() = check { auth, network ->
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { calls++; started.complete(Unit); release.await(); PlayRpcResponse(200, f.saved()) }, true)
        coroutineScope {
            val first = async { writer.create(f.create()) }; started.await()
            problem(PlayWriteProblem.BUSY) { writer.create(f.create().copy(request_id = UUID(0, 99))) }
            release.complete(Unit); first.await()
        }
        assertEquals(1, calls)
    }
    @Test fun `cancellation during dispatch preserves original request`() = check { auth, network ->
        val started = CompletableDeferred<Unit>()
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { started.complete(Unit); awaitCancellation() }, true)
        coroutineScope {
            val first = launch { writer.create(f.create()) }; started.await(); first.cancelAndJoin()
        }
        assertEquals(f.request, writer.pendingRequest!!.requestId)
    }
    @Test fun `editor failures do not create or clear mutation uncertainty`() = check { auth, network ->
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport {
            if (it.url.pathSegments.last() == "create_play_v1") throw IOException() else PlayRpcResponse(200, "{}")
        }, true)
        problem(PlayWriteProblem.INVALID_RESPONSE) { writer.editor(f.play) }; assertNull(writer.pendingRequest)
        problem(PlayWriteProblem.UNCERTAIN) { writer.create(f.create()) }
        problem(PlayWriteProblem.INVALID_RESPONSE) { writer.editor(f.play) }; assertNotNull(writer.pendingRequest)
    }
    @Test fun `delete timeout replay checks target and confirms deletion`() = check { auth, network ->
        var calls = 0
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport {
            calls++; if (calls == 1) throw IOException() else PlayRpcResponse(200, f.deleted(true))
        }, true)
        problem(PlayWriteProblem.UNCERTAIN) { writer.delete(f.delete()) }
        assertTrue((writer.retryPending() as PlayWriteResult.Deleted).value.replayed)
        assertNull(writer.pendingRequest)
    }
    @Test fun `write ahead journal is committed before HTTP and confirmation survives success`() = check { auth, network ->
        val events = mutableListOf<String>()
        val memory = MemoryPendingPlayStore()
        val store = object : PendingPlayStore {
            override suspend fun load(accountId: UUID) = memory.load(accountId).also { events += "load" }
            override suspend fun save(accountId: UUID, record: StoredPlayMutation?) { events += if (record?.confirmedResponse != null) "confirmed" else "record"; memory.save(accountId, record) }
        }
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport {
            assertNotNull(memory.load(f.account)); events += "send"; PlayRpcResponse(200, f.saved())
        }, true, PlayWriteJournal(f.account, store))
        writer.create(f.create())
        assertEquals(listOf("load", "record", "send", "confirmed"), events); assertNotNull(memory.load(f.account)!!.confirmedResponse)
    }
    @Test fun `fresh client restores exact request without sending and manual replay survives restart`() = check { auth, network ->
        val store = MemoryPendingPlayStore()
        val bodies = mutableListOf<String>()
        val first = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { bodies += body(it); throw IOException() }, true, PlayWriteJournal(f.account, store))
        problem(PlayWriteProblem.UNCERTAIN) { first.create(f.create()) }; first.close()
        val second = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { bodies += body(it); PlayRpcResponse(200, f.saved(true)) }, true, PlayWriteJournal(f.account, store))
        assertEquals(f.request, second.restorePending()!!.requestId); assertEquals(1, bodies.size)
        problem(PlayWriteProblem.UNCERTAIN) { second.create(f.create().copy(request_id = UUID(0, 90))) }
        second.retryPending(); assertEquals(bodies[0], bodies[1]); assertNotNull(store.load(f.account)!!.confirmedResponse)
    }
    @Test fun `journal write failure prevents all dispatch and does not expose storage messages`() = check { auth, network ->
        var calls = 0
        val store = object : PendingPlayStore {
            override suspend fun load(accountId: UUID): StoredPlayMutation? = null
            override suspend fun save(accountId: UUID, record: StoredPlayMutation?) { throw IOException("private storage details") }
        }
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { calls++; PlayRpcResponse(200, f.saved()) }, true, PlayWriteJournal(f.account, store))
        problem(PlayWriteProblem.STORAGE) { writer.create(f.create()) }; assertEquals(0, calls)
    }
    @Test fun `corrupt restored payload blocks even a new command before HTTP`() = check { auth, network ->
        val store = MemoryPendingPlayStore()
        store.save(f.account, StoredPlayMutation(accountId = f.account.toString(), requestId = f.request.toString(), playId = null,
            kind = PlayMutationKind.CREATE, body = PlaySaveJson.createArguments(f.create()).toString().replace(f.request.toString(), UUID(0, 91).toString())))
        var calls = 0
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { calls++; PlayRpcResponse(200, f.saved()) }, true, PlayWriteJournal(f.account, store))
        problem(PlayWriteProblem.STORAGE) { writer.restorePending() }
        problem(PlayWriteProblem.STORAGE) { writer.create(f.create()) }; assertEquals(0, calls); assertNotNull(store.load(f.account))
    }
    @Test fun `restoration requires online verified account and another account sees no record`() = check { auth, network ->
        val store = MemoryPendingPlayStore()
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { throw IOException() }, true, PlayWriteJournal(f.account, store))
        problem(PlayWriteProblem.UNCERTAIN) { writer.create(f.create()) }; writer.close()
        val restored = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { fail("No RPC during restore"); PlayRpcResponse(200, "") }, true, PlayWriteJournal(f.account, store))
        network.connected.value = false
        authProblem(AuthProblem.NETWORK_UNAVAILABLE) { restored.restorePending() }
        assertNull(restored.pendingRequest); network.connected.value = true
        val wrong = SupabasePlayWrites(auth, network, UUID(0, 99), PlayRpcTransport { fail(); PlayRpcResponse(200, "") }, true, PlayWriteJournal(UUID(0, 99), store))
        authProblem(AuthProblem.INVALID_CREDENTIALS) { wrong.restorePending() }; assertNull(wrong.pendingRequest)
        assertNotNull(restored.restorePending()); assertNull(store.load(UUID(0, 99)))
    }
    @Test fun `shared journal rejects a mutation from another client while first is active`() = check { auth, network ->
        val journal = PlayWriteJournal(f.account)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val transport = PlayRpcTransport { calls++; started.complete(Unit); release.await(); PlayRpcResponse(200, f.saved()) }
        val first = SupabasePlayWrites(auth, network, f.account, transport, true, journal)
        val second = SupabasePlayWrites(auth, network, f.account, transport, true, journal)
        coroutineScope {
            val operation = async { first.create(f.create()) }; started.await()
            problem(PlayWriteProblem.BUSY) { second.create(f.create()) }
            problem(PlayWriteProblem.BUSY) { second.restoreCompleted() }
            release.complete(Unit); operation.await()
        }
        assertEquals(1, calls)
    }
    @Test fun `confirmed success with failed confirmation storage remains recoverable`() = check { auth, network ->
        val memory = MemoryPendingPlayStore()
        var failRemoval = true
        val store = object : PendingPlayStore {
            override suspend fun load(accountId: UUID) = memory.load(accountId)
            override suspend fun save(accountId: UUID, record: StoredPlayMutation?) {
                if (record?.confirmedResponse != null && failRemoval) throw IOException()
                memory.save(accountId, record)
            }
        }
        var calls = 0
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { calls++; PlayRpcResponse(200, f.saved(calls > 1)) }, true, PlayWriteJournal(f.account, store))
        problem(PlayWriteProblem.STORAGE) { writer.create(f.create()) }; assertNotNull(writer.pendingRequest)
        failRemoval = false; writer.retryPending(); assertNotNull(store.load(f.account)!!.confirmedResponse); assertEquals(2, calls)
    }
    @Test fun `disabled client does not read persisted data`() = check { auth, network ->
        val store = object : PendingPlayStore {
            override suspend fun load(accountId: UUID): StoredPlayMutation? { fail("Storage must remain untouched"); return null }
            override suspend fun save(accountId: UUID, record: StoredPlayMutation?) { fail() }
        }
        val writer = SupabasePlayWrites(auth, network, f.account, enabled = false, journal = PlayWriteJournal(f.account, store))
        problem(PlayWriteProblem.UNAVAILABLE) { writer.restorePending() }; writer.close()
    }
    @Test fun `confirmation recovered after restart never sends another RPC`() = check { auth, network ->
        val store = MemoryPendingPlayStore()
        var calls = 0
        val first = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { calls++; PlayRpcResponse(200, f.saved()) }, true, PlayWriteJournal(f.account, store))
        first.create(f.create()); first.close()
        val second = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { calls++; PlayRpcResponse(200, f.saved()) }, true, PlayWriteJournal(f.account, store))
        assertNull(second.restorePending())
        val result = second.restoreCompleted() as PlayWriteResult.Saved
        assertTrue(result.restored); assertEquals(f.play, result.value.play_id)
        second.retryPending(); second.create(f.create()); assertEquals(1, calls)
    }
    @Test fun `same request ID with changed body conflicts against durable confirmation`() = check { auth, network ->
        var calls = 0
        val writer = SupabasePlayWrites(auth, network, f.account, PlayRpcTransport { calls++; PlayRpcResponse(200, f.saved()) }, true)
        writer.create(f.create())
        problem(PlayWriteProblem.CONFLICT) { writer.create(f.create().copy(payload = f.input().copy(notes = "changed"))) }
        assertEquals(1, calls)
    }

    @Test fun `confirmed result is read before deferred journal cache clearing`() = check { auth, network ->
        lateinit var journal: PlayWriteJournal
        val record = StoredPlayMutation(accountId = f.account.toString(), requestId = f.request.toString(),
            playId = null, kind = PlayMutationKind.CREATE, body = PlaySaveJson.createArguments(f.create()).toString(),
            confirmedResponse = f.saved())
        val store = object : PendingPlayStore {
            override suspend fun load(accountId: UUID): StoredPlayMutation {
                journal.forgetOnRelease()
                return record
            }
            override suspend fun save(accountId: UUID, record: StoredPlayMutation?) { fail("Read only") }
        }
        journal = PlayWriteJournal(f.account, store)
        val writer = SupabasePlayWrites(auth, network, f.account,
            PlayRpcTransport { fail("No RPC"); PlayRpcResponse(200, "") }, true, journal)
        val result = writer.restoreCompleted() as PlayWriteResult.Saved
        assertEquals(f.play, result.value.play_id); assertTrue(result.restored)
        assertNull(journal.pending)
    }

    @Test fun `real loopback transport blocks redirects 503 replay and dropped connection retry`() = runBlocking {
        for (status in listOf("503 Unavailable\r\nRetry-After: 0", "307 Redirect\r\nLocation: /second", "DROP")) {
            val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
            val calls = AtomicInteger()
            val worker = Thread {
                try {
                    while (!server.isClosed) server.accept().use { socket ->
                        socket.soTimeout = 2000
                        val input = socket.getInputStream().bufferedReader()
                        if (input.readLine() == null) return@use
                        var length = 0
                        while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt() }
                        repeat(length) { input.read() }; calls.incrementAndGet()
                        if (status != "DROP") socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                } catch (_: IOException) { }
            }.apply { isDaemon = true; start() }
            val transport = NoRetryPlayRpcTransport()
            try {
                val response = runCatching { transport.send(Request.Builder().url("http://127.0.0.1:${server.localPort}/rpc")
                    .post("{}".toRequestBody()).build()) }
                if (status.startsWith("307")) assertEquals(307, response.getOrThrow().status)
                else assertTrue(response.exceptionOrNull() is PlayWriteFailure)
                assertEquals(1, calls.get())
            } finally { transport.close(); server.close(); worker.join(2500) }
        }
    }
    @Test fun `real RPC transport exposes only a bounded error code`() = runBlocking {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val privateBody = """{"code":"PGRST202","message":"secret","details":"private"}"""
        val worker = Thread {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader(); input.readLine()
                var length = 0
                while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt() }
                repeat(length) { input.read() }
                socket.getOutputStream().write(("HTTP/1.1 404 Not Found\r\nContent-Length: ${privateBody.length}\r\nConnection: close\r\n\r\n" + privateBody).toByteArray())
            }
        }.apply { isDaemon = true; start() }
        val transport = NoRetryPlayRpcTransport()
        try {
            val response = transport.send(Request.Builder().url("http://127.0.0.1:${server.localPort}/rpc").post("{}".toRequestBody()).build())
            assertEquals(404, response.status); assertEquals("""{"code":"PGRST202"}""", response.body)
        } finally { transport.close(); server.close(); worker.join(2500) }
    }
}
