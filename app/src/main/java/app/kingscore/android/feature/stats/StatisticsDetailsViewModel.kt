package app.kingscore.android.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class GameStatisticsState(val data: GameStatisticsPage? = null, val loading: Boolean = false, val problem: DataProblem? = null)
data class StatisticsChartState(val data: StatisticsChart? = null, val loading: Boolean = true, val problem: DataProblem? = null,
    val cursor: String? = null, val previous: List<String?> = emptyList(), val selection: Set<UUID>? = null,
    val options: List<ChartPlayer> = emptyList())
data class StatisticsDetailsState(val overview: StatisticsOverview? = null, val loading: Boolean = true, val problem: DataProblem? = null,
    val expanded: Set<UUID> = emptySet(), val games: Map<UUID, GameStatisticsState> = emptyMap(),
    val charts: Map<UUID?, StatisticsChartState> = mapOf(null to StatisticsChartState()),
    val playerOptions: List<ChartPlayer> = emptyList(), val optionsLoading: Boolean = false, val optionsProblem: DataProblem? = null)

class StatisticsDetailsViewModel(private val repository: StatisticsRepository,
    private val onAccessFailure: (AuthProblem) -> Unit,
) : ViewModel() {
    private val state = MutableStateFlow(StatisticsDetailsState())
    val statistics: StateFlow<StatisticsDetailsState> = state
    private val jobs = mutableMapOf<String, Job>()
    private var generation = 0
    private var initialized = false
    private var observedRevision: Int? = null
    private var conflictRetries = 0
    private val selections = mutableMapOf<UUID?, Set<UUID>?>()

    fun show(revision: Int) { if (!initialized || observedRevision != revision) { observedRevision = revision; refresh() } }
    fun refresh() { conflictRetries = 0; startFresh() }
    private fun startFresh() {
        generation++; jobs.values.toList().forEach(Job::cancel); jobs.clear(); initialized = true
        state.value = StatisticsDetailsState(expanded = state.value.expanded)
        loadOverview(false); loadChart(null)
    }
    private fun request(key: String, onFailure: (DataProblem) -> Unit, work: suspend () -> Unit) {
        jobs[key]?.cancel(); val stamp = generation
        jobs[key] = viewModelScope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: AuthFailure) { if (stamp == generation) { clearMemory(); onAccessFailure(failure.problem) } }
            catch (failure: DataFailure) {
                if (stamp != generation) return@launch
                if (failure.problem == DataProblem.REVISION_CHANGED) {
                    if (conflictRetries++ == 0) startFresh()
                    else { generation++; jobs.values.toList().forEach(Job::cancel)
                        state.value = StatisticsDetailsState(loading = false, problem = DataProblem.REVISION_CHANGED,
                            charts = mapOf(null to StatisticsChartState(loading = false, problem = DataProblem.REVISION_CHANGED))) }
                } else onFailure(failure.problem)
            }
        }
    }
    private suspend fun ensureCurrent(stamp: Int) {
        currentCoroutineContext().ensureActive()
        if (stamp != generation) throw CancellationException()
    }
    fun moreGames() { if (!state.value.loading && state.value.overview?.next != null) loadOverview(true) }
    private fun loadOverview(more: Boolean) {
        val before = state.value.overview
        val stamp = generation
        state.value = state.value.copy(loading = true, problem = null)
        request("overview", { problem -> state.value = state.value.copy(overview = null, loading = false, problem = problem, games = emptyMap()) }) {
            val page = repository.overview(if (more) before?.revision else null, if (more) before?.next else null)
            ensureCurrent(stamp)
            if (state.value.charts.values.any { it.data?.revision?.let { revision -> revision != page.revision } == true })
                throw DataFailure(DataProblem.REVISION_CHANGED)
            val result = if (more && before != null) {
                requirePage(page.games.map { it.id }, before.games.map { it.id }, page.next, before.next)
                if (page.totalGames != before.totalGames || page.winRates != before.winRates) throw DataFailure(DataProblem.INVALID_RESPONSE)
                page.copy(games = before.games + page.games)
            } else page
            state.value = state.value.copy(overview = result, loading = false)
            if (!more) state.value.expanded.filter { id -> result.games.any { it.id == id } }.forEach { loadGame(it); loadChart(it) }
        }
    }
    fun toggleGame(id: UUID) {
        val open = id !in state.value.expanded
        state.value = state.value.copy(expanded = if (open) state.value.expanded + id else state.value.expanded - id)
        if (open) {
            if (state.value.games[id]?.data == null && state.value.games[id]?.loading != true) loadGame(id)
            if (state.value.charts[id]?.data == null && state.value.charts[id]?.loading != true) loadChart(id)
        }
    }
    fun loadGame(id: UUID, more: Boolean = false) {
        val overview = state.value.overview ?: return
        val before = state.value.games[id]?.data
        val stamp = generation
        state.value = state.value.copy(games = state.value.games + (id to GameStatisticsState(before, loading = true)))
        request("game:$id", { problem -> state.value = state.value.copy(games = state.value.games + (id to GameStatisticsState(problem = problem))) }) {
            val page = repository.gameStatistics(id, overview.revision, if (more) before?.next else null)
            ensureCurrent(stamp)
            if (page.revision != overview.revision) throw DataFailure(DataProblem.REVISION_CHANGED)
            if (page.game != overview.games.find { it.id == id }) throw DataFailure(DataProblem.INVALID_RESPONSE)
            val result = if (more && before != null) {
                requirePage(page.players.map { it.id }, before.players.map { it.id }, page.next, before.next)
                if (page.totalPlayers != before.totalPlayers) throw DataFailure(DataProblem.INVALID_RESPONSE)
                page.copy(players = before.players + page.players)
            } else page
            state.value = state.value.copy(games = state.value.games + (id to GameStatisticsState(result)))
        }
    }
    fun nextChart(id: UUID?) {
        val current = state.value.charts[id] ?: return
        val data = current.data ?: return
        if (current.loading || data.next == null) return
        loadChart(id, data.next, data.publication, current.previous + listOf(current.cursor))
    }
    fun previousChart(id: UUID?) {
        val current = state.value.charts[id] ?: return
        if (current.loading || current.previous.isEmpty()) return
        loadChart(id, current.previous.last(), current.data?.publication, current.previous.dropLast(1))
    }
    fun selectPlayers(id: UUID?, players: Set<UUID>) {
        if (players.isEmpty() || players.size > 64) return
        selections[id] = players.toSet(); loadChart(id)
    }
    fun loadChart(id: UUID?, cursor: String? = null, publication: UUID? = null, previous: List<String?> = emptyList()) {
        val stamp = generation; val selected = selections[id]
        val options = state.value.charts[id]?.options ?: emptyList()
        state.value = state.value.copy(charts = state.value.charts + (id to StatisticsChartState(
            loading = true, cursor = cursor, previous = previous, selection = selected, options = options)))
        request("chart:$id", { problem ->
            state.value = state.value.copy(charts = state.value.charts + (id to StatisticsChartState(
                loading = false, problem = problem, selection = selected, options = options)))
            if (problem == DataProblem.SELECTION_REQUIRED) loadPlayerOptions()
        }) {
            val data = repository.eloChart(id, publication, cursor, selected)
            ensureCurrent(stamp)
            if (state.value.overview?.revision?.let { it != data.revision } == true) throw DataFailure(DataProblem.REVISION_CHANGED)
            if (data.next != null && (data.plays.isEmpty() || data.next == cursor)) throw DataFailure(DataProblem.INVALID_RESPONSE)
            state.value = state.value.copy(charts = state.value.charts + (id to StatisticsChartState(
                data, loading = false, cursor = cursor, previous = previous, selection = selected,
                options = if (data.availableSeries == data.players.size) data.players else options)))
        }
    }
    fun loadPlayerOptions() {
        if (state.value.optionsLoading || state.value.playerOptions.isNotEmpty()) return
        val stamp = generation
        state.value = state.value.copy(optionsLoading = true, optionsProblem = null)
        request("players", { problem -> state.value = state.value.copy(optionsLoading = false, optionsProblem = problem) }) {
            val players = repository.chartPlayers(); ensureCurrent(stamp)
            state.value = state.value.copy(playerOptions = players, optionsLoading = false)
        }
    }
    private fun <T> requirePage(ids: List<T>, previous: List<T>, next: String?, priorCursor: String?) {
        if (ids.any { it in previous } || ids.distinct().size != ids.size ||
            next != null && (ids.isEmpty() || next == priorCursor)) throw DataFailure(DataProblem.INVALID_RESPONSE)
    }
    fun clearMemory() {
        generation++; jobs.values.toList().forEach(Job::cancel); jobs.clear(); selections.clear()
        initialized = false; observedRevision = null; state.value = StatisticsDetailsState()
    }
    override fun onCleared() = clearMemory()
}
