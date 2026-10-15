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
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class SupabaseEloPublicationTest {
    private class Network : NetworkMonitor { override val connected = MutableStateFlow(true) }
    private fun fixture(action: suspend (SupabaseAuthRepository, Network) -> Unit) = runBlocking {
        val actor = EloPublicationFixture.actor
        val user = """{"id":"$actor","aud":"authenticated","app_metadata":{}}"""
        val client = createSupabaseClient("https://example.test", "public-test-key") {
            httpEngine = MockEngine { respond(user, headers = headersOf(HttpHeaders.ContentType, "application/json")) }
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
    @Test fun `ordinary verified account uses only publication RPC with empty arguments and bearer header`() = fixture { auth, network ->
        val requests = mutableListOf<Request>()
        val body = EloPublicationFixture.snapshot().toString()
        val source = SupabaseEloPublication(auth, network, CommentTransport { requests += it; CommentHttpResponse(200, body) })
        assertEquals(body, source.read(EloPublicationFixture.actor))
        source.verify(EloPublicationFixture.actor)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("/rest/v1/rpc/get_elo_publication_v1", request.url.encodedPath)
        assertNull(request.url.query); assertEquals("POST", request.method)
        assertEquals("Bearer user-access", request.header("Authorization"))
        assertEquals("public-test-key", request.header("apikey"))
        assertEquals("{}", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        source.close()
    }
    @Test fun `offline wrong account and forbidden response cannot reveal a publication`() = fixture { auth, network ->
        var calls = 0
        val source = SupabaseEloPublication(auth, network, CommentTransport { calls++; CommentHttpResponse(403, "") })
        network.connected.value = false
        try { source.read(EloPublicationFixture.actor); fail() } catch (failure: AuthFailure) { assertEquals(AuthProblem.NETWORK_UNAVAILABLE, failure.problem) }
        network.connected.value = true
        try { source.read(UUID(0, 888)); fail() } catch (failure: AuthFailure) { assertEquals(AuthProblem.INVALID_CREDENTIALS, failure.problem) }
        assertEquals(0, calls)
        try { source.read(EloPublicationFixture.actor); fail() } catch (failure: DataFailure) { assertEquals(DataProblem.FORBIDDEN, failure.problem) }
        assertEquals(1, calls)
        source.close()
    }
}
