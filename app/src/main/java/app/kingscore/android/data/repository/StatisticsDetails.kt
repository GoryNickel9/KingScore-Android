package app.kingscore.android.data.repository

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class WinRateStatistic(val gameId: UUID?, val name: String, val participations: Long, val wins: Long, val percent: Int)
data class GameStatistics(val id: UUID?, val name: String, val imageUrl: String?, val plays: Long,
    val participations: Long, val scoreSum: BigDecimal, val scoreSamples: Long, val scoreAverage: BigDecimal?,
    val minutes: BigDecimal, val durationSamples: Long, val durationAverage: BigDecimal?, val unattributed: Long)
data class PlayerStatistic(val id: UUID, val name: String, val avatar: String?, val participations: Long,
    val scoreSum: BigDecimal, val samples: Long, val average: BigDecimal?)
data class StatisticsOverview(val revision: String, val linked: Boolean, val winRates: List<WinRateStatistic>,
    val games: List<GameStatistics>, val totalGames: Long, val next: String?)
data class GameStatisticsPage(val revision: String, val game: GameStatistics, val players: List<PlayerStatistic>,
    val totalPlayers: Long, val next: String?)
data class ChartPlayer(val id: UUID, val name: String, val color: String?)
data class ChartPlay(val id: UUID, val date: LocalDate, val ordinal: Long, val ratings: Map<UUID, Long>)
data class StatisticsChart(val revision: String, val publication: UUID?, val gameId: UUID?, val status: String,
    val players: List<ChartPlayer>, val initial: Map<UUID, Long>, val plays: List<ChartPlay>, val total: Long,
    val next: String?, val availableSeries: Int = players.size)
