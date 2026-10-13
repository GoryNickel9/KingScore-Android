package app.kingscore.android.feature.ratings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import kingscore.contracts.v1.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.UUID

data class RatingsState(
    val games: List<GameRow> = emptyList(), val ranking: Ranking? = null, val loading: Boolean = true,
    val problem: DataProblem? = null, val gameId: UUID? = null, val selected: PublishedRating? = null,
    val history: List<EloHistoryRow> = emptyList(), val nextHistory: RatingHistoryCursor? = null,
    val historyLoading: Boolean = false, val historyProblem: DataProblem? = null, val readAt: Instant? = null,
)
class RatingsViewModel(private val repository: RatingsRepository, private val onAccessFailure: (AuthProblem) -> Unit) : ViewModel() {
    private val state = MutableStateFlow(RatingsState())
    val ratings: StateFlow<RatingsState> = state
    private var request: Job? = null
    private var historyRequest: Job? = null
    private var generation = 0
    private var historyGeneration = 0
    private var initialized = false

    fun show(gameId: UUID?) { if (!initialized || state.value.gameId != gameId) refresh(gameId) }
    fun refresh(gameId: UUID? = state.value.gameId) {
        initialized = true
        generation++
        historyGeneration++
        request?.cancel(); historyRequest?.cancel()
        val stamp = generation
        state.value = RatingsState(games = state.value.games, gameId = gameId)
        request = viewModelScope.launch {
            try {
                val games = if (state.value.games.isEmpty()) repository.games() else state.value.games
                if (stamp != generation) return@launch
                state.value = state.value.copy(games = games)
                val ranking = repository.ranking(gameId)
                if (stamp == generation) state.value = RatingsState(games, ranking, loading = false, gameId = gameId, readAt = Instant.now())
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) { failAccess(failure, stamp)
            } catch (failure: DataFailure) { if (stamp == generation) state.value = state.value.copy(loading = false, problem = failure.problem) }
        }
    }
    fun select(rating: PublishedRating) {
        historyGeneration++
        historyRequest?.cancel()
        state.value = state.value.copy(selected = rating, history = emptyList(), nextHistory = null, historyProblem = null)
        loadHistory(null)
    }
    fun moreHistory() { if (historyRequest?.isActive != true) state.value.nextHistory?.let(::loadHistory) }
    fun retryHistory() { if (historyRequest?.isActive != true) loadHistory(state.value.nextHistory) }
    fun closeHistory() {
        historyGeneration++
        historyRequest?.cancel()
        state.value = state.value.copy(selected = null, history = emptyList(), nextHistory = null, historyLoading = false, historyProblem = null)
    }
    fun clearMemory() {
        generation++; historyGeneration++
        request?.cancel(); historyRequest?.cancel()
        state.value = RatingsState()
    }
    override fun onCleared() { clearMemory() }
    private fun loadHistory(cursor: RatingHistoryCursor?) {
        val selected = state.value.selected ?: return
        val stamp = generation
        val historyStamp = historyGeneration
        state.value = state.value.copy(historyLoading = true, historyProblem = null)
        historyRequest = viewModelScope.launch {
            try {
                val page = repository.history(selected, cursor)
                if (stamp == generation && historyStamp == historyGeneration) state.value = state.value.copy(
                    history = (if (cursor == null) page.items else state.value.history + page.items).distinctBy { it.id },
                    nextHistory = page.next, historyLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) { if (historyStamp == historyGeneration) failAccess(failure, stamp)
            } catch (failure: DataFailure) { if (stamp == generation && historyStamp == historyGeneration)
                state.value = state.value.copy(historyLoading = false, historyProblem = failure.problem) }
        }
    }
    private fun failAccess(failure: AuthFailure, stamp: Int) { if (stamp == generation) { clearMemory(); onAccessFailure(failure.problem) } }
}
