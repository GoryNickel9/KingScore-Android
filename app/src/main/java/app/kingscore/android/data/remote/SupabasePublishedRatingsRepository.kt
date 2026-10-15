package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.time.ZoneOffset
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kingscore.contracts.v1.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Rankings and history pages always project one validated, global publication. */
internal class SupabasePublishedRatingsRepository(private val reader: AuthenticatedReader, private val accountId: UUID,
    private val source: EloPublicationSource,
) : RatingsRepository {
    private data class LoadedPublication(val publication: EloPublication, val players: List<PlayerRow>, val readAt: Instant)
    @Volatile private var publication: LoadedPublication? = null
    @Volatile private var catalog: List<GameRow>? = null
    private val reads = Mutex()
    private val cacheLock = Any()
    private val generation = AtomicInteger()
    private val closed = AtomicBoolean(false)
    private fun checkOpen() { if (closed.get()) throw DataFailure(DataProblem.INVALID_RESPONSE) }
    override suspend fun games(): List<GameRow> = reads.withLock {
        checkOpen()
        val stamp = generation.get()
        catalog?.let {
            verifyCached(stamp)
            return@withLock it
        }
        val games = reader.readAllById(accountId, "games", listOf("select" to "*"), RowDecoders::game) { it.id }
            .sortedWith(compareBy<GameRow> { it.name.lowercase() }.thenBy { it.id.toString() })
        synchronized(cacheLock) { checkCurrent(stamp); catalog = games }
        games
    }

    override suspend fun ranking(gameId: UUID?): Ranking = reads.withLock {
        checkOpen()
        val stamp = generation.get()
        val loaded = publication?.also { verifyCached(stamp) } ?: loadPublication(stamp)
        val snapshot = loaded.publication
        val players = loaded.players
        val result = checkNotNull(snapshot.result)
        val linked = players.filter { it.account_user_id == accountId }
        val names = result.generalHistory.associate { it.playerId to it.name }
        val catalog = players.associateBy { it.id }
        val states = if (gameId == null) result.generalPlayers else result.gamePlayers[gameId].orEmpty()
        val entries = states.values.map { state ->
            val name = state.name ?: names.getValue(state.playerId)
            val player = catalog[state.playerId]?.copy(name = name, alias = null) ?: PlayerRow(state.playerId,
                null, name, null, null, null, null, java.time.Instant.EPOCH, null, null)
            PublishedRating(state.playerId, player, state.playerId, gameId, null, state.rating, state.games,
                state.wins, state.losses, state.draws, null, snapshot.publicationId, state.date)
        }.sortedWith(compareByDescending<PublishedRating> { it.rating }.thenBy { it.playerId.toString() })
        checkCurrent(stamp)
        Ranking(linked.singleOrNull(), entries, loaded.readAt)
    }

    private suspend fun loadPublication(stamp: Int): LoadedPublication {
        val snapshot = try {
            val body = source.read(accountId)
            withContext(Dispatchers.Default) { EloPublicationJson.decode(body) }
        }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: DataFailure) { throw failure }
            catch (failure: app.kingscore.android.core.auth.AuthFailure) { throw failure }
            catch (_: Exception) { throw DataFailure(DataProblem.INVALID_RESPONSE) }
        when (snapshot.status) {
            "stale" -> throw DataFailure(DataProblem.PUBLICATION_STALE)
            "missing" -> throw DataFailure(DataProblem.PUBLICATION_MISSING)
        }
        // Catalog metadata may enrich avatars/account linkage, but cannot replace generation names.
        val players = reader.readAllById(accountId, "players", listOf("select" to "*"), RowDecoders::player) { it.id }
        val linked = players.filter { it.account_user_id == accountId }
        if (linked.size > 1) throw DataFailure(DataProblem.INVALID_RESPONSE)
        return synchronized(cacheLock) {
            checkCurrent(stamp)
            LoadedPublication(snapshot, players, Instant.now()).also { publication = it }
        }
    }

    private fun checkCurrent(stamp: Int) {
        if (closed.get() || stamp != generation.get()) throw CancellationException("Superseded ratings request")
    }

    private suspend fun verifyCached(stamp: Int) {
        try { source.verify(accountId) }
        catch (failure: app.kingscore.android.core.auth.AuthFailure) { invalidate(); throw failure }
        checkCurrent(stamp)
    }

    override suspend fun history(rating: PublishedRating, after: RatingHistoryCursor?): RatingHistoryPage {
        checkOpen()
        val stamp = generation.get()
        source.verify(accountId)
        val snapshot = publication?.publication ?: throw DataFailure(DataProblem.INVALID_RESPONSE)
        if (rating.publicationId == null || rating.publicationId != snapshot.publicationId) throw DataFailure(DataProblem.INVALID_RESPONSE)
        val player = rating.playerId ?: throw DataFailure(DataProblem.INVALID_RESPONSE)
        val result = checkNotNull(snapshot.result)
        val points = (if (rating.gameId == null) result.generalHistory else result.gameHistory)
            .filter { it.playerId == player && (rating.gameId == null || it.gameId == rating.gameId) }
            .map { point ->
                val date = point.date.atStartOfDay().toInstant(ZoneOffset.UTC)
                // Local row IDs identify a point within its generation; they are never sent as database IDs.
                val id = UUID.nameUUIDFromBytes("${snapshot.publicationId}:${rating.gameId}:${point.playId}:$player".toByteArray(Charsets.UTF_8))
                EloHistoryRow(id, point.playId, point.gameId, player, point.name, point.rating, point.change,
                    date, rating.gameId == null, point.games, null, date)
            }.asReversed()
        val cursor = if (after == null) -1 else points.indexOfFirst { it.date == after.date && it.id == after.id }
        if (after != null && cursor < 0) throw DataFailure(DataProblem.INVALID_RESPONSE)
        val page = points.drop(cursor + 1).take(PAGE_SIZE)
        checkCurrent(stamp)
        return RatingHistoryPage(page, page.lastOrNull()?.takeIf { page.size == PAGE_SIZE }?.let { RatingHistoryCursor(it.date, it.id) })
    }
    override fun invalidate() = synchronized(cacheLock) { generation.incrementAndGet(); publication = null; catalog = null }
    override fun close() { closed.set(true); invalidate(); source.close() }
}
