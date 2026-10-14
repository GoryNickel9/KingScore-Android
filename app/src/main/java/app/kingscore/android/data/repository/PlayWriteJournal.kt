package app.kingscore.android.data.repository

import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
enum class PlayMutationKind(val rpc: String) {
    CREATE("create_play_v1"), UPDATE("update_play_v1"), DELETE("delete_play_v1")
}

/** Contains no credential, endpoint or rating. The exact body survives transport ambiguity. */
@Serializable
data class StoredPlayMutation(
    val version: Int = 1, val accountId: String, val requestId: String, val playId: String?,
    val kind: PlayMutationKind, val body: String,
    val confirmedResponse: String? = null,
) {
    init {
        require(version == 1)
        require(UUID.fromString(accountId).toString() == accountId)
        require(UUID.fromString(requestId).toString() == requestId)
        require(playId == null || UUID.fromString(playId).toString() == playId)
        require((kind == PlayMutationKind.CREATE) == (playId == null))
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES)
        require(confirmedResponse == null || confirmedResponse.toByteArray(Charsets.UTF_8).size <= 1024)
    }
    val request: PendingPlayRequest get() = PendingPlayRequest(UUID.fromString(requestId), playId?.let(UUID::fromString))
    override fun toString(): String = "StoredPlayMutation(version=$version, kind=$kind)"
    companion object { const val MAX_BODY_BYTES = 1048576 + 1024 }
}

interface PendingPlayStore {
    suspend fun load(accountId: UUID): StoredPlayMutation?
    suspend fun save(accountId: UUID, record: StoredPlayMutation?)
}

/** Default for the still-disabled client; production persistence must be injected explicitly. */
class MemoryPendingPlayStore : PendingPlayStore {
    private val records = mutableMapOf<UUID, StoredPlayMutation>()
    override suspend fun load(accountId: UUID): StoredPlayMutation? = synchronized(records) { records[accountId] }
    override suspend fun save(accountId: UUID, record: StoredPlayMutation?) {
        synchronized(records) {
            require(record == null || record.accountId == accountId.toString())
            if (record == null) records.remove(accountId) else records[accountId] = record
        }
    }
}

/** Share one journal per account between clients; its lock precedes the Auth lock. */
class PlayWriteJournal(val accountId: UUID, private val store: PendingPlayStore = MemoryPendingPlayStore()) {
    internal val mutex = Mutex()
    @Volatile private var current: StoredPlayMutation? = null
    private var loaded = false
    @Volatile private var forgetAfterUse = false
    val pendingRequest: PendingPlayRequest? get() = current?.takeIf { it.confirmedResponse == null }?.request
    internal val pending: StoredPlayMutation? get() = current

    internal suspend fun restore() {
        if (!loaded) {
            val record = storage { store.load(accountId) }
            if (record != null && record.accountId != accountId.toString()) throw PlayWriteFailure(PlayWriteProblem.STORAGE)
            current = record; loaded = true
        }
    }
    internal suspend fun record(record: StoredPlayMutation?) {
        storage { withContext(NonCancellable) {
            store.save(accountId, record)
            current = record; loaded = true
        } }
    }
    internal fun forgetOnRelease() {
        forgetAfterUse = true
        if (mutex.tryLock()) try { releaseCache() } finally { mutex.unlock() }
    }
    internal fun releaseCache() {
        if (forgetAfterUse) { current = null; loaded = false; forgetAfterUse = false }
    }
    private suspend fun <T> storage(action: suspend () -> T): T = try { action() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { throw PlayWriteFailure(PlayWriteProblem.STORAGE) }
}
