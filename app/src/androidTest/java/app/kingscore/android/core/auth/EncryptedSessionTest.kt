package app.kingscore.android.core.auth

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EncryptedSessionTest {
    @Test fun sessionSurvivesManagerRecreationWithoutPlaintextAndLogoutRemovesIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = EncryptedSessionManager(context)
        manager.deleteSession()
        val session = UserSession("test-access-secret", "test-refresh-secret", expiresIn = 3600, tokenType = "bearer")
        try {
            manager.saveSession(session)
            val stored = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE).getString("encrypted", "")!!
            assertFalse(stored.contains(session.accessToken))
            assertFalse(stored.contains(session.refreshToken))
            assertEquals(session.refreshToken, EncryptedSessionManager(context).loadSession()!!.refreshToken)
            manager.deleteSession()
            assertNull(EncryptedSessionManager(context).loadSession())
        } finally { manager.deleteSession() }
    }

    @Test fun corruptedSessionReturnsToLoginInsteadOfLoadingInvalidCredentials() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("auth_session", Context.MODE_PRIVATE).edit().putString("encrypted", "invalid").commit()
        assertNull(EncryptedSessionManager(context).loadSession())
    }
}
