package app.kingscore.android.data.local

import android.content.Context
import app.kingscore.android.data.repository.CommentOutcomeStore
import app.kingscore.android.data.repository.CommentOutcomes
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredCommentOutcomes(val version: Int = 1, val accountId: String, val plays: List<String>) {
    fun validate(account: UUID): Set<UUID> {
        require(version == 1 && accountId == account.toString())
        require(plays.size <= CommentOutcomes.MAX_PENDING && plays.distinct().size == plays.size)
        return plays.map { value -> UUID.fromString(value).also { require(it.toString() == value) } }.toSet()
    }
}

/** Only encrypted play IDs, excluded from backup; never comment text or a retry queue. */
class EncryptedCommentOutcomeStore(context: Context) : CommentOutcomeStore {
    private val file = EncryptedAccountFile(context, "pending-comments-v1", "kingscore.comments.v1.", 65536)
    override suspend fun load(accountId: UUID): Set<UUID> {
        val bytes = file.read(accountId) ?: return emptySet()
        return Json.decodeFromString<StoredCommentOutcomes>(bytes.toString(Charsets.UTF_8)).validate(accountId)
    }
    override suspend fun save(accountId: UUID, plays: Set<UUID>) {
        require(plays.size <= CommentOutcomes.MAX_PENDING)
        val record = StoredCommentOutcomes(accountId = accountId.toString(), plays = plays.map(UUID::toString).sorted())
        file.write(accountId, if (plays.isEmpty()) null else Json.encodeToString(record).toByteArray(Charsets.UTF_8))
    }
}
