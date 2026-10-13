package app.kingscore.android.core.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.bearerAuth
import io.ktor.http.HttpMethod
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class SupabaseAuthRepository(private val client: SupabaseClient) : AuthRepository {
    private val mutex = Mutex()
    override val invalidations: Flow<AuthProblem> = client.auth.sessionStatus.mapNotNull { status ->
        when (status) {
            is SessionStatus.NotAuthenticated -> AuthProblem.INVALID_CREDENTIALS
            is SessionStatus.RefreshFailure -> AuthProblem.BACKEND_UNAVAILABLE
            else -> null
        }
    }

    override suspend fun restoreVerifiedAccount(): Account? = mutex.withLock {
        translateFailures {
            if (client.auth.currentSessionOrNull() == null && !client.auth.loadFromStorage(autoRefresh = false)) return@translateFailures null
            verifyCurrentSession()
        }
    }

    override suspend fun signIn(email: String, password: String): Account = mutex.withLock {
        translateFailures {
            client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            client.auth.stopAutoRefreshForCurrentSession()
            verifyCurrentSession() ?: throw AuthFailure(AuthProblem.INVALID_CREDENTIALS)
        }
    }

    private suspend fun verifyCurrentSession(): Account? {
        val session = client.auth.currentSessionOrNull() ?: return null
        var refreshed = false
        try {
            if (session.expiresAt.epochSeconds <= System.currentTimeMillis() / 1000 + 30) {
                refreshed = true
                client.auth.refreshCurrentSession()
                client.auth.stopAutoRefreshForCurrentSession()
            }
            return client.auth.retrieveUserForCurrentSession(updateSession = true).toAccount()
        } catch (failure: RestException) {
            if (failure.statusCode !in listOf(400, 401, 403)) throw failure
            if (refreshed) {
                withContext(NonCancellable) { client.auth.clearSession() }
                return null
            }
            // A revoked/expired access token gets one refresh attempt, never a loop.
            try {
                client.auth.refreshCurrentSession()
                client.auth.stopAutoRefreshForCurrentSession()
                return client.auth.retrieveUserForCurrentSession(updateSession = true).toAccount()
            } catch (refreshFailure: RestException) {
                if (refreshFailure.statusCode !in listOf(400, 401, 403)) throw refreshFailure
                withContext(NonCancellable) { client.auth.clearSession() }
                return null
            }
        }
    }

    override suspend fun signOut() = mutex.withLock {
        translateFailures {
            val token = client.auth.currentAccessTokenOrNull()
            // Clear disk and memory before a possibly failing/revoked remote logout.
            withContext(NonCancellable) { client.auth.clearSession() }
            if (token != null) {
                val response = client.httpClient.request("${client.supabaseHttpUrl}/auth/v1/logout?scope=local") {
                    method = HttpMethod.Post
                    bearerAuth(token)
                }
                if (response.status.value !in 200..299 && response.status.value !in listOf(401, 403)) {
                    throw AuthFailure(AuthProblem.BACKEND_UNAVAILABLE)
                }
            }
        }
    }

    private fun UserInfo.toAccount(): Account = Account(
        id = UUID.fromString(id),
        name = userMetadata?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: email?.substringBefore('@') ?: "Giocatore",
        email = email,
        isAdmin = appMetadata?.get("role")?.jsonPrimitive?.contentOrNull == "admin",
    )

    private suspend fun <T> translateFailures(action: suspend () -> T): T = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: AuthFailure) {
        throw failure
    } catch (failure: RestException) {
        // Never expose SDK exception messages: they can contain Authorization headers.
        throw AuthFailure(if (failure.statusCode in listOf(400, 401, 403)) AuthProblem.INVALID_CREDENTIALS else AuthProblem.BACKEND_UNAVAILABLE)
    } catch (_: HttpRequestException) {
        throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE)
    } catch (_: HttpRequestTimeoutException) {
        throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE)
    } catch (_: IOException) {
        throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE)
    } catch (_: GeneralSecurityException) {
        throw AuthFailure(AuthProblem.LOCAL_STORAGE)
    } catch (_: SerializationException) {
        throw AuthFailure(AuthProblem.UNEXPECTED_RESPONSE)
    } catch (_: IllegalArgumentException) {
        throw AuthFailure(AuthProblem.UNEXPECTED_RESPONSE)
    } catch (_: IllegalStateException) {
        throw AuthFailure(AuthProblem.LOCAL_STORAGE)
    }
}
