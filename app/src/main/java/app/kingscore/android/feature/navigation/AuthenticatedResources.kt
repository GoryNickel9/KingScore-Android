package app.kingscore.android.feature.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import app.kingscore.android.data.repository.PlaysRepository
import app.kingscore.android.data.repository.RatingsRepository
import app.kingscore.android.data.repository.StatisticsRepository
import java.util.UUID

/** Retained by the Activity across configuration changes, released when access is removed. */
class AuthenticatedResources : ViewModel() {
    class Session(val accountId: UUID, val plays: PlaysRepository?,
        val statistics: StatisticsRepository?, val ratings: RatingsRepository?) : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
        internal fun close() { viewModelStore.clear(); plays?.commentWrites?.close() }
    }
    private var current: Session? = null
    fun acquire(accountId: UUID, plays: ((UUID) -> PlaysRepository)?,
        statistics: ((UUID) -> StatisticsRepository)?, ratings: ((UUID) -> RatingsRepository)?): Session {
        current?.takeIf { it.accountId == accountId }?.let { return it }
        current?.let(::release)
        return Session(accountId, plays?.invoke(accountId), statistics?.invoke(accountId), ratings?.invoke(accountId))
            .also { current = it }
    }
    fun release(session: Session) {
        if (current !== session) return
        current = null
        session.close()
    }
    override fun onCleared() { current?.let(::release) }
}
