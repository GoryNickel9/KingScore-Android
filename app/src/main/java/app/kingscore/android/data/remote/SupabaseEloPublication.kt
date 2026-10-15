package app.kingscore.android.data.remote

import app.kingscore.android.core.auth.*
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.repository.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal interface EloPublicationSource : AutoCloseable {
    suspend fun read(account: UUID): String
    suspend fun verify(account: UUID)
    override fun close() {}
}

/** The only ratings request is an authenticated, bounded read RPC; no calculator or cache writes. */
internal class SupabaseEloPublication(private val auth: SupabaseAuthRepository, private val network: NetworkMonitor,
    private val transport: CommentTransport = NoRetryCommentTransport(captureErrorCode = true, maxSuccessBytes = { 16L * 1048576 }),
) : EloPublicationSource {
    override suspend fun verify(account: UUID) {
        checkNetwork()
        auth.withVerifiedSession(account) { _, _ -> checkNetwork() }
    }
    override suspend fun read(account: UUID): String {
        checkNetwork()
        return auth.withVerifiedSession(account) { client, token ->
            checkNetwork()
            val request = Request.Builder().url("${client.supabaseHttpUrl}/rest/v1/rpc/get_elo_publication_v1")
                .header("Authorization", "Bearer $token").header("apikey", client.supabaseKey)
                .header("Accept", "application/json").post("{}".toRequestBody("application/json".toMediaType())).build()
            val response = try { transport.send(request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: CommentFailure) { throw DataFailure(DataProblem.INVALID_RESPONSE) }
            checkNetwork()
            when (response.status) {
                200 -> response.body
                401 -> throw AuthFailure(AuthProblem.INVALID_CREDENTIALS)
                403 -> throw DataFailure(DataProblem.FORBIDDEN)
                in 500..599 -> throw AuthFailure(AuthProblem.BACKEND_UNAVAILABLE)
                else -> throw DataFailure(DataProblem.INVALID_RESPONSE)
            }
        }
    }
    private fun checkNetwork() { if (!network.connected.value) throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE) }
    override fun close() = transport.close()
}
