package app.kingscore.android.data.remote

import app.kingscore.android.core.auth.*
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.repository.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kingscore.contracts.v1.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class PlayRpcResponse(val status: Int, val body: String)
fun interface PlayRpcTransport : AutoCloseable {
    suspend fun send(request: Request): PlayRpcResponse
    override fun close() {}
}

/** Reuses the existing single-exchange HTTP protection without changing comment writes. */
class NoRetryPlayRpcTransport : PlayRpcTransport {
    private val delegate = NoRetryCommentTransport(captureErrorCode = true)
    override suspend fun send(request: Request): PlayRpcResponse = try {
        delegate.send(request).let { PlayRpcResponse(it.status, it.body) }
    } catch (_: CommentFailure) { throw PlayWriteFailure(PlayWriteProblem.UNCERTAIN) }
    override fun close() { delegate.close() }
}

class SupabasePlayWrites(
    private val auth: SupabaseAuthRepository, private val network: NetworkMonitor, private val accountId: UUID,
    private val transport: PlayRpcTransport = NoRetryPlayRpcTransport(),
    private val enabled: Boolean = false,
    private val journal: PlayWriteJournal = PlayWriteJournal(accountId),
) : PlayWrites {
    init { require(journal.accountId == accountId) }
    private val closed = AtomicBoolean(false)
    override val pendingRequest: PendingPlayRequest? get() = if (closed.get()) null else journal.pendingRequest

    override suspend fun restorePending(): PendingPlayRequest? {
        checkAccess()
        if (!journal.mutex.tryLock()) throw PlayWriteFailure(PlayWriteProblem.BUSY)
        try {
            return auth.withVerifiedSession(accountId) { _, _ -> checkAccess(); restoreJournal(); checkAccess(); journal.pendingRequest }
        } finally { journal.releaseCache(); journal.mutex.unlock() }
    }
    override suspend fun restoreCompleted(): PlayWriteResult? {
        checkAccess()
        if (!journal.mutex.tryLock()) throw PlayWriteFailure(PlayWriteProblem.BUSY)
        try {
            return auth.withVerifiedSession(accountId) { _, _ ->
                checkAccess(); restoreJournal(); checkAccess()
                journal.pending?.takeIf { it.confirmedResponse != null }?.let(::confirmedResult)
            }
        } finally { journal.releaseCache(); journal.mutex.unlock() }
    }
    override suspend fun editor(playId: UUID): PlayEditorResult {
        val response = send("get_play_editor_v1", PlaySaveJson.editorArguments(playId).toString())
        return decode(false) { PlaySaveJson.editor(response.body, playId) }.also { checkAccess() }
    }
    override suspend fun create(command: CreatePlayCommand): PlayMutationResult =
        (write(stored(PlayMutationKind.CREATE, PlaySaveJson.createArguments(command).toString(), command.request_id, null)) as PlayWriteResult.Saved).value
    override suspend fun update(command: UpdatePlayCommand): PlayMutationResult =
        (write(stored(PlayMutationKind.UPDATE, PlaySaveJson.updateArguments(command).toString(), command.request_id, command.play_id)) as PlayWriteResult.Saved).value
    override suspend fun delete(command: DeletePlayCommand): DeletePlayResult =
        (write(stored(PlayMutationKind.DELETE, PlaySaveJson.deleteArguments(command).toString(), command.request_id, command.play_id)) as PlayWriteResult.Deleted).value
    override suspend fun retryPending(): PlayWriteResult = write(null)

    private fun stored(kind: PlayMutationKind, body: String, request: UUID, play: UUID?) =
        StoredPlayMutation(accountId = accountId.toString(), requestId = request.toString(), playId = play?.toString(), kind = kind, body = body)

    private suspend fun restoreJournal() {
        journal.restore()
        journal.pending?.let {
            try {
                it.copy() // Re-run structural validation after decoding a persisted version.
                PlaySaveJson.validateStored(it)
                if (it.confirmedResponse != null) confirmedResult(it)
            } catch (_: Exception) { throw PlayWriteFailure(PlayWriteProblem.STORAGE) }
        }
    }
    private fun confirmedResult(record: StoredPlayMutation): PlayWriteResult =
        if (record.kind == PlayMutationKind.DELETE) PlayWriteResult.Deleted(PlaySaveJson.deletion(checkNotNull(record.confirmedResponse), checkNotNull(record.request.playId)), restored = true)
        else PlayWriteResult.Saved(PlaySaveJson.mutation(checkNotNull(record.confirmedResponse), record.request.playId), restored = true)
    private suspend fun write(newRequest: StoredPlayMutation?): PlayWriteResult {
        checkAccess()
        if (!journal.mutex.tryLock()) throw PlayWriteFailure(PlayWriteProblem.BUSY)
        try {
            return auth.withVerifiedSession(accountId) { client, token ->
                checkAccess(); restoreJournal()
                val previous = journal.pending
                if (previous?.confirmedResponse != null && (newRequest == null || previous.requestId == newRequest.requestId)) {
                    if (newRequest != null && (previous.kind != newRequest.kind || previous.playId != newRequest.playId ||
                        Json.parseToJsonElement(previous.body) != Json.parseToJsonElement(newRequest.body))) throw PlayWriteFailure(PlayWriteProblem.CONFLICT)
                    return@withVerifiedSession confirmedResult(previous)
                }
                if (newRequest != null && journal.pendingRequest != null) throw PlayWriteFailure(PlayWriteProblem.UNCERTAIN)
                val request = newRequest ?: journal.pending ?: throw PlayWriteFailure(PlayWriteProblem.INVALID_INPUT)
                journal.record(request)
                currentCoroutineContext().ensureActive(); checkAccess()
                val response = exchange(client.supabaseHttpUrl, client.supabaseKey, token, request.kind.rpc, request.body,
                    request, preservePendingOnError = newRequest == null)
                val result = decode(true) {
                    if (request.kind == PlayMutationKind.DELETE) PlayWriteResult.Deleted(PlaySaveJson.deletion(response.body, checkNotNull(request.request.playId)))
                    else PlayWriteResult.Saved(PlaySaveJson.mutation(response.body, request.request.playId))
                }
                checkAccess(); journal.record(request.copy(confirmedResponse = response.body)); checkAccess()
                result
            }
        } finally { journal.releaseCache(); journal.mutex.unlock() }
    }
    private suspend fun send(rpc: String, body: String): PlayRpcResponse {
        checkAccess()
        return auth.withVerifiedSession(accountId) { client, token ->
            checkAccess(); exchange(client.supabaseHttpUrl, client.supabaseKey, token, rpc, body)
        }
    }
    private suspend fun exchange(base: String, key: String, token: String, rpc: String, body: String,
        prepared: StoredPlayMutation? = null, preservePendingOnError: Boolean = false): PlayRpcResponse {
        val request = Request.Builder().url("$base/rest/v1/rpc/$rpc")
            .header("Authorization", "Bearer $token").header("apikey", key)
            .header("Accept", "application/json").post(body.toRequestBody("application/json".toMediaType())).build()
        val response = try { transport.send(request) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: PlayWriteFailure) {
                if (prepared == null) throw PlayWriteFailure(PlayWriteProblem.INVALID_RESPONSE)
                throw failure
            }
            catch (_: IOException) { throw PlayWriteFailure(if (prepared == null) PlayWriteProblem.INVALID_RESPONSE else PlayWriteProblem.UNCERTAIN) }
        checkAccess()
        val code = if (response.status == 200) null else runCatching {
            Json.parseToJsonElement(response.body).jsonObject["code"]?.jsonPrimitive
                ?.takeIf { it.isString }?.content
        }.getOrNull()
        // A gateway's status alone cannot prove that a mutation was rejected before commit.
        val rejected = when (response.status) {
            400, 409 -> code == "PT${response.status}"
            401 -> code in setOf("PT401", "PGRST301")
            403 -> code in setOf("PT403", "42501")
            404 -> code in setOf("PT404", "PGRST202")
            else -> false
        }
        if (response.status != 200 && !rejected)
            throw PlayWriteFailure(if (prepared == null) PlayWriteProblem.INVALID_RESPONSE else PlayWriteProblem.UNCERTAIN)
        return when (response.status) {
            200 -> response
            401 -> { if (prepared != null && !preservePendingOnError) journal.record(null); throw AuthFailure(AuthProblem.INVALID_CREDENTIALS) }
            400, 403, 404, 409 -> {
                if (prepared != null && !preservePendingOnError) journal.record(null)
                throw PlayWriteFailure(when (response.status) {
                    403 -> PlayWriteProblem.FORBIDDEN
                    404 -> if (code == "PGRST202")
                        PlayWriteProblem.UNAVAILABLE else PlayWriteProblem.NOT_FOUND
                    409 -> PlayWriteProblem.CONFLICT
                    else -> PlayWriteProblem.INVALID_INPUT
                })
            }
            else -> throw PlayWriteFailure(if (prepared == null) PlayWriteProblem.INVALID_RESPONSE else PlayWriteProblem.UNCERTAIN)
        }
    }
    private inline fun <T> decode(write: Boolean, action: () -> T): T = try { action() }
        catch (_: Exception) { throw PlayWriteFailure(if (write) PlayWriteProblem.UNCERTAIN else PlayWriteProblem.INVALID_RESPONSE) }
    private fun checkAccess() {
        if (!enabled) throw PlayWriteFailure(PlayWriteProblem.UNAVAILABLE)
        if (closed.get()) throw PlayWriteFailure(PlayWriteProblem.CLOSED)
        if (!network.connected.value) throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE)
    }
    override fun close() { closed.set(true); transport.close(); journal.forgetOnRelease() }
}
