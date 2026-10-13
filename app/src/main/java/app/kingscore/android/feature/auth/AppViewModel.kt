package app.kingscore.android.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kingscore.android.core.auth.Account
import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.core.auth.AuthRepository
import app.kingscore.android.core.network.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface AccessState {
    data object Checking : AccessState
    data class SignedOut(val problem: AuthProblem? = null) : AccessState
    data class Connected(val account: Account) : AccessState
    data class Blocked(val problem: AuthProblem) : AccessState
}

class AppViewModel(
    private val auth: AuthRepository,
    private val network: NetworkMonitor,
) : ViewModel() {
    private val state = MutableStateFlow<AccessState>(AccessState.Checking)
    val access: StateFlow<AccessState> = state
    private var operation: Job? = null
    private var logout: Job? = null
    private var logoutFailed = false

    init {
        viewModelScope.launch {
            network.connected.collect { connected ->
                if (connected) recheck() else {
                    operation?.cancel()
                    state.value = AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE)
                }
            }
        }
        viewModelScope.launch {
            auth.invalidations.collect { problem ->
                if (state.value is AccessState.Connected) {
                    state.value = if (problem == AuthProblem.INVALID_CREDENTIALS) AccessState.SignedOut()
                        else AccessState.Blocked(problem)
                }
            }
        }
    }

    fun recheck() {
        if (!network.connected.value || operation?.isActive == true || logout?.isActive == true || logoutFailed) return
        runOperation {
            val account = auth.restoreVerifiedAccount()
            state.value = account?.let(AccessState::Connected) ?: AccessState.SignedOut()
        }
    }

    fun onForeground() {
        // Returning from a password manager must keep the login form mounted.
        if (state.value !is AccessState.SignedOut) recheck()
    }

    fun verifyActiveSession() {
        if (state.value is AccessState.Connected) recheck()
    }

    fun signIn(email: String, password: String) {
        if (!network.connected.value || operation?.isActive == true || logout?.isActive == true) return
        logoutFailed = false
        if (email.trim().isEmpty() || password.isEmpty()) {
            state.value = AccessState.SignedOut(AuthProblem.INVALID_CREDENTIALS)
            return
        }
        runOperation { state.value = AccessState.Connected(auth.signIn(email.trim(), password)) }
    }

    fun signOut() {
        if (logout?.isActive == true) return
        logoutFailed = false
        operation?.cancel()
        // Immediately remove the authenticated navigation tree before network work.
        state.value = if (network.connected.value) AccessState.SignedOut() else AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE)
        logout = viewModelScope.launch {
            try {
                auth.signOut()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AuthFailure) {
                if (failure.problem == AuthProblem.LOCAL_STORAGE) {
                    logoutFailed = true
                    state.value = AccessState.Blocked(AuthProblem.LOCAL_STORAGE)
                }
                // Remote logout errors do not restore an already cleared local session.
            } finally {
                if (!logoutFailed) {
                    state.value = if (network.connected.value) AccessState.SignedOut()
                        else AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE)
                }
            }
        }
    }

    fun retry() {
        if (logoutFailed) signOut() else recheck()
    }

    private fun runOperation(action: suspend () -> Unit) {
        operation = viewModelScope.launch {
            state.value = AccessState.Checking
            try {
                action()
                if (!network.connected.value) state.value = AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AuthFailure) {
                state.value = when (failure.problem) {
                    AuthProblem.INVALID_CREDENTIALS -> AccessState.SignedOut(failure.problem)
                    else -> AccessState.Blocked(failure.problem)
                }
            }
        }
    }
}
