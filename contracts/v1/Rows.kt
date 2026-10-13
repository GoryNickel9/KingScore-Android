// Generated from the captured remote catalog; do not edit by hand.
// Wire adapters and JSON serializers belong to phase 2.
package kingscore.contracts.v1

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class GameRow(
    val id: UUID,
    val name: String,
    val bga_id: String?,
    val image_url: String?,
    val min_players: Int?,
    val max_players: Int?,
    val created_at: Instant?,
)

data class PlayRow(
    val id: UUID,
    val user_id: UUID?,
    val game_id: UUID?,
    val played_at: LocalDate,
    val location: String?,
    val notes: String?,
    val duration_minutes: Int?,
    val source: String?,
    val bga_table_id: String?,
    val created_at: Instant?,
    val is_cooperative: Boolean?,
    val team_won: Boolean?,
)

data class PlayScoreRow(
    val id: UUID,
    val play_id: UUID?,
    val player_id: UUID?,
    val score: BigDecimal?,
    val winner: Boolean?,
    val color: String?,
    val role: String?,
    val position: Int?,
)

data class PlayerRow(
    val id: UUID,
    val user_id: UUID?,
    val name: String,
    val is_me: Boolean?,
    val created_at: Instant?,
    val alias: String?,
    val avatar_url: String?,
    val updated_at: Instant,
    val color: String?,
    val account_user_id: UUID?,
)

data class PlayCommentRow(
    val id: UUID,
    val play_id: UUID,
    val user_id: UUID,
    val author_name: String,
    val content: String,
    val created_at: Instant,
    val updated_at: Instant,
)

data class GameRatingRow(
    val id: UUID,
    val player_id: UUID?,
    val game_id: UUID?,
    val rating: Int?,
    val games_played: Int?,
    val wins: Int?,
    val losses: Int?,
    val draws: Int?,
    val last_played_at: Instant?,
    val created_at: Instant?,
    val updated_at: Instant?,
)

data class GeneralRatingRow(
    val id: UUID,
    val player_id: UUID?,
    val user_id: UUID?,
    val rating: Int?,
    val games_played: Int?,
    val wins: Int?,
    val losses: Int?,
    val draws: Int?,
    val last_played_at: Instant?,
    val created_at: Instant?,
    val updated_at: Instant?,
)

data class EloHistoryRow(
    val id: UUID,
    val play_id: UUID,
    val game_id: UUID?,
    val player_id: UUID,
    val player_name: String,
    val rating: Int,
    val rating_change: Int,
    val date: Instant,
    val is_general: Boolean,
    val games_played: Int?,
    val user_id: UUID?,
    val created_at: Instant,
)
