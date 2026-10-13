package app.kingscore.android.core.auth

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class Account(
    val id: UUID,
    val name: String,
    val email: String?,
    val isAdmin: Boolean,
)

enum class AuthProblem { INVALID_CREDENTIALS, BACKEND_UNAVAILABLE, NETWORK_UNAVAILABLE, LOCAL_STORAGE, UNEXPECTED_RESPONSE }
class AuthFailure(val problem: AuthProblem) : Exception(problem.name)

/** Exposes only the account operations permitted by the Android product. */
interface AuthRepository {
    val invalidations: Flow<AuthProblem> get() = emptyFlow()
    suspend fun restoreVerifiedAccount(): Account?
    suspend fun signIn(email: String, password: String): Account
    suspend fun signOut()
}
