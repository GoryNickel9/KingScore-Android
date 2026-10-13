package app.kingscore.android.feature.plays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.*
import java.time.Instant
import kingscore.contracts.v1.PlayDetail
import kingscore.contracts.v1.PlaySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HistoryState(
    val items: List<PlayDetail> = emptyList(), val next: PlayCursor? = null,
    val loading: Boolean = true, val loadingMore: Boolean = false,
    val problem: DataProblem? = null, val updatedAt: Instant? = null,
)

class HistoryViewModel(private val repository: PlaysRepository, private val onAccessFailure: (AuthProblem) -> Unit) : ViewModel() {
    private val state = MutableStateFlow(HistoryState())
    val history: StateFlow<HistoryState> = state
    private var request: Job? = null
    private var generation = 0
    private var source: PlaySource? = null
    private var initialized = false

    fun show(source: PlaySource?) {
        if (!initialized || this.source != source) refresh(source)
    }

    fun refresh(source: PlaySource? = this.source) {
        initialized = true
        this.source = source
        generation++
        request?.cancel()
        state.value = HistoryState()
        load(null, generation)
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
        request?.cancel()
        state.value = HistoryState()
    }

    override fun onCleared() { clearMemory() }

    private fun load(cursor: PlayCursor?, stamp: Int) {
        val requestedSource = source
        request = viewModelScope.launch {
            try {
                val page = repository.history(requestedSource, cursor)
                if (stamp != generation) return@launch
                val items = if (cursor == null) page.items else (state.value.items + page.items).distinctBy { it.play.id }
                state.value = HistoryState(items, page.next, loading = false, updatedAt = Instant.now())
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) {
                if (stamp == generation) { clearMemory(); onAccessFailure(failure.problem) }
            } catch (failure: DataFailure) {
                if (stamp == generation) state.value = state.value.copy(loading = false, loadingMore = false, problem = failure.problem)
            }
        }
    }
}
