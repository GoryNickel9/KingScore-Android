package kingscore.contracts.v1

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Historical reads keep source as String?; new writes use the web's wire values. */
enum class PlaySource(val wireValue: String) {
    IRL("offline"), ONLINE("online")
}

/** No author, account ID, role or timestamps supplied by the client. */
data class CreateComment(val play_id: UUID, val content: String) {
    init { validateComment(content) }
}

data class UpdateComment(val content: String) {
    init { validateComment(content) }
}

private fun validateComment(content: String) {
    require(content == content.trim()) { "Comment must be trimmed" }
    // PostgreSQL char_length counts code points, unlike JVM String.length.
    require(content.codePointCount(0, content.length) in 1..1000) { "Comment must contain 1..1000 code points" }
    require('\u0000' !in content) { "NUL is not a PostgreSQL text character" }
    var index = 0
    while (index < content.length) {
        val char = content[index++]
        if (Character.isHighSurrogate(char)) {
            require(index < content.length && Character.isLowSurrogate(content[index])) { "Unpaired surrogate" }
            index++
        } else require(!Character.isLowSurrogate(char)) { "Unpaired surrogate" }
    }
}

/** Proposed RPC payload. IDs on update must already belong to that play. */
data class ScoreInput(
    val id: UUID?,
    val player_id: UUID,
    val score: BigDecimal?,
    val winner: Boolean,
    val color: String?,
    val role: String?,
)

/** Proposed create/update payload; position is derived and validated server-side. */
data class PlayInput(
    val game_id: UUID,
    val played_at: LocalDate,
    val duration_minutes: Int?,
    val source: PlaySource,
    val notes: String?,
    val is_cooperative: Boolean,
    val team_won: Boolean?,
    val scores: List<ScoreInput>,
) {
    init {
        require(duration_minutes == null || duration_minutes >= 0) { "Negative duration" }
        require(is_cooperative || team_won == null) { "Competitive play cannot contain a team result" }
        require(scores.isNotEmpty()) { "At least one participant required" }
        require(scores.map { it.player_id }.distinct().size == scores.size) { "Duplicate participant" }
        val existingIds = scores.mapNotNull { it.id }
        require(existingIds.distinct().size == existingIds.size) { "Duplicate score ID" }
        if (is_cooperative && team_won != null) {
            require(scores.all { it.winner == team_won }) { "Individual result contradicts cooperative result" }
        }
    }
}

/** These RPCs are specified, not deployed. Never fall back to multiple writes. */
data class CreatePlayCommand(val request_id: UUID, val payload: PlayInput) {
    init { require(payload.scores.all { it.id == null }) { "Create cannot reuse score IDs" } }
}

data class UpdatePlayCommand(
    val request_id: UUID,
    val play_id: UUID,
    val expected_revision: String,
    val payload: PlayInput,
) {
    init { require(expected_revision.isNotBlank()) { "Revision required" } }
}

data class DeletePlayCommand(val request_id: UUID, val play_id: UUID, val expected_revision: String) {
    init { require(expected_revision.isNotBlank()) { "Revision required" } }
}

data class PlayDetail(val play: PlayRow, val game: GameRow?, val scores: List<ScoreWithPlayer>)
data class ScoreWithPlayer(val score: PlayScoreRow, val player: PlayerRow?)
data class PlayEditorResult(val detail: PlayDetail, val revision: String)

/** Proposed mutation response. revision is opaque; Android never computes ELO. */
data class PlayMutationResult(val play_id: UUID, val revision: String, val replayed: Boolean)
data class DeletePlayResult(val play_id: UUID, val deleted: Boolean, val replayed: Boolean)
