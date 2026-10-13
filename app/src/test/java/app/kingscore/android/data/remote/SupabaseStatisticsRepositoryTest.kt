package app.kingscore.android.data.remote

import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SupabaseStatisticsRepositoryTest {
    private val account = id(99)
    private val linkedId = id(7)
    // November in Rome, still October in UTC: plays are calendar dates.
    private val clock = Clock.fixed(Instant.parse("2026-10-31T23:30:00Z"), ZoneId.of("Europe/Rome"))

    @Test fun `complete keyset scan exceeds one thousand plays without retaining pages`() = runBlocking {
        val rows = (1..1051).reversed().map { play(it) }
        var calls = 0
        var expected: PlayCursor? = null
        val history = FakePlays { source, cursor ->
            assertNull(source)
            assertEquals(expected, cursor)
            val batch = rows.drop(calls * PAGE_SIZE).take(PAGE_SIZE)
            calls++
            val next = batch.lastOrNull()?.takeIf { batch.size == PAGE_SIZE }?.let { PlayCursor(it.play.played_at, it.play.id) }
            expected = next
            PlayPage(batch, next)
        }
        val progress = mutableListOf<Int>()
        val result = SupabaseStatisticsRepository(profile("[]"), account, history, clock).load(progress::add)
        assertEquals(22, calls)
        assertEquals((1..21).map { it * PAGE_SIZE } + 1051, progress)
        assertEquals(1051, result.totalPlays)
        assertEquals(1051, result.monthPlays)
        assertEquals(0, result.uniqueGames)
        assertEquals(0, result.uniquePlayers)
        assertNull(result.linkedPlayer)
        assertEquals(0, result.personalPlays)
    }

    @Test fun `calendar months durations UUID identity linked owner and personal denominator are explicit`() = runBlocking {
        val game = id(30)
        val another = id(31)
        val linked = player(linkedId, account, "Stesso alias")
        val other = player(id(8), id(98), "Stesso alias")
        val rows = listOf(
            play(4, game, "2026-11-01", 0, listOf(score(linked, true), score(linked, false), score(other, true))),
            play(3, game, "2026-10-31", null, listOf(score(other, true))),
            play(2, game, "2025-11-01", -10, listOf(score(linked, null))),
            play(1, another, "2026-11-02", 75, listOf(score(other, false))),
        )
        val result = SupabaseStatisticsRepository(profile(linkedJson()), account,
            FakePlays { _, _ -> PlayPage(rows, null) }, clock).load {}
        assertEquals(id(123), result.linkedPlayer!!.user_id)
        assertEquals(account, result.linkedPlayer!!.account_user_id)
        assertEquals(4, result.totalPlays)
        assertEquals(2, result.monthPlays)
        assertEquals(2, result.uniquePlayers)
        assertEquals(2, result.uniqueGames)
        assertEquals(75L, result.recordedMinutes)
        assertEquals(2, result.durationSamples)
        assertEquals(2, result.personalPlays)
        assertEquals(1, result.personalWins)
        assertEquals(listOf(3, 1), result.games.map { it.plays })
    }

    @Test fun `duplicate play IDs count once and game names refresh from later joins`() = runBlocking {
        val game = id(30)
        val first = play(3, game, minutes = 30)
        val second = play(2, game, minutes = 0).copy(game = GameRow(game, "Nome aggiornato", null, null, null, null, null))
        var calls = 0
        val history = FakePlays { _, cursor ->
            calls++
            if (cursor == null) PlayPage(listOf(first), PlayCursor(first.play.played_at, first.play.id))
            else PlayPage(listOf(first.copy(play = first.play.copy(duration_minutes = 999)), second), null)
        }
        val progress = mutableListOf<Int>()
        val result = SupabaseStatisticsRepository(profile("[]"), account, history, clock).load(progress::add)
        assertEquals(2, calls)
        assertEquals(listOf(1, 2), progress)
        assertEquals(2, result.totalPlays)
        assertEquals(30L, result.recordedMinutes)
        assertEquals(2, result.durationSamples)
        assertEquals(GameStatistic(game, "Nome aggiornato", 2), result.games.single())
    }

    @Test fun `equal game counts use unsigned UUID order and missing profile never infers is me`() = runBlocking {
        val low = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")
        val high = UUID.fromString("80000000-0000-0000-0000-000000000001")
        val unrelated = player(linkedId, id(98), "Stesso alias").copy(is_me = true, user_id = account)
        val rows = listOf(play(2, high, scores = listOf(score(unrelated, true))), play(1, low))
        val result = SupabaseStatisticsRepository(profile("[]"), account, FakePlays { _, _ -> PlayPage(rows, null) }, clock).load {}
        assertEquals(listOf(low, high), result.games.map { it.gameId })
        assertNull(result.linkedPlayer)
        assertEquals(0, result.personalPlays)
        assertEquals(0, result.personalWins)
        assertEquals(1, result.uniquePlayers)
    }

    @Test fun `missing ambiguous malformed or foreign profile cannot fall back to historical owner`() = runBlocking {
        var scans = 0
        val history = FakePlays { _, _ -> scans++; PlayPage(emptyList(), null) }
        val invalid = listOf("[{},{}]", linkedJson().replace("$account", "${id(98)}"), "not JSON", "[${linkedJson().removeSurrounding("[", "]")},${linkedJson().removeSurrounding("[", "]")}]")
        for (body in invalid) {
            try {
                SupabaseStatisticsRepository(profile(body), account, history, clock).load {}
                fail("Expected invalid profile")
            } catch (failure: DataFailure) { assertEquals(DataProblem.INVALID_RESPONSE, failure.problem) }
        }
        assertEquals(0, scans)
        val empty = SupabaseStatisticsRepository(profile("[]"), account, history, clock).load {}
        assertEquals(0, empty.totalPlays)
        assertTrue(empty.games.isEmpty())
        assertEquals(0L, empty.recordedMinutes)
    }

    @Test fun `failure after progress does not return a partial snapshot and stalled cursors fail`() = runBlocking {
        val first = play(2)
        val cursor = PlayCursor(first.play.played_at, first.play.id)
        val progress = mutableListOf<Int>()
        val repository = SupabaseStatisticsRepository(profile("[]"), account, FakePlays { _, after ->
            if (after == null) PlayPage(listOf(first), cursor) else throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE)
        }, clock)
        try { repository.load(progress::add); fail("Expected auth failure") }
        catch (failure: AuthFailure) { assertEquals(AuthProblem.NETWORK_UNAVAILABLE, failure.problem) }
        assertEquals(listOf(1), progress)
        val stalled = SupabaseStatisticsRepository(profile("[]"), account,
            FakePlays { _, _ -> PlayPage(listOf(first), cursor) }, clock)
        try { stalled.load {}; fail("Expected stalled cursor error") }
        catch (failure: DataFailure) { assertEquals(DataProblem.INVALID_RESPONSE, failure.problem) }
    }

    private fun profile(body: String) = AuthenticatedReader { expected, resource, parameters ->
        assertEquals(account, expected)
        assertEquals("players", resource)
        assertEquals(mapOf("select" to "*", "account_user_id" to "eq.$account", "limit" to "2"), parameters.toMap())
        body
    }
    private fun linkedJson() = """[{"id":"$linkedId","user_id":"${id(123)}","name":"Giocatore","is_me":false,"created_at":null,"alias":"Stesso alias","avatar_url":null,"updated_at":"2026-10-13T00:00:00Z","color":null,"account_user_id":"$account"}]"""
    private fun id(value: Int) = UUID(0, value.toLong())
    private fun player(id: UUID, linked: UUID, alias: String) = PlayerRow(id, this.id(123), "Giocatore", false,
        null, alias, null, Instant.EPOCH, null, linked)
    private fun score(player: PlayerRow, winner: Boolean?) = ScoreWithPlayer(PlayScoreRow(id(200), null, player.id,
        null, winner, null, null, null), player)
    private fun play(value: Int, game: UUID? = null, date: String = "2026-11-01", minutes: Int? = null,
        scores: List<ScoreWithPlayer> = emptyList()) = PlayDetail(PlayRow(id(value), id(123), game,
        LocalDate.parse(date), null, null, minutes, "offline", null, null, null, null), null, scores)

    private class FakePlays(private val scan: suspend (PlaySource?, PlayCursor?) -> PlayPage) : PlaysRepository {
        override suspend fun history(source: PlaySource?, after: PlayCursor?) = scan(source, after)
        override suspend fun detail(id: UUID): PlayDetail? = error("Statistics must not fetch details")
        override suspend fun comments(id: UUID, after: CommentCursor?): CommentPage = error("Statistics must not fetch comments")
    }
}
