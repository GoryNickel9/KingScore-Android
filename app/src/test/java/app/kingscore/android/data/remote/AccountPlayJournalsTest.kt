package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AccountPlayJournalsTest {
    @Test fun `registry preserves account lock across memory clearing and isolates owners`() = runBlocking {
        val registry = AccountPlayJournals(MemoryPendingPlayStore())
        val f = PlayWriteFixtures
        val journal = registry.forAccount(f.account)
        journal.mutex.lock()
        journal.record(StoredPlayMutation(accountId = f.account.toString(), requestId = f.request.toString(),
            playId = null, kind = PlayMutationKind.CREATE, body = PlaySaveJson.createArguments(f.create()).toString()))
        registry.clearMemory()
        assertSame(journal, registry.forAccount(f.account))
        assertTrue(journal.mutex.isLocked)
        assertNotSame(journal, registry.forAccount(UUID(8, 8)))
        journal.releaseCache(); journal.mutex.unlock()
        assertNull(journal.pendingRequest)
        journal.restore(); assertEquals(f.request, journal.pendingRequest?.requestId)
        assertEquals(PlayMutationKind.CREATE, journal.pendingRequest?.kind)
    }
}
