package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentOutcomesTest {
    private val account = UUID(0, 1)
    private val play = UUID(0, 2)
    @Test fun `new process registry restores intent after memory is forgotten`() = runTest {
        val store = MemoryCommentOutcomeStore()
        val old = CommentOutcomes(account, store)
        old.begin(play); old.forget(); assertFalse(old.has(play))
        val next = CommentOutcomes(account, store)
        next.restore(); assertTrue(next.has(play))
        next.acknowledge(play); assertFalse(next.has(play)); assertTrue(store.load(account).isEmpty())
    }
    @Test fun `failed durable write never publishes a pending cache entry`() = runTest {
        val store = object : CommentOutcomeStore {
            override suspend fun load(accountId: UUID) = emptySet<UUID>()
            override suspend fun save(accountId: UUID, plays: Set<UUID>) { throw IOException("private path") }
        }
        val outcomes = CommentOutcomes(account, store)
        try { outcomes.begin(play); fail() } catch (failure: CommentFailure) {
            assertEquals(CommentProblem.STORAGE, failure.problem); assertEquals("STORAGE", failure.message)
        }
        assertFalse(outcomes.has(play))
    }
    @Test fun `failed removal keeps original marker and forbids replacement`() = runTest {
        val memory = MemoryCommentOutcomeStore()
        var fail = false
        val store = object : CommentOutcomeStore {
            override suspend fun load(accountId: UUID) = memory.load(accountId)
            override suspend fun save(accountId: UUID, plays: Set<UUID>) {
                if (fail) throw IOException(); memory.save(accountId, plays)
            }
        }
        val outcomes = CommentOutcomes(account, store); outcomes.begin(play); fail = true
        try { outcomes.acknowledge(play); fail() } catch (failure: CommentFailure) { assertEquals(CommentProblem.STORAGE, failure.problem) }
        assertTrue(outcomes.has(play)); assertTrue(play in memory.load(account))
        try { outcomes.begin(play); fail() } catch (failure: CommentFailure) { assertEquals(CommentProblem.UNCERTAIN, failure.problem) }
    }
    @Test fun `cancellation and cache forgetting during persistence retain durable marker`() = runTest {
        val memory = MemoryCommentOutcomeStore(); val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val store = object : CommentOutcomeStore {
            override suspend fun load(accountId: UUID) = memory.load(accountId)
            override suspend fun save(accountId: UUID, plays: Set<UUID>) { started.complete(Unit); finish.await(); memory.save(accountId, plays) }
        }
        val outcomes = CommentOutcomes(account, store)
        val task = launch { outcomes.begin(play) }; started.await(); outcomes.forget(); task.cancel(); finish.complete(Unit); task.join()
        assertFalse(outcomes.has(play)); assertTrue(play in memory.load(account))
        outcomes.restore(); assertTrue(outcomes.has(play))
    }
    @Test fun `multiple play markers are serialized without losing updates`() = runTest {
        val store = MemoryCommentOutcomeStore(); val registry = AccountCommentOutcomes(store)
        val outcomes = registry.forAccount(account)
        coroutineScope { (2L..12L).map { async { outcomes.begin(UUID(0, it)) } }.awaitAll() }
        registry.clear(); assertSame(outcomes, registry.forAccount(account))
        outcomes.restore(); assertEquals(11, store.load(account).size)
        assertTrue(store.load(UUID(0, 99)).isEmpty())
    }
    @Test fun `bounded registry rejects oversized persisted set without accepting writes`() = runTest {
        val store = MemoryCommentOutcomeStore()
        store.save(account, (0L..CommentOutcomes.MAX_PENDING.toLong()).map { UUID(0, it) }.toSet())
        val outcomes = CommentOutcomes(account, store)
        try { outcomes.begin(play); fail() } catch (failure: CommentFailure) { assertEquals(CommentProblem.STORAGE, failure.problem) }
        assertFalse(outcomes.has(play))
    }
}
