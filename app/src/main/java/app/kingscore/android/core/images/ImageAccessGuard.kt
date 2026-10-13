package app.kingscore.android.core.images

import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.core.network.NetworkMonitor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

internal class ImageAccessGuard(private val network: NetworkMonitor,
    private val verifyAccount: suspend () -> Unit, private val onAccessFailure: suspend (AuthProblem) -> Unit) {
    private val closed = AtomicBoolean(false)

    suspend fun <T> checked(load: suspend () -> T): T {
        try {
            checkAccess()
            verifyAccount()
            checkAccess()
            return load().also { checkAccess() }
        } catch (failure: AuthFailure) {
            if (!closed.get()) onAccessFailure(failure.problem)
            throw failure
        }
    }

    fun close() { closed.set(true) }

    fun checkOpen() {
        if (closed.get()) throw CancellationException("Image session closed")
    }

    fun checkAccess() {
        checkOpen()
        if (!network.connected.value) throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE)
    }
}
