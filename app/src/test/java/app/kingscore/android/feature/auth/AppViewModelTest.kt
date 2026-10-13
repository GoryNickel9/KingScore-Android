package app.kingscore.android.feature.auth

import app.kingscore.android.core.auth.Account
import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.core.auth.AuthRepository
import app.kingscore.android.core.network.NetworkMonitor
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val network = FakeNetwork()
    private val repository = FakeAuth()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `offline startup never restores cached account`() = runTest(dispatcher) {
        network.connected.value = false
        repository.account = account()
        val model = AppViewModel(repository, network)
        advanceUntilIdle()
        assertTrue(model.access.value is AccessState.Blocked)
        assertEquals(0, repository.restores)
    }

    @Test fun `restored account is shown only after server verification`() = runTest(dispatcher) {
        val validation = CompletableDeferred<Account?>()
        repository.restoreWait = validation
        val model = AppViewModel(repository, network)
        advanceUntilIdle()
        assertEquals(AccessState.Checking, model.access.value)
        validation.complete(account())
        advanceUntilIdle()
        assertTrue(model.access.value is AccessState.Connected)
    }

    @Test fun `disconnect hides account and reconnect revalidates it`() = runTest(dispatcher) {
        repository.account = account()
        val model = AppViewModel(repository, network)
        advanceUntilIdle()
        assertTrue(model.access.value is AccessState.Connected)
        network.connected.value = false
        advanceUntilIdle()
        assertTrue(model.access.value is AccessState.Blocked)
        network.connected.value = true
        advanceUntilIdle()
        assertEquals(2, repository.restores)
        assertTrue(model.access.value is AccessState.Connected)
    }

    @Test fun `bad credentials remain on login and never open data`() = runTest(dispatcher) {
        repository.failure = AuthProblem.INVALID_CREDENTIALS
        val model = AppViewModel(repository, network)
        advanceUntilIdle()
        model.signIn("user@example.com", "wrong")
        advanceUntilIdle()
        assertEquals(AccessState.SignedOut(AuthProblem.INVALID_CREDENTIALS), model.access.value)
    }

    @Test fun `foreground and periodic checks preserve the signed out login form`() = runTest(dispatcher) {
        val model = AppViewModel(repository, network)
        advanceUntilIdle()
        val restores = repository.restores
        model.onForeground()
        model.verifyActiveSession()
        advanceUntilIdle()
        assertEquals(restores, repository.restores)
        assertEquals(AccessState.SignedOut(), model.access.value)
    }

    @Test fun `foreground and periodic checks validate an active session`() = runTest(dispatcher) {
        repository.account = account()
        val model = AppViewModel(repository, network)
        advanceUntilIdle()
        model.onForeground()
        advanceUntilIdle()
        model.verifyActiveSession()
        advanceUntilIdle()
        assertEquals(3, repository.restores)
        assertTrue(model.access.value is AccessState.Connected)
    }

    @Test fun `backend failure blocks authenticated navigation`() = runTest(dispatcher) {
        repository.failure = AuthProblem.BACKEND_UNAVAILABLE
        val model = AppViewModel(repository, network)
        advanceUntilIdle()
        assertEquals(AccessState.Blocked(AuthProblem.BACKEND_UNAVAILABLE), model.access.value)
    }

    @Test fun `periodic verification keeps navigation mounted until rejection`() = runTest(dispatcher) {
        repository.account = account()
        val model = AppViewModel(repository, network)
        advanceUntilIdle()
        val pending = CompletableDeferred<Account?>()
        repository.restoreWait = pending
        model.verifyActiveSession()
        runCurrent()
        assertTrue(model.access.value is AccessState.Connected)
        pending.complete(null)
        advanceUntilIdle()
        assertEquals(AccessState.SignedOut(), model.access.value)
    }

    @Test fun `data request failure cancels pending verification and blocks access`() = runTest(dispatcher) {
        repository.account = account()
        val model = AppViewModel(repository, network)
        advanceUntilIdle()
        repository.restoreWait = CompletableDeferred()
        model.verifyActiveSession()
        runCurrent()
        model.rejectAccess(AuthProblem.BACKEND_UNAVAILABLE)
        advanceUntilIdle()
        assertEquals(AccessState.Blocked(AuthProblem.BACKEND_UNAVAILABLE), model.access.value)
    }

    @Test fun `logout is not cancelled by network loss and cannot restore an old account`() = runTest(dispatcher) {
        repository.account = account()
        repository.logoutWait = CompletableDeferred()
        val model = AppViewModel(repository, network)
        advanceUntilIdle()
        model.signOut()
        advanceUntilIdle()
        assertNull(repository.account)
        network.connected.value = false
        advanceUntilIdle()
        network.connected.value = true
        advanceUntilIdle()
        assertFalse(model.access.value is AccessState.Connected)
        repository.logoutWait!!.complete(Unit)
        advanceUntilIdle()
        model.recheck()
        advanceUntilIdle()
        assertEquals(AccessState.SignedOut(), model.access.value)
    }

    @Test fun `failed local logout is explicit and suppresses automatic restore`() = runTest(dispatcher) {
        repository.account = account()
        val model = AppViewModel(repository, network)
        advanceUntilIdle()
        repository.logoutFailure = AuthProblem.LOCAL_STORAGE
        model.signOut()
        advanceUntilIdle()
        assertEquals(AccessState.Blocked(AuthProblem.LOCAL_STORAGE), model.access.value)
        val restores = repository.restores
        model.recheck()
        advanceUntilIdle()
        assertEquals(restores, repository.restores)
    }

    @Test fun `SDK session invalidation immediately removes authenticated navigation`() = runTest(dispatcher) {
        repository.account = account()
        val model = AppViewModel(repository, network)
        advanceUntilIdle()
        repository.invalidations.emit(AuthProblem.INVALID_CREDENTIALS)
        advanceUntilIdle()
        assertEquals(AccessState.SignedOut(), model.access.value)
    }

    private class FakeNetwork : NetworkMonitor { override val connected = MutableStateFlow(true) }
    private class FakeAuth : AuthRepository {
        override val invalidations = MutableSharedFlow<AuthProblem>()
        var account: Account? = null
        var restores = 0
        var failure: AuthProblem? = null
        var logoutFailure: AuthProblem? = null
        var restoreWait: CompletableDeferred<Account?>? = null
        var logoutWait: CompletableDeferred<Unit>? = null
        override suspend fun restoreVerifiedAccount(): Account? {
            restores++
            failure?.let { throw AuthFailure(it) }
            val pending = restoreWait
            return if (pending != null) pending.await() else account
        }
        override suspend fun signIn(email: String, password: String): Account {
            failure?.let { throw AuthFailure(it) }
            return account()
        }
        override suspend fun signOut() {
            logoutFailure?.let { throw AuthFailure(it) }
            account = null
            logoutWait?.await()
        }
    }
    companion object {
        private fun account() = Account(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Giocatore test", "test@example.com", false)
    }
}
