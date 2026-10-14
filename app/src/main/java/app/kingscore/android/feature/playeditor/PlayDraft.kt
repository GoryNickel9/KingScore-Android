package app.kingscore.android.feature.playeditor

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*

data class DraftScore(
    val id: UUID? = null, val playerId: UUID, val score: String = "", val winner: Boolean = false,
    val color: String? = null, val role: String? = null,
)
data class PlayDraft(
    val gameId: UUID? = null, val playedAt: String, val duration: String = "", val source: PlaySource = PlaySource.IRL,
    val notes: String? = null, val cooperative: Boolean = false, val teamWon: Boolean? = null,
    val scores: List<DraftScore> = emptyList(),
) {
    fun input(): PlayInput {
        val decimal = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$")
        require(playedAt.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
        val minutes = if (duration.isEmpty()) null else {
            require(duration.matches(Regex("0|[1-9][0-9]*"))); duration.toInt()
        }
        return PlayInput(requireNotNull(gameId), LocalDate.parse(playedAt), minutes, source, notes, cooperative, teamWon,
            scores.map { row ->
                require(row.score.length <= 1048576)
                val number = if (row.score.isEmpty()) null else { require(decimal.matches(row.score)); BigDecimal(row.score) }
                ScoreInput(row.id, row.playerId, number, row.winner, row.color, row.role)
            })
    }
    companion object {
        fun from(snapshot: PlayEditorResult): PlayDraft? {
            if (!snapshot.editability.editable) return null
            val play = snapshot.detail.play
            return PlayDraft(requireNotNull(play.game_id), play.played_at.toString(), play.duration_minutes?.toString().orEmpty(),
                requireNotNull(PlaySource.entries.firstOrNull { it.wireValue == play.source }), play.notes, requireNotNull(play.is_cooperative), play.team_won,
                snapshot.detail.scores.map { row -> DraftScore(row.score.id, requireNotNull(row.score.player_id),
                    row.score.score?.toPlainString().orEmpty(), requireNotNull(row.score.winner), row.score.color, row.score.role) }).also { it.input() }
        }
    }
}
