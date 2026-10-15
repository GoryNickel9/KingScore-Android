package app.kingscore.android.feature.plays

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
class CommentChangeCallbackTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakePlays()
    private var changes = 0
    private val models = mutableListOf<PlayDetailViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { models.forEach { it.clearMemory() }; Dispatchers.resetMain() }
    private fun model() = PlayDetailViewModel(repository, play.play.id, { error("Unexpected access failure: $it") }, account,
        onCommentsChanged = { changes++ }).also { models += it }

    @Test fun `confirmed create update and delete each notify once while reads do not notify`() = runTest(dispatcher) {
        val model = model()
        model.refresh(); advanceUntilIdle()
        assertEquals(0, changes)
        model.composeComment(); model.changeComment("Created"); model.saveComment(); advanceUntilIdle()
        assertEquals(1, changes)
        assertEquals(CommentNotice.CREATED, model.detail.value.commentNotice)
        val row = model.detail.value.comments.single()
        model.composeComment(row.id); model.changeComment("Updated"); model.saveComment(); advanceUntilIdle()
        assertEquals(2, changes)
        assertEquals(CommentNotice.UPDATED, model.detail.value.commentNotice)
        model.requestCommentDeletion(row.id); model.deleteComment(); advanceUntilIdle()
        assertEquals(3, changes)
        assertEquals(CommentNotice.DELETED, model.detail.value.commentNotice)
        model.recheckComments(); model.refresh(); advanceUntilIdle()
        assertEquals(3, changes)
    }

    @Test fun `uncertain write does not notify until reviewed outcome is acknowledged`() = runTest(dispatcher) {
        val model = model()
        model.refresh(); advanceUntilIdle()
        repository.writer.afterWrite = { repository.writer.uncertain = true; throw CommentFailure(CommentProblem.UNCERTAIN) }
        model.composeComment(); model.changeComment("Maybe committed"); model.saveComment(); advanceUntilIdle()
        assertEquals(CommentProblem.UNCERTAIN, model.detail.value.commentWriteProblem)
        assertEquals(0, changes)
        model.acknowledgeComments(); advanceUntilIdle()
        assertEquals(0, changes)
        model.recheckComments(); advanceUntilIdle()
        assertTrue(model.detail.value.commentReviewReady)
        assertEquals(0, changes)
        model.acknowledgeComments(); advanceUntilIdle()
        assertEquals(CommentNotice.RECONCILED, model.detail.value.commentNotice)
        assertEquals(1, changes)
        model.acknowledgeComments(); advanceUntilIdle()
        assertEquals(1, changes)
    }

    @Test fun `confirmed mutation notifies even if subsequent comment read fails`() = runTest(dispatcher) {
        val model = model()
        model.refresh(); advanceUntilIdle()
        repository.failReads = true
        model.composeComment(); model.changeComment("Saved"); model.saveComment(); advanceUntilIdle()
        assertEquals(1, changes)
        assertEquals(CommentNotice.CREATED, model.detail.value.commentNotice)
        assertEquals(DataProblem.FORBIDDEN, model.detail.value.commentsProblem)
        assertNull(model.detail.value.commentWriteProblem)
    }

    @Test fun `late non cancellable write after clear cannot emit a change callback`() = runTest(dispatcher) {
        val pending = CompletableDeferred<Unit>()
        val model = model()
        model.refresh(); advanceUntilIdle()
        repository.writer.afterWrite = { withContext(NonCancellable) { pending.await() } }
        model.composeComment(); model.changeComment("Late"); model.saveComment(); runCurrent()
        model.clearMemory()
        pending.complete(Unit); advanceUntilIdle()
        assertEquals(0, changes)
        assertNull(model.detail.value.detail)
    }

    @Test fun `failed acknowledgement does not announce a confirmed change`() = runTest(dispatcher) {
        repository.writer.uncertain = true
        repository.writer.failAcknowledgement = true
        val model = model()
        model.refresh(); advanceUntilIdle()
        assertTrue(model.detail.value.commentReviewReady)
        model.acknowledgeComments(); advanceUntilIdle()
        assertEquals(0, changes)
        assertEquals(CommentProblem.STORAGE, model.detail.value.commentWriteProblem)
        assertNull(model.detail.value.commentNotice)
    }

    @Test fun `retained comments cannot be created edited or deleted while the detail refresh is pending`() = runTest(dispatcher) {
        val mine = PlayCommentRow(id(100), play.play.id, account, "Server", "Existing", Instant.EPOCH, Instant.EPOCH)
        repository.rows = listOf(mine)
        val model = model()
        model.refresh(); advanceUntilIdle()
        val pending = CompletableDeferred<PlayDetail?>()
        repository.fetchDetail = { pending.await() }
        model.refresh(); runCurrent()
        assertTrue(model.detail.value.loading)
        assertEquals(play, model.detail.value.detail)
        assertEquals(listOf(mine), model.detail.value.comments)
        model.composeComment(); model.changeComment("New while refreshing"); model.saveComment()
        model.composeComment(mine.id); model.changeComment("Edit while refreshing"); model.saveComment()
        model.requestCommentDeletion(mine.id); model.deleteComment()
        runCurrent()
        assertEquals(0, repository.writer.writes)
        assertEquals(0, changes)
        assertNull(model.detail.value.commentEditor)
        assertNull(model.detail.value.deletingComment)
        pending.complete(play); advanceUntilIdle()
        model.composeComment(mine.id); model.changeComment("Edit after refresh"); model.saveComment(); advanceUntilIdle()
        assertEquals(1, repository.writer.writes)
        assertEquals(1, changes)
        assertEquals("Edit after refresh", model.detail.value.comments.single().content)
    }

    @Test fun `forbidden refresh erases retained private comments and editor but preserves uncertain journal`() = runTest(dispatcher) {
        repository.rows = listOf(PlayCommentRow(id(90), play.play.id, account, "Server", "Existing private comment", Instant.EPOCH, Instant.EPOCH))
        val model = model()
        model.refresh(); advanceUntilIdle()
        repository.writer.afterWrite = { repository.writer.uncertain = true; throw CommentFailure(CommentProblem.UNCERTAIN) }
        model.composeComment(); model.changeComment("Private uncertain text"); model.saveComment(); advanceUntilIdle()
        assertNotNull(model.detail.value.commentEditor)
        assertTrue(repository.writer.uncertain)
        val pending = CompletableDeferred<PlayDetail?>()
        repository.fetchDetail = { pending.await() }
        model.refresh(); runCurrent()
        assertNotNull(model.detail.value.detail)
        assertEquals("Existing private comment", model.detail.value.comments.single().content)
        pending.completeExceptionally(DataFailure(DataProblem.FORBIDDEN)); advanceUntilIdle()
        assertEquals(DataProblem.FORBIDDEN, model.detail.value.problem)
        assertFalse(model.detail.value.loading)
        assertNull(model.detail.value.detail)
        assertTrue(model.detail.value.comments.isEmpty())
        assertNull(model.detail.value.commentEditor)
        assertNull(model.detail.value.deletingComment)
        assertNull(model.detail.value.commentCursor)
        assertEquals(CommentProblem.UNCERTAIN, model.detail.value.commentWriteProblem)
        assertTrue(repository.writer.uncertain)
        val writes = repository.writer.writes
        model.composeComment(); model.saveComment(); model.deleteComment(); model.acknowledgeComments(); advanceUntilIdle()
        assertEquals(writes, repository.writer.writes)
        assertTrue(repository.writer.uncertain)
        assertEquals(0, changes)
    }

    private class FakePlays : PlaysRepository {
        var rows = listOf<PlayCommentRow>()
        var failReads = false
        var fetchDetail: suspend () -> PlayDetail? = { play }
        val writer = Writer(this)
        override val commentWrites: CommentWrites get() = writer
        override suspend fun player(id: UUID): PlayerRow? = null
        override suspend fun game(id: UUID): GameRow? = null
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?) = PlayPage(listOf(play), null)
        override suspend fun filterOptions() = HistoryOptions()
        override suspend fun detail(id: UUID) = fetchDetail()
        override suspend fun comments(id: UUID, after: CommentCursor?): CommentPage {
            if (failReads) throw DataFailure(DataProblem.FORBIDDEN)
            return CommentPage(rows, null)
        }
    }
    private class Writer(private val repository: FakePlays) : CommentWrites {
        var uncertain = false
        var failAcknowledgement = false
        var afterWrite: suspend () -> Unit = {}
        var writes = 0
        override fun hasUncertainOutcome(playId: UUID) = uncertain
        override suspend fun acknowledgeOutcome(playId: UUID) {
            if (failAcknowledgement) throw CommentFailure(CommentProblem.STORAGE)
            uncertain = false
        }
        override suspend fun create(command: CreateComment): PlayCommentRow {
            writes++
            val row = PlayCommentRow(id(100), command.play_id, account, "Server", command.content, Instant.EPOCH, Instant.EPOCH)
            repository.rows = repository.rows + row
            afterWrite()
            return row
        }
        override suspend fun update(playId: UUID, commentId: UUID, command: UpdateComment): PlayCommentRow {
            writes++
            val updated = repository.rows.single { it.id == commentId }.copy(content = command.content)
            repository.rows = repository.rows.map { if (it.id == commentId) updated else it }
            afterWrite()
            return updated
        }
        override suspend fun delete(playId: UUID, commentId: UUID): PlayCommentRow {
            writes++
            val deleted = repository.rows.single { it.id == commentId }
            repository.rows = repository.rows.filterNot { it.id == commentId }
            afterWrite()
            return deleted
        }
    }
    companion object {
        private fun id(n: Int) = UUID(0, n.toLong())
        private val account = id(1)
        private val play = PlayDetail(PlayRow(id(2), null, null, LocalDate.parse("2026-10-12"), null, null,
            null, "online", null, null, false, null), null, emptyList())
    }
}
