package app.kingscore.android.data.local

import android.content.Context
import app.kingscore.android.data.repository.PendingPlayStore
import app.kingscore.android.data.repository.StoredPlayMutation
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Existing per-account format and aliases are retained by the shared encrypted file. */
class EncryptedPendingPlayStore(context: Context) : PendingPlayStore {
    private val file = EncryptedAccountFile(context, "pending-plays-v1", "kingscore.pending.v1.", 4 * 1048576)
    override suspend fun load(accountId: UUID): StoredPlayMutation? {
        val bytes = file.read(accountId) ?: return null
        return Json.decodeFromString<StoredPlayMutation>(bytes.toString(Charsets.UTF_8)).also { check(it.accountId == accountId.toString()) }
    }
    override suspend fun save(accountId: UUID, record: StoredPlayMutation?) {
        require(record == null || record.accountId == accountId.toString())
        file.write(accountId, record?.let { Json.encodeToString(it).toByteArray(Charsets.UTF_8) })
    }
}
