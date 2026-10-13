package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.PlaySource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SupabasePlaysRepositoryTest {
    private val account = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val date = LocalDate.parse("2026-10-13")
    private fun uuid(value: Int) = UUID.fromString("80000000-0000-0000-0000-${value.toString(16).padStart(12, '0')}")

    @Test fun `keyset traverses more than one thousand records without offsets or lost rows`() = runBlocking {
        val ids = (1..1051).map(::uuid).reversed()
        var calls = 0
        var expectedCursor: UUID? = null
        val reader = AuthenticatedReader { expectedAccount, resource, parameters ->
            assertEquals(account, expectedAccount)
            assertEquals("plays", resource)
            val query = parameters.toMap()
            assertEquals("50", query["limit"])
            assertEquals("played_at.desc,id.desc", query["order"])
            assertFalse(query.containsKey("offset"))
            assertFalse(query.containsKey("user_id"))
            val cursor = expectedCursor
            assertNull(query["or"])
            if (cursor == null) assertNull(query["and"])
            else assertEquals("(or(played_at.lt.$date,and(played_at.eq.$date,id.lt.$cursor)))", query["and"])
            val page = ids.filter { cursor == null || it.toString() < cursor.toString() }.take(PAGE_SIZE)
            expectedCursor = page.lastOrNull()
            calls++
            page.joinToString(prefix = "[", postfix = "]") { play(it) }
        }
        val repository = SupabasePlaysRepository(reader, account)
        val found = mutableListOf<UUID>()
        var cursor: PlayCursor? = null
        do {
            val page = repository.history(HistoryFilters(), cursor)
            found += page.items.map { it.play.id }
            cursor = page.next
        } while (cursor != null)
        assertEquals(ids, found)
        assertEquals(1051, found.toSet().size)
        assertEquals(22, calls)
    }

    @Test fun `UUID ordering follows PostgreSQL unsigned order across sign boundary`() = runBlocking {
        val high = UUID.fromString("80000000-0000-0000-0000-000000000001")
        val low = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")
        val repository = repository("[${play(high)},${play(low)}]")
        assertEquals(listOf(high, low), repository.history().items.map { it.play.id })
        val tail = repository("[${play(low)}]").history(HistoryFilters(), PlayCursor(date, high))
        assertEquals(low, tail.items.single().play.id)
    }

    @Test fun `source filter is sent to server and null relations retain zero and decimal scores`() = runBlocking {
        val id = uuid(1)
        val reader = AuthenticatedReader { _, _, parameters ->
            assertEquals("eq.online", parameters.toMap()["source"])
            "[${play(id, "online", scores = "[${score(id, "0")},${score(uuid(2), "-9007199254740993.125")}]")} ]"
        }
        val item = SupabasePlaysRepository(reader, account).history(HistoryFilters(source = PlaySource.ONLINE)).items.single()
        assertNull(item.game)
        assertNull(item.scores.first().player)
        assertEquals(BigDecimal.ZERO, item.scores.first().score.score)
        assertEquals(BigDecimal("-9007199254740993.125"), item.scores.last().score.score)
        assertNull(item.play.user_id)
        assertNull(item.play.team_won)
    }

    @Test fun `out of order duplicate oversized wrong source and stale cursor pages are rejected`() = runBlocking {
        val first = uuid(2)
        val second = uuid(1)
        val invalid = listOf(
            "[${play(second)},${play(first)}]",
            "[${play(first)},${play(first)}]",
            (1..51).reversed().joinToString(prefix = "[", postfix = "]") { play(uuid(it)) },
        )
        for (body in invalid) expectInvalid { repository(body).history() }
        expectInvalid { repository("[${play(first, "offline")}]").history(HistoryFilters(source = PlaySource.ONLINE)) }
        expectInvalid { repository("[${play(first)}]").history(HistoryFilters(), PlayCursor(date, first)) }
    }

    @Test fun `detail distinguishes missing record forbidden response and malformed record`() = runBlocking {
        val id = uuid(1)
        val reader = AuthenticatedReader { expected, resource, parameters ->
            assertEquals(account, expected)
            assertEquals("plays", resource)
            assertEquals("eq.$id", parameters.toMap()["id"])
            assertEquals("1", parameters.toMap()["limit"])
            "[]"
        }
        assertNull(SupabasePlaysRepository(reader, account).detail(id))
        val forbidden = SupabasePlaysRepository(AuthenticatedReader { _, _, _ -> throw DataFailure(DataProblem.FORBIDDEN) }, account)
        try { forbidden.detail(id); fail("Expected forbidden") } catch (failure: DataFailure) { assertEquals(DataProblem.FORBIDDEN, failure.problem) }
        expectInvalid { repository("[{\"id\":\"$id\"}]").detail(id) }
        expectInvalid { repository("[${play(uuid(2))}]").detail(id) }
        expectInvalid { repository("not JSON").detail(id) }
        val record = repository("[${play(id)}]").detail(id)!!
        assertTrue(record.scores.isEmpty())
        assertNull(record.game)
    }

    @Test fun `comment cursor compares actual instants and includes UUID tie breaker`() = runBlocking {
        val playId = uuid(1)
        val previousId = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")
        val nextId = UUID.fromString("80000000-0000-0000-0000-000000000001")
        val instant = Instant.parse("2026-10-13T00:00:00Z")
        val reader = AuthenticatedReader { _, resource, parameters ->
            assertEquals("play_comments", resource)
            val query = parameters.toMap()
            assertEquals("created_at.asc,id.asc", query["order"])
            assertEquals("eq.$playId", query["play_id"])
            assertEquals("(created_at.gt.$instant,and(created_at.eq.$instant,id.gt.$previousId))", query["or"])
            "[${comment(nextId, playId, "2026-10-13T02:00:00+02:00")},${comment(uuid(3), playId, "2026-10-13T00:00:01Z")}]"
        }
        val page = SupabasePlaysRepository(reader, account).comments(playId, CommentCursor(instant, previousId))
        assertEquals(instant, page.items.first().created_at)
        assertNull(page.next)
        expectInvalid { SupabasePlaysRepository(AuthenticatedReader { _, _, _ -> "[${comment(previousId, playId, instant.toString())}]" }, account).comments(playId, CommentCursor(instant, previousId)) }
        expectInvalid { SupabasePlaysRepository(AuthenticatedReader { _, _, _ -> "[${comment(nextId, uuid(2), instant.toString())}]" }, account).comments(playId, null) }
    }

    private fun repository(body: String) = SupabasePlaysRepository(AuthenticatedReader { _, _, _ -> body }, account)
    private suspend fun expectInvalid(action: suspend () -> Any?) {
        try { action(); fail("Expected invalid response") } catch (failure: DataFailure) { assertEquals(DataProblem.INVALID_RESPONSE, failure.problem) }
    }
    private fun play(id: UUID, source: String? = null, scores: String = "[]") = """{"id":"$id","user_id":null,"game_id":null,"played_at":"$date","location":null,"notes":null,"duration_minutes":null,"source":${source?.let { "\"$it\"" } ?: "null"},"bga_table_id":null,"created_at":null,"is_cooperative":null,"team_won":null,"games":null,"play_scores":$scores}"""
    private fun score(id: UUID, value: String) = """{"id":"$id","play_id":null,"player_id":null,"score":$value,"winner":null,"color":null,"role":null,"position":null,"players":null}"""
    private fun comment(id: UUID, play: UUID, timestamp: String) = """{"id":"$id","play_id":"$play","user_id":"$account","author_name":"Test","content":"Ciao","created_at":"$timestamp","updated_at":"$timestamp"}"""
}
