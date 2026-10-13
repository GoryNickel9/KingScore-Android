package app.kingscore.android.data.remote

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class RowDecodersTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private fun objectOf(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun score(value: String) = objectOf("""{"id":"$id","play_id":null,"player_id":null,"score":$value,"winner":null,"color":null,"role":null,"position":null}""")
    private fun play() = objectOf("""{"id":"$id","user_id":null,"game_id":null,"played_at":"2026-10-13","location":null,"notes":null,"duration_minutes":null,"source":null,"bga_table_id":null,"created_at":null,"is_cooperative":null,"team_won":null}""")

    @Test fun `numeric JSON never passes through Double and null is not zero`() {
        assertNull(RowDecoders.playScore(score("null")).score)
        assertEquals(BigDecimal.ZERO, RowDecoders.playScore(score("0")).score)
        assertEquals(BigDecimal("9007199254740993.125"), RowDecoders.playScore(score("9007199254740993.125")).score)
        assertEquals(BigDecimal("-1.25"), RowDecoders.playScore(score("-1.25")).score)
    }

    @Test fun `calendar dates are preserved and timestamps retain their offset`() {
        assertEquals(LocalDate.parse("2026-10-13"), RowDecoders.play(play()).played_at)
        val comment = objectOf("""{"id":"$id","play_id":"$id","user_id":"$id","author_name":"Test","content":"Ciao","created_at":"2026-10-13T00:30:00+02:00","updated_at":"2026-10-13T00:30:00+02:00"}""")
        assertEquals(Instant.parse("2026-10-12T22:30:00Z"), RowDecoders.playComment(comment).created_at)
    }

    @Test fun `missing required wire fields do not silently become null or zero`() {
        val incomplete = JsonObject(play().filterKeys { it != "game_id" })
        assertThrows(NoSuchElementException::class.java) { RowDecoders.play(incomplete) }
        assertThrows(IllegalArgumentException::class.java) { RowDecoders.playScore(score("\"NaN\"")) }
    }

    @Test fun `detail tolerates missing game and player relationships`() {
        val detail = JsonObject(play() + mapOf("games" to JsonNull, "play_scores" to Json.parseToJsonElement("[]")))
        assertNull(RowDecoders.playDetail(detail).game)
        assertEquals(emptyList<Any>(), RowDecoders.playDetail(detail).scores)
    }

    @Test fun `ratings and player metadata preserve historical nulls`() {
        val game = objectOf("""{"id":"$id","name":"Gioco","bga_id":null,"image_url":null,"min_players":null,"max_players":null,"created_at":null}""")
        assertNull(RowDecoders.game(game).created_at)
        val player = objectOf("""{"id":"$id","user_id":null,"name":"Giocatore","is_me":null,"created_at":null,"alias":null,"avatar_url":null,"updated_at":"2026-10-13T00:00:00Z","color":null,"account_user_id":null}""")
        assertNull(RowDecoders.player(player).account_user_id)
        val fields = "\"id\":\"$id\",\"player_id\":null,\"rating\":null,\"games_played\":null,\"wins\":null,\"losses\":null,\"draws\":null,\"last_played_at\":null,\"created_at\":null,\"updated_at\":null"
        assertNull(RowDecoders.gameRating(objectOf("{$fields,\"game_id\":null}")).rating)
        assertNull(RowDecoders.generalRating(objectOf("{$fields,\"user_id\":null}")).rating)
        val history = objectOf("""{"id":"$id","play_id":"$id","game_id":null,"player_id":"$id","player_name":"Test","rating":1000,"rating_change":-10,"date":"2026-10-13T00:00:00Z","is_general":true,"games_played":null,"user_id":null,"created_at":"2026-10-13T00:00:00Z"}""")
        assertEquals(-10, RowDecoders.eloHistory(history).rating_change)
    }
}
