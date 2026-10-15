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
        model.show(null); runCurrent()
        model.show(id(5)); runCurrent()
        pending.complete(Ranking(null, listOf(rating(1, 999))))
        advanceUntilIdle()
        assertEquals(id(5), model.ratings.value.gameId)
        assertEquals(1200, model.ratings.value.ranking!!.entries.single().rating)
    }
    @Test fun `refresh keeps the same ranking and timestamp until its replacement is read`() = runTest(dispatcher) {
        val readAt = Instant.parse("2026-10-15T09:00:00Z")
        val first = Ranking(null, listOf(rating(1, 1100)), readAt = readAt)
        repo.fetch = { first }
        val model = RatingsViewModel(repo) { fail("Unexpected access error") }
        model.show(null); advanceUntilIdle()
        val pending = CompletableDeferred<Ranking>()
        repo.fetch = { pending.await() }
        model.refresh(); runCurrent()
        assertTrue(model.ratings.value.loading)
        assertEquals(first, model.ratings.value.ranking)
        assertEquals(readAt, model.ratings.value.readAt)
        pending.complete(first.copy(entries = listOf(rating(1, 1200)), readAt = readAt.plusSeconds(60)))
        advanceUntilIdle()
        assertFalse(model.ratings.value.loading)
        assertEquals(1200, model.ratings.value.ranking!!.entries.single().rating)
        assertEquals(readAt.plusSeconds(60), model.ratings.value.readAt)
    }
    @Test fun `changing the game clears old values immediately before the new reply`() = runTest(dispatcher) {
        repo.fetch = { Ranking(null, listOf(rating(1, 1100))) }
        val model = RatingsViewModel(repo) {}
        model.show(null); advanceUntilIdle()
        val pending = CompletableDeferred<Ranking>()
        repo.fetch = { pending.await() }
        model.show(id(5))
        assertEquals(id(5), model.ratings.value.gameId)
        assertNull(model.ratings.value.ranking)
        assertNull(model.ratings.value.readAt)
        pending.complete(Ranking(null, listOf(rating(2, 950))))
        advanceUntilIdle()
        assertEquals(950, model.ratings.value.ranking!!.entries.single().rating)
    }
    @Test fun `show and game switching reuse the repository while explicit refresh invalidates it`() = runTest(dispatcher) {
        repo.catalog = listOf(GameRow(id(5), "Azul", null, null, null, null, null))
        val model = RatingsViewModel(repo) {}
        model.show(null); advanceUntilIdle()
        model.show(null); advanceUntilIdle()
        model.show(id(5)); advanceUntilIdle()
        model.show(null); advanceUntilIdle()
        assertEquals(listOf(null, id(5), null), repo.requests)
        assertEquals(0, repo.invalidations)
        assertEquals(0, repo.historyCalls)
        assertEquals(1, repo.catalogCalls)
        repo.catalog = repo.catalog + GameRow(id(6), "Jaipur", null, null, null, null, null)
        model.refresh(); advanceUntilIdle()
        assertEquals(1, repo.invalidations)
        assertEquals(2, repo.catalogCalls)
        assertEquals(repo.catalog, model.ratings.value.games)
    }
    @Test fun `network failure clears retained private standings and catalogs before blocked access`() = runTest(dispatcher) {
        lateinit var model: RatingsViewModel
        var failure: AuthProblem? = null
        repo.catalog = listOf(GameRow(id(5), "Azul", null, null, null, null, null))
        repo.fetch = { Ranking(null, listOf(rating(1, 1100))) }
        model = RatingsViewModel(repo) {
            assertNull(model.ratings.value.ranking)
            assertTrue(model.ratings.value.games.isEmpty())
            failure = it
        }
        model.show(null); advanceUntilIdle()
        repo.fetch = { throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE) }
        model.refresh(); advanceUntilIdle()
        assertEquals(AuthProblem.NETWORK_UNAVAILABLE, failure)
    }
    @Test fun `a forbidden ranking preserves the loaded game picker for another scope`() = runTest(dispatcher) {
        repo.catalog = listOf(GameRow(id(5), "Azul", null, null, null, null, null))
        repo.fetch = { throw DataFailure(DataProblem.FORBIDDEN) }
        val model = RatingsViewModel(repo) { fail("Forbidden data is not invalid authentication") }
        model.show(null); advanceUntilIdle()
        assertEquals(repo.catalog, model.ratings.value.games)
        assertEquals(DataProblem.FORBIDDEN, model.ratings.value.problem)
        assertNull(model.ratings.value.ranking)
    }
    @Test fun `stale and missing publication clear old standings on the error response`() = runTest(dispatcher) {
        val model = RatingsViewModel(repo) { fail("Publication status does not invalidate login") }
        for (problem in listOf(DataProblem.PUBLICATION_STALE, DataProblem.PUBLICATION_MISSING)) {
            repo.fetch = { Ranking(null, listOf(rating(1, 1100))) }
            model.refresh(); advanceUntilIdle()
            repo.fetch = { throw DataFailure(problem) }
            model.refresh()
            assertNotNull(model.ratings.value.ranking)
            advanceUntilIdle()
            assertEquals(problem, model.ratings.value.problem)
            assertNull(model.ratings.value.ranking)
            assertNull(model.ratings.value.readAt)
            repo.fetch = { Ranking(null, listOf(rating(1, 1200))) }
            model.refresh(); advanceUntilIdle()
            assertNull(model.ratings.value.problem)
            assertEquals(1200, model.ratings.value.ranking!!.entries.first().rating)
        }
    }
    @Test fun `switching scopes preserves the repository read time and never downloads elo history`() = runTest(dispatcher) {
        val downloadedAt = Instant.parse("2026-10-15T09:00:00Z")
        repo.fetch = { Ranking(null, emptyList(), readAt = downloadedAt) }
        val model = RatingsViewModel(repo) {}
        model.show(null); advanceUntilIdle()
        model.show(id(5)); advanceUntilIdle()
        assertEquals(downloadedAt, model.ratings.value.readAt)
        assertEquals(0, repo.historyCalls)
    }
    @Test fun `clearing memory discards a late reply and allows a fresh show`() = runTest(dispatcher) {
        val pending = CompletableDeferred<Ranking>()
        repo.fetch = { withContext(NonCancellable) { pending.await() } }
        val model = RatingsViewModel(repo) {}
        model.show(null); runCurrent()
        model.clearMemory()
        pending.complete(Ranking(null, listOf(rating(1, 1100)))); advanceUntilIdle()
        assertNull(model.ratings.value.ranking)
        repo.fetch = { Ranking(null, listOf(rating(1, 1200))) }
        model.show(null); advanceUntilIdle()
        assertEquals(1200, model.ratings.value.ranking!!.entries.single().rating)
    }
    private class FakeRatings : RatingsRepository {
        var catalog: List<GameRow> = emptyList()
        var fetch: suspend (UUID?) -> Ranking = { Ranking(null, emptyList()) }
        val requests = mutableListOf<UUID?>()
        var invalidations = 0
        var catalogCalls = 0
        var historyCalls = 0
        override suspend fun games(): List<GameRow> { catalogCalls++; return catalog }
        override suspend fun ranking(gameId: UUID?): Ranking { requests += gameId; return fetch(gameId) }
        override fun invalidate() { invalidations++ }
        override suspend fun history(rating: PublishedRating, after: RatingHistoryCursor?): RatingHistoryPage {
            historyCalls++
            error("The ranking screen must not request ELO history")
        }
    }
    companion object {
        private fun id(n: Int) = UUID(0, n.toLong())
        private fun rating(n: Int, value: Int?) = PublishedRating(id(n), null, id(n), null, id(99), value, null, null, null, null, null)
    }
}
