package app.kingscore.android.data.repository

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface CommentOutcomeStore {
    suspend fun load(accountId: UUID): Set<UUID>
    suspend fun save(accountId: UUID, plays: Set<UUID>)
}
class MemoryCommentOutcomeStore : CommentOutcomeStore {
    private val records = ConcurrentHashMap<UUID, Set<UUID>>()
    override suspend fun load(accountId: UUID) = records[accountId].orEmpty().toSet()
    override suspend fun save(accountId: UUID, plays: Set<UUID>) { records[accountId] = plays.toSet() }
}

/** Only play IDs. No content, credentials, retry body or offline queue. */
class CommentOutcomes(val accountId: UUID, private val store: CommentOutcomeStore = MemoryCommentOutcomeStore()) {
    private val mutex = Mutex()
    @Volatile private var pending: Set<UUID> = emptySet()
    private var loaded = false
    @Volatile private var forgetAfterUse = false
    fun has(playId: UUID) = playId in pending
    suspend fun restore() = locked { load() }
    suspend fun begin(playId: UUID) = locked {
        load()
        if (playId in pending) throw CommentFailure(CommentProblem.UNCERTAIN)
        require(pending.size < MAX_PENDING)
        persist(pending + playId)
    }
    suspend fun acknowledge(playId: UUID) = locked { load(); persist(pending - playId) }
    fun forget() {
        forgetAfterUse = true
        if (mutex.tryLock()) try { releaseCache() } finally { mutex.unlock() }
    }
    private suspend fun load() {
        if (!loaded) {
            val values = store.load(accountId).toSet()
            require(values.size <= MAX_PENDING)
            pending = values; loaded = true
        }
    }
    private suspend fun persist(values: Set<UUID>) = withContext(NonCancellable) {
        store.save(accountId, values)
        pending = values; loaded = true
    }
    private fun releaseCache() {
        if (forgetAfterUse) { pending = emptySet(); loaded = false; forgetAfterUse = false }
    }
    private suspend fun <T> locked(action: suspend () -> T): T = mutex.withLock {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CommentFailure) { throw failure }
        catch (_: Exception) { throw CommentFailure(CommentProblem.STORAGE) }
        finally { releaseCache() }
    }
    companion object { const val MAX_PENDING = 1024 }
}

class AccountCommentOutcomes(private val store: CommentOutcomeStore = MemoryCommentOutcomeStore()) {
    private var account: UUID? = null
    private val outcomes = ConcurrentHashMap<UUID, CommentOutcomes>()
    @Synchronized fun forAccount(id: UUID): CommentOutcomes {
        if (account != id) { clear(); account = id }
        return outcomes.computeIfAbsent(id) { CommentOutcomes(it, store) }
    }
    @Synchronized fun clear() { outcomes.values.forEach { it.forget() }; account = null }
}
