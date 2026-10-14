package app.kingscore.android.data.local

import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.data.repository.CommentOutcomes
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EncryptedCommentOutcomeTest {
    private val account = UUID(7, 51)
    private val other = UUID(7, 52)
    private val play = UUID(7, 53)
    @Test fun encryptedMarkerSurvivesRegistryRecreationWithoutPlaintextIDs() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedCommentOutcomeStore(context)
        try {
            val first = CommentOutcomes(account, store); first.begin(play); first.forget()
            val bytes = File(context.noBackupFilesDir, "pending-comments-v1/$account.bin").readBytes()
            assertFalse(bytes.toString(Charsets.UTF_8).contains(play.toString()))
            val next = CommentOutcomes(account, EncryptedCommentOutcomeStore(context)); next.restore(); assertTrue(next.has(play))
            next.acknowledge(play); assertTrue(store.load(account).isEmpty())
        } finally { store.save(account, emptySet()) }
    }
    @Test fun corruptionOrOtherAccountCiphertextIsNotAcceptedOrDeleted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedCommentOutcomeStore(context)
        try {
            store.save(account, setOf(play))
            val dir = File(context.noBackupFilesDir, "pending-comments-v1")
            File(dir, "$account.bin").copyTo(File(dir, "$other.bin"), overwrite = true)
            try { store.load(other); fail() } catch (_: Exception) { }
            val file = File(dir, "$account.bin"); val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            try { store.load(account); fail() } catch (_: Exception) { }
            assertArrayEquals(bytes, file.readBytes())
        } finally { store.save(account, emptySet()); store.save(other, emptySet()) }
    }
    @Test fun previousPlayFormatAndKeyAliasRemainReadable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedPendingPlayStore(context)
        val record = app.kingscore.android.data.repository.StoredPlayMutation(accountId = account.toString(),
            requestId = play.toString(), playId = null, kind = app.kingscore.android.data.repository.PlayMutationKind.CREATE, body = "{}")
        try {
            store.save(account, record)
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("kingscore.pending.v1.$account", null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(account.toString().toByteArray(Charsets.UTF_8))
            val plain = """{"version":1,"accountId":"$account","requestId":"$play","playId":null,"kind":"CREATE","body":"{}","confirmedResponse":null}"""
            File(context.noBackupFilesDir, "pending-plays-v1/$account.bin").writeBytes(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
            assertEquals(record, store.load(account))
        } finally { store.save(account, null) }
    }
}
