package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.time.Clock
import java.time.YearMonth
import java.util.UUID
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Production summary path: one bounded RPC, with no full-history scan fallback. */
internal class SupabaseSummaryRepository(private val rpc: SummaryRpc, private val reader: AuthenticatedReader,
    private val account: UUID, private val clock: Clock = Clock.systemDefaultZone(),
) : StatisticsRepository, AutoCloseable {
    private val revision = MutableStateFlow<String?>(null)
    override val dataRevision: StateFlow<String?> = revision
    private fun record(value: String) {
        val before = revision.value
        if (before != null && value.toLong() < before.toLong()) throw DataFailure(DataProblem.REVISION_CHANGED)
        revision.value = value
    }
    override suspend fun load(onProgress: (Int) -> Unit): StatisticsSnapshot {
        val month = YearMonth.now(clock)
        val result = SummaryJson.home(rpc.read("get_app_summary_v1", buildJsonObject { put("p_month", month.atDay(1).toString()) }), month)
        record(checkNotNull(result.sourceRevision))
        onProgress(result.totalPlays)
        return result
    }
    override suspend fun overview(revision: String?, cursor: String?): StatisticsOverview =
        SummaryJson.overview(rpc.read("get_statistics_overview_v1", buildJsonObject {
            revision?.let { put("p_expected_revision", it) }; cursor?.let { put("p_cursor", it) }; put("p_limit", 20)
        })).also {
            if (revision != null && it.revision != revision) throw DataFailure(DataProblem.REVISION_CHANGED)
            record(it.revision)
        }
    override suspend fun gameStatistics(id: UUID, revision: String, cursor: String?): GameStatisticsPage =
        SummaryJson.details(rpc.read("get_game_statistics_v1", buildJsonObject {
            put("p_game_id", id.toString()); put("p_expected_revision", revision)
            cursor?.let { put("p_cursor", it) }; put("p_limit", 50)
        })).also {
            if (it.revision != revision) throw DataFailure(DataProblem.REVISION_CHANGED)
            if (it.game.id != id) throw DataFailure(DataProblem.INVALID_RESPONSE)
            record(it.revision)
        }
    override suspend fun eloChart(gameId: UUID?, publication: UUID?, cursor: String?, players: Set<UUID>?): StatisticsChart =
        SummaryJson.chart(rpc.read("get_elo_chart_v1", buildJsonObject {
            gameId?.let { put("p_game_id", it.toString()) }; publication?.let { put("p_expected_publication_id", it.toString()) }
            cursor?.let { put("p_cursor", it) }; put("p_limit_plays", 100)
            players?.let { put("p_player_ids", JsonArray(it.sortedBy(UUID::toString).map { id -> JsonPrimitive(id.toString()) })) }
        })).also {
            if (it.gameId != gameId) throw DataFailure(DataProblem.INVALID_RESPONSE)
            if (publication != null && it.publication != publication) throw DataFailure(DataProblem.REVISION_CHANGED)
            record(it.revision)
        }
    override suspend fun chartPlayers(): List<ChartPlayer> {
        val result = mutableListOf<ChartPlayer>()
        var after: UUID? = null
        while (true) {
            val text = reader.get(account, "players", buildList {
                add("select" to "id,name,alias,color"); add("order" to "id.asc"); add("limit" to "1000")
                after?.let { add("id" to "gt.$it") }
            })
            val page = SummaryJson.players(text)
            if (page.isEmpty()) return result
            if (page.zipWithNext().any { (a,b) -> a.id.toString() >= b.id.toString() } ||
                after?.let { page.first().id.toString() <= it.toString() } == true) throw DataFailure(DataProblem.INVALID_RESPONSE)
            result += page; after = page.last().id
        }
    }
    override fun close() { (rpc as? AutoCloseable)?.close() }
}
