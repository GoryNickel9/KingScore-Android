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
    val commentCounts: Map<java.util.UUID, Int> = emptyMap(), val commentCountsLoading: Boolean = false,
    val commentCountsProblem: DataProblem? = null,
)

class HistoryViewModel(private val repository: PlaysRepository, private val onAccessFailure: (AuthProblem) -> Unit,
    private val includeCommentCounts: Boolean = true) : ViewModel() {
    private val state = MutableStateFlow(HistoryState())
    val history: StateFlow<HistoryState> = state
    private var request: Job? = null
    private var generation = 0
    private var optionsRequest: Job? = null
    private var optionsGeneration = 0
    private var initialized = false
    private var countsRequest: Job? = null
    private var countsGeneration = 0
    private var commentRevision: Int? = null
    private var refreshFailed = false

    fun show(filters: HistoryFilters = state.value.filters) {
        if (!initialized || state.value.filters != filters) refresh(filters)
    }

    fun refresh(filters: HistoryFilters = state.value.filters) {
        initialized = true
        refreshFailed = false
        generation++
        request?.cancel()
        val normalized = filters.copy(query = filters.query.trim())
        val previous = state.value
        countsRequest?.cancel(); countsGeneration++
        state.value = if (previous.filters == normalized && previous.updatedAt != null) previous.copy(
            loading = true, loadingMore = false, problem = null, commentCountsLoading = false,
        ) else HistoryState(filters = normalized, options = previous.options,
            optionsLoading = previous.optionsLoading, optionsProblem = previous.optionsProblem)
        load(null, generation)
    }

    fun commentsChanged(revision: Int) {
        val changed = commentRevision != null && commentRevision != revision
        commentRevision = revision
        if (changed) refreshCommentCounts()
    }

    fun refreshCommentCounts() {
        if (!includeCommentCounts) return
        val ids = state.value.items.map { it.play.id }.toSet()
        countsRequest?.cancel()
        val stamp = ++countsGeneration
        if (ids.isEmpty()) {
            state.value = state.value.copy(commentCounts = emptyMap(), commentCountsLoading = false, commentCountsProblem = null)
            return
        }
        state.value = state.value.copy(commentCountsLoading = true, commentCountsProblem = null)
        countsRequest = viewModelScope.launch {
            try {
                val counts = repository.commentCounts(ids)
                if (stamp != countsGeneration) return@launch
                if (counts != null && (counts.keys != ids || counts.values.any { it < 0 })) throw DataFailure(DataProblem.INVALID_RESPONSE)
                state.value = state.value.copy(commentCounts = counts.orEmpty(), commentCountsLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) {
                if (stamp == countsGeneration) { clearMemory(); onAccessFailure(failure.problem) }
            } catch (failure: DataFailure) {
                if (stamp == countsGeneration) state.value = state.value.copy(commentCounts = emptyMap(),
                    commentCountsLoading = false, commentCountsProblem = failure.problem)
            }
        }
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
        if (refreshFailed || state.value.updatedAt == null) refresh() else loadMore()
    }

    fun clearMemory() {
        generation++
        optionsGeneration++
        request?.cancel()
        optionsRequest?.cancel()
        countsRequest?.cancel(); countsGeneration++
        commentRevision = null
        initialized = false
        refreshFailed = false
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
                refreshCommentCounts()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) {
                if (stamp == generation) { clearMemory(); onAccessFailure(failure.problem) }
            } catch (failure: DataFailure) {
                if (stamp == generation) {
                    refreshFailed = cursor == null
                    if (failure.problem == DataProblem.FORBIDDEN) {
                        countsRequest?.cancel(); countsGeneration++
                        optionsRequest?.cancel(); optionsGeneration++
                    }
                    state.value = if (failure.problem == DataProblem.FORBIDDEN) HistoryState(filters = requestedFilters,
                        loading = false, problem = failure.problem) else state.value.copy(loading = false,
                        loadingMore = false, problem = failure.problem)
                }
            }
        }
    }
}
