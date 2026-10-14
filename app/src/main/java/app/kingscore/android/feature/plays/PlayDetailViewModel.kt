package app.kingscore.android.feature.plays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.*
import java.util.UUID
import kingscore.contracts.v1.PlayCommentRow
import kingscore.contracts.v1.PlayDetail
import kingscore.contracts.v1.CreateComment
import kingscore.contracts.v1.UpdateComment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CommentEditor(val id: UUID? = null, val content: String = "")
enum class CommentNotice { CREATED, UPDATED, DELETED, RECONCILED }
data class DetailState(
    val detail: PlayDetail? = null, val loading: Boolean = true, val missing: Boolean = false,
    val problem: DataProblem? = null, val comments: List<PlayCommentRow> = emptyList(),
    val commentCursor: CommentCursor? = null, val commentsLoading: Boolean = false,
    val commentsProblem: DataProblem? = null,
    val commentEditor: CommentEditor? = null, val deletingComment: UUID? = null,
    val commentSaving: Boolean = false, val commentWriteProblem: CommentProblem? = null,
    val commentReviewReady: Boolean = false,
    val commentNotice: CommentNotice? = null,
) {
    val commentWritesBlocked: Boolean get() = commentWriteProblem == CommentProblem.UNCERTAIN || commentWriteProblem == CommentProblem.STORAGE
}

class PlayDetailViewModel(private val repository: PlaysRepository, private val id: UUID,
    private val onAccessFailure: (AuthProblem) -> Unit, private val accountId: UUID? = null) : ViewModel() {
    private val state = MutableStateFlow(DetailState())
    val detail: StateFlow<DetailState> = state
    private var request: Job? = null
    private var commentRequest: Job? = null
    private var generation = 0
    private var commentGeneration = 0
    private var mutation: Job? = null

    fun refresh() {
        if (state.value.commentSaving) return
        val uncertain = state.value.commentWriteProblem == CommentProblem.UNCERTAIN || repository.commentWrites?.hasUncertainOutcome(id) == true
        val editor = state.value.commentEditor.takeIf { uncertain }
        val deleting = state.value.deletingComment.takeIf { uncertain }
        clearMemory()
        if (uncertain) state.value = state.value.copy(commentWriteProblem = CommentProblem.UNCERTAIN)
        val stamp = generation
        request = viewModelScope.launch {
            try {
                val play = repository.detail(id)
                if (stamp != generation) return@launch
                var writeProblem = CommentProblem.UNCERTAIN.takeIf { uncertain }
                try {
                    if (repository.commentWrites?.restoreUncertainOutcome(id) == true) writeProblem = CommentProblem.UNCERTAIN
                } catch (failure: CommentFailure) { writeProblem = failure.problem }
                if (stamp != generation) return@launch
                state.value = DetailState(detail = play, loading = false, missing = play == null, commentEditor = editor,
                    deletingComment = deleting, commentWriteProblem = writeProblem)
                if (play != null) loadComments(null, stamp, reconcileAll = writeProblem == CommentProblem.UNCERTAIN)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) { accessFailure(failure, stamp)
            } catch (failure: DataFailure) {
                if (stamp == generation) state.value = state.value.copy(loading = false, problem = failure.problem,
                    commentEditor = editor, deletingComment = deleting)
            }
        }
    }

    fun moreComments() {
        if (commentRequest?.isActive == true || state.value.commentSaving) return
        state.value.commentCursor?.let { loadComments(it, generation) }
    }

    fun retryComments() {
        if (commentRequest?.isActive == true || state.value.detail == null || state.value.commentSaving) return
        if (state.value.commentWriteProblem == CommentProblem.UNCERTAIN) { recheckComments(); return }
        loadComments(state.value.commentCursor, generation)
    }

    fun recheckComments() {
        if (state.value.detail == null || state.value.commentSaving) return
        if (state.value.commentWriteProblem == CommentProblem.STORAGE) { refresh(); return }
        commentRequest?.cancel()
        commentGeneration++
        state.value = state.value.copy(comments = emptyList(), commentCursor = null, commentsProblem = null, commentReviewReady = false)
        loadComments(null, generation, reconcileAll = state.value.commentWriteProblem == CommentProblem.UNCERTAIN)
    }

    fun composeComment(commentId: UUID? = null) {
        if (!canWrite()) return
        val row = commentId?.let { ownComment(it) ?: return }
        state.value = state.value.copy(commentEditor = CommentEditor(commentId, row?.content.orEmpty()),
            deletingComment = null, commentWriteProblem = null, commentNotice = null)
    }

    fun changeComment(content: String) {
        if (state.value.commentSaving || state.value.commentWritesBlocked) return
        state.value.commentEditor?.let { state.value = state.value.copy(commentEditor = it.copy(content = content),
            commentWriteProblem = state.value.commentWriteProblem?.takeIf { problem -> problem == CommentProblem.UNCERTAIN || problem == CommentProblem.STORAGE }) }
    }

    fun dismissComment() {
        if (state.value.commentSaving) return
        state.value = state.value.copy(commentEditor = null, deletingComment = null,
            commentWriteProblem = state.value.commentWriteProblem?.takeIf { it == CommentProblem.UNCERTAIN || it == CommentProblem.STORAGE })
    }

    fun requestCommentDeletion(commentId: UUID) {
        if (!canWrite() || ownComment(commentId) == null) return
        state.value = state.value.copy(deletingComment = commentId, commentEditor = null, commentWriteProblem = null, commentNotice = null)
    }

    fun saveComment() {
        val editor = state.value.commentEditor ?: return
        if (!canWrite()) return
        val content = editor.content.trim()
        try { UpdateComment(content) } catch (_: IllegalArgumentException) {
            state.value = state.value.copy(commentWriteProblem = CommentProblem.INVALID_CONTENT); return
        }
        if (editor.id != null && ownComment(editor.id) == null) return
        writeComment(if (editor.id == null) CommentNotice.CREATED else CommentNotice.UPDATED) {
            if (editor.id == null) it.create(CreateComment(id, content)) else it.update(id, editor.id, UpdateComment(content))
        }
    }

    fun deleteComment() {
        val commentId = state.value.deletingComment ?: return
        if (!canWrite() || ownComment(commentId) == null) return
        writeComment(CommentNotice.DELETED) { it.delete(id, commentId) }
    }

    private fun ownComment(commentId: UUID) = state.value.comments.firstOrNull { it.id == commentId && it.user_id == accountId }
    private fun canWrite() = repository.commentWrites != null && accountId != null && state.value.detail != null &&
        !state.value.commentSaving && !state.value.commentsLoading && !state.value.commentWritesBlocked

    fun acknowledgeComments() {
        val writer = repository.commentWrites ?: return
        if (!state.value.commentReviewReady || state.value.commentsLoading || state.value.commentSaving ||
            state.value.commentWriteProblem != CommentProblem.UNCERTAIN) return
        val stamp = generation
        state.value = state.value.copy(commentSaving = true)
        mutation = viewModelScope.launch {
            try {
                writer.acknowledgeOutcome(id)
                if (stamp == generation) state.value = state.value.copy(commentSaving = false, commentWriteProblem = null,
                    commentReviewReady = false, commentEditor = null, deletingComment = null, commentNotice = CommentNotice.RECONCILED)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: AuthFailure) { accessFailure(failure, stamp) }
            catch (failure: CommentFailure) {
                if (stamp == generation) state.value = state.value.copy(commentSaving = false, commentWriteProblem = failure.problem)
            }
        }
    }

    private fun writeComment(notice: CommentNotice, action: suspend (CommentWrites) -> PlayCommentRow) {
        val writer = repository.commentWrites ?: return
        val stamp = generation
        state.value = state.value.copy(commentSaving = true, commentWriteProblem = null, commentNotice = null)
        mutation = viewModelScope.launch {
            try {
                action(writer)
                if (stamp != generation) return@launch
                state.value = state.value.copy(commentSaving = false, commentEditor = null, deletingComment = null, commentNotice = notice)
                recheckComments()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) { accessFailure(failure, stamp)
            } catch (failure: CommentFailure) {
                if (stamp == generation) state.value = state.value.copy(commentSaving = false, commentWriteProblem = failure.problem)
            }
        }
    }

    fun clearMemory() {
        generation++
        request?.cancel()
        commentRequest?.cancel()
        mutation?.cancel()
        commentGeneration++
        state.value = DetailState()
    }

    override fun onCleared() { clearMemory() }

    private fun loadComments(cursor: CommentCursor?, stamp: Int, reconcileAll: Boolean = false) {
        val reconcile = reconcileAll || (cursor == null && state.value.commentWriteProblem == CommentProblem.UNCERTAIN)
        val commentStamp = ++commentGeneration
        state.value = state.value.copy(commentsLoading = true, commentsProblem = null, commentReviewReady = false)
        commentRequest = viewModelScope.launch {
            try {
                var page = repository.comments(id, cursor)
                if (stamp != generation || commentStamp != commentGeneration) return@launch
                val collected = page.items.toMutableList()
                while (reconcile && page.next != null) {
                    page = repository.comments(id, page.next)
                    if (stamp != generation || commentStamp != commentGeneration) return@launch
                    collected += page.items
                }
                val comments = if (cursor == null) collected else (state.value.comments + collected).distinctBy { it.id }
                state.value = state.value.copy(comments = comments, commentCursor = page.next, commentsLoading = false,
                    commentReviewReady = reconcile && page.next == null,
                    commentNotice = state.value.commentNotice)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: AuthFailure) { if (commentStamp == commentGeneration) accessFailure(failure, stamp)
            } catch (failure: DataFailure) {
                if (stamp == generation && commentStamp == commentGeneration) state.value = state.value.copy(commentsLoading = false, commentsProblem = failure.problem)
            }
        }
    }

    private fun accessFailure(failure: AuthFailure, stamp: Int) {
        if (stamp == generation) { clearMemory(); onAccessFailure(failure.problem) }
    }
}
