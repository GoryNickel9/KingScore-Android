package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kingscore.contracts.v1.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rankings and history pages always project one validated, global publication. */
internal class SupabasePublishedRatingsRepository(private val reader: AuthenticatedReader, private val accountId: UUID,
    private val source: EloPublicationSource,
) : RatingsRepository {
    private var publication: EloPublication? = null
    private val generation = AtomicInteger()
    private val closed = AtomicBoolean(false)
    private fun checkOpen() { if (closed.get()) throw DataFailure(DataProblem.INVALID_RESPONSE) }
    override suspend fun games(): List<GameRow> {
        checkOpen()
        val games = reader.readAllById(accountId, "games", listOf("select" to "*"), RowDecoders::game) { it.id }
            .sortedWith(compareBy<GameRow> { it.name.lowercase() }.thenBy { it.id.toString() })
        checkOpen()
        return games
    }

    override suspend fun ranking(gameId: UUID?): Ranking {
        checkOpen()
        val stamp = generation.incrementAndGet()
        publication = null
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
        val result = checkNotNull(snapshot.result)
        // Catalog metadata may enrich avatars/account linkage, but cannot replace generation names.
        val players = reader.readAllById(accountId, "players", listOf("select" to "*"), RowDecoders::player) { it.id }
        val linked = players.filter { it.account_user_id == accountId }
        if (linked.size > 1) throw DataFailure(DataProblem.INVALID_RESPONSE)
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
        if (closed.get() || stamp != generation.get()) throw CancellationException("Superseded ratings request")
        publication = snapshot
        return Ranking(linked.singleOrNull(), entries)
    }

    override suspend fun history(rating: PublishedRating, after: RatingHistoryCursor?): RatingHistoryPage {
        checkOpen()
        source.verify(accountId)
        val snapshot = publication ?: throw DataFailure(DataProblem.INVALID_RESPONSE)
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
        checkOpen()
        return RatingHistoryPage(page, page.lastOrNull()?.takeIf { page.size == PAGE_SIZE }?.let { RatingHistoryCursor(it.date, it.id) })
    }
    override fun close() { closed.set(true); generation.incrementAndGet(); publication = null; source.close() }
}
