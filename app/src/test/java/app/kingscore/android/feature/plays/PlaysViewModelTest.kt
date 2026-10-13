package app.kingscore.android.feature.plays

import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.*
import java.time.Instant
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
class PlaysViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakePlays()
    private val accessProblems = mutableListOf<AuthProblem>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `failed next page keeps rows and cursor and retry never duplicates`() = runTest(dispatcher) {
        val first = play(1)
        val cursor = PlayCursor(first.play.played_at, first.play.id)
        repository.page = { _, after -> if (after == null) PlayPage(listOf(first), cursor)
            else throw DataFailure(DataProblem.INVALID_RESPONSE) }
        val model = HistoryViewModel(repository, accessProblems::add)
        model.show(null)
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(first), model.history.value.items)
        assertEquals(cursor, model.history.value.next)
        assertEquals(DataProblem.INVALID_RESPONSE, model.history.value.problem)
        repository.page = { _, after -> assertEquals(cursor, after); PlayPage(listOf(first, play(2)), null) }
        model.retry()
        advanceUntilIdle()
        assertEquals(2, model.history.value.items.size)
        assertNull(model.history.value.next)
        assertNull(model.history.value.problem)
    }

    @Test fun `filter changes and memory clear reject late noncancellable responses`() = runTest(dispatcher) {
        val stale = CompletableDeferred<PlayPage>()
        repository.page = { source, _ -> if (source == null) withContext(NonCancellable) { stale.await() }
            else PlayPage(listOf(play(2)), null) }
        val model = HistoryViewModel(repository, accessProblems::add)
        model.show(null)
        runCurrent()
        model.show(PlaySource.ONLINE)
        runCurrent()
        stale.complete(PlayPage(listOf(play(1)), null))
        advanceUntilIdle()
        assertEquals(listOf(play(2)), model.history.value.items)
        model.clearMemory()
        assertTrue(model.history.value.items.isEmpty())
        assertNull(model.history.value.updatedAt)
    }

    @Test fun `back navigation preserves loaded pages but explicit refresh starts over`() = runTest(dispatcher) {
        var calls = 0
        repository.page = { _, _ -> calls++; PlayPage(listOf(play(1)), null) }
        val model = HistoryViewModel(repository, accessProblems::add)
        model.show(null)
        advanceUntilIdle()
        model.show(null)
        advanceUntilIdle()
        assertEquals(1, calls)
        model.refresh()
        assertTrue(model.history.value.items.isEmpty())
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test fun `auth or network failure removes loaded rows before blocking shell`() = runTest(dispatcher) {
        repository.page = { _, _ -> PlayPage(listOf(play(1)), PlayCursor(LocalDate.now(), id(1))) }
        lateinit var model: HistoryViewModel
        model = HistoryViewModel(repository) { assertTrue(model.history.value.items.isEmpty()); accessProblems += it }
        model.show(null)
        advanceUntilIdle()
        repository.page = { _, _ -> throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE) }
        model.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(AuthProblem.NETWORK_UNAVAILABLE), accessProblems)
        assertTrue(model.history.value.items.isEmpty())
    }

    @Test fun `absent detail is not an empty or failed comment list`() = runTest(dispatcher) {
        repository.play = null
        val model = PlayDetailViewModel(repository, id(1), accessProblems::add)
        model.refresh()
        advanceUntilIdle()
        assertTrue(model.detail.value.missing)
        assertNull(model.detail.value.problem)
        assertEquals(0, repository.commentCalls)
    }

    @Test fun `comment pagination error preserves detail and retries same cursor`() = runTest(dispatcher) {
        val comment = comment(1)
        val cursor = CommentCursor(comment.created_at, comment.id)
        repository.commentPage = { after -> if (after == null) CommentPage(listOf(comment), cursor)
            else throw DataFailure(DataProblem.FORBIDDEN) }
        val model = PlayDetailViewModel(repository, id(1), accessProblems::add)
        model.refresh()
        advanceUntilIdle()
        model.moreComments()
        advanceUntilIdle()
        assertNotNull(model.detail.value.detail)
        assertEquals(listOf(comment), model.detail.value.comments)
        assertEquals(DataProblem.FORBIDDEN, model.detail.value.commentsProblem)
        repository.commentPage = { after -> assertEquals(cursor, after); CommentPage(listOf(comment(2)), null) }
        model.retryComments()
        advanceUntilIdle()
        assertEquals(2, model.detail.value.comments.size)
        assertNull(model.detail.value.commentCursor)
    }

    @Test fun `session rejection during comments clears detail and reports invalid access`() = runTest(dispatcher) {
        repository.commentPage = { throw AuthFailure(AuthProblem.INVALID_CREDENTIALS) }
        val model = PlayDetailViewModel(repository, id(1), accessProblems::add)
        model.refresh()
        advanceUntilIdle()
        assertNull(model.detail.value.detail)
        assertTrue(model.detail.value.comments.isEmpty())
        assertEquals(listOf(AuthProblem.INVALID_CREDENTIALS), accessProblems)
    }

    @Test fun `cleared detail cannot be restored by a late response`() = runTest(dispatcher) {
        val stale = CompletableDeferred<PlayDetail?>()
        repository.detailWait = stale
        val model = PlayDetailViewModel(repository, id(1), accessProblems::add)
        model.refresh()
        runCurrent()
        model.clearMemory()
        stale.complete(play(1))
        advanceUntilIdle()
        assertNull(model.detail.value.detail)
        assertEquals(0, repository.commentCalls)
    }

    @Test fun `presentation preserves stored winners cooperative result and nulls`() {
        val highScore = PlayScoreRow(id(3), id(1), null, java.math.BigDecimal("999"), false, null, null, null)
        val winner = highScore.copy(id = id(4), score = null, winner = true, position = 1)
        val detail = play(1).copy(scores = listOf(ScoreWithPlayer(highScore, null), ScoreWithPlayer(winner, null)))
        assertEquals("Vincitore: Giocatore non disponibile", resultLabel(detail))
        assertEquals("Esito della squadra non indicato", resultLabel(detail.copy(play = detail.play.copy(is_cooperative = true))))
        assertEquals("Sconfitta della squadra", resultLabel(detail.copy(play = detail.play.copy(is_cooperative = true, team_won = false))))
    }

    private class FakePlays : PlaysRepository {
        var page: suspend (PlaySource?, PlayCursor?) -> PlayPage = { _, _ -> PlayPage(emptyList(), null) }
        var commentPage: suspend (CommentCursor?) -> CommentPage = { CommentPage(emptyList(), null) }
        var play: PlayDetail? = play(1)
        var detailWait: CompletableDeferred<PlayDetail?>? = null
        var commentCalls = 0
        override suspend fun history(source: PlaySource?, after: PlayCursor?) = page(source, after)
        override suspend fun detail(id: UUID): PlayDetail? = detailWait?.let { withContext(NonCancellable) { it.await() } } ?: play
        override suspend fun comments(id: UUID, after: CommentCursor?): CommentPage { commentCalls++; return commentPage(after) }
    }

    companion object {
        private fun id(n: Int) = UUID(0, n.toLong())
        private fun play(n: Int) = PlayDetail(PlayRow(id(n), null, null, LocalDate.parse("2026-10-13"), null, null,
            null, "online", null, null, false, null), null, emptyList())
        private fun comment(n: Int) = PlayCommentRow(id(n), id(1), id(9), "Test", "Commento $n",
            Instant.parse("2026-10-13T12:00:00Z"), Instant.parse("2026-10-13T12:00:00Z"))
    }
}
