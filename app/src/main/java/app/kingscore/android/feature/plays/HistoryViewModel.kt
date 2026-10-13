package app.kingscore.android.feature.plays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.*
import java.time.Instant
import kingscore.contracts.v1.PlayDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HistoryState(
    val items: List<PlayDetail> = emptyList(), val next: PlayCursor? = null,
    val loading: Boolean = true, val loadingMore: Boolean = false,
    val problem: DataProblem? = null, val updatedAt: Instant? = null,
    val filters: HistoryFilters = HistoryFilters(), val options: HistoryOptions? = null,
    val optionsLoading: Boolean = false, val optionsProblem: DataProblem? = null,
)

class HistoryViewModel(private val repository: PlaysRepository, private val onAccessFailure: (AuthProblem) -> Unit) : ViewModel() {
    private val state = MutableStateFlow(HistoryState())
    val history: StateFlow<HistoryState> = state
    private var request: Job? = null
    private var generation = 0
    private var optionsRequest: Job? = null
    private var optionsGeneration = 0
    private var initialized = false

    fun show(filters: HistoryFilters = state.value.filters) {
        if (!initialized || state.value.filters != filters) refresh(filters)
    }

    fun refresh(filters: HistoryFilters = state.value.filters) {
        initialized = true
        generation++
        request?.cancel()
        state.value = HistoryState(filters = filters.copy(query = filters.query.trim()), options = state.value.options,
            optionsLoading = state.value.optionsLoading, optionsProblem = state.value.optionsProblem)
        load(null, generation)
    }

    fun loadOptions() {
        if (state.value.options != null || optionsRequest?.isActive == true) return
        val stamp = optionsGeneration
        state.value = state.value.copy(optionsLoading = true, optionsProblem = null)
        optionsRequest = viewModelScope.launch {
            try {
                val options = repository.filterOptions()
                if (stamp == optionsGeneration) state.value = state.value.copy(options = options, optionsLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) {
                if (stamp == optionsGeneration) { clearMemory(); onAccessFailure(failure.problem) }
            } catch (failure: DataFailure) {
                if (stamp == optionsGeneration) state.value = state.value.copy(optionsLoading = false, optionsProblem = failure.problem)
            }
        }
    }

    fun loadMore() {
        if (request?.isActive == true) return
        val cursor = state.value.next ?: return
        state.value = state.value.copy(loadingMore = true, problem = null)
        load(cursor, generation)
    }

    fun retry() {
        if (state.value.updatedAt == null) refresh() else loadMore()
    }

    fun clearMemory() {
        generation++
        optionsGeneration++
        request?.cancel()
        optionsRequest?.cancel()
        initialized = false
        state.value = HistoryState()
    }

    override fun onCleared() { clearMemory() }

    private fun load(cursor: PlayCursor?, stamp: Int) {
        val requestedFilters = state.value.filters
        request = viewModelScope.launch {
            try {
                val page = repository.history(requestedFilters, cursor)
                if (stamp != generation) return@launch
                val items = if (cursor == null) page.items else (state.value.items + page.items).distinctBy { it.play.id }
                state.value = state.value.copy(items = items, next = page.next, loading = false, loadingMore = false,
                    problem = null, updatedAt = Instant.now())
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) {
                if (stamp == generation) { clearMemory(); onAccessFailure(failure.problem) }
            } catch (failure: DataFailure) {
                if (stamp == generation) state.value = state.value.copy(loading = false, loadingMore = false, problem = failure.problem)
            }
        }
    }
}
