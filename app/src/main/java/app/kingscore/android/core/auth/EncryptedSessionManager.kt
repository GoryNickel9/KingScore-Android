package app.kingscore.android.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Tokens are encrypted with a non-exportable per-app Android Keystore key. */
class EncryptedSessionManager(context: Context) : SessionManager {
    private val preferences = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saveSession(session: UserSession) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(json.encodeToString(session).toByteArray(Charsets.UTF_8))
            val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
            check(preferences.edit().putString("encrypted", value).commit()) { "Session storage write failed" }
        }
    }

    override suspend fun loadSession(): UserSession? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val value = preferences.getString("encrypted", null) ?: return@withLock null
            try {
                val parts = value.split(':', limit = 2)
                require(parts.size == 2)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                val decoded = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
                json.decodeFromString<UserSession>(decoded)
            } catch (_: GeneralSecurityException) {
                clearCorruptSession()
            } catch (_: SerializationException) {
                clearCorruptSession()
            } catch (_: IllegalArgumentException) {
                clearCorruptSession()
            }
        }
    }

    override suspend fun deleteSession() = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Destroy the encryption key as well, so stale ciphertext cannot be restored.
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(KEY_ALIAS) }
            check(preferences.edit().clear().commit()) { "Session removal failed" }
        }
    }

    private fun clearCorruptSession(): UserSession? {
        check(preferences.edit().clear().commit()) { "Invalid session removal failed" }
        return null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private companion object { const val KEY_ALIAS = "kingscore.auth.v1" }
}
