package app.kingscore.android.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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

/** Shared by the two journals; preserves the existing play-journal format and aliases. */
internal class EncryptedAccountFile(context: Context, directoryName: String,
    private val keyPrefix: String, private val maxBytes: Int) {
    private val directory = File(context.noBackupFilesDir, directoryName)
    private fun file(account: UUID) = AtomicFile(File(directory, "$account.bin"))
    suspend fun read(account: UUID): ByteArray? = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val atomic = file(account)
            if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return@withLock null
            val bytes = atomic.openRead().use(::readBounded)
            check(bytes.size in 29..maxBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(account, create = false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(account.toString().toByteArray(Charsets.UTF_8))
            cipher.doFinal(bytes, 12, bytes.size - 12)
        }
    }
    suspend fun write(account: UUID, plain: ByteArray?) = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val atomic = file(account)
            if (plain == null) {
                atomic.delete()
                check(!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists())
            } else {
                check(directory.isDirectory || directory.mkdirs())
                require(plain.size <= maxBytes - 28)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key(account, create = true))
                cipher.updateAAD(account.toString().toByteArray(Charsets.UTF_8))
                val bytes = cipher.iv + cipher.doFinal(plain)
                val output = atomic.startWrite()
                try {
                    output.write(bytes); output.fd.sync(); atomic.finishWrite(output)
                    // Some AtomicFile rename failures are logged only; verify before dispatching HTTP.
                    check(atomic.openRead().use(::readBounded).contentEquals(bytes))
                } catch (failure: Exception) { atomic.failWrite(output); throw failure }
            }
        }
        Unit
    }
    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= maxBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun key(account: UUID, create: Boolean): SecretKey {
        val alias = "$keyPrefix$account"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Journal encryption key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private companion object { val storageMutex = Mutex() }
}
