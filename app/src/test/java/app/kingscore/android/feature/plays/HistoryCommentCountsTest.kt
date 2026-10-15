package app.kingscore.android.feature.plays

import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.*
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryCommentCountsTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakePlays()
    private val accessProblems = mutableListOf<AuthProblem>()
    private val models = mutableListOf<HistoryViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { models.forEach { it.clearMemory() }; Dispatchers.resetMain() }
    private fun model(includeCounts: Boolean = true) = HistoryViewModel(repository,
        onAccessFailure = accessProblems::add, includeCommentCounts = includeCounts).also { models += it }

    @Test fun `plays appear while their comment counts are still pending`() = runTest(dispatcher) {
        val pending = CompletableDeferred<Map<UUID, Int>?>()
        repository.counts = { pending.await() }
        val model = model()
        model.show(); runCurrent()
        assertEquals(listOf(play(1)), model.history.value.items)
        assertFalse(model.history.value.loading)
        assertTrue(model.history.value.commentCountsLoading)
        assertTrue(model.history.value.commentCounts.isEmpty())
        pending.complete(mapOf(id(1) to 7)); advanceUntilIdle()
        assertEquals(mapOf(id(1) to 7), model.history.value.commentCounts)
        assertFalse(model.history.value.commentCountsLoading)
    }

    @Test fun `comment revision refreshes only counts and the same revision is a no op`() = runTest(dispatcher) {
        var count = 1
        repository.counts = { ids -> ids.associateWith { count } }
        val model = model()
        model.commentsChanged(0); model.show(); advanceUntilIdle()
        assertEquals(1, repository.historyCalls)
        assertEquals(1, repository.countRequests.size)
        count = 2
        model.commentsChanged(1); advanceUntilIdle()
        assertEquals(mapOf(id(1) to 2), model.history.value.commentCounts)
        assertEquals(1, repository.historyCalls)
        assertEquals(2, repository.countRequests.size)
        model.commentsChanged(1); advanceUntilIdle()
        assertEquals(2, repository.countRequests.size)
    }

    @Test fun `clear rejects a non cancellable late count response`() = runTest(dispatcher) {
        val pending = CompletableDeferred<Map<UUID, Int>?>()
        repository.counts = { withContext(NonCancellable) { pending.await() } }
        val model = model()
        model.show(); runCurrent()
        model.clearMemory()
        pending.complete(mapOf(id(1) to 5)); advanceUntilIdle()
        assertTrue(model.history.value.items.isEmpty())
        assertTrue(model.history.value.commentCounts.isEmpty())
        assertFalse(model.history.value.commentCountsLoading)
        assertNull(model.history.value.updatedAt)
        assertTrue(accessProblems.isEmpty())
    }

    @Test fun `late authentication failure after clear cannot block a new session`() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        repository.counts = { withContext(NonCancellable) { release.await(); throw AuthFailure(AuthProblem.INVALID_CREDENTIALS) } }
        val model = model()
        model.show(); runCurrent(); model.clearMemory()
        release.complete(Unit); advanceUntilIdle()
        assertTrue(accessProblems.isEmpty())
        assertTrue(model.history.value.items.isEmpty())
    }

    @Test fun `count error keeps plays and removes old values without inventing zeros`() = runTest(dispatcher) {
        repository.counts = { ids -> ids.associateWith { 3 } }
        val model = model()
        model.show(); advanceUntilIdle()
        repository.counts = { throw DataFailure(DataProblem.FORBIDDEN) }
        model.refreshCommentCounts(); advanceUntilIdle()
        assertEquals(listOf(play(1)), model.history.value.items)
        assertTrue(model.history.value.commentCounts.isEmpty())
        assertEquals(DataProblem.FORBIDDEN, model.history.value.commentCountsProblem)
        assertNull(model.history.value.problem)
        assertFalse(model.history.value.commentCountsLoading)
        assertEquals(1, repository.historyCalls)
    }

    @Test fun `unsupported counts remain unknown and malformed maps fail explicitly`() = runTest(dispatcher) {
        val model = model()
        repository.counts = { null }
        model.show(); advanceUntilIdle()
        assertTrue(model.history.value.commentCounts.isEmpty())
        assertNull(model.history.value.commentCountsProblem)
        for (invalid in listOf(emptyMap(), mapOf(id(1) to -1), mapOf(id(2) to 0))) {
            repository.counts = { invalid }
            model.refreshCommentCounts(); advanceUntilIdle()
            assertTrue(model.history.value.commentCounts.isEmpty())
            assertEquals(DataProblem.INVALID_RESPONSE, model.history.value.commentCountsProblem)
            assertEquals(listOf(play(1)), model.history.value.items)
        }
    }

    @Test fun `same filter refresh retains completed rows while waiting then replaces them`() = runTest(dispatcher) {
        val model = model()
        val filter = HistoryFilters(query = "  Skull  ", source = PlaySource.ONLINE)
        model.show(filter); advanceUntilIdle()
        val readAt = model.history.value.updatedAt
        val pending = CompletableDeferred<PlayPage>()
        repository.page = { _, _ -> pending.await() }
        model.refresh(filter)
        assertEquals(listOf(play(1)), model.history.value.items)
        assertEquals(readAt, model.history.value.updatedAt)
        assertTrue(model.history.value.loading)
        runCurrent()
        pending.complete(PlayPage(listOf(play(2)), null)); advanceUntilIdle()
        assertEquals(listOf(play(2)), model.history.value.items)
        assertEquals(HistoryFilters(query = "Skull", source = PlaySource.ONLINE), model.history.value.filters)
        assertFalse(model.history.value.loading)
    }

    @Test fun `retry after failed retained refresh reloads first page instead of the previous next cursor`() = runTest(dispatcher) {
        val cursor = PlayCursor(LocalDate.parse("2026-10-12"), id(1))
        val filter = HistoryFilters(query = "Skull", source = PlaySource.ONLINE)
        val requests = mutableListOf<Pair<HistoryFilters, PlayCursor?>>()
        var phase = 0
        repository.page = { filters, after ->
            requests += filters to after
            when (phase) {
                0 -> PlayPage(listOf(play(1)), cursor)
                1 -> throw DataFailure(DataProblem.INVALID_RESPONSE)
                else -> PlayPage(listOf(play(2)), null)
            }
        }
        repository.counts = { ids -> ids.associateWith { 4 } }
        val model = model()
        model.show(filter); advanceUntilIdle()
        val readAt = model.history.value.updatedAt
        phase = 1
        model.refresh(); advanceUntilIdle()
        assertEquals(listOf(play(1)), model.history.value.items)
        assertEquals(mapOf(id(1) to 4), model.history.value.commentCounts)
        assertEquals(readAt, model.history.value.updatedAt)
        assertEquals(cursor, model.history.value.next)
        assertEquals(DataProblem.INVALID_RESPONSE, model.history.value.problem)
        phase = 2
        model.retry(); advanceUntilIdle()
        assertEquals(listOf(filter to null, filter to null, filter to null), requests)
        assertEquals(listOf(play(2)), model.history.value.items)
        assertEquals(mapOf(id(2) to 4), model.history.value.commentCounts)
        assertNull(model.history.value.next)
        assertNull(model.history.value.problem)
    }

    @Test fun `forbidden refresh removes retained private plays counts and pagination`() = runTest(dispatcher) {
        val cursor = PlayCursor(LocalDate.parse("2026-10-12"), id(1))
        val filter = HistoryFilters(source = PlaySource.ONLINE)
        repository.page = { _, _ -> PlayPage(listOf(play(1)), cursor) }
        repository.counts = { ids -> ids.associateWith { 6 } }
        val model = model()
        model.show(filter); advanceUntilIdle()
        assertEquals(mapOf(id(1) to 6), model.history.value.commentCounts)
        val response = CompletableDeferred<PlayPage>()
        repository.page = { _, _ -> response.await() }
        model.refresh(); runCurrent()
        assertEquals(listOf(play(1)), model.history.value.items)
        response.completeExceptionally(DataFailure(DataProblem.FORBIDDEN)); advanceUntilIdle()
        assertTrue(model.history.value.items.isEmpty())
        assertTrue(model.history.value.commentCounts.isEmpty())
        assertNull(model.history.value.next)
        assertNull(model.history.value.updatedAt)
        assertFalse(model.history.value.loading)
        assertFalse(model.history.value.commentCountsLoading)
        assertEquals(DataProblem.FORBIDDEN, model.history.value.problem)
        assertEquals(filter, model.history.value.filters)
        assertEquals(1, repository.countRequests.size)
        assertTrue(accessProblems.isEmpty())
    }

    @Test fun `refresh to an empty history clears previous counts without another count read`() = runTest(dispatcher) {
        repository.counts = { ids -> ids.associateWith { 5 } }
        val model = model()
        model.show(); advanceUntilIdle()
        assertEquals(mapOf(id(1) to 5), model.history.value.commentCounts)
        repository.page = { _, _ -> PlayPage(emptyList(), null) }
        model.refresh(); advanceUntilIdle()
        assertTrue(model.history.value.items.isEmpty())
        assertTrue(model.history.value.commentCounts.isEmpty())
        assertFalse(model.history.value.commentCountsLoading)
        assertNull(model.history.value.commentCountsProblem)
        assertNull(model.history.value.problem)
        assertEquals(1, repository.countRequests.size)
    }

    @Test fun `forbidden history cannot be repopulated by late non cancellable counts or catalogs`() = runTest(dispatcher) {
        val cursor = PlayCursor(LocalDate.parse("2026-10-12"), id(1))
        repository.page = { _, after -> if (after == null) PlayPage(listOf(play(1)), cursor)
            else throw DataFailure(DataProblem.FORBIDDEN) }
        val model = model()
        model.show(); advanceUntilIdle()
        val pendingCounts = CompletableDeferred<Map<UUID, Int>?>()
        val pendingOptions = CompletableDeferred<HistoryOptions>()
        repository.counts = { withContext(NonCancellable) { pendingCounts.await() } }
        repository.options = { withContext(NonCancellable) { pendingOptions.await() } }
        model.refreshCommentCounts(); model.loadOptions(); runCurrent()
        assertTrue(model.history.value.commentCountsLoading)
        assertTrue(model.history.value.optionsLoading)
        model.loadMore(); runCurrent()
        assertEquals(DataProblem.FORBIDDEN, model.history.value.problem)
        pendingCounts.complete(mapOf(id(1) to 99))
        pendingOptions.complete(HistoryOptions(games = listOf(GameRow(id(10), "Private game", null, null, 2, 6, null))))
        advanceUntilIdle()
        assertTrue(model.history.value.items.isEmpty())
        assertTrue(model.history.value.commentCounts.isEmpty())
        assertNull(model.history.value.options)
        assertFalse(model.history.value.optionsLoading)
        assertFalse(model.history.value.commentCountsLoading)
        assertEquals(DataProblem.FORBIDDEN, model.history.value.problem)
        assertTrue(accessProblems.isEmpty())
    }

    @Test fun `filter change removes old counts and rejects a late previous selection`() = runTest(dispatcher) {
        val stale = CompletableDeferred<Map<UUID, Int>?>()
        repository.counts = { ids -> if (id(1) in ids) withContext(NonCancellable) { stale.await() } else ids.associateWith { 9 } }
        repository.page = { filters, _ -> PlayPage(listOf(play(if (filters.source == PlaySource.ONLINE) 2 else 1)), null) }
        val model = model()
        model.show(); runCurrent()
        model.show(HistoryFilters(source = PlaySource.ONLINE)); runCurrent()
        stale.complete(mapOf(id(1) to 99)); advanceUntilIdle()
        assertEquals(listOf(play(2)), model.history.value.items)
        assertEquals(mapOf(id(2) to 9), model.history.value.commentCounts)
        assertTrue(accessProblems.isEmpty())
    }

    @Test fun `pagination counts all visible plays in one request per loaded page`() = runTest(dispatcher) {
        val cursor = PlayCursor(LocalDate.parse("2026-10-12"), id(1))
        repository.page = { _, after -> if (after == null) PlayPage(listOf(play(1)), cursor)
            else PlayPage(listOf(play(2)), null) }
        val model = model()
        model.show(); advanceUntilIdle(); model.loadMore(); advanceUntilIdle()
        assertEquals(listOf(setOf(id(1)), setOf(id(1), id(2))), repository.countRequests)
        assertEquals(setOf(id(1), id(2)), model.history.value.commentCounts.keys)
        assertEquals(2, repository.historyCalls)
    }

    @Test fun `summary readers can opt out of comment count requests`() = runTest(dispatcher) {
        repository.counts = { error("Counts should not be read by a summary") }
        val model = model(includeCounts = false)
        model.show(); advanceUntilIdle()
        model.commentsChanged(0); model.commentsChanged(1); advanceUntilIdle()
        assertEquals(listOf(play(1)), model.history.value.items)
        assertTrue(repository.countRequests.isEmpty())
    }

    @Test fun `authentication failure from counts clears private rows before reporting it`() = runTest(dispatcher) {
        repository.counts = { throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE) }
        lateinit var model: HistoryViewModel
        model = HistoryViewModel(repository, onAccessFailure = {
            assertTrue(model.history.value.items.isEmpty())
            assertTrue(model.history.value.commentCounts.isEmpty())
            accessProblems += it
        }).also { models += it }
        model.show(); advanceUntilIdle()
        assertEquals(listOf(AuthProblem.NETWORK_UNAVAILABLE), accessProblems)
        assertNull(model.history.value.updatedAt)
    }

    private class FakePlays : PlaysRepository {
        var historyCalls = 0
        val countRequests = mutableListOf<Set<UUID>>()
        var page: suspend (HistoryFilters, PlayCursor?) -> PlayPage = { _, _ -> PlayPage(listOf(play(1)), null) }
        var counts: suspend (Set<UUID>) -> Map<UUID, Int>? = { ids -> ids.associateWith { 0 } }
        var options: suspend () -> HistoryOptions = { HistoryOptions() }
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?): PlayPage { historyCalls++; return page(filters, after) }
        override suspend fun commentCounts(playIds: Set<UUID>): Map<UUID, Int>? { countRequests += playIds.toSet(); return counts(playIds) }
        override suspend fun player(id: UUID): PlayerRow? = null
        override suspend fun game(id: UUID): GameRow? = null
        override suspend fun filterOptions() = options()
        override suspend fun detail(id: UUID): PlayDetail? = null
        override suspend fun comments(id: UUID, after: CommentCursor?) = CommentPage(emptyList(), null)
    }

    companion object {
        private fun id(n: Int) = UUID(0, n.toLong())
        private fun play(n: Int) = PlayDetail(PlayRow(id(n), null, null, LocalDate.parse("2026-10-12"), null, null,
            null, "online", null, null, false, null), null, emptyList())
    }
}
