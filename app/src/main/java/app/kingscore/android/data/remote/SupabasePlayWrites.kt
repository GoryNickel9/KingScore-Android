package app.kingscore.android.data.remote

import app.kingscore.android.core.auth.*
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.repository.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kingscore.contracts.v1.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
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
) : PlayWrites {
    private data class Prepared(val rpc: String, val body: String, val request: PendingPlayRequest, val deletion: Boolean = false)
    private val closed = AtomicBoolean(false)
    private val mutation = Mutex()
    @Volatile private var pending: Prepared? = null
    override val pendingRequest: PendingPlayRequest? get() = pending?.request

    override suspend fun editor(playId: UUID): PlayEditorResult {
        val response = send("get_play_editor_v1", PlaySaveJson.editorArguments(playId).toString())
        return decode(false) { PlaySaveJson.editor(response.body, playId) }.also { checkAccess() }
    }
    override suspend fun create(command: CreatePlayCommand): PlayMutationResult =
        (write(Prepared("create_play_v1", PlaySaveJson.createArguments(command).toString(), PendingPlayRequest(command.request_id, null))) as PlayWriteResult.Saved).value
    override suspend fun update(command: UpdatePlayCommand): PlayMutationResult =
        (write(Prepared("update_play_v1", PlaySaveJson.updateArguments(command).toString(), PendingPlayRequest(command.request_id, command.play_id))) as PlayWriteResult.Saved).value
    override suspend fun delete(command: DeletePlayCommand): DeletePlayResult =
        (write(Prepared("delete_play_v1", PlaySaveJson.deleteArguments(command).toString(), PendingPlayRequest(command.request_id, command.play_id), true)) as PlayWriteResult.Deleted).value

    override suspend fun retryPending(): PlayWriteResult = write(null)

    private suspend fun write(newRequest: Prepared?): PlayWriteResult {
        checkAccess()
        if (!mutation.tryLock()) throw PlayWriteFailure(PlayWriteProblem.BUSY)
        try {
            if (newRequest != null && pending != null) throw PlayWriteFailure(PlayWriteProblem.UNCERTAIN)
            val request = newRequest ?: pending ?: throw PlayWriteFailure(PlayWriteProblem.INVALID_INPUT)
            val response = send(request.rpc, request.body, request, preservePendingOnError = newRequest == null)
            val result = decode(true) {
                if (request.deletion) PlayWriteResult.Deleted(PlaySaveJson.deletion(response.body, checkNotNull(request.request.playId)))
                else PlayWriteResult.Saved(PlaySaveJson.mutation(response.body, request.request.playId))
            }
            checkAccess()
            pending = null
            return result
        } finally { mutation.unlock() }
    }

    private suspend fun send(rpc: String, body: String, prepared: Prepared? = null, preservePendingOnError: Boolean = false): PlayRpcResponse {
        checkAccess()
        return auth.withVerifiedSession(accountId) { client, token ->
            checkAccess()
            val request = Request.Builder().url("${client.supabaseHttpUrl}/rest/v1/rpc/$rpc")
                .header("Authorization", "Bearer $token").header("apikey", client.supabaseKey)
                .header("Accept", "application/json").post(body.toRequestBody("application/json".toMediaType())).build()
            if (prepared != null) pending = prepared
            val response = try { transport.send(request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: PlayWriteFailure) {
                    if (prepared == null) throw PlayWriteFailure(PlayWriteProblem.INVALID_RESPONSE)
                    throw failure
                }
                catch (_: IOException) { throw PlayWriteFailure(if (prepared == null) PlayWriteProblem.INVALID_RESPONSE else PlayWriteProblem.UNCERTAIN) }
            checkAccess()
            when (response.status) {
                200 -> response
                401 -> { if (prepared != null && !preservePendingOnError) pending = null; throw AuthFailure(AuthProblem.INVALID_CREDENTIALS) }
                400, 403, 404, 409, 422 -> {
                    if (prepared != null && !preservePendingOnError) pending = null
                    throw PlayWriteFailure(when (response.status) {
                        403 -> PlayWriteProblem.FORBIDDEN
                        404 -> if (runCatching { Json.parseToJsonElement(response.body).jsonObject["code"]?.jsonPrimitive?.contentOrNull }.getOrNull() == "PGRST202")
                            PlayWriteProblem.UNAVAILABLE else PlayWriteProblem.NOT_FOUND
                        409 -> PlayWriteProblem.CONFLICT
                        else -> PlayWriteProblem.INVALID_INPUT
                    })
                }
                else -> throw PlayWriteFailure(if (prepared == null) PlayWriteProblem.INVALID_RESPONSE else PlayWriteProblem.UNCERTAIN)
            }
        }
    }
    private inline fun <T> decode(write: Boolean, action: () -> T): T = try { action() }
        catch (_: Exception) { throw PlayWriteFailure(if (write) PlayWriteProblem.UNCERTAIN else PlayWriteProblem.INVALID_RESPONSE) }

    private fun checkAccess() {
        if (!enabled) throw PlayWriteFailure(PlayWriteProblem.UNAVAILABLE)
        if (closed.get()) throw PlayWriteFailure(PlayWriteProblem.CLOSED)
        if (!network.connected.value) throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE)
    }
    override fun close() { closed.set(true); transport.close() }
}
