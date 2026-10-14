package app.kingscore.android.data.repository

import java.util.UUID
import kingscore.contracts.v1.CreateComment
import kingscore.contracts.v1.UpdateComment
import kingscore.contracts.v1.PlayCommentRow

enum class CommentProblem { INVALID_CONTENT, FORBIDDEN, NOT_FOUND, UNCERTAIN, STORAGE }
class CommentFailure(val problem: CommentProblem) : Exception(problem.name)

interface CommentWrites : AutoCloseable {
    override fun close() {}
    fun hasUncertainOutcome(playId: UUID): Boolean = false
    suspend fun restoreUncertainOutcome(playId: UUID): Boolean = hasUncertainOutcome(playId)
    suspend fun acknowledgeOutcome(playId: UUID) {}
    suspend fun create(command: CreateComment): PlayCommentRow
    suspend fun update(playId: UUID, commentId: UUID, command: UpdateComment): PlayCommentRow
    suspend fun delete(playId: UUID, commentId: UUID): PlayCommentRow
}
