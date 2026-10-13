package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlayerRepositoryTest {
    private val account = id(99)
    private val player = id(10)
    private fun id(value: Int) = UUID(0, value.toLong())
    private fun row(key: UUID = player) = """{"id":"$key","user_id":null,"name":"Marta Rossi","is_me":true,"created_at":null,"alias":"","avatar_url":null,"updated_at":"2026-10-13T19:00:00+02:00","color":null,"account_user_id":null}"""
    private fun repository(body: String) = SupabasePlaysRepository(AuthenticatedReader { owner, resource, parameters ->
        assertEquals(account, owner)
        assertEquals("players", resource)
        assertEquals(listOf("select" to "*", "id" to "eq.$player", "limit" to "1"), parameters)
        body
    }, account)

    @Test fun `player read uses verified account UUID and preserves nullable historical metadata`() = runBlocking {
        val record = repository("[${row()}]").player(player)!!
        assertEquals(player, record.id)
        assertEquals("Marta Rossi", record.name)
        assertEquals("", record.alias)
        assertNull(record.account_user_id)
        assertNull(record.avatar_url)
        assertNull(record.user_id)
        assertEquals(Instant.parse("2026-10-13T17:00:00Z"), record.updated_at)
    }

    @Test fun `missing player and permission error remain distinct`() = runBlocking<Unit> {
        assertNull(repository("[]").player(player))
        val forbidden = SupabasePlaysRepository(AuthenticatedReader { _, _, _ -> throw DataFailure(DataProblem.FORBIDDEN) }, account)
        try { forbidden.player(player); fail("Expected permission error") }
        catch (failure: DataFailure) { assertEquals(DataProblem.FORBIDDEN, failure.problem) }
    }

    @Test fun `wrong UUID duplicate rows and invalid player payloads fail explicitly`() = runBlocking<Unit> {
        for (body in listOf("[${row(id(11))}]", "[${row()},${row()}]", "{}", "[{\"id\":\"$player\"}]", "not JSON",
            "[${row().replace("2026-10-13T19:00:00+02:00", "invalid")}]")) {
            try { repository(body).player(player); fail("Expected invalid response") }
            catch (failure: DataFailure) { assertEquals(DataProblem.INVALID_RESPONSE, failure.problem) }
        }
    }

    @Test fun `player history traverses 1051 plays preserving all participants and scope on every page`() = runBlocking {
        val date = LocalDate.parse("2026-10-13")
        val ids = (1..1051).map(::id).reversed()
        var previous: UUID? = null
        var calls = 0
        val reader = AuthenticatedReader { owner, resource, parameters ->
            assertEquals(account, owner)
            assertEquals("plays", resource)
            val query = parameters.toMap()
            assertEquals("*,games(*),play_scores(*,players(*)),matching_scores:play_scores!inner(player_id)", query["select"])
            assertEquals("eq.$player", query["matching_scores.player_id"])
            assertEquals("50", query["limit"])
            assertEquals("played_at.desc,id.desc", query["order"])
            assertNull(query["offset"])
            assertNull(query["user_id"])
            assertNull(query["play_scores.player_id"])
            if (previous == null) assertNull(query["and"])
            else assertEquals("(or(played_at.lt.$date,and(played_at.eq.$date,id.lt.$previous)))", query["and"])
            val page = ids.filter { previous == null || it.toString() < previous.toString() }.take(PAGE_SIZE)
            previous = page.lastOrNull()
            calls++
            page.joinToString(prefix = "[", postfix = "]") { play ->
                val scores = listOf(player, id(11)).mapIndexed { index, participant ->
                    """{"id":"${id(5000 + play.leastSignificantBits.toInt() * 2 + index)}","play_id":"$play","player_id":"$participant","score":0,"winner":false,"color":null,"role":null,"position":null,"players":null}"""
                }.joinToString(prefix = "[", postfix = "]")
                """{"id":"$play","user_id":null,"game_id":null,"played_at":"$date","location":null,"notes":null,"duration_minutes":null,"source":null,"bga_table_id":null,"created_at":null,"is_cooperative":null,"team_won":null,"games":null,"play_scores":$scores}"""
            }
        }
        val repository = SupabasePlaysRepository(reader, account)
        val found = mutableListOf<UUID>()
        var cursor: PlayCursor? = null
        do {
            val page = repository.history(HistoryFilters(playerId = player), cursor)
            assertTrue(page.items.all { it.scores.size == 2 && it.scores.map { score -> score.score.player_id }.containsAll(listOf(player, id(11))) })
            found += page.items.map { it.play.id }
            cursor = page.next
        } while (cursor != null)
        assertEquals(ids, found)
        assertEquals(1051, found.toSet().size)
        assertEquals(22, calls)
    }
}
