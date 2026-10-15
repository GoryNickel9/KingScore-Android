package app.kingscore.android

import android.app.Application
import app.kingscore.android.core.auth.EncryptedSessionManager
import app.kingscore.android.core.auth.SupabaseAuthRepository
import app.kingscore.android.core.network.AndroidNetworkMonitor
import app.kingscore.android.data.remote.SupabaseReader
import app.kingscore.android.data.remote.SupabasePlaysRepository
import app.kingscore.android.data.repository.PlaysRepository
import app.kingscore.android.data.repository.RatingsRepository
import app.kingscore.android.data.repository.StatisticsRepository
import app.kingscore.android.data.repository.AccountCommentOutcomes
import app.kingscore.android.data.local.EncryptedCommentOutcomeStore
import app.kingscore.android.data.remote.SupabasePublishedRatingsRepository
import app.kingscore.android.data.remote.SupabaseEloPublication
import app.kingscore.android.data.remote.SupabaseSummaryRepository
import app.kingscore.android.data.remote.SupabaseSummaryRpc
import app.kingscore.android.data.remote.SupabaseCommentWrites
import java.util.UUID
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.okhttp.OkHttp
import java.net.URI
import app.kingscore.android.core.images.AccountImages
import app.kingscore.android.core.auth.AuthFailure
import app.kingscore.android.core.auth.AuthProblem
import kotlin.time.Duration.Companion.seconds
import app.kingscore.android.data.local.EncryptedPendingPlayStore
import app.kingscore.android.data.repository.AccountPlayJournals
import app.kingscore.android.data.repository.PlayWrites
import app.kingscore.android.data.remote.SupabasePlayWrites

// Enable only after the backend and legacy writer cutover have been authorized and verified.
internal const val ATOMIC_PLAY_EDITOR_ENABLED = true

class KingScoreApplication : Application() {
    lateinit var network: AndroidNetworkMonitor
        private set
    var auth: SupabaseAuthRepository? = null
        private set
    private val commentOutcomes by lazy { AccountCommentOutcomes(EncryptedCommentOutcomeStore(this)) }
    private val playJournals = lazy { AccountPlayJournals(EncryptedPendingPlayStore(this)) }
    fun clearAccountWriteMemory() {
        commentOutcomes.clear()
        if (playJournals.isInitialized()) playJournals.value.clearMemory()
    }
    val playWritesFactory: ((UUID) -> PlayWrites)?
        get() = if (!ATOMIC_PLAY_EDITOR_ENABLED || auth == null) null else { accountId ->
            SupabasePlayWrites(checkNotNull(auth), network, accountId, enabled = true,
                journal = playJournals.value.forAccount(accountId))
        }

    fun plays(accountId: UUID): PlaysRepository = SupabasePlaysRepository(SupabaseReader(checkNotNull(auth), network), accountId,
        SupabaseCommentWrites(checkNotNull(auth), network, accountId, outcomes = commentOutcomes.forAccount(accountId)))
    fun ratings(accountId: UUID): RatingsRepository = SupabasePublishedRatingsRepository(SupabaseReader(checkNotNull(auth), network),
        accountId, SupabaseEloPublication(checkNotNull(auth), network))
    fun statistics(accountId: UUID): StatisticsRepository = SupabaseSummaryRepository(
        SupabaseSummaryRpc(checkNotNull(auth), network, accountId), SupabaseReader(checkNotNull(auth), network), accountId)
    fun images(accountId: UUID, onAccessFailure: (AuthProblem) -> Unit) = AccountImages(this, BuildConfig.SUPABASE_URL,
        network, verifyAccount = {
            if (!network.connected.value) throw AuthFailure(AuthProblem.NETWORK_UNAVAILABLE)
            checkNotNull(auth).withVerifiedSession(accountId) { _, _ -> Unit }
        }, onAccessFailure)

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
