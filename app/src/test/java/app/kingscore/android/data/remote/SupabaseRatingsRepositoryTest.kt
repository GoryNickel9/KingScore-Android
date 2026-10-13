package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SupabaseRatingsRepositoryTest {
    private val account = id(9000)
    private val owner = id(9001)
    private val linkedPlayer = id(9002)
    private val game = id(9003)

    @Test fun `catalog scans more than one thousand games by ID and sorts the complete result by name`() = runBlocking {
        val rows = (1..1051).map { n -> id(n) to gameJson(id(n), "Gioco ${(1052 - n).toString().padStart(4, '0')}") }
        var calls = 0
        val reader = AuthenticatedReader { expected, resource, parameters ->
            assertEquals(account, expected)
            assertEquals("games", resource)
            val query = parameters.toMap()
            assertEquals("*", query["select"])
            assertFalse(query.containsKey("offset"))
            val after = if (calls == 0) null else id(calls * PAGE_SIZE)
            assertEquals(after?.let { "gt.$it" }, query["id"])
            calls++
            page(rows, query)
        }
        val result = SupabaseRatingsRepository(reader, account).games()
        assertEquals(1051, result.size)
        assertEquals(22, calls)
        assertEquals(id(1051), result.first().id)
        assertEquals(id(1), result.last().id)
        assertEquals(1051, result.map { it.id }.toSet().size)
    }

    @Test fun `catalog rejects malformed duplicate descending and nonadvancing ID pages`() = runBlocking {
        val invalidBodies = listOf("{}", "[{}]", "[${gameJson(id(1))},${gameJson(id(1))}]",
            "[${gameJson(id(2))},${gameJson(id(1))}]")
        for (body in invalidBodies) expectInvalid { SupabaseRatingsRepository(reader { _, _ -> body }, account).games() }
        val first = (1..50).map { n -> id(n) to gameJson(id(n)) }
        var calls = 0
        val stalled = reader { resource, query ->
            assertEquals("games", resource)
            calls++
            if (query["id"] == null) page(first, query) else "[${gameJson(id(50))}]"
        }
        expectInvalid { SupabaseRatingsRepository(stalled, account).games() }
        assertEquals(2, calls)
    }

    @Test fun `general ranking uses linked historical owner and sorts all pages by published rating with null last`() = runBlocking {
        val values = (1..1051).associate { n -> id(n) to if (n == 1051) null else n % 7 * 100 }
        val rows = (1..1051).map { n -> id(n) to ratingJson(id(n), id(10000 + n), values.getValue(id(n)), ownerId = owner) }
        var profileReads = 0
        var rankingReads = 0
        val repository = SupabaseRatingsRepository(reader { resource, query ->
            when (resource) {
                "players" -> { assertProfileQuery(query); profileReads++; "[${profileJson()}]" }
                "player_general_ratings" -> {
                    assertEquals("eq.$owner", query["user_id"])
                    assertFalse(query.containsKey("game_id"))
                    assertEquals("*,players(*)", query["select"])
                    rankingReads++
                    page(rows, query)
                }
                else -> error("Unexpected resource: $resource")
            }
        }, account)
        val result = repository.ranking(null)
        assertEquals(owner, result.profile!!.user_id)
        assertEquals(account, result.profile!!.account_user_id)
        assertEquals(1, profileReads)
        assertEquals(22, rankingReads)
        assertEquals(1051, result.entries.size)
        val expected = values.entries.sortedWith(compareByDescending<Map.Entry<UUID, Int?>> { it.value }.thenBy { it.key.toString() }).map { it.key }
        assertEquals(expected, result.entries.map { it.id })
        assertNull(result.entries.last().rating)
        assertTrue(result.entries.all { it.ownerId == owner })
    }

    @Test fun `game ranking is global with game filter only and keeps nullable missing player joins`() = runBlocking {
        val high = UUID.fromString("80000000-0000-0000-0000-000000000001")
        val low = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")
        val rows = listOf(
            low to ratingJson(low, id(1), 1100, gameId = game),
            high to ratingJson(high, id(2), 1100, gameId = game),
        )
        val result = SupabaseRatingsRepository(reader { resource, query ->
            if (resource == "players") { assertProfileQuery(query); "[${profileJson()}]" }
            else {
                assertEquals("player_ratings", resource)
                assertEquals("eq.$game", query["game_id"])
                assertFalse(query.containsKey("user_id"))
                assertFalse(query.containsKey("account_user_id"))
                page(rows, query)
            }
        }, account).ranking(game)
        assertEquals(listOf(low, high), result.entries.map { it.id })
        assertTrue(result.entries.all { it.player == null && it.ownerId == null })
        assertEquals(listOf(id(1), id(2)), result.entries.map { it.playerId })
    }

    @Test fun `ranking rejects wrong owner wrong game inconsistent player join duplicate player and ambiguous link`() = runBlocking {
        val invalidGeneral = listOf(
            "[${ratingJson(id(1), id(2), 1000, ownerId = account)}]",
            "[${ratingJson(id(1), id(2), 1000, ownerId = owner, joinedPlayer = profileJson(id(3)))}]",
            "[${ratingJson(id(1), id(2), 1000, ownerId = owner)},${ratingJson(id(2), id(2), 1100, ownerId = owner)}]",
            "[{\"id\":\"${id(1)}\"}]",
        )
        for (body in invalidGeneral) expectInvalid {
            SupabaseRatingsRepository(reader { resource, _ -> if (resource == "players") "[${profileJson()}]" else body }, account).ranking(null)
        }
        expectInvalid {
            SupabaseRatingsRepository(reader { resource, _ ->
                if (resource == "players") "[${profileJson()}]" else "[${ratingJson(id(1), id(2), 1000, gameId = id(123))}]"
            }, account).ranking(game)
        }
        for (profiles in listOf("[${profileJson()},${profileJson(id(123))}]", "[${profileJson(linkedAccount = id(123))}]")) {
            expectInvalid { SupabaseRatingsRepository(reader { resource, _ -> assertEquals("players", resource); profiles }, account).ranking(null) }
        }
    }

    @Test fun `general history filters owner player and kind and keyset compares instants and unsigned IDs`() = runBlocking {
        val published = published(gameId = null)
        val high = UUID.fromString("80000000-0000-0000-0000-000000000001")
        val low = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")
        val instant = Instant.parse("2026-11-01T00:00:00Z")
        val repository = SupabaseRatingsRepository(reader { resource, query ->
            assertEquals("elo_history", resource)
            assertEquals("eq.$owner", query["user_id"])
            assertEquals("eq.$linkedPlayer", query["player_id"])
            assertEquals("eq.true", query["is_general"])
            assertFalse(query.containsKey("game_id"))
            assertEquals("date.desc,id.desc", query["order"])
            assertEquals("50", query["limit"])
            assertEquals("(date.lt.$instant,and(date.eq.$instant,id.lt.$high))", query["or"])
            "[${historyJson(low, general = true, ownerId = owner, date = "2026-11-01T01:00:00+01:00")}]"
        }, account)
        val result = repository.history(published, RatingHistoryCursor(instant, high))
        assertEquals(low, result.items.single().id)
        assertEquals(instant, result.items.single().date)
        assertNull(result.next)
    }

    @Test fun `game history is scoped to game and player while missing histories do not invoke recalculation`() = runBlocking {
        var calls = 0
        val repository = SupabaseRatingsRepository(reader { resource, query ->
            calls++
            assertEquals("elo_history", resource)
            assertEquals("eq.$linkedPlayer", query["player_id"])
            assertEquals("eq.$game", query["game_id"])
            assertEquals("eq.false", query["is_general"])
            assertFalse(query.containsKey("user_id"))
            assertFalse(query.containsKey("or"))
            "[]"
        }, account)
        val result = repository.history(published(gameId = game), null)
        assertTrue(result.items.isEmpty())
        assertNull(result.next)
        assertEquals(1, calls)
        val withoutPlayer = repository.history(published(gameId = game).copy(playerId = null), null)
        assertTrue(withoutPlayer.items.isEmpty())
        assertEquals(1, calls)
    }

    @Test fun `history rejects wrong scope stale cursor duplicate rows and supports a full page next cursor`() = runBlocking {
        val instant = Instant.parse("2026-11-01T00:00:00Z")
        val invalidBodies = listOf(
            "[${historyJson(id(1), gameId = id(123))}]",
            "[${historyJson(id(1), playerId = id(123), gameId = game)}]",
            "[${historyJson(id(1), gameId = game, general = true)}]",
            "[${historyJson(id(1), gameId = game)},${historyJson(id(1), gameId = game)}]",
            "not JSON",
        )
        for (body in invalidBodies) expectInvalid {
            SupabaseRatingsRepository(reader { _, _ -> body }, account).history(published(gameId = game), null)
        }
        expectInvalid {
            SupabaseRatingsRepository(reader { _, _ -> "[${historyJson(id(1), general = true, ownerId = account)}]" }, account)
                .history(published(gameId = null), null)
        }
        expectInvalid {
            SupabaseRatingsRepository(reader { _, _ -> "[${historyJson(id(2), gameId = game)}]" }, account)
                .history(published(gameId = game), RatingHistoryCursor(instant, id(1)))
        }
        val body = (1..50).reversed().joinToString(prefix = "[", postfix = "]") { historyJson(id(it), gameId = game) }
        val page = SupabaseRatingsRepository(reader { _, _ -> body }, account).history(published(gameId = game), null)
        assertEquals(50, page.items.size)
        assertEquals(RatingHistoryCursor(instant, id(1)), page.next)
    }

    @Test fun `unlinked profile falls back to authenticated owner without creating empty or default ratings`() = runBlocking {
        val resources = mutableListOf<String>()
        val ranking = SupabaseRatingsRepository(reader { resource, query ->
            resources += resource
            when (resource) {
                "players" -> { assertProfileQuery(query); "[]" }
                "player_general_ratings" -> {
                    assertEquals("eq.$account", query["user_id"])
                    assertEquals("id.asc", query["order"])
                    "[]"
                }
                else -> error("Unexpected resource")
            }
        }, account).ranking(null)
        assertNull(ranking.profile)
        assertTrue(ranking.entries.isEmpty())
        assertEquals(listOf("players", "player_general_ratings"), resources)
    }

    private fun reader(body: suspend (String, Map<String, String>) -> String) = AuthenticatedReader { expected, resource, parameters ->
        assertEquals(account, expected)
        body(resource, parameters.toMap())
    }
    private fun assertProfileQuery(query: Map<String, String>) {
        assertEquals(mapOf("select" to "*", "account_user_id" to "eq.$account", "limit" to "2"), query)
    }
    private fun page(rows: List<Pair<UUID, String>>, query: Map<String, String>): String {
        assertEquals("id.asc", query["order"])
        assertEquals("50", query["limit"])
        val after = query["id"]?.also { assertTrue(it.startsWith("gt.")) }?.removePrefix("gt.")
        return rows.filter { after == null || it.first.toString() > after }.take(PAGE_SIZE)
            .joinToString(prefix = "[", postfix = "]") { it.second }
    }
    private suspend fun expectInvalid(action: suspend () -> Any?) {
        try { action(); fail("Expected invalid response") }
        catch (failure: DataFailure) { assertEquals(DataProblem.INVALID_RESPONSE, failure.problem) }
    }
    private fun id(value: Int) = UUID(0, value.toLong())
    private fun gameJson(id: UUID, name: String = "Gioco") = """{"id":"$id","name":"$name","bga_id":null,"image_url":null,"min_players":null,"max_players":null,"created_at":null}"""
    private fun profileJson(player: UUID = linkedPlayer, linkedAccount: UUID = account) = """{"id":"$player","user_id":"$owner","name":"Giocatore","is_me":false,"created_at":null,"alias":null,"avatar_url":null,"updated_at":"2026-11-01T00:00:00Z","color":null,"account_user_id":"$linkedAccount"}"""
    private fun ratingJson(id: UUID, player: UUID?, rating: Int?, gameId: UUID? = null, ownerId: UUID? = null,
        joinedPlayer: String = "null"): String {
        val scope = if (gameId == null) "\"user_id\":${ownerId?.let { "\"$it\"" } ?: "null"}" else "\"game_id\":\"$gameId\""
        return """{"id":"$id","player_id":${player?.let { "\"$it\"" } ?: "null"},$scope,"rating":${rating ?: "null"},"games_played":null,"wins":null,"losses":null,"draws":null,"last_played_at":null,"created_at":null,"updated_at":null,"players":$joinedPlayer}"""
    }
    private fun published(gameId: UUID?) = PublishedRating(id(1), null, linkedPlayer, gameId,
        if (gameId == null) owner else null, 1000, null, null, null, null, null)
    private fun historyJson(id: UUID, playerId: UUID = linkedPlayer, gameId: UUID? = null,
        general: Boolean = false, ownerId: UUID? = null, date: String = "2026-11-01T00:00:00Z") =
        """{"id":"$id","play_id":"${this.id(200)}","game_id":${gameId?.let { "\"$it\"" } ?: "null"},"player_id":"$playerId","player_name":"Giocatore","rating":1100,"rating_change":-10,"date":"$date","is_general":$general,"games_played":null,"user_id":${ownerId?.let { "\"$it\"" } ?: "null"},"created_at":"2026-11-01T00:00:00Z"}"""
}
