package app.kingscore.android.data.remote

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*

internal object PlayWriteFixtures {
    val account = UUID(0, 1)
    val play = UUID(0, 2)
    val game = UUID(0, 3)
    val player = UUID(0, 4)
    val scoreId = UUID(0, 5)
    val request = UUID(0, 6)
    fun score(value: String? = "9007199254740993.125") = ScoreInput(null, player, value?.let(::BigDecimal), false, "red", "builder")
    fun input(scores: List<ScoreInput> = listOf(score())) = PlayInput(game, LocalDate.of(2026, 10, 14),
        30, PlaySource.IRL, null, false, null, scores)
    fun create() = CreatePlayCommand(request, input())
    fun update() = UpdatePlayCommand(request, play, "v1:opaque", input(listOf(score().copy(id = scoreId))))
    fun delete() = DeletePlayCommand(request, play, "v1:opaque")
    fun saved(replayed: Boolean = false) = """{"play_id":"$play","revision":"v1:new","replayed":$replayed}"""
    fun deleted(replayed: Boolean = false) = """{"play_id":"$play","deleted":true,"replayed":$replayed}"""
    fun editor(value: String? = "9007199254740993.125", legacy: Boolean = false, participants: Int = 1): String {
        val rows = (1..participants).joinToString(",") { i ->
            val id = if (i == 1) scoreId else UUID(0, i + 100L)
            """{"score":{"id":"$id","play_id":"$play","player_id":"$player","score":${value?.let { "\"$it\"" } ?: "null"},"winner":${if (legacy) "null" else "false"},"color":"red","role":"builder","position":null},"player":null}"""
        }
        return """{"detail":{"play":{"id":"$play","user_id":"$account","game_id":null,"played_at":"2026-10-14","location":"imported","notes":null,"duration_minutes":null,"source":${if (legacy) "null" else "\"offline\""},"bga_table_id":"123","created_at":"2026-10-14T00:00:00Z","is_cooperative":false,"team_won":null},"game":null,"scores":[$rows]},"revision":"v1:opaque","editability":{"editable":${!legacy},"issues":${if (legacy) "[\"nullable-winner\"]" else "[]"}}}"""
    }
}
