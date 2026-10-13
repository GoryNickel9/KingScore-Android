package app.kingscore.android.feature.ratings

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
class RatingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeRatings()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `parity ranks never turn missing ratings into zero`() {
        assertEquals(listOf(1, 1, 3, null), competitionRanks(listOf(rating(1, 1100), rating(2, 1100), rating(3, 1000), rating(4, null))).values.toList())
    }
    @Test fun `filter changes cannot be overwritten by an old noncancellable reply`() = runTest(dispatcher) {
        val pending = CompletableDeferred<Ranking>()
        repo.fetch = { if (it == null) withContext(NonCancellable) { pending.await() } else Ranking(null, listOf(rating(2, 1200))) }
        val model = RatingsViewModel(repo) { fail("Unexpected access error") }
        model.show(null)
        runCurrent()
        model.show(id(5))
        runCurrent()
        pending.complete(Ranking(null, listOf(rating(1, 999))))
        advanceUntilIdle()
        assertEquals(id(5), model.ratings.value.gameId)
        assertEquals(1200, model.ratings.value.ranking!!.entries.single().rating)
    }
    @Test fun `history page error keeps cursor and retries without duplicated results`() = runTest(dispatcher) {
        val row = history(1)
        val cursor = RatingHistoryCursor(row.date, row.id)
        repo.historyFetch = { after -> if (after == null) RatingHistoryPage(listOf(row), cursor) else throw DataFailure(DataProblem.FORBIDDEN) }
        val model = RatingsViewModel(repo) { fail("Unexpected access error") }
        model.show(null)
        advanceUntilIdle()
        model.select(rating(1, 1100))
        advanceUntilIdle()
        model.moreHistory()
        advanceUntilIdle()
        assertEquals(listOf(row), model.ratings.value.history)
        assertEquals(cursor, model.ratings.value.nextHistory)
        repo.historyFetch = { after -> assertEquals(cursor, after); RatingHistoryPage(listOf(row, history(2)), null) }
        model.retryHistory()
        advanceUntilIdle()
        assertEquals(2, model.ratings.value.history.size)
        assertNull(model.ratings.value.historyProblem)
    }
    @Test fun `closing history discards a late reply`() = runTest(dispatcher) {
        val pending = CompletableDeferred<RatingHistoryPage>()
        repo.historyFetch = { withContext(NonCancellable) { pending.await() } }
        val model = RatingsViewModel(repo) {}
        model.show(null)
        advanceUntilIdle()
        model.select(rating(1, 1100))
        runCurrent()
        model.closeHistory()
        pending.complete(RatingHistoryPage(listOf(history(1)), null))
        advanceUntilIdle()
        assertNull(model.ratings.value.selected)
        assertTrue(model.ratings.value.history.isEmpty())
    }
    @Test fun `network failure clears both ranking and history before reporting blocked access`() = runTest(dispatcher) {
        lateinit var model: RatingsViewModel
        var failure: AuthProblem? = null
        model = RatingsViewModel(repo) { assertNull(model.ratings.value.ranking); assertTrue(model.ratings.value.history.isEmpty()); failure = it }
        model.show(null)
        advanceUntilIdle()
        repo.historyFetch = { throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE) }
        model.select(rating(1, 1100))
        advanceUntilIdle()
        assertEquals(AuthProblem.NETWORK_UNAVAILABLE, failure)
    }

    @Test fun `a forbidden ranking preserves the loaded game picker for another scope`() = runTest(dispatcher) {
        repo.catalog = listOf(GameRow(id(5), "Azul", null, null, null, null, null))
        repo.fetch = { throw DataFailure(DataProblem.FORBIDDEN) }
        val model = RatingsViewModel(repo) { fail("Forbidden data is not invalid authentication") }
        model.show(null)
        advanceUntilIdle()
        assertEquals(repo.catalog, model.ratings.value.games)
        assertEquals(DataProblem.FORBIDDEN, model.ratings.value.problem)
        assertNull(model.ratings.value.ranking)
    }
    private class FakeRatings : RatingsRepository {
        var catalog: List<GameRow> = emptyList()
        var fetch: suspend (UUID?) -> Ranking = { Ranking(null, emptyList()) }
        var historyFetch: suspend (RatingHistoryCursor?) -> RatingHistoryPage = { RatingHistoryPage(emptyList(), null) }
        override suspend fun games(): List<GameRow> = catalog
        override suspend fun ranking(gameId: UUID?) = fetch(gameId)
        override suspend fun history(rating: PublishedRating, after: RatingHistoryCursor?) = historyFetch(after)
    }
    companion object {
        private fun id(n: Int) = UUID(0, n.toLong())
        private fun rating(n: Int, value: Int?) = PublishedRating(id(n), null, id(n), null, id(99), value, null, null, null, null, null)
        private fun history(n: Int) = EloHistoryRow(id(n), id(3), null, id(1), "Test", 1100, 10, Instant.EPOCH, true, null, id(99), Instant.EPOCH)
    }
}
