package app.kingscore.android.feature.plays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.*
import java.util.UUID
import kingscore.contracts.v1.PlayCommentRow
import kingscore.contracts.v1.PlayDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DetailState(
    val detail: PlayDetail? = null, val loading: Boolean = true, val missing: Boolean = false,
    val problem: DataProblem? = null, val comments: List<PlayCommentRow> = emptyList(),
    val commentCursor: CommentCursor? = null, val commentsLoading: Boolean = false,
    val commentsProblem: DataProblem? = null,
)

class PlayDetailViewModel(private val repository: PlaysRepository, private val id: UUID,
    private val onAccessFailure: (AuthProblem) -> Unit) : ViewModel() {
    private val state = MutableStateFlow(DetailState())
    val detail: StateFlow<DetailState> = state
    private var request: Job? = null
    private var commentRequest: Job? = null
    private var generation = 0

    fun refresh() {
        clearMemory()
        val stamp = generation
        request = viewModelScope.launch {
            try {
                val play = repository.detail(id)
                if (stamp != generation) return@launch
                state.value = DetailState(detail = play, loading = false, missing = play == null)
                if (play != null) loadComments(null, stamp)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) { accessFailure(failure, stamp)
            } catch (failure: DataFailure) {
                if (stamp == generation) state.value = DetailState(loading = false, problem = failure.problem)
            }
        }
    }

    fun moreComments() {
        if (commentRequest?.isActive == true) return
        state.value.commentCursor?.let { loadComments(it, generation) }
    }

    fun retryComments() {
        if (commentRequest?.isActive == true || state.value.detail == null) return
        loadComments(state.value.commentCursor, generation)
    }

    fun clearMemory() {
        generation++
        request?.cancel()
        commentRequest?.cancel()
        state.value = DetailState()
    }

    override fun onCleared() { clearMemory() }

    private fun loadComments(cursor: CommentCursor?, stamp: Int) {
        state.value = state.value.copy(commentsLoading = true, commentsProblem = null)
        commentRequest = viewModelScope.launch {
            try {
                val page = repository.comments(id, cursor)
                if (stamp != generation) return@launch
                val comments = if (cursor == null) page.items else (state.value.comments + page.items).distinctBy { it.id }
                state.value = state.value.copy(comments = comments, commentCursor = page.next, commentsLoading = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) { accessFailure(failure, stamp)
            } catch (failure: DataFailure) {
                if (stamp == generation) state.value = state.value.copy(commentsLoading = false, commentsProblem = failure.problem)
            }
        }
    }

    private fun accessFailure(failure: AuthFailure, stamp: Int) {
        if (stamp == generation) { clearMemory(); onAccessFailure(failure.problem) }
    }
}
