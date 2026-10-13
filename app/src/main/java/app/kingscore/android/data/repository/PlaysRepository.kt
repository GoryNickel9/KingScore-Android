package app.kingscore.android.data.repository

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.PlayCommentRow
import kingscore.contracts.v1.PlayDetail
import kingscore.contracts.v1.PlaySource
import kingscore.contracts.v1.GameRow
import kingscore.contracts.v1.PlayerRow

const val PAGE_SIZE = 50
data class PlayCursor(val date: LocalDate, val id: UUID)
data class CommentCursor(val createdAt: Instant, val id: UUID)
data class PlayPage(val items: List<PlayDetail>, val next: PlayCursor?)
data class CommentPage(val items: List<PlayCommentRow>, val next: CommentCursor?)
const val MAX_HISTORY_QUERY = 120
data class HistoryFilters(
    val query: String = "", val source: PlaySource? = null, val gameId: UUID? = null,
    val playerId: UUID? = null, val from: LocalDate? = null, val to: LocalDate? = null,
) {
    init {
        require(query.length <= MAX_HISTORY_QUERY && query.none { it.isISOControl() })
        require(from == null || to == null || from <= to)
    }
    val active: Boolean get() = query.isNotBlank() || source != null || gameId != null || playerId != null || from != null || to != null
}
data class HistoryOptions(val games: List<GameRow> = emptyList(), val players: List<PlayerRow> = emptyList())
enum class DataProblem { FORBIDDEN, INVALID_RESPONSE }
class DataFailure(val problem: DataProblem) : Exception(problem.name)

interface PlaysRepository {
    suspend fun history(filters: HistoryFilters = HistoryFilters(), after: PlayCursor? = null): PlayPage
    suspend fun filterOptions(): HistoryOptions
    suspend fun detail(id: UUID): PlayDetail?
    suspend fun comments(id: UUID, after: CommentCursor? = null): CommentPage
}
