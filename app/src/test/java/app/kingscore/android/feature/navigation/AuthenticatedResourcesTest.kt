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
    @Test fun `confirmed play changes survive recreation but stop with the session`() {
        val resources = AuthenticatedResources()
        val session = resources.acquire(account, { FakePlays() }, null, null)
        session.playSaved()
        assertEquals(1, resources.acquire(account, null, null, null).playDataRevision.value)
        resources.release(session)
        session.playSaved()
        val fresh = resources.acquire(account, { FakePlays() }, null, null)
        assertEquals(1, session.playDataRevision.value)
        assertEquals(0, fresh.playDataRevision.value)
        resources.release(fresh)
    }
    @Test fun `role or capability loss closes old models before reusing the same account`() {
        val resources = AuthenticatedResources()
        val old = resources.acquire(account, { FakePlays() }, null, null, isAdmin = true, editorAvailable = true)
        val model = ViewModelProvider(old, modelFactory { ProbeModel() })[ProbeModel::class.java]
        val revoked = resources.acquire(account, { FakePlays() }, null, null, isAdmin = false, editorAvailable = true)
        assertNotSame(old, revoked); assertEquals(1, model.cleared)
        resources.release(old)
        assertSame(revoked, resources.acquire(account, null, null, null, isAdmin = false, editorAvailable = true))
        val unavailable = resources.acquire(account, null, null, null, isAdmin = false, editorAvailable = false)
        assertNotSame(revoked, unavailable)
        resources.release(unavailable)
    }
    @Test fun `access removal closes the ratings publication source only once`() {
        val resources = AuthenticatedResources()
        var closed = 0
        val ratings = object : RatingsRepository {
            override suspend fun games() = emptyList<kingscore.contracts.v1.GameRow>()
            override suspend fun ranking(gameId: UUID?) = Ranking(null, emptyList())
            override suspend fun history(rating: PublishedRating, after: RatingHistoryCursor?) = RatingHistoryPage(emptyList(), null)
            override fun close() { closed++ }
        }
        val session = resources.acquire(account, null, null, { ratings })
        resources.release(session); resources.release(session)
        assertEquals(1, closed)
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
