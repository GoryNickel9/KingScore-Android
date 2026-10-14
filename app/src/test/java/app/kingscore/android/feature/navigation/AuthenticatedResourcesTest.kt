package app.kingscore.android.feature.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.kingscore.android.data.repository.*
import app.kingscore.android.ui.modelFactory
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AuthenticatedResourcesTest {
    private val account = UUID(0, 1)
    @Test fun `same account retains repositories and child models across acquisition`() {
        val resources = AuthenticatedResources()
        var created = 0
        val session = resources.acquire(account, { created++; FakePlays() }, null, null)
        val model = ViewModelProvider(session, modelFactory { ProbeModel() })[ProbeModel::class.java]
        val restored = resources.acquire(account, { created++; FakePlays() }, null, null)
        assertSame(session, restored); assertEquals(1, created)
        assertSame(model, ViewModelProvider(restored, modelFactory { ProbeModel() })[ProbeModel::class.java])
        resources.release(session)
    }
    @Test fun `access removal clears child models and closes the writer only once`() {
        val resources = AuthenticatedResources(); val repo = FakePlays()
        val session = resources.acquire(account, { repo }, null, null)
        val model = ViewModelProvider(session, modelFactory { ProbeModel() })[ProbeModel::class.java]
        resources.release(session); resources.release(session)
        assertEquals(1, model.cleared); assertEquals(1, repo.closed)
        assertNotSame(session, resources.acquire(account, { FakePlays() }, null, null))
    }
    @Test fun `account switch and a late old disposal cannot clear the new account`() {
        val resources = AuthenticatedResources(); val oldRepo = FakePlays(); val newRepo = FakePlays()
        val old = resources.acquire(account, { oldRepo }, null, null)
        val next = resources.acquire(UUID(0, 2), { newRepo }, null, null)
        assertEquals(1, oldRepo.closed)
        resources.release(old)
        assertEquals(0, newRepo.closed)
        assertSame(next, resources.acquire(UUID(0, 2), null, null, null))
        resources.release(next); assertEquals(1, newRepo.closed)
    }
    private class ProbeModel : ViewModel() {
        var cleared = 0
        override fun onCleared() { cleared++ }
    }
    private class FakePlays : PlaysRepository {
        var closed = 0
        override val commentWrites = object : CommentWrites {
            override suspend fun create(command: kingscore.contracts.v1.CreateComment): kingscore.contracts.v1.PlayCommentRow = error("unused")
            override suspend fun update(playId: UUID, commentId: UUID, command: kingscore.contracts.v1.UpdateComment): kingscore.contracts.v1.PlayCommentRow = error("unused")
            override suspend fun delete(playId: UUID, commentId: UUID): kingscore.contracts.v1.PlayCommentRow = error("unused")
            override fun hasUncertainOutcome(playId: UUID) = false
            override suspend fun acknowledgeOutcome(playId: UUID) {}
            override fun close() { closed++ }
        }
        override suspend fun player(id: UUID): kingscore.contracts.v1.PlayerRow? = null
        override suspend fun game(id: UUID): kingscore.contracts.v1.GameRow? = null
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?) = PlayPage(emptyList(), null)
        override suspend fun filterOptions() = HistoryOptions()
        override suspend fun detail(id: UUID): kingscore.contracts.v1.PlayDetail? = null
        override suspend fun comments(id: UUID, after: CommentCursor?) = CommentPage(emptyList(), null)
    }
}
