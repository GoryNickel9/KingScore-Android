// Generated from contracts/v1/schema.json. Keeps JSON numeric precision.
package app.kingscore.android.data.remote

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.serialization.json.*

object RowDecoders {
    fun game(row: JsonObject): GameRow = with(WireRow(row)) {
        GameRow(
            id = uuid("id"),
            name = text("name"),
            bga_id = textOrNull("bga_id"),
            image_url = textOrNull("image_url"),
            min_players = integerOrNull("min_players"),
            max_players = integerOrNull("max_players"),
            created_at = instantOrNull("created_at"),
        )
    }

    fun play(row: JsonObject): PlayRow = with(WireRow(row)) {
        PlayRow(
            id = uuid("id"),
            user_id = uuidOrNull("user_id"),
            game_id = uuidOrNull("game_id"),
            played_at = date("played_at"),
            location = textOrNull("location"),
            notes = textOrNull("notes"),
            duration_minutes = integerOrNull("duration_minutes"),
            source = textOrNull("source"),
            bga_table_id = textOrNull("bga_table_id"),
            created_at = instantOrNull("created_at"),
            is_cooperative = booleanOrNull("is_cooperative"),
            team_won = booleanOrNull("team_won"),
        )
    }

    fun playScore(row: JsonObject): PlayScoreRow = with(WireRow(row)) {
        PlayScoreRow(
            id = uuid("id"),
            play_id = uuidOrNull("play_id"),
            player_id = uuidOrNull("player_id"),
            score = decimalOrNull("score"),
            winner = booleanOrNull("winner"),
            color = textOrNull("color"),
            role = textOrNull("role"),
            position = integerOrNull("position"),
        )
    }

    fun player(row: JsonObject): PlayerRow = with(WireRow(row)) {
        PlayerRow(
            id = uuid("id"),
            user_id = uuidOrNull("user_id"),
            name = text("name"),
            is_me = booleanOrNull("is_me"),
            created_at = instantOrNull("created_at"),
            alias = textOrNull("alias"),
            avatar_url = textOrNull("avatar_url"),
            updated_at = instant("updated_at"),
            color = textOrNull("color"),
            account_user_id = uuidOrNull("account_user_id"),
        )
    }

    fun playComment(row: JsonObject): PlayCommentRow = with(WireRow(row)) {
        PlayCommentRow(
            id = uuid("id"),
            play_id = uuid("play_id"),
            user_id = uuid("user_id"),
            author_name = text("author_name"),
            content = text("content"),
            created_at = instant("created_at"),
            updated_at = instant("updated_at"),
        )
    }

    fun gameRating(row: JsonObject): GameRatingRow = with(WireRow(row)) {
        GameRatingRow(
            id = uuid("id"),
            player_id = uuidOrNull("player_id"),
            game_id = uuidOrNull("game_id"),
            rating = integerOrNull("rating"),
            games_played = integerOrNull("games_played"),
            wins = integerOrNull("wins"),
            losses = integerOrNull("losses"),
            draws = integerOrNull("draws"),
            last_played_at = instantOrNull("last_played_at"),
            created_at = instantOrNull("created_at"),
            updated_at = instantOrNull("updated_at"),
        )
    }

    fun generalRating(row: JsonObject): GeneralRatingRow = with(WireRow(row)) {
        GeneralRatingRow(
            id = uuid("id"),
            player_id = uuidOrNull("player_id"),
            user_id = uuidOrNull("user_id"),
            rating = integerOrNull("rating"),
            games_played = integerOrNull("games_played"),
            wins = integerOrNull("wins"),
            losses = integerOrNull("losses"),
            draws = integerOrNull("draws"),
            last_played_at = instantOrNull("last_played_at"),
            created_at = instantOrNull("created_at"),
            updated_at = instantOrNull("updated_at"),
        )
    }

    fun eloHistory(row: JsonObject): EloHistoryRow = with(WireRow(row)) {
        EloHistoryRow(
            id = uuid("id"),
            play_id = uuid("play_id"),
            game_id = uuidOrNull("game_id"),
            player_id = uuid("player_id"),
            player_name = text("player_name"),
            rating = integer("rating"),
            rating_change = integer("rating_change"),
            date = instant("date"),
            is_general = boolean("is_general"),
            games_played = integerOrNull("games_played"),
            user_id = uuidOrNull("user_id"),
            created_at = instant("created_at"),
        )
    }

    fun playDetail(row: JsonObject): PlayDetail = PlayDetail(
        play = play(row),
        game = row.getValue("games").let { if (it == JsonNull) null else game(it.jsonObject) },
        scores = row.getValue("play_scores").jsonArray.map { element ->
            val score = element.jsonObject
            ScoreWithPlayer(playScore(score), score.getValue("players").let { if (it == JsonNull) null else player(it.jsonObject) })
        },
    )
}

private class WireRow(private val row: JsonObject) {
    private fun primitive(name: String): JsonPrimitive = row.getValue(name).jsonPrimitive
    private fun missing(name: String): Boolean = row.getValue(name) == JsonNull
    fun text(name: String): String = primitive(name).also { require(it.isString) { "Expected text: $name" } }.content
    fun integer(name: String): Int = primitive(name).also { require(!it.isString) { "Expected integer: $name" } }.int
    fun boolean(name: String): Boolean = primitive(name).also { require(!it.isString) { "Expected boolean: $name" } }.boolean
    fun decimal(name: String): BigDecimal = primitive(name).also { require(!it.isString) { "Expected numeric: $name" } }.content.toBigDecimal()
    fun uuid(name: String): UUID = UUID.fromString(text(name))
    fun date(name: String): LocalDate = LocalDate.parse(text(name))
    fun instant(name: String): Instant = Instant.parse(text(name))
    fun uuidOrNull(name: String) = if (missing(name)) null else uuid(name)
    fun textOrNull(name: String) = if (missing(name)) null else text(name)
    fun integerOrNull(name: String) = if (missing(name)) null else integer(name)
    fun booleanOrNull(name: String) = if (missing(name)) null else boolean(name)
    fun decimalOrNull(name: String) = if (missing(name)) null else decimal(name)
    fun dateOrNull(name: String) = if (missing(name)) null else date(name)
    fun instantOrNull(name: String) = if (missing(name)) null else instant(name)
}
