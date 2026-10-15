package app.kingscore.android.data.remote

import app.kingscore.android.core.auth.*
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.repository.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal fun interface SummaryRpc {
    suspend fun read(name: String, body: JsonObject): String
}

internal class SupabaseSummaryRpc(private val auth: SupabaseAuthRepository, private val network: NetworkMonitor,
    private val account: UUID,
    private val transport: CommentTransport = NoRetryCommentTransport(captureErrorCode = true, maxSuccessBytes = { 4L * 1048576 }),
) : SummaryRpc, AutoCloseable {
    override suspend fun read(name: String, body: JsonObject): String {
        require(name in setOf("get_app_summary_v1", "get_statistics_overview_v1", "get_game_statistics_v1", "get_elo_chart_v1"))
        checkNetwork()
        return auth.withVerifiedSession(account) { client, token ->
            checkNetwork()
            val request = Request.Builder().url("${client.supabaseHttpUrl}/rest/v1/rpc/$name")
                .header("Authorization", "Bearer $token").header("apikey", client.supabaseKey)
                .header("Accept", "application/json").post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val response = try { transport.send(request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: CommentFailure) { throw DataFailure(DataProblem.INVALID_RESPONSE) }
            checkNetwork()
            when (response.status) {
                200 -> response.body
                401 -> throw AuthFailure(AuthProblem.INVALID_CREDENTIALS)
                403 -> throw DataFailure(DataProblem.FORBIDDEN)
                409 -> throw DataFailure(DataProblem.REVISION_CHANGED)
                422 -> throw DataFailure(if (name == "get_elo_chart_v1") DataProblem.SELECTION_REQUIRED else DataProblem.INVALID_RESPONSE)
                in 500..599 -> throw AuthFailure(AuthProblem.BACKEND_UNAVAILABLE)
                else -> throw DataFailure(DataProblem.INVALID_RESPONSE)
            }
        }
    }
    private fun checkNetwork() { if (!network.connected.value) throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE) }
    override fun close() = transport.close()
}
