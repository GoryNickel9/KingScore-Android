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
import kotlinx.serialization.json.*
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class SummaryRpcTest {
    private class Network : NetworkMonitor { override val connected=MutableStateFlow(true) }
    private fun fixture(action:suspend(SupabaseAuthRepository,Network)->Unit)=runBlocking {
        val client=createSupabaseClient("https://example.test","public-test-key") {
            httpEngine=MockEngine { request ->
                if (request.url.encodedPath.endsWith("/token")) respond("""{"message":"Invalid refresh token"}""",HttpStatusCode.Unauthorized,
                    headers=headersOf(HttpHeaders.ContentType,"application/json"))
                else respond("""{"id":"${SummaryFixture.actor}","aud":"authenticated","app_metadata":{}}""",
                    headers=headersOf(HttpHeaders.ContentType,"application/json")) }
            defaultLogLevel=LogLevel.NONE
            install(Auth) { autoLoadFromStorage=false;alwaysAutoRefresh=false;enableLifecycleCallbacks=false
                sessionManager=MemorySessionManager(UserSession("user-access","user-refresh",expiresIn=3600,tokenType="bearer"))
                codeVerifierCache=MemoryCodeVerifierCache() }
        }
        try { client.auth.loadFromStorage(autoRefresh=false);action(SupabaseAuthRepository(client),Network()) }
        finally { client.close() }
    }
    @Test fun `summary requests verify account bearer body key and read-only allowlist`()=fixture { auth,network ->
        val calls=mutableListOf<Request>()
        val rpc=SupabaseSummaryRpc(auth,network,SummaryFixture.actor,CommentTransport { calls+=it;CommentHttpResponse(200,SummaryFixture.home().toString()) })
        val body=buildJsonObject { put("p_month","2026-10-01") }
        rpc.read("get_app_summary_v1",body)
        val request=calls.single();assertEquals("POST",request.method);assertEquals("/rest/v1/rpc/get_app_summary_v1",request.url.encodedPath)
        assertEquals("Bearer user-access",request.header("Authorization"));assertEquals("public-test-key",request.header("apikey"))
        assertEquals(body.toString(),Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        try { rpc.read("publish_elo_v1",body);fail() } catch(_:IllegalArgumentException) { }
        assertEquals(1,calls.size);rpc.close()
    }
    @Test fun `offline wrong account and lost Internet after response reveal no data`()=fixture { auth,network ->
        var calls=0
        val rpc=SupabaseSummaryRpc(auth,network,SummaryFixture.actor,CommentTransport { calls++;network.connected.value=false;CommentHttpResponse(200,"{}") })
        network.connected.value=false
        try { rpc.read("get_app_summary_v1",buildJsonObject {});fail() } catch(f:AuthFailure) { assertEquals(AuthProblem.NETWORK_UNAVAILABLE,f.problem) }
        assertEquals(0,calls);network.connected.value=true
        val wrong=SupabaseSummaryRpc(auth,network,UUID(0,999),CommentTransport { error("Wrong account reached transport") })
        try { wrong.read("get_app_summary_v1",buildJsonObject {});fail() } catch(f:AuthFailure) { assertEquals(AuthProblem.INVALID_CREDENTIALS,f.problem) }
        try { rpc.read("get_app_summary_v1",buildJsonObject {});fail() } catch(f:AuthFailure) { assertEquals(AuthProblem.NETWORK_UNAVAILABLE,f.problem) }
        assertEquals(1,calls);rpc.close();wrong.close()
    }
    @Test fun `permission version selection and server failures remain distinct without retries`()=fixture { auth,network ->
        var status=403;var calls=0
        val rpc=SupabaseSummaryRpc(auth,network,SummaryFixture.actor,CommentTransport { calls++;CommentHttpResponse(status,"") })
        for((code,problem) in listOf(403 to DataProblem.FORBIDDEN,409 to DataProblem.REVISION_CHANGED,422 to DataProblem.SELECTION_REQUIRED,400 to DataProblem.INVALID_RESPONSE)) {
            status=code;try { rpc.read("get_elo_chart_v1",buildJsonObject {});fail() } catch(f:DataFailure) { assertEquals(problem,f.problem) }
        }
        for((code,problem) in listOf(503 to AuthProblem.BACKEND_UNAVAILABLE,401 to AuthProblem.INVALID_CREDENTIALS)) {
            status=code;try { rpc.read("get_elo_chart_v1",buildJsonObject {});fail() } catch(f:AuthFailure) { assertEquals(problem,f.problem) }
        }
        assertEquals(6,calls);rpc.close()
    }
}
