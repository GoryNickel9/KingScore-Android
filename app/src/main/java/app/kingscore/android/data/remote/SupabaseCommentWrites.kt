package app.kingscore.android.data.remote

import app.kingscore.android.core.auth.*
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.repository.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kingscore.contracts.v1.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody

data class CommentHttpResponse(val status: Int, val body: String)
fun interface CommentTransport : AutoCloseable {
    suspend fun send(request: Request): CommentHttpResponse
    override fun close() {}
}

/** A write is never replayed by the HTTP engine, including a 503 Retry-After response. */
class NoRetryCommentTransport(private val captureErrorCode: Boolean = false,
    private val maxSuccessBytes: ((Request) -> Long)? = null) : CommentTransport {
    private val maxErrorCodeBytes = 8192L
    private val closed = AtomicBoolean(false)
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).cache(null)
        .callTimeout(15, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            if (checkNotNull(chain.request().tag(AtomicInteger::class.java)).getAndIncrement() != 0)
                throw IOException("Repeated comment write blocked")
            chain.proceed(chain.request())
        }.build()

    override suspend fun send(request: Request): CommentHttpResponse = suspendCancellableCoroutine { continuation ->
        if (closed.get()) { continuation.cancel(); return@suspendCancellableCoroutine }
        val call = client.newCall(request.newBuilder().tag(AtomicInteger::class.java, AtomicInteger()).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(CommentFailure(CommentProblem.UNCERTAIN)) }
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { CommentHttpResponse(it.code, if (it.isSuccessful) successBody(it)
                        else if (captureErrorCode) errorCode(it) else "") }
                } catch (_: IOException) { continuation.resumeWithException(CommentFailure(CommentProblem.UNCERTAIN)); return }
                continuation.resume(result)
            }
        })
    }
    private fun successBody(response: Response): String {
        val limit = maxSuccessBytes?.invoke(response.request) ?: return response.body?.string().orEmpty()
        require(limit in 1 until Long.MAX_VALUE)
        val source = response.body?.source() ?: return ""
        source.request(limit + 1)
        if (source.buffer.size > limit) throw IOException("Response exceeds limit")
        return try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(source.readByteArray())).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { throw IOException("Invalid response encoding") }
    }
    private fun errorCode(response: Response): String {
        val source = response.body?.source() ?: return ""
        source.request(maxErrorCodeBytes + 1)
        if (source.buffer.size > maxErrorCodeBytes) return ""
        val body = source.readUtf8()
        val code = runCatching { Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive
            ?.takeIf { it.isString }?.content }.getOrNull() ?: return ""
        if (!Regex("^[A-Z0-9]{5,16}$").matches(code)) return ""
        // Share only the bounded machine code, never server messages or diagnostic details.
        return buildJsonObject { put("code", code) }.toString()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.dispatcher.cancelAll()
        val executor = client.dispatcher.executorService
        // Closing an idle TLS socket can write close_notify; never do it on the UI thread.
        executor.execute {
            try { client.connectionPool.evictAll() }
            finally { executor.shutdown() }
        }
    }
}

class SupabaseCommentWrites(private val auth: SupabaseAuthRepository, private val network: NetworkMonitor,
    private val accountId: UUID, private val transport: CommentTransport = NoRetryCommentTransport(),
    private val outcomes: CommentOutcomes = CommentOutcomes(accountId)) : CommentWrites {
    @Volatile private var closed = false
    init { require(outcomes.accountId == accountId) }
    override fun hasUncertainOutcome(playId: UUID): Boolean = outcomes.has(playId)
    override suspend fun restoreUncertainOutcome(playId: UUID): Boolean {
        checkNetwork()
        return auth.withVerifiedSession(accountId) { _, _ -> checkNetwork(); outcomes.restore(); checkNetwork(); outcomes.has(playId) }
    }
    override suspend fun acknowledgeOutcome(playId: UUID) {
        checkNetwork()
        auth.withVerifiedSession(accountId) { _, _ -> checkNetwork(); outcomes.acknowledge(playId); checkNetwork() }
    }
    override suspend fun create(command: CreateComment): PlayCommentRow = write("POST", command.play_id, null,
        buildJsonObject { put("play_id", command.play_id.toString()); put("content", command.content) }, command.content)

    override suspend fun update(playId: UUID, commentId: UUID, command: UpdateComment): PlayCommentRow =
        write("PATCH", playId, commentId, buildJsonObject { put("content", command.content) }, command.content)

    override suspend fun delete(playId: UUID, commentId: UUID): PlayCommentRow = write("DELETE", playId, commentId, null, null)
    override fun close() { closed = true; outcomes.forget(); transport.close() }

    private suspend fun write(method: String, playId: UUID, commentId: UUID?, payload: JsonObject?, content: String?): PlayCommentRow {
        checkNetwork()
        return auth.withVerifiedSession(accountId) { client, token ->
            checkNetwork()
            val url = "${client.supabaseHttpUrl}/rest/v1/play_comments".toHttpUrl().newBuilder()
                .addQueryParameter("select", "*").apply {
                    if (commentId != null) {
                        addQueryParameter("id", "eq.$commentId")
                        addQueryParameter("user_id", "eq.$accountId")
                        addQueryParameter("play_id", "eq.$playId")
                    }
                }.build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .header("apikey", client.supabaseKey).header("Prefer", "return=representation")
                .header("Accept", "application/json").method(method, payload?.toString()?.toRequestBody("application/json".toMediaType())).build()
            outcomes.begin(playId)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            checkNetwork()
            val response = try { transport.send(request) } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: CommentFailure) { throw failure
            } catch (_: IOException) { throw CommentFailure(CommentProblem.UNCERTAIN) }
            checkNetwork()
            when (response.status) {
                in 200..299 -> {
                    val rows = try { Json.parseToJsonElement(response.body).jsonArray.map { RowDecoders.playComment(it.jsonObject) } }
                        catch (_: Exception) { throw CommentFailure(CommentProblem.UNCERTAIN) }
                    if (rows.isEmpty() && commentId != null) { outcomes.acknowledge(playId); throw CommentFailure(CommentProblem.NOT_FOUND) }
                    val row = rows.singleOrNull() ?: throw CommentFailure(CommentProblem.UNCERTAIN)
                    if (row.play_id != playId || row.user_id != accountId || (commentId != null && row.id != commentId) ||
                        (content != null && row.content != content)) throw CommentFailure(CommentProblem.UNCERTAIN)
                    outcomes.acknowledge(playId)
                    row
                }
                401 -> { outcomes.acknowledge(playId); throw AuthFailure(AuthProblem.INVALID_CREDENTIALS) }
                403 -> { outcomes.acknowledge(playId); throw CommentFailure(CommentProblem.FORBIDDEN) }
                404 -> { outcomes.acknowledge(playId); throw CommentFailure(CommentProblem.NOT_FOUND) }
                400, 409, 422 -> { outcomes.acknowledge(playId); throw CommentFailure(CommentProblem.INVALID_CONTENT) }
                else -> throw CommentFailure(CommentProblem.UNCERTAIN)
            }
        }
    }

    private fun checkNetwork() {
        if (closed) throw AuthFailure(AuthProblem.INVALID_CREDENTIALS)
        if (!network.connected.value) throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE)
    }
}
