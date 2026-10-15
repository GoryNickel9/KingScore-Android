package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.json.*

internal const val STATISTICS_PAGE_SIZE = 500

/** Same stable cursor as history, with only the fields needed to compute complete totals. */
internal suspend fun loadStatisticsPage(reader: AuthenticatedReader, accountId: UUID, after: PlayCursor?): StatisticsPage {
    val parameters = mutableListOf(
        "select" to "id,played_at,duration_minutes,game_id,games(id,name,image_url),play_scores(player_id,winner)",
        "order" to "played_at.desc,id.desc", "limit" to STATISTICS_PAGE_SIZE.toString(),
    )
    after?.let { parameters += "or" to "(played_at.lt.${it.date},and(played_at.eq.${it.date},id.lt.${it.id}))" }
    val body = reader.get(accountId, "plays", parameters)
    return try {
        val elements = Json.parseToJsonElement(body).jsonArray
        require(elements.size <= STATISTICS_PAGE_SIZE)
        val rows = elements.map { element ->
            val row = element.jsonObject
            val gameId = row.nullableText("game_id")?.let(UUID::fromString)
            val game = row.getValue("games").takeUnless { it == JsonNull }?.jsonObject
            require(game == null || gameId != null && UUID.fromString(game.text("id")) == gameId)
            StatisticsPlay(
                UUID.fromString(row.text("id")), LocalDate.parse(row.text("played_at")),
                row.getValue("duration_minutes").takeUnless { it == JsonNull }?.jsonPrimitive?.also { require(!it.isString) }?.int,
                gameId, game?.text("name"), game?.nullableText("image_url"),
                row.getValue("play_scores").jsonArray.map { scoreElement ->
                    val score = scoreElement.jsonObject
                    StatisticsScore(score.nullableText("player_id")?.let(UUID::fromString),
                        score.getValue("winner").takeUnless { it == JsonNull }?.jsonPrimitive?.also { require(!it.isString) }?.boolean)
                },
            )
        }
        val cursors = rows.map { PlayCursor(it.playedAt, it.id) }
        require(cursors.zipWithNext().all { (a, b) -> compare(a, b) > 0 })
        require(after == null || cursors.all { compare(it, after) < 0 })
        // The server can cap a page below our requested limit; only an empty page proves completion.
        StatisticsPage(rows, cursors.lastOrNull())
    } catch (_: Exception) { throw DataFailure(DataProblem.INVALID_RESPONSE) }
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.nullableText(key: String): String? = if (getValue(key) == JsonNull) null else text(key)
private fun compare(a: PlayCursor, b: PlayCursor): Int =
    a.date.compareTo(b.date).takeIf { it != 0 } ?: a.id.toString().compareTo(b.id.toString())
