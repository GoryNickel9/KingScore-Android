package app.kingscore.android.feature.navigation

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ServerRevisionTest {
    @Test fun `observed source version survives activity observer recreation without missing invalidation`() {
        val model=AuthenticatedResources()
        val session=model.acquire(UUID(0,1),null,null,null)
        session.observeServerRevision("7");assertEquals(0,session.playDataRevision.value)
        // A new Activity restarts its flow collector; the retained session remembers version 7.
        val retained=model.acquire(UUID(0,1),null,null,null)
        assertSame(session,retained)
        retained.observeServerRevision("8");assertEquals(1,retained.playDataRevision.value)
        retained.observeServerRevision("8");assertEquals(1,retained.playDataRevision.value)
        model.release(session)
    }
    @Test fun `account changes reset source history and released sessions ignore late versions`() {
        val model=AuthenticatedResources()
        val first=model.acquire(UUID(0,1),null,null,null)
        first.observeServerRevision("7")
        val second=model.acquire(UUID(0,2),null,null,null)
        second.observeServerRevision("8");assertEquals(0,second.playDataRevision.value)
        first.observeServerRevision("9");assertEquals(0,first.playDataRevision.value)
        model.release(second)
    }
}
