package app.kingscore.android.feature.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.DataFailure
import app.kingscore.android.data.repository.DataProblem
import app.kingscore.android.data.repository.PlaysRepository
import java.util.UUID
import kingscore.contracts.v1.GameRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class GameState(val game: GameRow? = null, val loading: Boolean = true,
    val missing: Boolean = false, val problem: DataProblem? = null, val revision: Int = 0)

class GameDetailViewModel(private val repository: PlaysRepository, private val id: UUID,
    private val onAccessFailure: (AuthProblem) -> Unit) : ViewModel() {
    private val state = MutableStateFlow(GameState())
    val detail: StateFlow<GameState> = state
    private var request: Job? = null
    private var generation = 0
    private var initialized = false

    fun show() { if (!initialized) refresh() }

    fun refresh() {
        clearMemory()
        initialized = true
        val stamp = generation
        request = viewModelScope.launch {
            try {
                val game = repository.game(id)
                if (stamp == generation) state.value = GameState(game, loading = false, missing = game == null, revision = stamp)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) {
                if (stamp == generation) { clearMemory(); onAccessFailure(failure.problem) }
            } catch (failure: DataFailure) {
                if (stamp == generation) state.value = GameState(loading = false, problem = failure.problem)
            }
        }
    }

    fun clearMemory() {
        generation++
        request?.cancel()
        initialized = false
        state.value = GameState()
    }

    override fun onCleared() { clearMemory() }
}
