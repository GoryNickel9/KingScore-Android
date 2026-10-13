package app.kingscore.android.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.DataFailure
import app.kingscore.android.data.repository.DataProblem
import app.kingscore.android.data.repository.StatisticsRepository
import app.kingscore.android.data.repository.StatisticsSnapshot
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class StatisticsState(
    val snapshot: StatisticsSnapshot? = null,
    val loading: Boolean = true,
    val processed: Int = 0,
    val problem: DataProblem? = null,
    val updatedAt: Instant? = null,
)

class StatisticsViewModel(
    private val repository: StatisticsRepository,
    private val onAccessFailure: (AuthProblem) -> Unit,
) : ViewModel() {
    private val state = MutableStateFlow(StatisticsState())
    val statistics: StateFlow<StatisticsState> = state
    private var request: Job? = null
    private var generation = 0
    private var initialized = false

    fun show() {
        if (!initialized) refresh()
    }

    fun refresh() {
        clearMemory()
        initialized = true
        val stamp = generation
        request = viewModelScope.launch {
            try {
                val snapshot = repository.load { processed ->
                    if (stamp == generation) state.value = state.value.copy(processed = processed)
                }
                if (stamp != generation) return@launch
                state.value = StatisticsState(snapshot, loading = false,
                    processed = snapshot.totalPlays, updatedAt = Instant.now())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AuthFailure) {
                if (stamp == generation) {
                    clearMemory()
                    onAccessFailure(failure.problem)
                }
            } catch (failure: DataFailure) {
                if (stamp == generation) state.value = state.value.copy(loading = false, problem = failure.problem)
            }
        }
    }

    fun clearMemory() {
        generation++
        initialized = false
        request?.cancel()
        state.value = StatisticsState()
    }

    override fun onCleared() { clearMemory() }
}
