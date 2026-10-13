package app.kingscore.android.feature.players

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.DataFailure
import app.kingscore.android.data.repository.DataProblem
import app.kingscore.android.data.repository.PlaysRepository
import java.util.UUID
import kingscore.contracts.v1.PlayerRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PlayerState(val player: PlayerRow? = null, val loading: Boolean = true,
    val missing: Boolean = false, val problem: DataProblem? = null, val revision: Int = 0)

class PlayerDetailViewModel(private val repository: PlaysRepository, private val id: UUID,
    private val onAccessFailure: (AuthProblem) -> Unit) : ViewModel() {
    private val state = MutableStateFlow(PlayerState())
    val detail: StateFlow<PlayerState> = state
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
                val player = repository.player(id)
                if (stamp == generation) state.value = PlayerState(player, loading = false, missing = player == null, revision = stamp)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) {
                if (stamp == generation) { clearMemory(); onAccessFailure(failure.problem) }
            } catch (failure: DataFailure) {
                if (stamp == generation) state.value = PlayerState(loading = false, problem = failure.problem)
            }
        }
    }

    fun clearMemory() {
        generation++
        request?.cancel()
        initialized = false
        state.value = PlayerState()
    }

    override fun onCleared() { clearMemory() }
}
