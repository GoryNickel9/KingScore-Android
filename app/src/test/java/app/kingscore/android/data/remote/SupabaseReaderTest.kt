package app.kingscore.android.data.remote

import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.core.auth.SupabaseAuthRepository
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.repository.DataFailure
import app.kingscore.android.data.repository.DataProblem
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SupabaseReaderTest {
    private val account = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val user = """{"id":"$account","aud":"authenticated","email":"test@example.com","app_metadata":{"role":"user"},"user_metadata":{"name":"Test"}}"""
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun session() = UserSession("user-access", "user-refresh", expiresIn = 3600, tokenType = "bearer")
    private class Network(initial: Boolean = true) : NetworkMonitor { override val connected = MutableStateFlow(initial) }

    @Test fun `offline and missing session never issue anonymous requests`() = runBlocking {
        var calls = 0
        val manager = MemorySessionManager(session())
        val client = client(manager, MockEngine { calls++; respond(user, headers = jsonHeaders) })
        try {
            val network = Network(false)
            val reader = SupabaseReader(SupabaseAuthRepository(client), network)
            expectAuth(AuthProblem.NETWORK_UNAVAILABLE) { reader.get(account, "plays", emptyList()) }
            network.connected.value = true
            // Stored credentials are not an authenticated in-memory session.
            expectAuth(AuthProblem.INVALID_CREDENTIALS) { reader.get(account, "plays", emptyList()) }
            assertEquals(0, calls)
            assertNotNull(manager.loadSession())
        } finally { client.close() }
    }

    @Test fun `every data read verifies the current account and sends its bearer token`() = runBlocking {
        val paths = mutableListOf<String>()
        val manager = MemorySessionManager(session())
        val client = client(manager, MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("Bearer user-access", request.headers[HttpHeaders.Authorization])
            assertNotEquals("Bearer public-test-key", request.headers[HttpHeaders.Authorization])
            if (request.url.encodedPath == "/auth/v1/user") respond(user, headers = jsonHeaders)
            else {
                assertEquals("/rest/v1/plays", request.url.encodedPath)
                assertEquals("eq.online", request.url.parameters["source"])
                assertEquals("played_at.desc,id.desc", request.url.parameters["order"])
                respond("[]", headers = jsonHeaders)
            }
        })
        try {
            client.auth.loadFromStorage(autoRefresh = false)
            val reader = SupabaseReader(SupabaseAuthRepository(client), Network())
            val query = listOf("source" to "eq.online", "order" to "played_at.desc,id.desc")
            assertEquals("[]", reader.get(account, "plays", query))
            assertEquals("[]", reader.get(account, "plays", query))
            assertEquals(listOf("/auth/v1/user", "/rest/v1/plays", "/auth/v1/user", "/rest/v1/plays"), paths)
        } finally { client.close() }
    }

    @Test fun `different verified account is rejected before touching data`() = runBlocking {
        var userChecks = 0
        var dataReads = 0
        val client = client(MemorySessionManager(session()), MockEngine { request ->
            if (request.url.encodedPath == "/auth/v1/user") { userChecks++; respond(user, headers = jsonHeaders) }
            else { dataReads++; respond("[]", headers = jsonHeaders) }
        })
        try {
            client.auth.loadFromStorage(autoRefresh = false)
            val reader = SupabaseReader(SupabaseAuthRepository(client), Network())
            expectAuth(AuthProblem.INVALID_CREDENTIALS) { reader.get(UUID.randomUUID(), "plays", emptyList()) }
            assertEquals(1, userChecks)
            assertEquals(0, dataReads)
        } finally { client.close() }
    }

    @Test fun `one unauthorized data response refreshes once verifies user and retries with new token`() = runBlocking {
        var reads = 0
        var refreshes = 0
        val paths = mutableListOf<String>()
        val manager = MemorySessionManager(session())
        val client = client(manager, MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/auth/v1/user" -> respond(user, headers = jsonHeaders)
                "/auth/v1/token" -> {
                    assertEquals("refresh_token", request.url.parameters["grant_type"])
                    refreshes++
                    respond(refreshedSession(), headers = jsonHeaders)
                }
                "/rest/v1/plays" -> {
                    reads++
                    if (reads == 1) {
                        assertEquals("Bearer user-access", request.headers[HttpHeaders.Authorization])
                        respond("{}", HttpStatusCode.Unauthorized, jsonHeaders)
                    } else {
                        assertEquals("Bearer refreshed-access", request.headers[HttpHeaders.Authorization])
                        respond("[]", headers = jsonHeaders)
                    }
                }
                else -> error("Unexpected endpoint")
            }
        })
        try {
            client.auth.loadFromStorage(autoRefresh = false)
            assertEquals("[]", SupabaseReader(SupabaseAuthRepository(client), Network()).get(account, "plays", emptyList()))
            assertEquals(1, refreshes)
            assertEquals(2, reads)
            assertEquals(listOf("/auth/v1/user", "/rest/v1/plays", "/auth/v1/token", "/auth/v1/user", "/rest/v1/plays"), paths)
            assertEquals("refreshed-refresh", manager.loadSession()!!.refreshToken)
            assertFalse(client.auth.isAutoRefreshRunning)
        } finally { client.close() }
    }

    @Test fun `second unauthorized data response clears session without another refresh`() = runBlocking {
        var refreshes = 0
        var reads = 0
        val manager = MemorySessionManager(session())
        val client = client(manager, MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/v1/user" -> respond(user, headers = jsonHeaders)
                "/auth/v1/token" -> { refreshes++; respond(refreshedSession(), headers = jsonHeaders) }
                else -> { reads++; respond("{}", HttpStatusCode.Unauthorized, jsonHeaders) }
            }
        })
        try {
            client.auth.loadFromStorage(autoRefresh = false)
            expectAuth(AuthProblem.INVALID_CREDENTIALS) { SupabaseReader(SupabaseAuthRepository(client), Network()).get(account, "plays", emptyList()) }
            assertEquals(1, refreshes)
            assertEquals(2, reads)
            assertNull(manager.loadSession())
            assertNull(client.auth.currentSessionOrNull())
        } finally { client.close() }
    }

    @Test fun `refresh used by user verification is not repeated when data then rejects token`() = runBlocking {
        var userChecks = 0
        var refreshes = 0
        var reads = 0
        val manager = MemorySessionManager(session())
        val client = client(manager, MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/v1/user" -> {
                    userChecks++
                    if (userChecks == 1) respond("{}", HttpStatusCode.Unauthorized, jsonHeaders)
                    else respond(user, headers = jsonHeaders)
                }
                "/auth/v1/token" -> { refreshes++; respond(refreshedSession(), headers = jsonHeaders) }
                else -> { reads++; respond("{}", HttpStatusCode.Unauthorized, jsonHeaders) }
            }
        })
        try {
            client.auth.loadFromStorage(autoRefresh = false)
            expectAuth(AuthProblem.INVALID_CREDENTIALS) { SupabaseReader(SupabaseAuthRepository(client), Network()).get(account, "plays", emptyList()) }
            assertEquals(1, refreshes)
            assertEquals(1, reads)
            assertNull(manager.loadSession())
        } finally { client.close() }
    }

    @Test fun `forbidden data response preserves valid session and does not refresh or logout`() = runBlocking {
        val paths = mutableListOf<String>()
        val manager = MemorySessionManager(session())
        val client = client(manager, MockEngine { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath == "/auth/v1/user") respond(user, headers = jsonHeaders)
            else respond("{\"secret\":\"private-server-body\"}", HttpStatusCode.Forbidden, jsonHeaders)
        })
        try {
            client.auth.loadFromStorage(autoRefresh = false)
            try {
                SupabaseReader(SupabaseAuthRepository(client), Network()).get(account, "plays", emptyList())
                fail("Expected forbidden")
            } catch (failure: DataFailure) {
                assertEquals(DataProblem.FORBIDDEN, failure.problem)
                assertFalse(failure.message!!.contains("private-server-body"))
            }
            assertEquals(listOf("/auth/v1/user", "/rest/v1/plays"), paths)
            assertNotNull(manager.loadSession())
            assertNotNull(client.auth.currentSessionOrNull())
        } finally { client.close() }
    }

    @Test fun `disconnect during data request never exposes the returned body`() = runBlocking {
        val network = Network()
        val client = client(MemorySessionManager(session()), MockEngine { request ->
            if (request.url.encodedPath == "/auth/v1/user") respond(user, headers = jsonHeaders)
            else {
                network.connected.value = false
                respond("[\"must-not-be-shown\"]", headers = jsonHeaders)
            }
        })
        try {
            client.auth.loadFromStorage(autoRefresh = false)
            expectAuth(AuthProblem.NETWORK_UNAVAILABLE) { SupabaseReader(SupabaseAuthRepository(client), network).get(account, "plays", emptyList()) }
        } finally { client.close() }
    }

    private suspend fun expectAuth(problem: AuthProblem, action: suspend () -> Any?) {
        try { action(); fail("Expected $problem") } catch (failure: AuthFailure) { assertEquals(problem, failure.problem) }
    }
    private fun refreshedSession() = """{"access_token":"refreshed-access","refresh_token":"refreshed-refresh","expires_in":3600,"token_type":"bearer","user":$user}"""
    private fun client(manager: MemorySessionManager, engine: MockEngine): SupabaseClient = createSupabaseClient("https://example.supabase.co", "public-test-key") {
        httpEngine = engine
        defaultLogLevel = LogLevel.NONE
        install(Auth) {
            autoLoadFromStorage = false
            alwaysAutoRefresh = false
            enableLifecycleCallbacks = false
            sessionManager = manager
            codeVerifierCache = MemoryCodeVerifierCache()
        }
    }
}
