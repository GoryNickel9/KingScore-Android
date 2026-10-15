package app.kingscore.android.data.remote

import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal object EloPublicationFixture {
    val actor = UUID(0, 99)
    val player = UUID(0, 1)
    val other = UUID(0, 2)
    val game = UUID(0, 10)
    val publication = UUID(0, 30)
    fun snapshot(count: Int = 1, id: UUID = publication, revision: String = "9007199254740993"): JsonObject {
        fun state(player: UUID, name: String, winner: Boolean, general: Boolean) = buildJsonObject {
            put("playerId", player.toString()); if (!general) put("playerName", name)
            put("rating", 1000 + if (winner) count else -count); put("gamesPlayed", count)
            put("wins", if (winner) count else 0); put("losses", if (winner) 0 else count); put("draws", 0)
            put("lastPlayedAt", "2026-10-15")
        }
        fun history(general: Boolean) = buildJsonArray {
            for (n in 1..count) for ((player, name, change) in listOf(Triple(player, "Anna", 1), Triple(other, "Bruno", -1))) add(buildJsonObject {
                if (!general) put("gameId", game.toString()) else put("gamesPlayed", n)
                put("playId", UUID(1, n.toLong()).toString()); put("playerId", player.toString()); put("playerName", name)
                put("rating", 1000 + change * n); put("ratingChange", change); put("date", "2026-10-15")
            })
        }
        return buildJsonObject {
            put("scopeId", "global"); put("algorithm", "kingscore-elo-legacy-v1"); put("status", "current")
            put("sourceRevision", revision); put("publishedRevision", revision); put("publicationId", id.toString())
            put("result", buildJsonObject {
                put("gamePlayers", buildJsonObject { if (count > 0) put(game.toString(), buildJsonObject {
                    put(player.toString(), state(player, "Anna", true, false)); put(other.toString(), state(other, "Bruno", false, false))
                }) })
                put("generalPlayers", buildJsonObject { if (count > 0) {
                    put(player.toString(), state(player, "Anna", true, true)); put(other.toString(), state(other, "Bruno", false, true))
                } })
                put("gameHistory", history(false)); put("generalHistory", history(true)); put("processedPlays", count); put("skippedPlays", 0)
            })
        }
    }
    fun pending(status: String): JsonObject = buildJsonObject {
        put("scopeId", "global"); put("status", status); put("sourceRevision", "2"); put("result", JsonNull)
        put("algorithm", if (status == "missing") JsonNull else JsonPrimitive("kingscore-elo-legacy-v1"))
        put("publishedRevision", if (status == "missing") JsonNull else JsonPrimitive("1"))
        put("publicationId", if (status == "missing") JsonNull else JsonPrimitive(publication.toString()))
    }
}

class EloPublicationJsonTest {
    private val f = EloPublicationFixture
    @Test fun `complete global publication keeps exact bigint identity and all 1200 plays`() {
        val snapshot = EloPublicationJson.decode(f.snapshot(1200).toString())
        assertEquals("9007199254740993", snapshot.sourceRevision)
        assertEquals(f.publication, snapshot.publicationId)
        assertEquals(2400, snapshot.result!!.gameHistory.size)
        assertEquals(2400, snapshot.result.generalHistory.size)
        assertEquals(2200, snapshot.result.generalPlayers.getValue(f.player).rating)
        assertEquals(1200, snapshot.result.gamePlayers.getValue(f.game).getValue(f.player).games)
    }
    @Test fun `current empty is distinct from stale and missing`() {
        val empty = EloPublicationJson.decode(f.snapshot(0).toString())
        assertEquals("current", empty.status); assertTrue(empty.result!!.generalPlayers.isEmpty())
        for (status in listOf("stale", "missing")) {
            val pending = EloPublicationJson.decode(f.pending(status).toString())
            assertEquals(status, pending.status); assertNull(pending.result)
        }
    }
    @Test fun `historical null game scope remains a complete global publication`() {
        val root = f.snapshot()
        val result = root.getValue("result").jsonObject
        val nullGames = buildJsonObject { put("null", result.getValue("gamePlayers").jsonObject.getValue(f.game.toString())) }
        val history = JsonArray(result.getValue("gameHistory").jsonArray.map { JsonObject(it.jsonObject + ("gameId" to JsonNull)) })
        val snapshot = EloPublicationJson.decode(JsonObject(root + ("result" to JsonObject(result +
            ("gamePlayers" to nullGames) + ("gameHistory" to history)))).toString())
        assertEquals(2, snapshot.result!!.gamePlayers.getValue(null).size)
        assertTrue(snapshot.result.gameHistory.all { it.gameId == null })
    }
    @Test fun `scope algorithm exact fields and canonical revisions are mandatory`() {
        val root = f.snapshot()
        val invalid = listOf(root + ("extra" to JsonNull), root - "result", root + ("scopeId" to JsonPrimitive(f.actor.toString())),
            root + ("algorithm" to JsonPrimitive("other")), root + ("status" to JsonPrimitive("ready")),
            root + ("publishedRevision" to JsonPrimitive("1")), root + ("sourceRevision" to JsonPrimitive(123)),
            root + ("publicationId" to JsonPrimitive("invalid"))) + listOf("01", "-1", "1e3", "9223372036854775808").map {
                root + ("sourceRevision" to JsonPrimitive(it)) + ("publishedRevision" to JsonPrimitive(it))
            }
        invalid.forEach { assertThrows(Exception::class.java) { EloPublicationJson.decode(JsonObject(it).toString()) } }
        val max = EloPublicationJson.decode(f.snapshot(revision = Long.MAX_VALUE.toString()).toString())
        assertEquals(Long.MAX_VALUE.toString(), max.sourceRevision)
    }
    @Test fun `stale or missing cannot contain a result or impossible revision order`() {
        for (root in listOf(
            f.pending("missing") + ("result" to f.snapshot().getValue("result")),
            f.pending("missing") + ("algorithm" to JsonPrimitive("kingscore-elo-legacy-v1")),
            f.pending("stale") + ("publishedRevision" to JsonPrimitive("2")),
            f.pending("stale") + ("publishedRevision" to JsonPrimitive("3")),
            f.pending("stale") + ("result" to f.snapshot().getValue("result")),
        )) assertThrows(Exception::class.java) { EloPublicationJson.decode(JsonObject(root).toString()) }
    }
    @Test fun `partial duplicate out of order and inconsistent histories are rejected`() {
        val root = f.snapshot(2)
        val result = root.getValue("result").jsonObject
        val history = result.getValue("gameHistory").jsonArray
        val general = result.getValue("generalHistory").jsonArray
        val invalid = listOf(
            result + ("processedPlays" to JsonPrimitive(1)), result + ("skippedPlays" to JsonPrimitive(-1)),
            result + ("gameHistory" to JsonArray(history.drop(1))), result + ("gameHistory" to JsonArray(history + history.first())),
            result + ("gameHistory" to JsonArray(history.reversed())), result + ("generalHistory" to JsonArray(general.dropLast(1))),
            result + ("generalHistory" to JsonArray(general.mapIndexed { index, value -> if (index == 0)
                JsonObject(value.jsonObject + ("ratingChange" to JsonPrimitive(5))) else value })),
            result + ("generalPlayers" to buildJsonObject {}),
        )
        invalid.forEach { assertThrows(Exception::class.java) { EloPublicationJson.decode(JsonObject(root + ("result" to JsonObject(it))).toString()) } }
    }
    @Test fun `stats names dates integer types and range cannot silently change during decoding`() {
        val body = f.snapshot().toString()
        for (invalid in listOf(
            body.replace("\"wins\":1", "\"wins\":0"), body.replace("\"rating\":1001", "\"rating\":1002"),
            body.replaceFirst("\"Anna\"", "\"Different\""), body.replace("2026-10-15", "2026-02-30"),
            body.replace("\"rating\":1001", "\"rating\":\"1001\""), body.replace("\"rating\":1001", "\"rating\":2147483648"),
        )) assertThrows(Exception::class.java) { EloPublicationJson.decode(invalid) }
    }
}
