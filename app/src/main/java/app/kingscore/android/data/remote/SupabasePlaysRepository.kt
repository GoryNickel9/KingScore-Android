package app.kingscore.android.data.remote

import app.kingscore.android.data.repository.*
import java.util.UUID
import kingscore.contracts.v1.PlaySource
import kingscore.contracts.v1.PlayDetail
import kingscore.contracts.v1.PlayCommentRow
import kotlinx.serialization.json.*

class SupabasePlaysRepository(private val reader: AuthenticatedReader, private val accountId: UUID) : PlaysRepository {
    override suspend fun history(source: PlaySource?, after: PlayCursor?): PlayPage {
        val parameters = mutableListOf(
            "select" to "*,games(*),play_scores(*,players(*))",
            "order" to "played_at.desc,id.desc", "limit" to PAGE_SIZE.toString(),
        )
        source?.let { parameters += "source" to "eq.${it.wireValue}" }
        after?.let { parameters += "or" to "(played_at.lt.${it.date},and(played_at.eq.${it.date},id.lt.${it.id}))" }
        val rows = decode(reader.get(accountId, "plays", parameters)) { RowDecoders.playDetail(it) }
        validate {
            require(rows.size <= PAGE_SIZE)
            val cursors = rows.map { PlayCursor(it.play.played_at, it.play.id) }
            require(rows.all { source == null || it.play.source == source.wireValue })
            require(cursors.zipWithNext().all { (a, b) -> compare(a, b) > 0 })
            require(after == null || cursors.all { compare(it, after) < 0 })
        }
        return PlayPage(rows, rows.lastOrNull()?.takeIf { rows.size == PAGE_SIZE }?.let { PlayCursor(it.play.played_at, it.play.id) })
    }

    override suspend fun detail(id: UUID): PlayDetail? {
        val rows = decode(reader.get(accountId, "plays", listOf(
            "select" to "*,games(*),play_scores(*,players(*))", "id" to "eq.$id", "limit" to "1",
        ))) { RowDecoders.playDetail(it) }
        validate { require(rows.size <= 1 && rows.all { it.play.id == id }) }
        return rows.singleOrNull()
    }

    override suspend fun comments(id: UUID, after: CommentCursor?): CommentPage {
        val parameters = mutableListOf("select" to "*", "play_id" to "eq.$id",
            "order" to "created_at.asc,id.asc", "limit" to PAGE_SIZE.toString())
        after?.let { parameters += "or" to "(created_at.gt.${it.createdAt},and(created_at.eq.${it.createdAt},id.gt.${it.id}))" }
        val rows = decode(reader.get(accountId, "play_comments", parameters)) { RowDecoders.playComment(it) }
        validate {
            require(rows.size <= PAGE_SIZE && rows.all { it.play_id == id })
            val cursors = rows.map { CommentCursor(it.created_at, it.id) }
            require(cursors.zipWithNext().all { (a, b) -> compare(a, b) < 0 })
            require(after == null || cursors.all { compare(it, after) > 0 })
        }
        return CommentPage(rows, rows.lastOrNull()?.takeIf { rows.size == PAGE_SIZE }?.let { CommentCursor(it.created_at, it.id) })
    }

    private fun compare(a: PlayCursor, b: PlayCursor): Int = a.date.compareTo(b.date).takeIf { it != 0 } ?: a.id.toString().compareTo(b.id.toString())
    private fun compare(a: CommentCursor, b: CommentCursor): Int = a.createdAt.compareTo(b.createdAt).takeIf { it != 0 } ?: a.id.toString().compareTo(b.id.toString())

    private fun <T> decode(body: String, decoder: (JsonObject) -> T): List<T> = validate {
        Json.parseToJsonElement(body).jsonArray.map { decoder(it.jsonObject) }
    }
    private inline fun <T> validate(block: () -> T): T = try { block() } catch (_: Exception) {
        // Parsing errors never expose remote bodies or SDK headers to UI/logs.
        throw DataFailure(DataProblem.INVALID_RESPONSE)
    }
}
