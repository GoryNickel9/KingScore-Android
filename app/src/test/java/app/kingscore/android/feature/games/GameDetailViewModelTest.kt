package app.kingscore.android.feature.games

import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val id = UUID(0, 10)
    private val game = GameRow(id, "Azul", null, null, 2, 4, null)
    private var read: suspend () -> GameRow? = { game }
    private var calls = 0
    private val failures = mutableListOf<AuthProblem>()
    private val repository = object : PlaysRepository {
        override suspend fun game(id: UUID): GameRow? { assertEquals(this@GameDetailViewModelTest.id, id); calls++; return read() }
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?): PlayPage = error("Metadata must not scan history")
        override suspend fun filterOptions(): HistoryOptions = error("Unused catalog")
        override suspend fun detail(id: UUID): PlayDetail? = error("Unused play")
        override suspend fun comments(id: UUID, after: CommentCursor?): CommentPage = error("Unused comments")
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `back preserves metadata but refresh publishes a new revision for identical game`() = runTest(dispatcher) {
        val model = GameDetailViewModel(repository, id, failures::add)
        model.show(); advanceUntilIdle()
        val revision = model.detail.value.revision
        model.show(); advanceUntilIdle()
        assertEquals(1, calls)
        model.refresh()
        assertNull(model.detail.value.game)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(game, model.detail.value.game)
        assertTrue(model.detail.value.revision > revision)
    }

    @Test fun `missing and forbidden have distinct retryable states`() = runTest(dispatcher) {
        val model = GameDetailViewModel(repository, id, failures::add)
        read = { null }; model.show(); advanceUntilIdle()
        assertTrue(model.detail.value.missing)
        assertNull(model.detail.value.problem)
        read = { throw DataFailure(DataProblem.FORBIDDEN) }; model.refresh(); advanceUntilIdle()
        assertFalse(model.detail.value.missing)
        assertEquals(DataProblem.FORBIDDEN, model.detail.value.problem)
        read = { game }; model.refresh(); advanceUntilIdle()
        assertEquals(game, model.detail.value.game)
        assertNull(model.detail.value.problem)
    }

    @Test fun `offline clears metadata before blocking the account`() = runTest(dispatcher) {
        lateinit var model: GameDetailViewModel
        model = GameDetailViewModel(repository, id) { assertNull(model.detail.value.game); failures += it }
        model.show(); advanceUntilIdle()
        read = { throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE) }
        model.refresh(); advanceUntilIdle()
        assertEquals(listOf(AuthProblem.NETWORK_UNAVAILABLE), failures)
        assertNull(model.detail.value.game)
    }

    @Test fun `late noncancellable reads cannot restore cleared or refreshed metadata`() = runTest(dispatcher) {
        val pending = CompletableDeferred<GameRow?>()
        read = { withContext(NonCancellable) { pending.await() } }
        val model = GameDetailViewModel(repository, id, failures::add)
        model.show(); runCurrent()
        model.clearMemory()
        read = { game.copy(name = "Nome aggiornato") }
        model.show(); runCurrent()
        pending.complete(game); advanceUntilIdle()
        assertEquals("Nome aggiornato", model.detail.value.game?.name)
        model.clearMemory()
        assertNull(model.detail.value.game)
    }

    @Test fun `partial and absent player bounds are not invented`() {
        assertEquals("2–4 giocatori", playerCountLabel(game))
        assertEquals("2 giocatori", playerCountLabel(game.copy(max_players = 2)))
        assertEquals("Da 2 giocatori", playerCountLabel(game.copy(max_players = null)))
        assertEquals("Fino a 4 giocatori", playerCountLabel(game.copy(min_players = null)))
        assertEquals("Numero di giocatori non indicato", playerCountLabel(game.copy(min_players = null, max_players = null)))
    }
}
