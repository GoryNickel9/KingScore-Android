package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SummaryJsonTest {
    private val month = YearMonth.of(2026,10)
    @Test fun `rules v2 chart preserves values and publication identity`() {
        val original = SummaryFixture.chart().toString()
        assertEquals(SummaryJson.chart(original), SummaryJson.chart(original.replace("kingscore-elo-legacy-v1", "kingscore-elo-rules-v2")))
    }
    @Test fun `rules v2 chart rejects unknown algorithms and inconsistent publications`() {
        val v2 = SummaryFixture.chart().toString().replace("kingscore-elo-legacy-v1", "kingscore-elo-rules-v2")
        invalid { SummaryJson.chart(v2.replace("kingscore-elo-rules-v2", "unknown")) }
        invalid { SummaryJson.chart(v2.replace("\"publishedRevision\":\"7\"", "\"publishedRevision\":\"6\"")) }
    }
    private fun invalid(action: () -> Unit) {
        try { action(); fail("Expected invalid response") } catch (failure: DataFailure) { assertEquals(DataProblem.INVALID_RESPONSE,failure.problem) }
    }
    @Test fun `home summary is a single RPC without any history or catalog scan`() = runBlocking {
        val calls = mutableListOf<Pair<String,JsonObject>>()
        val repository = SupabaseSummaryRepository(SummaryRpc { name, body -> calls += name to body; SummaryFixture.home().toString() },
            AuthenticatedReader { _,_,_ -> error("Unexpected REST scan") },SummaryFixture.actor,Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneOffset.UTC))
        val value=repository.load { assertEquals(4,it) }
        assertEquals(4,value.totalPlays);assertEquals(40L,value.recordedMinutes);assertEquals(2,value.durationSamples)
        assertEquals(2,value.personalWins);assertEquals(1,calls.size)
        assertEquals("get_app_summary_v1",calls.single().first)
        assertEquals(JsonPrimitive("2026-10-01"),calls.single().second["p_month"])
    }
    @Test fun `overview and details preserve win rate coverage zero null and exact decimal averages`() {
        val overview=SummaryJson.overview(SummaryFixture.overview().toString())
        assertEquals(50,overview.winRates.single().percent)
        assertEquals(4L,overview.games.single().scoreSamples)
        val details=SummaryJson.details(SummaryFixture.details().toString())
        assertEquals(BigDecimal("2.50"),details.players.first().average)
        val huge=JsonObject(SummaryFixture.gameRow + mapOf("scoreSum" to JsonPrimitive("9007199254740993.125"),"scoreAverage" to JsonPrimitive("9007199254740993.13")))
        assertEquals(BigDecimal("9007199254740993.13"),SummaryJson.overview(JsonObject(SummaryFixture.overview()+mapOf("games" to JsonArray(listOf(huge)))).toString()).games.first().scoreAverage)
        val empty=JsonObject(SummaryFixture.gameRow + mapOf("scoreSamples" to JsonPrimitive(0),"scoreAverage" to JsonNull))
        assertNull(SummaryJson.overview(JsonObject(SummaryFixture.overview()+mapOf("games" to JsonArray(listOf(empty)))).toString()).games.first().scoreAverage)
    }
    @Test fun `wrong scope version month counts or linked personal shape cannot become zero totals`() {
        for ((key,value) in mapOf("scope" to JsonPrimitive("other"),"contractVersion" to JsonPrimitive(2),
            "sourceRevision" to JsonPrimitive("9223372036854775808"),"month" to JsonPrimitive("2026-09")))
            invalid { SummaryJson.home(JsonObject(SummaryFixture.home()+mapOf(key to value)).toString(),month) }
        val totals=SummaryFixture.home().getValue("totals").jsonObject
        for ((key,value) in listOf("plays" to JsonPrimitive(-1),"durationSamples" to JsonPrimitive(5),"recordedMinutes" to JsonPrimitive("-1"),"plays" to JsonPrimitive("4")))
            invalid { SummaryJson.home(JsonObject(SummaryFixture.home()+mapOf("totals" to JsonObject(totals+mapOf(key to value)))).toString(),month) }
        invalid { SummaryJson.home(JsonObject(SummaryFixture.home()+mapOf("months" to JsonArray(emptyList()))).toString(),month) }
        invalid { SummaryJson.home("{}",month) }
    }
    @Test fun `incorrect win rate sample coverage and duplicate game IDs are rejected`() {
        val row=SummaryFixture.overview().getValue("winRateByGame").jsonArray.single().jsonObject
        invalid { SummaryJson.overview(JsonObject(SummaryFixture.overview()+mapOf("winRateByGame" to JsonArray(listOf(JsonObject(row+mapOf("percent" to JsonPrimitive(20))))))).toString()) }
        invalid { SummaryJson.overview(JsonObject(SummaryFixture.overview()+mapOf("games" to JsonArray(listOf(SummaryFixture.gameRow,SummaryFixture.gameRow)))).toString()) }
        val badRow=JsonObject(SummaryFixture.gameRow+mapOf("durationSamples" to JsonPrimitive(5)))
        invalid { SummaryJson.overview(JsonObject(SummaryFixture.overview()+mapOf("games" to JsonArray(listOf(badRow)))).toString()) }
    }
    @Test fun `chart keeps separate same-day plays with no invented initial rating`() {
        val chart=SummaryJson.chart(SummaryFixture.chart().toString())
        assertEquals(2,chart.plays.size);assertEquals(chart.plays[0].date,chart.plays[1].date)
        assertTrue(chart.initial.isEmpty());assertEquals(2,chart.players.size)
        assertEquals(1045L,chart.plays[1].ratings[SummaryFixture.player]);assertEquals("opaque-next",chart.next)
        val duplicate=SummaryFixture.chart().getValue("plays").jsonArray
        invalid { SummaryJson.chart(JsonObject(SummaryFixture.chart()+mapOf("plays" to JsonArray(listOf(duplicate[0],duplicate[0])))).toString()) }
    }
    @Test fun `stale chart cannot smuggle current points and revision must match publication`() {
        invalid { SummaryJson.chart(JsonObject(SummaryFixture.chart()+mapOf("status" to JsonPrimitive("stale"))).toString()) }
        invalid { SummaryJson.chart(JsonObject(SummaryFixture.chart()+mapOf("publishedRevision" to JsonPrimitive("6"))).toString()) }
        val stale=JsonObject(SummaryFixture.chart()+mapOf("status" to JsonPrimitive("stale"),"plays" to JsonArray(emptyList()),
            "series" to JsonArray(emptyList()),"nextCursor" to JsonNull,"totalPlays" to JsonPrimitive(0),"publishedRevision" to JsonPrimitive("6")))
        assertEquals("stale",SummaryJson.chart(stale.toString()).status)
    }
    @Test fun `repository binds game publication cursor and selected player parameters`() = runBlocking {
        val calls=mutableListOf<Pair<String,JsonObject>>()
        val repository=SupabaseSummaryRepository(SummaryRpc { name,body -> calls+=name to body
            when(name) { "get_statistics_overview_v1"->SummaryFixture.overview().toString()
                "get_game_statistics_v1"->SummaryFixture.details().toString();else->SummaryFixture.chart().toString() } },
            AuthenticatedReader { _,_,_->error("Unexpected REST scan") },SummaryFixture.actor)
        repository.overview("7","next");repository.gameStatistics(SummaryFixture.game,"7","next-players")
        repository.eloChart(null,SummaryFixture.publication,"next-chart",setOf(SummaryFixture.player))
        assertEquals(JsonPrimitive("7"),calls[0].second["p_expected_revision"])
        assertEquals(JsonPrimitive(SummaryFixture.game.toString()),calls[1].second["p_game_id"])
        assertEquals(JsonPrimitive("next-chart"),calls[2].second["p_cursor"])
        assertEquals(1,calls[2].second.getValue("p_player_ids").jsonArray.size)
        try { repository.overview("8");fail() } catch(failure:DataFailure) { assertEquals(DataProblem.REVISION_CHANGED,failure.problem) }
    }
    @Test fun `shared source revision advances once and cannot be rolled back by a late read`() = runBlocking {
        var revision="7"
        val repository=SupabaseSummaryRepository(SummaryRpc { name,_ ->
            val original=if(name=="get_app_summary_v1") SummaryFixture.home() else SummaryFixture.overview()
            JsonObject(original+mapOf("sourceRevision" to JsonPrimitive(revision))).toString()
        },AuthenticatedReader { _,_,_->error("Unexpected scan") },SummaryFixture.actor,
            Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneOffset.UTC))
        repository.load {};assertEquals("7",repository.dataRevision.value)
        revision="8";repository.overview();assertEquals("8",repository.dataRevision.value)
        revision="7"
        try { repository.load {};fail() } catch(failure:DataFailure) { assertEquals(DataProblem.REVISION_CHANGED,failure.problem) }
        assertEquals("8",repository.dataRevision.value)
    }
    @Test fun `player selection catalog pages until empty even below the requested server limit`() = runBlocking {
        var calls=0
        val repository=SupabaseSummaryRepository(SummaryRpc { _,_->error("No RPC needed") },AuthenticatedReader { _,_,parameters ->
            calls++
            if(calls==1) """[{"id":"${SummaryFixture.player}","name":"Anna","alias":null,"color":null}]"""
            else { assertTrue(parameters.contains("id" to "gt.${SummaryFixture.player}"));"[]" }
        },SummaryFixture.actor)
        assertEquals(1,repository.chartPlayers().size);assertEquals(2,calls)
    }
}
