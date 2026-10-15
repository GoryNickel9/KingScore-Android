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
    val problem: DataProblem? = null, val gameId: UUID? = null, val readAt: Instant? = null,
)
class RatingsViewModel(private val repository: RatingsRepository, private val onAccessFailure: (AuthProblem) -> Unit) : ViewModel() {
    private val state = MutableStateFlow(RatingsState())
    val ratings: StateFlow<RatingsState> = state
    private var request: Job? = null
    private var generation = 0
    private var initialized = false

    fun show(gameId: UUID?) { if (!initialized || state.value.gameId != gameId) load(gameId) }
    fun refresh(gameId: UUID? = state.value.gameId) {
        repository.invalidate()
        load(gameId, reloadCatalog = true)
    }
    private fun load(gameId: UUID?, reloadCatalog: Boolean = false) {
        initialized = true
        generation++
        request?.cancel()
        val stamp = generation
        state.value = if (state.value.gameId == gameId) state.value.copy(loading = true, problem = null)
            else RatingsState(games = state.value.games, gameId = gameId)
        request = viewModelScope.launch {
            try {
                val games = if (reloadCatalog || state.value.games.isEmpty()) repository.games() else state.value.games
                if (stamp != generation) return@launch
                state.value = state.value.copy(games = games)
                val ranking = repository.ranking(gameId)
                if (stamp == generation) state.value = RatingsState(games, ranking, loading = false, gameId = gameId, readAt = ranking.readAt ?: Instant.now())
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) { failAccess(failure, stamp)
            } catch (failure: DataFailure) { if (stamp == generation) state.value = state.value.copy(ranking = null, readAt = null, loading = false, problem = failure.problem) }
        }
    }
    fun clearMemory() {
        generation++
        request?.cancel()
        initialized = false
        state.value = RatingsState()
    }
    override fun onCleared() { clearMemory() }
    private fun failAccess(failure: AuthFailure, stamp: Int) { if (stamp == generation) { clearMemory(); onAccessFailure(failure.problem) } }
}
