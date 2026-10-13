package app.kingscore.android.data.repository

import java.time.Instant
import java.util.UUID
import kingscore.contracts.v1.*

data class PublishedRating(
    val id: UUID, val player: PlayerRow?, val playerId: UUID?, val gameId: UUID?, val ownerId: UUID?,
    val rating: Int?, val gamesPlayed: Int?, val wins: Int?, val losses: Int?, val draws: Int?, val updatedAt: Instant?,
)
data class Ranking(val profile: PlayerRow?, val entries: List<PublishedRating>)
data class RatingHistoryCursor(val date: Instant, val id: UUID)
data class RatingHistoryPage(val items: List<EloHistoryRow>, val next: RatingHistoryCursor?)
interface RatingsRepository {
    suspend fun games(): List<GameRow>
    suspend fun ranking(gameId: UUID?): Ranking
    suspend fun history(rating: PublishedRating, after: RatingHistoryCursor? = null): RatingHistoryPage
}
