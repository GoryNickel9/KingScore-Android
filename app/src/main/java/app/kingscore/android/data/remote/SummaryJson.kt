package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlinx.serialization.json.*

internal object SummaryJson {
    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
    private fun JsonObject.optional(key: String): String? = getValue(key).let { if (it is JsonNull) null else text(key) }
    private fun JsonObject.count(key: String): Long = getValue(key).jsonPrimitive.let {
        require(!it.isString); it.long.also { value -> require(value in 0..9007199254740991L) }
    }
    private fun JsonObject.uuid(key: String): UUID = UUID.fromString(text(key))
    private fun JsonObject.optionalUuid(key: String): UUID? = optional(key)?.let(UUID::fromString)
    private fun JsonObject.decimal(key: String): BigDecimal? = optional(key)?.let {
        require(it.matches(Regex("-?[0-9]+(?:\\.[0-9]+)?"))); BigDecimal(it)
    }
    private fun JsonObject.rows(key: String) = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.cursor(): String? = optional("nextCursor")?.also { require(it.isNotEmpty() && it.length <= 4096) }
    private fun root(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject.also {
        require(it.count("contractVersion") == 1L && it.text("scope") == "global-visible-v1")
        version(it); Instant.parse(it.text("computedAt"))
    }
    private fun version(value: JsonObject): String = value.text("sourceRevision").also {
        require(it.matches(Regex("0|[1-9][0-9]{0,18}")) && it.toLong() >= 0)
    }
    // shortcut: Home retains Int counts, migrate its presentation before totals exceed Int.MAX_VALUE.
    private fun countInt(value: Long) = Math.toIntExact(value)
    private inline fun <T> decode(block: () -> T): T = try { block() }
        catch (_: Exception) { throw DataFailure(DataProblem.INVALID_RESPONSE) }

    fun home(text: String, requestedMonth: YearMonth): StatisticsSnapshot = decode {
        val value = root(text); require(value.text("month") == requestedMonth.toString())
        val totals = value.getValue("totals").jsonObject; val personal = value.getValue("personal").jsonObject
        val total = totals.count("plays"); val duration = totals.count("durationSamples")
        require(duration <= total && totals.count("monthPlays") <= total)
        val months = value.rows("months").map { MonthStatistic(YearMonth.parse(it.text("month")), countInt(it.count("plays"))) }
        require(months.map { it.month } == (5L downTo 0L).map { requestedMonth.minusMonths(it) })
        require(months.last().plays.toLong() == totals.count("monthPlays") && months.sumOf { it.plays.toLong() } <= total)
        val favorites = value.rows("favoriteGames").map { GameStatistic(it.uuid("gameId"), it.text("name"), countInt(it.count("plays")), it.optional("imageUrl")) }
        require(favorites.size <= 3 && favorites.map { it.gameId }.distinct().size == favorites.size && favorites.all { it.plays.toLong() <= total })
        val linked = when (personal.text("status")) { "linked" -> true; "unlinked" -> false; else -> error("status") }
        val plays = if (linked) personal.count("plays") else 0L
        val wins = if (linked) personal.count("wins") else 0L
        if (linked) { personal.uuid("playerId"); personal.text("displayName"); require(wins <= plays && plays <= total) }
        else require(listOf("playerId","displayName","plays","wins").all { personal[it] is JsonNull })
        val minutes = requireNotNull(totals.decimal("recordedMinutes")).longValueExact(); require(minutes >= 0)
        StatisticsSnapshot(countInt(total), countInt(totals.count("monthPlays")), countInt(totals.count("participatingGames")),
            countInt(totals.count("participatingPlayers")), minutes, countInt(duration), favorites, null, countInt(plays), countInt(wins), months,
            sourceRevision = version(value))
    }
    private fun game(value: JsonObject): GameStatistics = with(value) {
        val plays = count("plays"); val samples = count("scoreSamples"); val participants = count("participations")
        val duration = count("durationSamples"); val average = decimal("scoreAverage"); val durationAverage = decimal("durationAverage")
        require(samples <= participants && duration <= plays && count("unattributedParticipations") <= participants)
        require((samples == 0L) == (average == null) && (duration == 0L) == (durationAverage == null))
        val minutes = requireNotNull(decimal("recordedMinutes")); require(minutes.signum() >= 0)
        GameStatistics(optionalUuid("gameId"), text("name"), optional("imageUrl"), plays, participants,
            requireNotNull(decimal("scoreSum")), samples, average, minutes, duration, durationAverage, count("unattributedParticipations"))
    }
    fun overview(text: String): StatisticsOverview = decode {
        val value = root(text)
        val linked = when (value.text("personalStatus")) { "linked" -> true; "unlinked" -> false; else -> error("status") }
        val wins = value.rows("winRateByGame").map {
            val plays = it.count("participations"); val won = it.count("wins"); val percent = countInt(it.count("percent"))
            require(plays > 0 && won <= plays && percent == BigDecimal(won).multiply(BigDecimal(100)).divide(BigDecimal(plays), 0, RoundingMode.HALF_UP).intValueExact())
            WinRateStatistic(it.optionalUuid("gameId"), it.text("name"), plays, won, percent)
        }
        val games = value.rows("games").map(::game); val total = value.count("totalGames")
        require(wins.size <= 10 && games.size <= 20 && games.size.toLong() <= total)
        require(games.map { it.id }.distinct().size == games.size && wins.map { it.gameId }.distinct().size == wins.size)
        require(linked || wins.isEmpty())
        StatisticsOverview(version(value), linked, wins, games, total, value.cursor())
    }
    fun details(text: String): GameStatisticsPage = decode {
        val value = root(text); val game = game(value.getValue("game").jsonObject)
        val players = value.rows("players").map {
            val samples = it.count("scoreSamples"); val participations = it.count("participations"); val average = it.decimal("scoreAverage")
            require(samples <= participations && (samples == 0L) == (average == null))
            PlayerStatistic(it.uuid("playerId"), it.optional("alias")?.takeIf(String::isNotEmpty) ?: it.text("name"),
                it.optional("avatarUrl"), participations, requireNotNull(it.decimal("scoreSum")), samples, average)
        }
        val total = value.count("totalPlayers")
        require(players.size <= 50 && players.size.toLong() <= total && players.map { it.id }.distinct().size == players.size)
        GameStatisticsPage(version(value), game, players, total, value.cursor())
    }
    private fun ratings(rows: List<JsonObject>): Map<UUID, Long> = rows.associate {
        val number = it.getValue("rating").jsonPrimitive; require(!number.isString)
        it.uuid("playerId") to number.long.also { v -> require(v in -9007199254740991L..9007199254740991L) }
    }.also { require(it.size == rows.size) }
    fun chart(text: String): StatisticsChart = decode {
        val value = root(text); require(value.text("scopeId") == "global")
        val status = value.text("status"); require(status in setOf("current", "stale", "missing"))
        val players = value.rows("series").map { ChartPlayer(it.uuid("playerId"), it.text("displayName"), it.optional("color")) }
        val ids = players.map { it.id }.toSet(); require(ids.size == players.size && ids.size <= 64)
        val initial = ratings(value.rows("initialRatings")); require(initial.keys.all { it in ids })
        val plays = value.rows("plays").map {
            ChartPlay(it.uuid("playId"), LocalDate.parse(it.text("date")), it.count("ordinal").also { v -> require(v > 0) },
                ratings(it.rows("ratings")).also { r -> require(r.keys.all { key -> key in ids }) })
        }
        require(plays.size <= 100 && plays.map { it.id }.distinct().size == plays.size)
        require(plays.zipWithNext().all { (a,b) -> a.ordinal + 1 == b.ordinal && a.date <= b.date })
        val publication = value.optionalUuid("publicationId"); val total = value.count("totalPlays")
        if (status == "current") {
            require(value.text("algorithm") in supportedEloAlgorithms)
            require(publication != null && value.optional("publishedRevision") == version(value) && plays.size.toLong() <= total)
            require(plays.lastOrNull()?.ordinal?.let { it <= total } != false)
            if (plays.firstOrNull()?.ordinal == 1L) require(initial.isEmpty())
        }
        else require(players.isEmpty() && initial.isEmpty() && plays.isEmpty() && value.cursor() == null)
        StatisticsChart(version(value), publication, value.optionalUuid("gameId"), status, players, initial, plays, total,
            value.cursor(), value["availableSeries"]?.jsonPrimitive?.int ?: players.size)
    }
    fun players(text: String): List<ChartPlayer> = decode {
        val rows = Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
        rows.map { ChartPlayer(it.uuid("id"), it.optional("alias")?.takeIf(String::isNotEmpty) ?: it.text("name"), it.optional("color")) }
            .also { require(it.map(ChartPlayer::id).distinct().size == it.size) }
    }
}
