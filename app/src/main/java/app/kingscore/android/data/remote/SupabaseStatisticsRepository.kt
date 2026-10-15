package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.DataFailure
import app.kingscore.android.data.repository.DataProblem
import app.kingscore.android.data.repository.GameStatistic
import app.kingscore.android.data.repository.HistoryFilters
import app.kingscore.android.data.repository.MonthStatistic
import app.kingscore.android.data.repository.PlayCursor
import app.kingscore.android.data.repository.PlaysRepository
import app.kingscore.android.data.repository.StatisticsRepository
import app.kingscore.android.data.repository.StatisticsSnapshot
import java.time.Clock
import java.time.YearMonth
import java.util.UUID
import kingscore.contracts.v1.PlayerRow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class SupabaseStatisticsRepository(
    private val reader: AuthenticatedReader,
    private val accountId: UUID,
    private val plays: PlaysRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : StatisticsRepository {
    override suspend fun load(onProgress: (Int) -> Unit): StatisticsSnapshot {
        val linkedPlayer = linkedPlayer()
        val month = YearMonth.now(clock)
        val months = (5L downTo 0L).associate { month.minusMonths(it) to 0 }.toMutableMap()
        val seenPlays = HashSet<UUID>()
        val players = HashSet<UUID>()
        val games = HashMap<UUID, GameTally>()
        var monthPlays = 0
        var recordedMinutes = 0L
        var durationSamples = 0
        var personalPlays = 0
        var personalWins = 0
        var cursor: PlayCursor? = null

        do {
            currentCoroutineContext().ensureActive()
            val page = plays.history(HistoryFilters(), cursor)
            // A malformed/non-advancing cursor must not create an endless scan.
            val previous = cursor
            if (page.next != null && (page.items.isEmpty() || previous != null && compare(page.next, previous) >= 0)) {
                throw DataFailure(DataProblem.INVALID_RESPONSE)
            }
            for (detail in page.items) {
                currentCoroutineContext().ensureActive()
                val play = detail.play
                if (!seenPlays.add(play.id)) continue
                val playedMonth = YearMonth.from(play.played_at)
                if (playedMonth == month) monthPlays++
                if (playedMonth in months) months[playedMonth] = months.getValue(playedMonth) + 1
                play.duration_minutes?.takeIf { it >= 0 }?.let {
                    recordedMinutes += it.toLong()
                    durationSamples++
                }
                play.game_id?.let { id ->
                    val tally = games.getOrPut(id) { GameTally() }
                    tally.plays++
                    detail.game?.let { tally.name = it.name; tally.imageUrl = it.image_url }
                }
                var participated = false
                var won = false
                for (row in detail.scores) {
                    val playerId = row.score.player_id ?: continue
                    players.add(playerId)
                    if (playerId == linkedPlayer?.id) {
                        participated = true
                        if (row.score.winner == true) won = true
                    }
                }
                if (participated) personalPlays++
                if (won) personalWins++
            }
            onProgress(seenPlays.size)
            cursor = page.next
        } while (cursor != null)

        currentCoroutineContext().ensureActive()
        return StatisticsSnapshot(
            totalPlays = seenPlays.size,
            monthPlays = monthPlays,
            uniqueGames = games.size,
            uniquePlayers = players.size,
            recordedMinutes = recordedMinutes,
            durationSamples = durationSamples,
            games = games.map { (id, tally) -> GameStatistic(id, tally.name, tally.plays, tally.imageUrl) }
                .sortedWith(compareByDescending<GameStatistic> { it.plays }.thenBy { it.gameId.toString() }),
            linkedPlayer = linkedPlayer,
            personalPlays = personalPlays,
            personalWins = personalWins,
            months = months.map { (month, count) -> MonthStatistic(month, count) },
        )
    }

    private suspend fun linkedPlayer(): PlayerRow? {
        val body = reader.get(accountId, "players", listOf(
            "select" to "*", "account_user_id" to "eq.$accountId", "limit" to "2",
        ))
        return try {
            val rows = Json.parseToJsonElement(body).jsonArray.map { RowDecoders.player(it.jsonObject) }
            require(rows.size <= 1 && rows.all { it.account_user_id == accountId })
            rows.singleOrNull()
        } catch (_: Exception) {
            throw DataFailure(DataProblem.INVALID_RESPONSE)
        }
    }

    private fun compare(a: PlayCursor, b: PlayCursor): Int =
        a.date.compareTo(b.date).takeIf { it != 0 } ?: a.id.toString().compareTo(b.id.toString())

    private class GameTally(var name: String = "Gioco non disponibile", var plays: Int = 0, var imageUrl: String? = null)
}
