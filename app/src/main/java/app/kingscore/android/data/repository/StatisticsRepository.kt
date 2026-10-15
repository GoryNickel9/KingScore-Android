package app.kingscore.android.data.repository

import java.util.UUID
import java.time.YearMonth
import kingscore.contracts.v1.PlayerRow

data class GameStatistic(val gameId: UUID, val name: String, val plays: Int, val imageUrl: String? = null)
data class MonthStatistic(val month: YearMonth, val plays: Int)

/** Complete scan totals. Missing durations are excluded; recorded zero is a sample. */
data class StatisticsSnapshot(
    val totalPlays: Int,
    val monthPlays: Int,
    val uniqueGames: Int,
    val uniquePlayers: Int,
    val recordedMinutes: Long,
    val durationSamples: Int,
    val games: List<GameStatistic>,
    val linkedPlayer: PlayerRow?,
    val personalPlays: Int,
    val personalWins: Int,
    val months: List<MonthStatistic> = emptyList(),
)

interface StatisticsRepository {
    /** Reports processed unique plays, never an incomplete StatisticsSnapshot. */
    suspend fun load(onProgress: (Int) -> Unit): StatisticsSnapshot
}
