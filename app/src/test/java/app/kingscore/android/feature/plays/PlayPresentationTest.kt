package app.kingscore.android.feature.plays

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import org.junit.Assert.*
import org.junit.Test

class PlayPresentationTest {
    @Test fun `one winner stays a victory`() {
        val detail = detail(true, false)
        assertEquals(PlayerOutcome.WIN, playerOutcome(detail, detail.scores[0]))
        assertEquals(PlayerOutcome.LOSS, playerOutcome(detail, detail.scores[1]))
        assertEquals("Vincitore: Giocatore 1", resultLabel(detail))
    }
    @Test fun `shared firsts are draws while shared losing positions stay losses`() {
        val detail = detail(true, true, false, false)
        assertEquals(listOf(PlayerOutcome.DRAW, PlayerOutcome.DRAW, PlayerOutcome.LOSS, PlayerOutcome.LOSS),
            detail.scores.map { playerOutcome(detail, it) })
        assertEquals("Pareggio: Giocatore 1, Giocatore 2", resultLabel(detail))
    }
    @Test fun `a total tie is a draw for every player`() {
        val detail = detail(true, true, true)
        assertTrue(detail.scores.all { playerOutcome(detail, it) == PlayerOutcome.DRAW })
    }
    @Test fun `explicit cooperative result overrides individual flags`() {
        for (won in listOf(true, false)) {
            val original = detail(!won, null)
            val detail = original.copy(play = original.play.copy(is_cooperative = true, team_won = won))
            assertTrue(detail.scores.all { playerOutcome(detail, it) == if (won) PlayerOutcome.WIN else PlayerOutcome.LOSS })
            assertEquals(if (won) "Vittoria della squadra" else "Sconfitta della squadra", resultLabel(detail))
        }
    }
    @Test fun `cooperative players never become draws when team result is missing`() {
        val original = detail(true, true)
        val detail = original.copy(play = original.play.copy(is_cooperative = true))
        assertTrue(detail.scores.all { playerOutcome(detail, it) == PlayerOutcome.WIN })
        assertEquals("Esito della squadra non indicato", resultLabel(detail))
    }
    @Test fun `missing outcome stays unknown without inventing a winner`() {
        val detail = detail(null, false)
        assertEquals(PlayerOutcome.UNKNOWN, playerOutcome(detail, detail.scores.first()))
        assertNull(playerOutcome(detail, detail.scores.first()).label)
        assertEquals("Vincitore non indicato", resultLabel(detail))
    }

    private fun detail(vararg winners: Boolean?): PlayDetail {
        val play = PlayRow(id(1), id(2), id(3), LocalDate.of(2026,10,15), null, null, null, "online", null, null, false, null)
        val scores = winners.mapIndexed { i, winner -> ScoreWithPlayer(
            PlayScoreRow(id(10+i), play.id, id(20+i), BigDecimal(i), winner, null, null, if (winner == true) 1 else 3),
            PlayerRow(id(20+i), id(2), "Giocatore ${i+1}", false, null, null, null, Instant.EPOCH, null, null)) }
        return PlayDetail(play, null, scores)
    }
    private fun id(value: Int) = UUID(0, value.toLong())
}
