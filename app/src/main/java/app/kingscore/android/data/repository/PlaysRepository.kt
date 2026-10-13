package app.kingscore.android.data.repository

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.PlayCommentRow
import kingscore.contracts.v1.PlayDetail
import kingscore.contracts.v1.PlaySource

const val PAGE_SIZE = 50
data class PlayCursor(val date: LocalDate, val id: UUID)
data class CommentCursor(val createdAt: Instant, val id: UUID)
data class PlayPage(val items: List<PlayDetail>, val next: PlayCursor?)
data class CommentPage(val items: List<PlayCommentRow>, val next: CommentCursor?)
enum class DataProblem { FORBIDDEN, INVALID_RESPONSE }
class DataFailure(val problem: DataProblem) : Exception(problem.name)

interface PlaysRepository {
    suspend fun history(source: PlaySource?, after: PlayCursor? = null): PlayPage
    suspend fun detail(id: UUID): PlayDetail?
    suspend fun comments(id: UUID, after: CommentCursor? = null): CommentPage
}
