package app.kingscore.android

import android.app.Application
import app.kingscore.android.core.auth.EncryptedSessionManager
import app.kingscore.android.core.auth.SupabaseAuthRepository
import app.kingscore.android.core.network.AndroidNetworkMonitor
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.okhttp.OkHttp
import java.net.URI
import kotlin.time.Duration.Companion.seconds

class KingScoreApplication : Application() {
    lateinit var network: AndroidNetworkMonitor
        private set
    var auth: SupabaseAuthRepository? = null
        private set

    override fun onCreate() {
        super.onCreate()
        network = AndroidNetworkMonitor(this)
        val url = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_PUBLIC_KEY
        if (url.isBlank() || key.isBlank() || key.startsWith("sb_secret_")) return
        val endpoint = runCatching { URI(url) }.getOrNull() ?: return
        if (endpoint.scheme != "https" || endpoint.host == null) return
        auth = SupabaseAuthRepository(createSupabaseClient(url, key) {
            httpEngine = OkHttp.create()
            requestTimeout = 15.seconds
            defaultLogLevel = LogLevel.NONE
            install(Auth) {
                autoLoadFromStorage = false
                alwaysAutoRefresh = false
                enableLifecycleCallbacks = false
                sessionManager = EncryptedSessionManager(this@KingScoreApplication)
            }
            install(Postgrest)
        })
    }
}
