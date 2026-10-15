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
enum class DataProblem { FORBIDDEN, INVALID_RESPONSE, PUBLICATION_STALE, PUBLICATION_MISSING }
class DataFailure(val problem: DataProblem) : Exception(problem.name)

interface PlaysRepository {
    val commentWrites: CommentWrites? get() = null
    suspend fun player(id: UUID): PlayerRow?
    suspend fun game(id: UUID): GameRow?
    suspend fun history(filters: HistoryFilters = HistoryFilters(), after: PlayCursor? = null): PlayPage
    suspend fun statisticsPage(after: PlayCursor? = null): StatisticsPage {
        val page = history(HistoryFilters(), after)
        return StatisticsPage(page.items.map { detail ->
            StatisticsPlay(detail.play.id, detail.play.played_at, detail.play.duration_minutes,
                detail.play.game_id, detail.game?.name, detail.game?.image_url,
                detail.scores.map { StatisticsScore(it.score.player_id, it.score.winner) })
        }, page.next)
    }
    suspend fun filterOptions(): HistoryOptions
    suspend fun detail(id: UUID): PlayDetail?
    suspend fun comments(id: UUID, after: CommentCursor? = null): CommentPage
    /** Null means this reader does not provide counts; missing/failed counts must never become zero. */
    suspend fun commentCounts(playIds: Set<UUID>): Map<UUID, Int>? = null
}
