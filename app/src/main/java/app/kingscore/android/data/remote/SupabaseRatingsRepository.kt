package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.*

class SupabaseRatingsRepository(private val reader: AuthenticatedReader, private val accountId: UUID) : RatingsRepository {
    override suspend fun games(): List<GameRow> = all("games", listOf("select" to "*"), RowDecoders::game) { it.id }
        .sortedWith(compareBy<GameRow> { it.name.lowercase() }.thenBy { it.id.toString() })

    override suspend fun ranking(gameId: UUID?): Ranking {
        val profiles = decode(reader.get(accountId, "players", listOf("select" to "*", "account_user_id" to "eq.$accountId", "limit" to "2")), RowDecoders::player)
        validate { require(profiles.size <= 1 && profiles.all { it.account_user_id == accountId }) }
        val profile = profiles.singleOrNull()
        // The general archive belongs to the historical owner, not a linked user's login ID.
        val owner = profile?.user_id ?: accountId
        val parameters = listOf("select" to "*,players(*)") + if (gameId == null) listOf("user_id" to "eq.$owner") else listOf("game_id" to "eq.$gameId")
        val resource = if (gameId == null) "player_general_ratings" else "player_ratings"
        val entries = all(resource, parameters, { row ->
            val player = row.getValue("players").let { if (it == JsonNull) null else RowDecoders.player(it.jsonObject) }
            if (gameId == null) {
                val rating = RowDecoders.generalRating(row)
                validate { require(rating.user_id == owner) }
                PublishedRating(rating.id, player, rating.player_id, null, rating.user_id, rating.rating,
                    rating.games_played, rating.wins, rating.losses, rating.draws, rating.updated_at)
            } else {
                val rating = RowDecoders.gameRating(row)
                validate { require(rating.game_id == gameId) }
                PublishedRating(rating.id, player, rating.player_id, rating.game_id, null, rating.rating,
                    rating.games_played, rating.wins, rating.losses, rating.draws, rating.updated_at)
            }
        }) { it.id }
        validate {
            val knownPlayers = entries.mapNotNull { it.playerId }
            require(knownPlayers.distinct().size == knownPlayers.size)
            require(entries.all { it.player == null || it.player.id == it.playerId })
        }
        return Ranking(profile, entries.sortedWith(compareByDescending<PublishedRating> { it.rating }.thenBy { it.id.toString() }))
    }

    override suspend fun history(rating: PublishedRating, after: RatingHistoryCursor?): RatingHistoryPage {
        val player = rating.playerId ?: return RatingHistoryPage(emptyList(), null)
        val parameters = mutableListOf("select" to "*", "player_id" to "eq.$player", "is_general" to "eq.${rating.gameId == null}",
            "order" to "date.desc,id.desc", "limit" to PAGE_SIZE.toString())
        rating.gameId?.let { parameters += "game_id" to "eq.$it" }
        rating.ownerId?.let { parameters += "user_id" to "eq.$it" }
        after?.let { parameters += "or" to "(date.lt.${it.date},and(date.eq.${it.date},id.lt.${it.id}))" }
        val rows = decode(reader.get(accountId, "elo_history", parameters), RowDecoders::eloHistory)
        validate {
            require(rows.size <= PAGE_SIZE)
            require(rows.all { it.player_id == player && it.is_general == (rating.gameId == null) &&
                (rating.gameId == null || it.game_id == rating.gameId) && (rating.ownerId == null || it.user_id == rating.ownerId) })
            val cursors = rows.map { RatingHistoryCursor(it.date, it.id) }
            require(cursors.zipWithNext().all { (a, b) -> compare(a, b) > 0 })
            require(after == null || cursors.all { compare(it, after) < 0 })
        }
        return RatingHistoryPage(rows, rows.lastOrNull()?.takeIf { rows.size == PAGE_SIZE }?.let { RatingHistoryCursor(it.date, it.id) })
    }

    private fun compare(a: RatingHistoryCursor, b: RatingHistoryCursor) = a.date.compareTo(b.date).takeIf { it != 0 } ?: a.id.toString().compareTo(b.id.toString())
    private suspend fun <T> all(resource: String, parameters: List<Pair<String, String>>, decode: (JsonObject) -> T, id: (T) -> UUID): List<T> {
        val items = mutableListOf<T>()
        var after: UUID? = null
        do {
            coroutineContext.ensureActive()
            val query = parameters + listOf("order" to "id.asc", "limit" to PAGE_SIZE.toString()) + after?.let { listOf("id" to "gt.$it") }.orEmpty()
            val page = decode(reader.get(accountId, resource, query), decode)
            validate {
                require(page.size <= PAGE_SIZE)
                require(page.zipWithNext().all { (a, b) -> id(a).toString() < id(b).toString() })
                require(after == null || page.all { id(it).toString() > after.toString() })
            }
            items += page
            after = page.lastOrNull()?.takeIf { page.size == PAGE_SIZE }?.let(id)
        } while (after != null)
        return items
    }
    private fun <T> decode(body: String, decoder: (JsonObject) -> T): List<T> = validate { Json.parseToJsonElement(body).jsonArray.map { decoder(it.jsonObject) } }
    private inline fun <T> validate(block: () -> T): T = try { block() } catch (_: Exception) { throw DataFailure(DataProblem.INVALID_RESPONSE) }
}
