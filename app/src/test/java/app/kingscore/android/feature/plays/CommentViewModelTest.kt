package app.kingscore.android.feature.plays

import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class CommentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakePlays()
    private val problems = mutableListOf<AuthProblem>()
    private val model = PlayDetailViewModel(repo, play.play.id, problems::add, account)
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { model.clearMemory(); Dispatchers.resetMain() }
    private suspend fun TestScope.load() { model.refresh(); advanceUntilIdle() }

    @Test fun `create trims content and rereads server rows with success notice`() = runTest(dispatcher) {
        load(); model.composeComment(); model.changeComment("  Nuovo\n😀  "); model.saveComment(); advanceUntilIdle()
        assertEquals(listOf("create:Nuovo\n😀"), repo.calls)
        assertNull(model.detail.value.commentEditor)
        assertEquals(CommentNotice.CREATED, model.detail.value.commentNotice)
        assertTrue(model.detail.value.comments.any { it.content == "Nuovo\n😀" && it.author_name == "Server" })
        assertNull(repo.reads.last())
    }

    @Test fun `update and delete only loaded own comments and preserve server author and timestamp`() = runTest(dispatcher) {
        load(); model.composeComment(own.id); model.changeComment("Modificato"); model.saveComment(); advanceUntilIdle()
        assertEquals("Modificato", model.detail.value.comments.first().content)
        assertEquals(CommentNotice.UPDATED, model.detail.value.commentNotice)
        model.requestCommentDeletion(own.id)
        assertEquals(own.id, model.detail.value.deletingComment)
        assertEquals(1, repo.calls.size)
        model.deleteComment(); advanceUntilIdle()
        assertEquals(listOf("update:Modificato", "delete"), repo.calls)
        assertEquals(listOf(other), model.detail.value.comments)
        assertEquals(CommentNotice.DELETED, model.detail.value.commentNotice)
    }

    @Test fun `different author cannot be edited or deleted even when UI passes an ID`() = runTest(dispatcher) {
        load(); model.composeComment(other.id); model.saveComment(); model.requestCommentDeletion(other.id); model.deleteComment()
        advanceUntilIdle()
        assertNull(model.detail.value.commentEditor)
        assertNull(model.detail.value.deletingComment)
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun `invalid input stays in editor without touching writer`() = runTest(dispatcher) {
        load(); model.composeComment()
        for (content in listOf("  ", "😀".repeat(1001), "\uD800", "\u0000")) {
            model.changeComment(content); model.saveComment(); advanceUntilIdle()
            assertEquals(content, model.detail.value.commentEditor!!.content)
            assertEquals(CommentProblem.INVALID_CONTENT, model.detail.value.commentWriteProblem)
        }
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun `inflight save disables repeat refresh dismiss and draft changes`() = runTest(dispatcher) {
        load()
        val wait = CompletableDeferred<Unit>()
        repo.beforeWrite = { wait.await() }
        model.composeComment(); model.changeComment("Una volta"); model.saveComment(); runCurrent()
        model.saveComment(); model.refresh(); model.dismissComment(); model.changeComment("Changed")
        assertEquals("Una volta", model.detail.value.commentEditor!!.content)
        assertTrue(model.detail.value.commentSaving)
        wait.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("create:Una volta"), repo.calls)
    }

    @Test fun `uncertain committed create is not repeated and reconciliation reads every page`() = runTest(dispatcher) {
        load()
        repo.afterWrite = { throw CommentFailure(CommentProblem.UNCERTAIN) }
        model.composeComment(); model.changeComment("Già salvato"); model.saveComment(); advanceUntilIdle()
        model.saveComment(); advanceUntilIdle()
        assertEquals(1, repo.calls.size)
        assertEquals(CommentProblem.UNCERTAIN, model.detail.value.commentWriteProblem)
        repo.reads.clear()
        val cursor = CommentCursor(own.created_at, own.id)
        repo.pages = { after -> if (after == null) CommentPage(listOf(own), cursor)
            else { assertEquals(cursor, after); CommentPage(repo.rows.drop(1), null) } }
        model.recheckComments(); advanceUntilIdle()
        assertEquals(listOf(null, cursor), repo.reads)
        assertNull(model.detail.value.commentWriteProblem)
        assertTrue(model.detail.value.comments.any { it.content == "Già salvato" })
        assertEquals(1, repo.calls.size)
        assertEquals("Già salvato", model.detail.value.commentEditor!!.content)
    }

    @Test fun `failed reconciliation keeps uncertain writes disabled and does not lose draft`() = runTest(dispatcher) {
        load(); repo.afterWrite = { throw CommentFailure(CommentProblem.UNCERTAIN) }
        model.composeComment(); model.changeComment("Bozza"); model.saveComment(); advanceUntilIdle()
        repo.pages = { throw DataFailure(DataProblem.FORBIDDEN) }
        model.recheckComments(); advanceUntilIdle(); model.saveComment(); advanceUntilIdle()
        assertEquals(CommentProblem.UNCERTAIN, model.detail.value.commentWriteProblem)
        assertEquals(DataProblem.FORBIDDEN, model.detail.value.commentsProblem)
        assertEquals("Bozza", model.detail.value.commentEditor!!.content)
        assertEquals(1, repo.calls.size)
    }

    @Test fun `successful mutation remains success when the following read fails`() = runTest(dispatcher) {
        load(); repo.pages = { throw DataFailure(DataProblem.INVALID_RESPONSE) }
        model.composeComment(); model.changeComment("Salvato"); model.saveComment(); advanceUntilIdle()
        assertEquals(CommentNotice.CREATED, model.detail.value.commentNotice)
        assertNull(model.detail.value.commentWriteProblem)
        assertEquals(DataProblem.INVALID_RESPONSE, model.detail.value.commentsProblem)
        assertNull(model.detail.value.commentEditor)
    }

    @Test fun `detail refresh and failed page retry cannot bypass full uncertain reconciliation`() = runTest(dispatcher) {
        load(); repo.afterWrite = { throw CommentFailure(CommentProblem.UNCERTAIN) }
        model.composeComment(); model.changeComment("Già salvato"); model.saveComment(); advanceUntilIdle()
        val cursor = CommentCursor(own.created_at, own.id)
        var failed = true
        repo.pages = { after -> if (after == null) CommentPage(listOf(own), cursor)
            else if (failed) throw DataFailure(DataProblem.INVALID_RESPONSE) else CommentPage(repo.rows.drop(1), null) }
        model.refresh(); advanceUntilIdle()
        assertEquals(CommentProblem.UNCERTAIN, model.detail.value.commentWriteProblem)
        model.saveComment(); advanceUntilIdle(); assertEquals(1, repo.calls.size)
        failed = false; repo.reads.clear(); model.retryComments(); advanceUntilIdle()
        assertEquals(listOf(null, cursor), repo.reads)
        assertNull(model.detail.value.commentWriteProblem)
        assertTrue(model.detail.value.comments.any { it.content == "Già salvato" })
        assertEquals(1, repo.calls.size)
    }

    @Test fun `access rejection removes draft and private rows`() = runTest(dispatcher) {
        load(); repo.beforeWrite = { throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE) }
        model.composeComment(); model.changeComment("Privato"); model.saveComment(); advanceUntilIdle()
        assertNull(model.detail.value.detail)
        assertNull(model.detail.value.commentEditor)
        assertTrue(model.detail.value.comments.isEmpty())
        assertEquals(listOf(AuthProblem.NETWORK_UNAVAILABLE), problems)
    }

    @Test fun `failed detail refresh preserves uncertainty until every comment page is verified`() = runTest(dispatcher) {
        load(); repo.afterWrite = { throw CommentFailure(CommentProblem.UNCERTAIN) }
        model.composeComment(); model.changeComment("Già salvato"); model.saveComment(); advanceUntilIdle()
        repo.detailRead = { throw DataFailure(DataProblem.FORBIDDEN) }
        model.refresh(); advanceUntilIdle()
        assertEquals(CommentProblem.UNCERTAIN, model.detail.value.commentWriteProblem)
        repo.detailRead = { play }
        val cursor = CommentCursor(own.created_at, own.id)
        repo.pages = { after -> if (after == null) CommentPage(listOf(own), cursor) else CommentPage(repo.rows.drop(1), null) }
        repo.reads.clear(); model.refresh(); advanceUntilIdle()
        assertEquals(listOf(null, cursor), repo.reads)
        assertNull(model.detail.value.commentWriteProblem)
        assertEquals(1, repo.calls.size)
    }

    @Test fun `a recreated view model reconciles writer uncertainty instead of silently enabling writes`() = runTest(dispatcher) {
        repo.uncertain = true
        val cursor = CommentCursor(own.created_at, own.id)
        repo.pages = { after -> if (after == null) CommentPage(listOf(own), cursor) else CommentPage(listOf(other), null) }
        val recreated = PlayDetailViewModel(repo, play.play.id, problems::add, account)
        recreated.refresh(); advanceUntilIdle()
        assertEquals(listOf(null, cursor), repo.reads)
        assertFalse(repo.uncertain)
        assertEquals(CommentNotice.RECONCILED, recreated.detail.value.commentNotice)
        assertTrue(repo.calls.isEmpty())
        recreated.clearMemory()
    }

    @Test fun `late mutation success or access error cannot restore cleared account`() = runTest(dispatcher) {
        for (failure in listOf(false, true)) {
            load()
            val wait = CompletableDeferred<Unit>()
            repo.beforeWrite = { withContext(NonCancellable) { wait.await(); if (failure) throw AuthFailure(AuthProblem.INVALID_CREDENTIALS) } }
            model.composeComment(); model.changeComment("Late"); model.saveComment(); runCurrent(); model.clearMemory()
            wait.complete(Unit); advanceUntilIdle()
            assertNull(model.detail.value.detail)
            assertNull(model.detail.value.commentEditor)
            assertNull(model.detail.value.commentNotice)
            assertTrue(problems.isEmpty())
        }
    }

    @Test fun `definitive forbidden and zero-row errors keep editor instead of reporting success`() = runTest(dispatcher) {
        load()
        for (problem in listOf(CommentProblem.FORBIDDEN, CommentProblem.NOT_FOUND)) {
            repo.beforeWrite = { throw CommentFailure(problem) }
            model.composeComment(own.id); model.changeComment("Tentativo"); model.saveComment(); advanceUntilIdle()
            assertEquals(problem, model.detail.value.commentWriteProblem)
            assertNull(model.detail.value.commentNotice)
            assertEquals("Tentativo", model.detail.value.commentEditor!!.content)
            model.dismissComment()
        }
    }

    private class FakePlays : PlaysRepository {
        var rows = mutableListOf(own, other)
        val calls = mutableListOf<String>()
        val reads = mutableListOf<CommentCursor?>()
        var pages: (suspend (CommentCursor?) -> CommentPage)? = null
        var beforeWrite: suspend () -> Unit = {}
        var afterWrite: suspend () -> Unit = {}
        var detailRead: suspend () -> PlayDetail? = { play }
        var uncertain = false
        override val commentWrites = object : CommentWrites {
            override fun hasUncertainOutcome(playId: UUID) = uncertain
            override fun acknowledgeOutcome(playId: UUID) { uncertain = false }
            override suspend fun create(command: CreateComment): PlayCommentRow {
                calls += "create:${command.content}"; beforeWrite()
                val row = own.copy(id = UUID(0, 40), author_name = "Server", content = command.content)
                rows += row; afterWrite(); return row
            }
            override suspend fun update(playId: UUID, commentId: UUID, command: UpdateComment): PlayCommentRow {
                calls += "update:${command.content}"; beforeWrite()
                val row = rows.first { it.id == commentId }.copy(content = command.content, author_name = "Server")
                rows[rows.indexOfFirst { it.id == commentId }] = row; afterWrite(); return row
            }
            override suspend fun delete(playId: UUID, commentId: UUID): PlayCommentRow {
                calls += "delete"; beforeWrite(); val row = rows.first { it.id == commentId }; rows.remove(row); afterWrite(); return row
            }
        }
        override suspend fun detail(id: UUID) = detailRead()
        override suspend fun comments(id: UUID, after: CommentCursor?): CommentPage { reads += after; return pages?.invoke(after) ?: CommentPage(rows.toList(), null) }
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?) = PlayPage(emptyList(), null)
        override suspend fun filterOptions() = HistoryOptions()
        override suspend fun player(id: UUID): PlayerRow? = null
        override suspend fun game(id: UUID): GameRow? = null
    }
    companion object {
        private val account = UUID(0, 1)
        private val play = PlayDetail(PlayRow(UUID(0, 2), null, null, LocalDate.parse("2026-10-13"), null, null, null, "online", null, null, false, null), null, emptyList())
        private val own = PlayCommentRow(UUID(0, 3), play.play.id, account, "Marta", "Mio", Instant.parse("2026-10-13T12:00:00Z"), Instant.parse("2026-10-13T12:00:00Z"))
        private val other = own.copy(id = UUID(0, 4), user_id = UUID(0, 9), author_name = "Luca", content = "Altrui")
    }
}
