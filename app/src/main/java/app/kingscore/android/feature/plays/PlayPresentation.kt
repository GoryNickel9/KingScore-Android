package app.kingscore.android.feature.plays

import kingscore.contracts.v1.PlayDetail
import kingscore.contracts.v1.PlayerRow
import kingscore.contracts.v1.ScoreWithPlayer
import java.time.format.DateTimeFormatter
import java.util.Locale

val playDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)
fun playerName(player: PlayerRow?): String = player?.alias?.takeIf { it.isNotBlank() } ?: player?.name ?: "Giocatore non disponibile"
fun sourceLabel(source: String?): String = when (source) {
    "online" -> "Online"
    "offline", "manual" -> "Dal vivo"
    null -> "Modalità non indicata"
    else -> source
}

internal enum class PlayerOutcome(val label: String?) {
    WIN("Vincitore"), DRAW("Pareggio"), LOSS(null), UNKNOWN(null)
}

internal fun playerOutcome(detail: PlayDetail, row: ScoreWithPlayer): PlayerOutcome {
    val winner = if (detail.play.is_cooperative == true) detail.play.team_won ?: row.score.winner else row.score.winner
    return when (winner) {
        null -> PlayerOutcome.UNKNOWN
        false -> PlayerOutcome.LOSS
        true -> if (detail.play.is_cooperative != true && detail.scores.count { it.score.winner == true } > 1)
            PlayerOutcome.DRAW else PlayerOutcome.WIN
    }
}

fun resultLabel(detail: PlayDetail): String {
    if (detail.play.is_cooperative == true) return when (detail.play.team_won) {
        true -> "Vittoria della squadra"
        false -> "Sconfitta della squadra"
        null -> "Esito della squadra non indicato"
    }
    val winners = detail.scores.filter { it.score.winner == true }.map { playerName(it.player) }
    return when (winners.size) {
        0 -> "Vincitore non indicato"
        1 -> "Vincitore: ${winners.single()}"
        else -> "Pareggio: ${winners.joinToString()}"
    }
}
