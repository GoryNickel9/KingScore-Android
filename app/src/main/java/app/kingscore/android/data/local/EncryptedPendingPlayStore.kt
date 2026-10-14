package app.kingscore.android.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.kingscore.android.data.repository.*
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Per-account AES-GCM journal outside backup. Corruption blocks writes; it is never discarded. */
class EncryptedPendingPlayStore(context: Context) : PendingPlayStore {
    private val directory = File(context.noBackupFilesDir, "pending-plays-v1")
    private fun file(accountId: UUID) = AtomicFile(File(directory, "$accountId.bin"))
    override suspend fun load(accountId: UUID): StoredPlayMutation? = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val atomic = file(accountId)
            if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return@withLock null
            val bytes = atomic.openRead().use(::readBounded)
            check(bytes.size in 29..MAX_CIPHERTEXT_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(accountId, create = false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(accountId.toString().toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
            Json.decodeFromString<StoredPlayMutation>(plain).also { check(it.accountId == accountId.toString()) }
        }
    }
    override suspend fun save(accountId: UUID, record: StoredPlayMutation?) = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val atomic = file(accountId)
            if (record == null) {
                atomic.delete()
                check(!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists())
            } else {
                require(record.accountId == accountId.toString())
                check(directory.isDirectory || directory.mkdirs())
                val plain = Json.encodeToString(record).toByteArray(Charsets.UTF_8)
                require(plain.size <= MAX_CIPHERTEXT_BYTES - 28)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key(accountId, create = true))
                cipher.updateAAD(accountId.toString().toByteArray(Charsets.UTF_8))
                val bytes = cipher.iv + cipher.doFinal(plain)
                val output = atomic.startWrite()
                try {
                    output.write(bytes); output.fd.sync(); atomic.finishWrite(output)
                    // AtomicFile reports some rename failures only in logs: verify before any HTTP dispatch.
                    check(atomic.openRead().use(::readBounded).contentEquals(bytes))
                } catch (failure: Exception) { atomic.failWrite(output); throw failure }
            }
        }
        Unit
    }
    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_CIPHERTEXT_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_CIPHERTEXT_BYTES + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun key(accountId: UUID, create: Boolean): SecretKey {
        val alias = "kingscore.pending.v1.$accountId"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Pending encryption key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private companion object {
        const val MAX_CIPHERTEXT_BYTES = 4 * 1048576
        // Keystore key creation and AtomicFile operations share physical state across store instances.
        val storageMutex = Mutex()
    }
}
