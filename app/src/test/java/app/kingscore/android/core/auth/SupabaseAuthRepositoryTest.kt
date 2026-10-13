package app.kingscore.android.core.auth

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class SupabaseAuthRepositoryTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val user = """{"id":"$id","aud":"authenticated","email":"test@example.com","app_metadata":{"role":"admin"},"user_metadata":{"name":"Test"}}"""
    private fun session() = UserSession("test-access", "test-refresh", expiresIn = 3600, tokenType = "bearer")

    @Test fun `password login uses only the existing-account endpoint and checks server role`() = runBlocking {
        val manager = MemorySessionManager()
        val ordinary = user.replace("\"role\":\"admin\"", "\"role\":\"user\"")
        val paths = mutableListOf<String>()
        val client = client(manager, MockEngine { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath == "/auth/v1/token") {
                assertEquals("password", request.url.parameters["grant_type"])
                respond("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"token_type":"bearer","user":$ordinary}""", headers = jsonHeaders())
            } else respond(ordinary, headers = jsonHeaders())
        })
        try {
            val result = SupabaseAuthRepository(client).signIn("test@example.com", "test-password")
            assertFalse(result.isAdmin)
            assertEquals(listOf("/auth/v1/token", "/auth/v1/user"), paths)
            assertNotNull(manager.loadSession())
        } finally { client.close() }
    }

    @Test fun `restore verifies the current user online before returning its role`() = runBlocking {
        var requests = 0
        val manager = MemorySessionManager(session())
        val client = client(manager, MockEngine { request ->
            assertEquals("/auth/v1/user", request.url.encodedPath)
            assertEquals("Bearer test-access", request.headers[HttpHeaders.Authorization])
            requests++
            respond(user, headers = jsonHeaders())
        })
        try {
            val account = SupabaseAuthRepository(client).restoreVerifiedAccount()!!
            assertEquals(UUID.fromString(id), account.id)
            assertTrue(account.isAdmin)
            assertEquals(1, requests)
        } finally { client.close() }
    }

    @Test fun `expired or revoked access token gets one refresh and a new user check`() = runBlocking {
        var userRequests = 0
        var refreshRequests = 0
        val manager = MemorySessionManager(session())
        val client = client(manager, MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/v1/user" -> {
                    userRequests++
                    if (userRequests == 1) respond("""{"msg":"expired"}""", HttpStatusCode.Unauthorized, jsonHeaders())
                    else respond(user, headers = jsonHeaders())
                }
                "/auth/v1/token" -> {
                    assertEquals("refresh_token", request.url.parameters["grant_type"])
                    refreshRequests++
                    respond("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"token_type":"bearer","user":$user}""", headers = jsonHeaders())
                }
                else -> error("Unexpected endpoint")
            }
        })
        try {
            assertNotNull(SupabaseAuthRepository(client).restoreVerifiedAccount())
            assertEquals(1, refreshRequests)
            assertEquals(2, userRequests)
            assertEquals("new-refresh", manager.loadSession()!!.refreshToken)
        } finally { client.close() }
    }

    @Test fun `rejected refresh clears persistent session instead of restoring cached identity`() = runBlocking {
        val manager = MemorySessionManager(session())
        val client = client(manager, MockEngine { request ->
            if (request.url.encodedPath == "/auth/v1/user") respond("""{"msg":"revoked"}""", HttpStatusCode.Unauthorized, jsonHeaders())
            else respond("""{"msg":"invalid refresh"}""", HttpStatusCode.BadRequest, jsonHeaders())
        })
        try {
            assertNull(SupabaseAuthRepository(client).restoreVerifiedAccount())
            assertNull(manager.loadSession())
        } finally { client.close() }
    }

    @Test fun `backend outage retains the token but never returns a cached user`() = runBlocking {
        val manager = MemorySessionManager(session())
        val client = client(manager, MockEngine {
            respond("""{"msg":"unavailable"}""", HttpStatusCode.ServiceUnavailable, jsonHeaders())
        })
        try {
            try {
                SupabaseAuthRepository(client).restoreVerifiedAccount()
                fail("Expected backend failure")
            } catch (failure: AuthFailure) {
                assertEquals(AuthProblem.BACKEND_UNAVAILABLE, failure.problem)
                assertFalse(failure.message!!.contains("test-access"))
            }
            assertNotNull(manager.loadSession())
        } finally { client.close() }
    }

    @Test fun `logout clears persistent session before sending the remote request`() = runBlocking {
        val manager = MemorySessionManager(session())
        val client = client(manager, MockEngine { request ->
            if (request.url.encodedPath == "/auth/v1/user") respond(user, headers = jsonHeaders()) else {
                assertEquals("/auth/v1/logout", request.url.encodedPath)
                assertEquals("local", request.url.parameters["scope"])
                assertNull(manager.loadSession())
                respond("", HttpStatusCode.NoContent)
            }
        })
        try {
            val repository = SupabaseAuthRepository(client)
            repository.restoreVerifiedAccount()
            repository.signOut()
            assertNull(manager.loadSession())
        } finally { client.close() }
    }

    @Test fun `cancelling logout cannot interrupt local credential erasure`() = runBlocking {
        val backing = MemorySessionManager(session())
        val deleting = CompletableDeferred<Unit>()
        val allowDelete = CompletableDeferred<Unit>()
        val manager = object : SessionManager {
            override suspend fun saveSession(session: UserSession) = backing.saveSession(session)
            override suspend fun loadSession() = backing.loadSession()
            override suspend fun deleteSession() {
                deleting.complete(Unit)
                allowDelete.await()
                backing.deleteSession()
            }
        }
        val client = client(manager, MockEngine { respond(user, headers = jsonHeaders()) })
        try {
            val repository = SupabaseAuthRepository(client)
            repository.restoreVerifiedAccount()
            val logout = launch { repository.signOut() }
            deleting.await()
            logout.cancel()
            allowDelete.complete(Unit)
            logout.join()
            assertNull(backing.loadSession())
        } finally { client.close() }
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")
    @Test fun `failed remote logout never restores the cleared local session`() = runBlocking {
        val manager = MemorySessionManager(session())
        val client = client(manager, MockEngine { request ->
            if (request.url.encodedPath == "/auth/v1/user") respond(user, headers = jsonHeaders())
            else respond("", HttpStatusCode.ServiceUnavailable)
        })
        try {
            val repository = SupabaseAuthRepository(client)
            repository.restoreVerifiedAccount()
            try {
                repository.signOut()
                fail("Expected remote revocation failure")
            } catch (failure: AuthFailure) {
                assertEquals(AuthProblem.BACKEND_UNAVAILABLE, failure.problem)
            }
            assertNull(manager.loadSession())
        } finally { client.close() }
    }
    private fun client(manager: SessionManager, engine: MockEngine) = createSupabaseClient("https://example.supabase.co", "public-test-key") {
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
