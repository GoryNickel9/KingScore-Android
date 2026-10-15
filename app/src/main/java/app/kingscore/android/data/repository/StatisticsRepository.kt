package app.kingscore.android.data.repository

import java.util.UUID
import java.time.YearMonth
import java.time.LocalDate
import kingscore.contracts.v1.PlayerRow

data class GameStatistic(val gameId: UUID, val name: String, val plays: Int, val imageUrl: String? = null)
data class MonthStatistic(val month: YearMonth, val plays: Int)
data class StatisticsScore(val playerId: UUID?, val winner: Boolean?)
/** Summary projection: deliberately excludes notes, exact scores, avatars and account metadata. */
data class StatisticsPlay(val id: UUID, val playedAt: LocalDate, val durationMinutes: Int?,
    val gameId: UUID?, val gameName: String?, val gameImageUrl: String?, val scores: List<StatisticsScore>)
data class StatisticsPage(val items: List<StatisticsPlay>, val next: PlayCursor?)

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
    val sourceRevision: String? = null,
)

interface StatisticsRepository {
    val dataRevision: kotlinx.coroutines.flow.StateFlow<String?>? get() = null
    /** Reports processed unique plays, never an incomplete StatisticsSnapshot. */
    suspend fun load(onProgress: (Int) -> Unit): StatisticsSnapshot
    suspend fun overview(revision: String? = null, cursor: String? = null): StatisticsOverview =
        throw DataFailure(DataProblem.INVALID_RESPONSE)
    suspend fun gameStatistics(id: UUID, revision: String, cursor: String? = null): GameStatisticsPage =
        throw DataFailure(DataProblem.INVALID_RESPONSE)
    suspend fun eloChart(gameId: UUID? = null, publication: UUID? = null, cursor: String? = null,
        players: Set<UUID>? = null): StatisticsChart = throw DataFailure(DataProblem.INVALID_RESPONSE)
    suspend fun chartPlayers(): List<ChartPlayer> = emptyList()
}
