package app.kingscore.android.data.local

import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.data.repository.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EncryptedPendingPlayTest {
    private val account = UUID(7, 41)
    private val other = UUID(7, 42)
    private fun record() = StoredPlayMutation(accountId = account.toString(), requestId = UUID(7, 43).toString(), playId = null,
        kind = PlayMutationKind.CREATE, body = "{\"test\":\"private-notes\"}")
    @Test fun encryptedRecordSurvivesStoreRecreationAndIsRemovedAfterAcknowledgement() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedPendingPlayStore(context)
        store.save(account, null)
        try {
            val completed = record().copy(confirmedResponse = "{\"confirmed\":true}")
            store.save(account, completed)
            val bytes = File(context.noBackupFilesDir, "pending-plays-v1/$account.bin").readBytes()
            assertFalse(bytes.toString(Charsets.UTF_8).contains("private-notes"))
            assertEquals(completed, EncryptedPendingPlayStore(context).load(account))
            store.save(account, null); assertNull(store.load(account))
        } finally { store.save(account, null) }
    }
    @Test fun movedCiphertextCannotBeReadByAnotherAccount() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedPendingPlayStore(context)
        try {
            store.save(account, record())
            val directory = File(context.noBackupFilesDir, "pending-plays-v1")
            File(directory, "$account.bin").copyTo(File(directory, "$other.bin"), overwrite = true)
            try { store.load(other); fail("Cross-account decryption accepted") } catch (_: Exception) { }
            assertNotNull(store.load(account))
        } finally { store.save(account, null); store.save(other, null) }
    }
    @Test fun corruptionIsReportedAndNeverErased() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedPendingPlayStore(context)
        try {
            store.save(account, record())
            val file = File(context.noBackupFilesDir, "pending-plays-v1/$account.bin")
            val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            try { store.load(account); fail("Corruption silently accepted") } catch (_: Exception) { }
            assertArrayEquals(bytes, file.readBytes())
        } finally { store.save(account, null) }
    }
}
