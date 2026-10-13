package app.kingscore.android.feature.players

import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import java.time.Instant
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val id = UUID(0, 10)
    private val player = PlayerRow(id, null, "Marta", true, null, null, null, Instant.EPOCH, null, null)
    private var read: suspend () -> PlayerRow? = { player }
    private var calls = 0
    private val failures = mutableListOf<AuthProblem>()
    private val repository = object : PlaysRepository {
        override suspend fun player(id: UUID): PlayerRow? { assertEquals(this@PlayerDetailViewModelTest.id, id); calls++; return read() }
        override suspend fun game(id: UUID): GameRow? = error("Unused game")
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?): PlayPage = error("Metadata must not scan history")
        override suspend fun filterOptions(): HistoryOptions = error("Unused catalog")
        override suspend fun detail(id: UUID): PlayDetail? = error("Unused play")
        override suspend fun comments(id: UUID, after: CommentCursor?): CommentPage = error("Unused comments")
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `back keeps player but identical refresh increments revision for history reload`() = runTest(dispatcher) {
        val model = PlayerDetailViewModel(repository, id, failures::add)
        model.show(); advanceUntilIdle()
        val revision = model.detail.value.revision
        model.show(); advanceUntilIdle()
        assertEquals(1, calls)
        model.refresh()
        assertNull(model.detail.value.player)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(player, model.detail.value.player)
        assertTrue(model.detail.value.revision > revision)
    }

    @Test fun `missing and denied player states are distinct and recoverable`() = runTest(dispatcher) {
        val model = PlayerDetailViewModel(repository, id, failures::add)
        read = { null }; model.show(); advanceUntilIdle()
        assertTrue(model.detail.value.missing)
        assertNull(model.detail.value.problem)
        read = { throw DataFailure(DataProblem.FORBIDDEN) }; model.refresh(); advanceUntilIdle()
        assertFalse(model.detail.value.missing)
        assertEquals(DataProblem.FORBIDDEN, model.detail.value.problem)
        read = { player }; model.refresh(); advanceUntilIdle()
        assertEquals(player, model.detail.value.player)
        assertNull(model.detail.value.problem)
    }

    @Test fun `network failure removes player before invoking account block`() = runTest(dispatcher) {
        lateinit var model: PlayerDetailViewModel
        model = PlayerDetailViewModel(repository, id) { assertNull(model.detail.value.player); failures += it }
        model.show(); advanceUntilIdle()
        read = { throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE) }
        model.refresh(); advanceUntilIdle()
        assertEquals(listOf(AuthProblem.NETWORK_UNAVAILABLE), failures)
        assertNull(model.detail.value.player)
    }

    @Test fun `late noncancellable player response cannot replace newer data or restore cleared memory`() = runTest(dispatcher) {
        val pending = CompletableDeferred<PlayerRow?>()
        read = { withContext(NonCancellable) { pending.await() } }
        val model = PlayerDetailViewModel(repository, id, failures::add)
        model.show(); runCurrent()
        model.clearMemory()
        read = { player.copy(alias = "Nome aggiornato") }
        model.show(); runCurrent()
        pending.complete(player); advanceUntilIdle()
        assertEquals("Nome aggiornato", model.detail.value.player?.alias)
        model.clearMemory()
        assertNull(model.detail.value.player)
    }

    @Test fun `late auth failure after refresh cannot block the newer accepted response`() = runTest(dispatcher) {
        val pending = CompletableDeferred<Unit>()
        read = { withContext(NonCancellable) { pending.await(); throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE) } }
        val model = PlayerDetailViewModel(repository, id, failures::add)
        model.show(); runCurrent()
        read = { player }
        model.refresh(); runCurrent()
        pending.complete(Unit); advanceUntilIdle()
        assertEquals(player, model.detail.value.player)
        assertTrue(failures.isEmpty())
    }
}
