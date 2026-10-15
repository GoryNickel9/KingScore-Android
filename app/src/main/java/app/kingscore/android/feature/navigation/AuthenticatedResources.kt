package app.kingscore.android.feature.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import app.kingscore.android.data.repository.PlaysRepository
import app.kingscore.android.data.repository.RatingsRepository
import app.kingscore.android.data.repository.StatisticsRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Retained by the Activity across configuration changes, released when access is removed. */
class AuthenticatedResources : ViewModel() {
    class Session(val accountId: UUID, val plays: PlaysRepository?,
        val statistics: StatisticsRepository?, val ratings: RatingsRepository?,
        internal val isAdmin: Boolean, internal val editorAvailable: Boolean) : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
        private var closed = false
        private val revision = MutableStateFlow(0)
        val playDataRevision: StateFlow<Int> = revision
        private val commentRevision = MutableStateFlow(0)
        val commentDataRevision: StateFlow<Int> = commentRevision
        fun commentsSaved() { if (!closed) commentRevision.value++ }
        fun playSaved() { if (!closed) { ratings?.invalidate(); revision.value++ } }
        internal fun close() {
            if (closed) return
            closed = true
            viewModelStore.clear(); plays?.commentWrites?.close(); ratings?.close()
        }
    }
    private var current: Session? = null
    fun acquire(accountId: UUID, plays: ((UUID) -> PlaysRepository)?,
        statistics: ((UUID) -> StatisticsRepository)?, ratings: ((UUID) -> RatingsRepository)?,
        isAdmin: Boolean = false, editorAvailable: Boolean = false): Session {
        current?.takeIf { it.accountId == accountId && it.isAdmin == isAdmin && it.editorAvailable == editorAvailable }?.let { return it }
        current?.let(::release)
        return Session(accountId, plays?.invoke(accountId), statistics?.invoke(accountId), ratings?.invoke(accountId), isAdmin, editorAvailable)
            .also { current = it }
    }
    fun release(session: Session) {
        if (current !== session) return
        current = null
        session.close()
    }
    override fun onCleared() { current?.let(::release) }
}
