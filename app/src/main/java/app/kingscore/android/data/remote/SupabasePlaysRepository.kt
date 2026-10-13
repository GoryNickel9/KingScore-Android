package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.util.UUID
import kingscore.contracts.v1.PlaySource
import kingscore.contracts.v1.PlayDetail
import kingscore.contracts.v1.PlayCommentRow
import kotlinx.serialization.json.*

class SupabasePlaysRepository(private val reader: AuthenticatedReader, private val accountId: UUID) : PlaysRepository {
    override suspend fun history(filters: HistoryFilters, after: PlayCursor?): PlayPage {
        val query = filters.query.trim()
        val projection = "*,games(*),play_scores(*,players(*))" +
            (if (query.isNotEmpty()) ",matched_game:games()" else "") +
            (if (filters.playerId != null) ",matching_scores:play_scores!inner(player_id)" else "")
        val parameters = mutableListOf(
            "select" to projection,
            "order" to "played_at.desc,id.desc", "limit" to PAGE_SIZE.toString(),
        )
        filters.source?.let { parameters += "source" to "eq.${it.wireValue}" }
        filters.gameId?.let { parameters += "game_id" to "eq.$it" }
        filters.playerId?.let { parameters += "matching_scores.player_id" to "eq.$it" }
        filters.from?.let { parameters += "played_at" to "gte.$it" }
        filters.to?.let { parameters += "played_at" to "lte.$it" }
        val conditions = mutableListOf<String>()
        if (query.isNotEmpty()) {
            val pattern = literalPattern(query)
            parameters += "matched_game.name" to "imatch.$pattern"
            conditions += "or(notes.imatch.$pattern,matched_game.not.is.null)"
        }
        after?.let { conditions += "or(played_at.lt.${it.date},and(played_at.eq.${it.date},id.lt.${it.id}))" }
        if (conditions.isNotEmpty()) parameters += "and" to conditions.joinToString(prefix = "(", postfix = ")", separator = ",")
        val rows = decode(reader.get(accountId, "plays", parameters)) { RowDecoders.playDetail(it) }
        validate {
            require(rows.size <= PAGE_SIZE)
            val cursors = rows.map { PlayCursor(it.play.played_at, it.play.id) }
            require(rows.all { (filters.source == null || it.play.source == filters.source.wireValue) &&
                (filters.gameId == null || it.play.game_id == filters.gameId) &&
                (filters.playerId == null || it.scores.any { score -> score.score.player_id == filters.playerId }) &&
                (filters.from == null || it.play.played_at >= filters.from) &&
                (filters.to == null || it.play.played_at <= filters.to) })
            require(cursors.zipWithNext().all { (a, b) -> compare(a, b) > 0 })
            require(after == null || cursors.all { compare(it, after) < 0 })
        }
        return PlayPage(rows, rows.lastOrNull()?.takeIf { rows.size == PAGE_SIZE }?.let { PlayCursor(it.play.played_at, it.play.id) })
    }

    override suspend fun filterOptions(): HistoryOptions {
        val games = reader.readAllById(accountId, "games", listOf("select" to "*"), RowDecoders::game) { it.id }
        val players = reader.readAllById(accountId, "players", listOf("select" to "*"), RowDecoders::player) { it.id }
        return HistoryOptions(games.sortedWith(compareBy<kingscore.contracts.v1.GameRow> { it.name.lowercase() }.thenBy { it.id.toString() }),
            players.sortedWith(compareBy<kingscore.contracts.v1.PlayerRow> { (it.alias?.takeIf(String::isNotBlank) ?: it.name).lowercase() }.thenBy { it.id.toString() }))
    }

    private fun literalPattern(query: String): String {
        // imatch avoids ILIKE's '*' alias; escaping prevents user input becoming executable regex.
        val regex = buildString { for (char in query) { if (char in "\\.^$|?*+()[]{}") append('\\'); append(char) } }
        return "\"${regex.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    override suspend fun detail(id: UUID): PlayDetail? {
        val rows = decode(reader.get(accountId, "plays", listOf(
            "select" to "*,games(*),play_scores(*,players(*))", "id" to "eq.$id", "limit" to "1",
        ))) { RowDecoders.playDetail(it) }
        validate { require(rows.size <= 1 && rows.all { it.play.id == id }) }
        return rows.singleOrNull()
    }

    override suspend fun comments(id: UUID, after: CommentCursor?): CommentPage {
        val parameters = mutableListOf("select" to "*", "play_id" to "eq.$id",
            "order" to "created_at.asc,id.asc", "limit" to PAGE_SIZE.toString())
        after?.let { parameters += "or" to "(created_at.gt.${it.createdAt},and(created_at.eq.${it.createdAt},id.gt.${it.id}))" }
        val rows = decode(reader.get(accountId, "play_comments", parameters)) { RowDecoders.playComment(it) }
        validate {
            require(rows.size <= PAGE_SIZE && rows.all { it.play_id == id })
            val cursors = rows.map { CommentCursor(it.created_at, it.id) }
            require(cursors.zipWithNext().all { (a, b) -> compare(a, b) < 0 })
            require(after == null || cursors.all { compare(it, after) > 0 })
        }
        return CommentPage(rows, rows.lastOrNull()?.takeIf { rows.size == PAGE_SIZE }?.let { CommentCursor(it.created_at, it.id) })
    }

    private fun compare(a: PlayCursor, b: PlayCursor): Int = a.date.compareTo(b.date).takeIf { it != 0 } ?: a.id.toString().compareTo(b.id.toString())
    private fun compare(a: CommentCursor, b: CommentCursor): Int = a.createdAt.compareTo(b.createdAt).takeIf { it != 0 } ?: a.id.toString().compareTo(b.id.toString())

    private fun <T> decode(body: String, decoder: (JsonObject) -> T): List<T> = validate {
        Json.parseToJsonElement(body).jsonArray.map { decoder(it.jsonObject) }
    }
    private inline fun <T> validate(block: () -> T): T = try { block() } catch (_: Exception) {
        // Parsing errors never expose remote bodies or SDK headers to UI/logs.
        throw DataFailure(DataProblem.INVALID_RESPONSE)
    }
}
