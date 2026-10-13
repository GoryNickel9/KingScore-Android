package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.PlaySource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoryFiltersRepositoryTest {
    private val account = id(9000)
    private val game = id(9001)
    private val player = id(9002)

    @Test fun `search matches game OR notes and intersects cursor without overwriting either condition`() = runBlocking<Unit> {
        val date = LocalDate.parse("2026-10-13")
        val after = PlayCursor(date, id(100))
        val requests = mutableListOf<List<Pair<String, String>>>()
        val repository = repository { resource, parameters ->
            assertEquals("plays", resource)
            requests += parameters
            val query = parameters.toMap()
            assertEquals("*,games(*),play_scores(*,players(*)),matched_game:games()", query["select"])
            assertEquals("imatch.\"Azul\"", query["matched_game.name"])
            assertNull(query["or"])
            val conditions = if (requests.size == 1) "(or(notes.imatch.\"Azul\",matched_game.not.is.null))"
                else "(or(notes.imatch.\"Azul\",matched_game.not.is.null),or(played_at.lt.$date,and(played_at.eq.$date,id.lt.${after.id})))"
            assertEquals(conditions, query["and"])
            assertEquals(1, parameters.count { it.first == "and" })
            "[]"
        }
        repository.history(HistoryFilters(query = "  Azul  "))
        repository.history(HistoryFilters(query = "Azul"), after)
        assertEquals(2, requests.size)
        // A notes match may have no game relationship; do not require games!inner.
        val noteOnly = SupabasePlaysRepository(AuthenticatedReader { _, _, _ -> "[${play(id(1), notes = "Azul")}]" }, account)
            .history(HistoryFilters(query = "Azul")).items.single()
        assertNull(noteOnly.game)
        assertEquals("Azul", noteOnly.play.notes)
    }

    @Test fun `literal POSIX patterns and PostgREST quoting preserve punctuation wildcards and Unicode`() = runBlocking<Unit> {
        // Expected payloads are explicit wire values, not produced by production's escape function.
        val cases = listOf(
            "*" to "\\\\*", "%" to "%", "_" to "_", "," to ",",
            "\"" to "\\\"", "\\" to "\\\\\\\\", "a.b" to "a\\\\.b",
            "[a-z]" to "\\\\[a-z\\\\]", "(a|b)" to "\\\\(a\\\\|b\\\\)",
            "a+b?" to "a\\\\+b\\\\?", "x{2}" to "x\\\\{2\\\\}",
            "^fin$" to "\\\\^fin\\\\$", "O'Brien" to "O'Brien", "Caffè😀" to "Caffè😀",
        )
        for ((input, quotedContent) in cases) {
            val expected = "\"$quotedContent\""
            val repository = repository { _, parameters ->
                val query = parameters.toMap()
                assertEquals("input=$input", "imatch.$expected", query["matched_game.name"])
                assertEquals("(or(notes.imatch.$expected,matched_game.not.is.null))", query["and"])
                assertFalse(query["matched_game.name"]!!.contains("\\Q"))
                "[]"
            }
            repository.history(HistoryFilters(query = input))
        }
        val blank = repository { _, parameters ->
            val query = parameters.toMap()
            assertEquals("*,games(*),play_scores(*,players(*))", query["select"])
            assertNull(query["matched_game.name"])
            assertNull(query["and"])
            "[]"
        }
        blank.history(HistoryFilters(query = "   "))
    }

    @Test fun `combined filters include both date boundaries and preserve all participant score rows`() = runBlocking<Unit> {
        val from = LocalDate.parse("2026-10-01")
        val to = LocalDate.parse("2026-10-31")
        val filters = HistoryFilters(query = "Azul", source = PlaySource.ONLINE, gameId = game,
            playerId = player, from = from, to = to)
        val scores = "[${score(id(10), player)},${score(id(11), id(9003))},${score(id(12), player)}]"
        val after = PlayCursor(LocalDate.parse("2026-11-01"), id(999))
        val repository = repository { resource, parameters ->
            assertEquals("plays", resource)
            val query = parameters.toMap()
            assertEquals("eq.online", query["source"])
            assertEquals("eq.$game", query["game_id"])
            assertEquals("eq.$player", query["matching_scores.player_id"])
            assertFalse(query.containsKey("play_scores.player_id"))
            assertEquals(listOf("gte.$from", "lte.$to"), parameters.filter { it.first == "played_at" }.map { it.second })
            assertEquals("*,games(*),play_scores(*,players(*)),matched_game:games(),matching_scores:play_scores!inner(player_id)", query["select"])
            assertEquals("(or(notes.imatch.\"Azul\",matched_game.not.is.null),or(played_at.lt.${after.date},and(played_at.eq.${after.date},id.lt.${after.id})))", query["and"])
            "[${play(id(2), to, "online", game, scores, "Azul")},${play(id(1), from, "online", game, scores, "Azul")}]"
        }
        val page = repository.history(filters, after)
        assertEquals(listOf(to, from), page.items.map { it.play.played_at })
        assertEquals(3, page.items.first().scores.size)
        assertEquals(listOf(player, id(9003), player), page.items.first().scores.map { it.score.player_id })
    }

    @Test fun `filter validation rejects rows with inconsistent game participant source or inclusive dates`() = runBlocking<Unit> {
        val from = LocalDate.parse("2026-10-01")
        val to = LocalDate.parse("2026-10-31")
        val filters = HistoryFilters(source = PlaySource.IRL, gameId = game, playerId = player, from = from, to = to)
        val scores = "[${score(id(10), player)}]"
        val invalid = listOf(
            play(id(1), from, "offline", id(123), scores),
            play(id(1), from, "offline", game, "[${score(id(10), id(123))}]"),
            play(id(1), from, "online", game, scores),
            play(id(1), from.minusDays(1), "offline", game, scores),
            play(id(1), to.plusDays(1), "offline", game, scores),
        )
        for (row in invalid) expectInvalid { repository { _, _ -> "[$row]" }.history(filters) }
        expectInvalid { repository { _, _ -> "[${play(id(1), from, "offline", game, scores)}]" }
            .history(filters, PlayCursor(from, id(1))) }
    }

    @Test fun `invalid query and reversed range are rejected before any request`() {
        assertThrows(IllegalArgumentException::class.java) { HistoryFilters(query = "x".repeat(MAX_HISTORY_QUERY + 1)) }
        assertThrows(IllegalArgumentException::class.java) { HistoryFilters(query = "line\nnext") }
        assertThrows(IllegalArgumentException::class.java) { HistoryFilters(query = "tab\tvalue") }
        assertThrows(IllegalArgumentException::class.java) { HistoryFilters(query = "delete\u007f") }
        assertThrows(IllegalArgumentException::class.java) {
            HistoryFilters(from = LocalDate.parse("2026-10-31"), to = LocalDate.parse("2026-10-01"))
        }
        assertFalse(HistoryFilters().active)
        assertFalse(HistoryFilters(query = "  ").active)
        assertTrue(HistoryFilters(to = LocalDate.parse("2026-10-31")).active)
        assertTrue(HistoryFilters(query = "x".repeat(MAX_HISTORY_QUERY)).active)
    }

    @Test fun `filter catalogs traverse more than one thousand IDs and retain distinct namesakes and null aliases`() = runBlocking<Unit> {
        val rows = (1..1051).map(::id)
        val calls = mutableMapOf<String, Int>()
        val repository = repository { resource, parameters ->
            assertTrue(resource == "games" || resource == "players")
            val query = parameters.toMap()
            val count = calls.getOrDefault(resource, 0)
            assertEquals("*", query["select"])
            assertEquals("id.asc", query["order"])
            assertEquals("50", query["limit"])
            assertFalse(query.containsKey("user_id"))
            assertFalse(query.containsKey("account_user_id"))
            assertFalse(query.containsKey("offset"))
            assertEquals(if (count == 0) null else "gt.${id(count * PAGE_SIZE)}", query["id"])
            calls[resource] = count + 1
            val after = query["id"]?.removePrefix("gt.")
            rows.filter { after == null || it.toString() > after }.take(PAGE_SIZE)
                .joinToString(prefix = "[", postfix = "]") {
                    if (resource == "games") gameJson(it) else playerJson(it)
                }
        }
        val options = repository.filterOptions()
        assertEquals(mapOf("games" to 22, "players" to 22), calls)
        assertEquals(1051, options.games.size)
        assertEquals(1051, options.players.size)
        assertEquals(rows, options.games.map { it.id })
        assertEquals(rows, options.players.map { it.id })
        assertEquals(1051, options.players.map { it.id }.toSet().size)
        assertTrue(options.players.all { it.alias == null })
        assertEquals(1, options.players.map { it.name }.toSet().size)
    }

    @Test fun `catalog sorting uses alias when nonblank otherwise name and rejects duplicate malformed or backwards IDs`() = runBlocking<Unit> {
        val low = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")
        val high = UUID.fromString("80000000-0000-0000-0000-000000000001")
        val options = repository { resource, _ ->
            if (resource == "games") "[${gameJson(low)},${gameJson(high)}]"
            else "[${playerJson(low, "Zoe", "")},${playerJson(high, "Zoe", "Ada")}]"
        }.filterOptions()
        assertEquals(listOf(low, high), options.games.map { it.id })
        assertEquals(listOf(high, low), options.players.map { it.id })
        for (resource in listOf("games", "players")) {
            val row = if (resource == "games") gameJson(id(1)) else playerJson(id(1))
            expectInvalid { repository { kind, _ -> if (kind == resource) "[$row,$row]" else "[]" }.filterOptions() }
            expectInvalid { repository { kind, _ -> if (kind == resource) "[{}]" else "[]" }.filterOptions() }
            val descending = if (resource == "games") "[${gameJson(high)},${gameJson(low)}]"
                else "[${playerJson(high)},${playerJson(low)}]"
            expectInvalid { repository { kind, _ -> if (kind == resource) descending else "[]" }.filterOptions() }
        }
    }

    private fun repository(body: suspend (String, List<Pair<String, String>>) -> String) = SupabasePlaysRepository(
        AuthenticatedReader { expected, resource, parameters -> assertEquals(account, expected); body(resource, parameters) }, account)
    private suspend fun expectInvalid(action: suspend () -> Any?) {
        try { action(); fail("Expected invalid response") }
        catch (failure: DataFailure) { assertEquals(DataProblem.INVALID_RESPONSE, failure.problem) }
    }
    private fun id(value: Int) = UUID(0, value.toLong())
    private fun play(id: UUID, date: LocalDate = LocalDate.parse("2026-10-13"), source: String? = null,
        gameId: UUID? = null, scores: String = "[]", notes: String? = null) =
        """{"id":"$id","user_id":null,"game_id":${gameId?.let { "\"$it\"" } ?: "null"},"played_at":"$date","location":null,"notes":${notes?.let { "\"$it\"" } ?: "null"},"duration_minutes":null,"source":${source?.let { "\"$it\"" } ?: "null"},"bga_table_id":null,"created_at":null,"is_cooperative":null,"team_won":null,"games":null,"play_scores":$scores}"""
    private fun score(id: UUID, playerId: UUID) = """{"id":"$id","play_id":null,"player_id":"$playerId","score":0,"winner":null,"color":null,"role":null,"position":null,"players":null}"""
    private fun gameJson(id: UUID) = """{"id":"$id","name":"Stesso nome","bga_id":null,"image_url":null,"min_players":null,"max_players":null,"created_at":null}"""
    private fun playerJson(id: UUID, name: String = "Stesso nome", alias: String? = null) =
        """{"id":"$id","user_id":null,"name":"$name","is_me":null,"created_at":null,"alias":${alias?.let { "\"$it\"" } ?: "null"},"avatar_url":null,"updated_at":"2026-10-13T00:00:00Z","color":null,"account_user_id":null}"""
}
