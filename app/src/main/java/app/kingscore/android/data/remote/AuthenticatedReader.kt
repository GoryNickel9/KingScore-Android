package app.kingscore.android.data.remote

import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.core.auth.SupabaseAuthRepository
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.repository.DataFailure
import app.kingscore.android.data.repository.DataProblem
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.util.UUID

fun interface AuthenticatedReader {
    suspend fun get(accountId: UUID, resource: String, parameters: List<Pair<String, String>>): String
}

class SupabaseReader(private val auth: SupabaseAuthRepository, private val network: NetworkMonitor) : AuthenticatedReader {
    override suspend fun get(accountId: UUID, resource: String, parameters: List<Pair<String, String>>): String {
        checkNetwork()
        require(resource in setOf("plays", "play_comments"))
        return auth.withVerifiedSession(accountId) { client, token ->
            checkNetwork()
            val response = client.httpClient.get("${client.supabaseHttpUrl}/rest/v1/$resource") {
                bearerAuth(token)
                url { parameters.forEach { (key, value) -> this.parameters.append(key, value) } }
            }
            checkNetwork()
            when (response.status.value) {
                in 200..299 -> response.bodyAsText().also { checkNetwork() }
                401 -> throw AuthFailure(AuthProblem.INVALID_CREDENTIALS)
                403 -> throw DataFailure(DataProblem.FORBIDDEN)
                in 500..599 -> throw AuthFailure(AuthProblem.BACKEND_UNAVAILABLE)
                else -> throw DataFailure(DataProblem.INVALID_RESPONSE)
            }
        }
    }

    private fun checkNetwork() {
        if (!network.connected.value) throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE)
    }
}
