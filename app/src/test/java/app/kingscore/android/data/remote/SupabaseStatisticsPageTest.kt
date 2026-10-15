package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SupabaseStatisticsPageTest {
    private val account = UUID(0, 99)
    private val game = UUID(0, 30)
    private val date = LocalDate.parse("2026-10-15")

    @Test fun `summary projection pages complete exact multiples with the same stable history cursor`() = runBlocking {
        var calls = 0
        val reader = AuthenticatedReader { expected, resource, parameters ->
            assertEquals(account, expected)
            assertEquals("plays", resource)
            val query = parameters.toMap()
            assertEquals("id,played_at,duration_minutes,game_id,games(id,name,image_url),play_scores(player_id,winner)", query["select"])
            assertEquals("played_at.desc,id.desc", query["order"])
            assertEquals("500", query["limit"])
            if (calls == 0) assertNull(query["or"])
            else {
                val last = if (calls == 1) 501 else 1
                assertEquals("(played_at.lt.$date,and(played_at.eq.$date,id.lt.${UUID(0, last.toLong())}))", query["or"])
            }
            val values = when (calls++) { 0 -> (1000 downTo 501); 1 -> (500 downTo 1); else -> IntRange.EMPTY }
            values.joinToString(prefix = "[", postfix = "]") { row(it) }
        }
        val repository = SupabasePlaysRepository(reader, account)
        val first = repository.statisticsPage()
        val second = repository.statisticsPage(first.next)
        val end = repository.statisticsPage(second.next)
        assertEquals((1000 downTo 1).map { UUID(0, it.toLong()) }, (first.items + second.items).map { it.id })
        assertTrue(end.items.isEmpty())
        assertNull(end.next)
        assertEquals(3, calls)
    }

    @Test fun `server cap of twenty rows cannot truncate a summary beyond fifty plays`() = runBlocking {
        val values = (65 downTo 1).toList()
        var calls = 0
        val reader = AuthenticatedReader { _, resource, parameters ->
            when (resource) {
                "players" -> "[]"
                "plays" -> {
                    assertEquals("500", parameters.toMap()["limit"])
                    val rows = values.drop(calls * 20).take(20)
                    if (calls == 0) assertNull(parameters.toMap()["or"])
                    else {
                        val previousLast = values[minOf(calls * 20, values.size) - 1]
                        assertEquals("(played_at.lt.$date,and(played_at.eq.$date,id.lt.${UUID(0, previousLast.toLong())}))",
                            parameters.toMap()["or"])
                    }
                    calls++
                    rows.joinToString(prefix = "[", postfix = "]") { row(it) }
                }
                else -> error("Unexpected resource $resource")
            }
        }
        val progress = mutableListOf<Int>()
        val result = SupabaseStatisticsRepository(reader, account, SupabasePlaysRepository(reader, account)).load(progress::add)
        assertEquals(65, result.totalPlays)
        assertEquals(65, result.games.single().plays)
        assertEquals(listOf(20, 40, 60, 65, 65), progress)
        assertEquals(5, calls)
    }

    @Test fun `483 lightweight plays require one data page and one empty completion page`() = runBlocking {
        var calls = 0
        val repository = SupabasePlaysRepository(AuthenticatedReader { _, _, parameters ->
            assertEquals("500", parameters.toMap()["limit"])
            if (calls++ == 0) (483 downTo 1).joinToString(prefix = "[", postfix = "]") { row(it) } else "[]"
        }, account)
        val first = repository.statisticsPage()
        assertEquals(483, first.items.size)
        assertNotNull(first.next)
        val final = repository.statisticsPage(first.next)
        assertTrue(final.items.isEmpty())
        assertNull(final.next)
        assertEquals(2, calls)
    }

    @Test fun `lightweight rows produce complete totals without score values notes or player joins`() = runBlocking {
        val reader = AuthenticatedReader { expected, resource, parameters ->
            assertEquals(account, expected)
            when (resource) {
                "players" -> "[]"
                "plays" -> {
                    assertFalse(parameters.toMap().getValue("select").contains('*'))
                    if (parameters.toMap()["or"] == null) "[${row(2, "0")},${row(1, "75")}]" else "[]"
                }
                else -> error("Unexpected resource $resource")
            }
        }
        val clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC)
        val progress = mutableListOf<Int>()
        val result = SupabaseStatisticsRepository(reader, account, SupabasePlaysRepository(reader, account), clock).load(progress::add)
        assertEquals(2, result.totalPlays)
        assertEquals(2, result.monthPlays)
        assertEquals(1, result.uniqueGames)
        assertEquals(2, result.uniquePlayers)
        assertEquals(75L, result.recordedMinutes)
        assertEquals(2, result.durationSamples)
        assertEquals(listOf(GameStatistic(game, "Azul", 2, "https://example.com/azul.jpg")), result.games)
        assertEquals(YearMonth.of(2026, 10), result.months.last().month)
        assertEquals(2, result.months.last().plays)
        assertEquals(listOf(2, 2), progress)
    }

    @Test fun `malformed oversized unordered and foreign cursor pages cannot become summary totals`() = runBlocking {
        val invalidBodies = listOf(
            "[{}]", "[${row(1).replace("\"winner\":true", "\"winner\":\"true\"")}]",
            "[${row(1).replace("\"duration_minutes\":null", "\"duration_minutes\":\"1\"")}]",
            "[${row(1)},${row(2)}]", "[${row(1)},${row(1)}]",
            "[${row(1).replace("\"games\":{\"id\":\"$game\"", "\"games\":{\"id\":\"${UUID(0, 333)}\"")}]",
            (501 downTo 1).joinToString(prefix = "[", postfix = "]") { row(it) },
        )
        for (body in invalidBodies) {
            val repository = SupabasePlaysRepository(AuthenticatedReader { _, _, _ -> body }, account)
            try { repository.statisticsPage(); fail("Malformed summary accepted") }
            catch (failure: DataFailure) { assertEquals(DataProblem.INVALID_RESPONSE, failure.problem) }
        }
        val repository = SupabasePlaysRepository(AuthenticatedReader { _, _, _ -> "[${row(2)}]" }, account)
        try { repository.statisticsPage(PlayCursor(date, UUID(0, 1))); fail("Cursor moved backwards") }
        catch (failure: DataFailure) { assertEquals(DataProblem.INVALID_RESPONSE, failure.problem) }
    }

    private fun row(value: Int, minutes: String = "null") = """{"id":"${UUID(0, value.toLong())}","played_at":"$date","duration_minutes":$minutes,"game_id":"$game","games":{"id":"$game","name":"Azul","image_url":"https://example.com/azul.jpg"},"play_scores":[{"player_id":"${UUID(0, 7)}","winner":true},{"player_id":"${UUID(0, 8)}","winner":false},{"player_id":null,"winner":null}]}"""
}
