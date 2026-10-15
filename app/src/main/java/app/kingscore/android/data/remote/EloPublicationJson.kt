package app.kingscore.android.data.remote

import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.json.*

internal val supportedEloAlgorithms = setOf("kingscore-elo-legacy-v1", "kingscore-elo-rules-v2")

internal data class EloPublishedState(val playerId: UUID, val name: String?, val rating: Int,
    val games: Int, val wins: Int, val losses: Int, val draws: Int, val date: LocalDate)
internal data class EloPublishedPoint(val gameId: UUID?, val playId: UUID, val playerId: UUID,
    val name: String, val rating: Int, val change: Int, val games: Int?, val date: LocalDate)
internal data class EloPublishedResult(val gamePlayers: Map<UUID?, Map<UUID, EloPublishedState>>,
    val generalPlayers: Map<UUID, EloPublishedState>, val gameHistory: List<EloPublishedPoint>,
    val generalHistory: List<EloPublishedPoint>)
internal data class EloPublication(val status: String, val sourceRevision: String, val publishedRevision: String?,
    val publicationId: UUID?, val result: EloPublishedResult?)

/** Validates a complete global generation; it never computes ratings or combines publications. */
internal object EloPublicationJson {
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun decode(body: String): EloPublication {
        val root = exact(Json.parseToJsonElement(body), "scopeId", "algorithm", "status", "sourceRevision",
            "publishedRevision", "publicationId", "result")
        require(text(root.getValue("scopeId")) == "global")
        val source = revision(root.getValue("sourceRevision"))
        val status = text(root.getValue("status"))
        if (status == "missing") {
            require(listOf("algorithm", "publishedRevision", "publicationId", "result").all { root.getValue(it) == JsonNull })
            return EloPublication(status, source, null, null, null)
        }
        require(text(root.getValue("algorithm")) in supportedEloAlgorithms)
        val published = revision(root.getValue("publishedRevision"))
        val id = uuid(root.getValue("publicationId"))
        return when (status) {
            "stale" -> {
                require(published.toLong() < source.toLong() && root.getValue("result") == JsonNull)
                EloPublication(status, source, published, id, null)
            }
            "current" -> {
                require(published == source)
                EloPublication(status, source, published, id, result(root.getValue("result")))
            }
            else -> error("Unknown publication status")
        }
    }
    private fun result(value: JsonElement): EloPublishedResult {
        val row = exact(value, "gamePlayers", "generalPlayers", "gameHistory", "generalHistory", "processedPlays", "skippedPlays")
        val processed = integer(row.getValue("processedPlays"), true)
        integer(row.getValue("skippedPlays"), true)
        val games = row.getValue("gamePlayers").jsonObject.map { (key, value) ->
            val game = if (key == "null") null else uuid(JsonPrimitive(key))
            val players = value.jsonObject.map { (player, current) -> uuid(JsonPrimitive(player)).let { it to state(current, it, true) } }.toMap()
            require(players.isNotEmpty())
            game to players
        }.toMap()
        val generals = row.getValue("generalPlayers").jsonObject.map { (key, value) ->
            uuid(JsonPrimitive(key)).let { it to state(value, it, false) }
        }.toMap()
        val allPlayers = games.values.flatMap { it.keys }.toSet()
        require(generals.keys == allPlayers)
        for ((player, general) in generals) {
            val states = games.values.mapNotNull { it[player] }
            require(states.sumOf { it.games.toLong() } == general.games.toLong() &&
                states.sumOf { it.wins.toLong() } == general.wins.toLong() &&
                states.sumOf { it.losses.toLong() } == general.losses.toLong() &&
                states.sumOf { it.draws.toLong() } == general.draws.toLong() && states.maxOf { it.date } == general.date)
        }
        val gameHistory = row.getValue("gameHistory").jsonArray.map { point(it, false) }
        val generalHistory = row.getValue("generalHistory").jsonArray.map { point(it, true) }
        fun ordered(points: List<EloPublishedPoint>) = points.zipWithNext().all { (a, b) ->
            a.date < b.date || (a.date == b.date && a.playId.toString() <= b.playId.toString()) }
        require(ordered(gameHistory) && ordered(generalHistory))
        val pairs = gameHistory.associateBy { it.playId to it.playerId }
        require(pairs.size == gameHistory.size)
        val plays = gameHistory.groupBy { it.playId }
        require(plays.size == processed && plays.values.all { points -> points.size >= 2 && points.all { it.gameId == points[0].gameId && it.date == points[0].date } })
        val perGame = gameHistory.groupBy { it.gameId to it.playerId }
        require(perGame.keys == games.flatMap { (game, players) -> players.keys.map { game to it } }.toSet())
        for ((key, history) in perGame) {
            val current = games.getValue(key.first).getValue(key.second)
            validateHistory(history)
            require(current.rating == history.last().rating && current.games == history.size && current.date == history.last().date && current.name == history.first().name)
        }
        require(generalHistory.map { it.playId to it.playerId }.toSet() == pairs.keys && generalHistory.size == pairs.size)
        for (point in generalHistory) {
            val match = pairs.getValue(point.playId to point.playerId)
            require(point.date == match.date && point.name == games.getValue(match.gameId).getValue(point.playerId).name)
        }
        val perPlayer = generalHistory.groupBy { it.playerId }
        require(perPlayer.keys == generals.keys)
        for ((player, history) in perPlayer) {
            val current = generals.getValue(player)
            validateHistory(history)
            require(history.withIndex().all { (index, point) -> point.games == index + 1 })
            require(current.rating == history.last().rating && current.games == history.size && current.date == history.last().date)
        }
        return EloPublishedResult(games, generals, gameHistory, generalHistory)
    }
    private fun validateHistory(points: List<EloPublishedPoint>) {
        var prior = 1000L
        for (point in points) { require(prior + point.change.toLong() == point.rating.toLong()); prior = point.rating.toLong() }
    }
    private fun state(value: JsonElement, player: UUID, game: Boolean): EloPublishedState {
        val keys = listOf("playerId", "rating", "gamesPlayed", "wins", "losses", "draws", "lastPlayedAt") + if (game) listOf("playerName") else emptyList()
        val row = exact(value, *keys.toTypedArray())
        require(uuid(row.getValue("playerId")) == player)
        val games = integer(row.getValue("gamesPlayed"), true)
        val wins = integer(row.getValue("wins"), true); val losses = integer(row.getValue("losses"), true); val draws = integer(row.getValue("draws"), true)
        require(games > 0 && wins.toLong() + losses + draws == games.toLong())
        return EloPublishedState(player, if (game) text(row.getValue("playerName")) else null,
            integer(row.getValue("rating")), games, wins, losses, draws, date(row.getValue("lastPlayedAt")))
    }
    private fun point(value: JsonElement, general: Boolean): EloPublishedPoint {
        val row = exact(value, "playId", "playerId", "playerName", "rating", "ratingChange", "date", if (general) "gamesPlayed" else "gameId")
        return EloPublishedPoint(if (general || row.getValue("gameId") == JsonNull) null else uuid(row.getValue("gameId")),
            uuid(row.getValue("playId")), uuid(row.getValue("playerId")), text(row.getValue("playerName")),
            integer(row.getValue("rating")), integer(row.getValue("ratingChange")),
            if (general) integer(row.getValue("gamesPlayed"), true) else null, date(row.getValue("date")))
    }
    private fun exact(value: JsonElement, vararg keys: String) = value.jsonObject.also { require(it.keys == keys.toSet()) }
    private fun text(value: JsonElement) = value.jsonPrimitive.also { require(it.isString) }.content
    private fun uuid(value: JsonElement): UUID = text(value).also { require(uuidPattern.matches(it)) }.let(UUID::fromString)
    private fun revision(value: JsonElement) = text(value).also { require(it.matches(Regex("0|[1-9][0-9]*")) && it.toLong() >= 0) }
    // Existing Android display models use Int. Reject overflow instead of rounding or truncating JSON numbers.
    private fun integer(value: JsonElement, nonnegative: Boolean = false) = value.jsonPrimitive.also { require(!it.isString) }.int.also { require(!nonnegative || it >= 0) }
    private fun date(value: JsonElement) = text(value).also { require(it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && !it.startsWith("0000-")) }.let(LocalDate::parse)
}
