package app.kingscore.android.data.repository

import java.util.UUID
import kingscore.contracts.v1.*

enum class PlayWriteProblem { UNAVAILABLE, CLOSED, BUSY, FORBIDDEN, NOT_FOUND, INVALID_INPUT, CONFLICT, INVALID_RESPONSE, UNCERTAIN, STORAGE }
class PlayWriteFailure(val problem: PlayWriteProblem) : Exception(problem.name)
data class PendingPlayRequest(val requestId: UUID, val playId: UUID?)
sealed interface PlayWriteResult {
    data class Saved(val value: PlayMutationResult, val restored: Boolean = false) : PlayWriteResult
    data class Deleted(val value: DeletePlayResult, val restored: Boolean = false) : PlayWriteResult
}

/** Prepared client only. No UI wiring, offline queue, BGA import or ELO operations. */
interface PlayWrites : AutoCloseable {
    val pendingRequest: PendingPlayRequest?
    suspend fun restorePending(): PendingPlayRequest?
    suspend fun restoreCompleted(): PlayWriteResult?
    suspend fun editor(playId: UUID): PlayEditorResult
    suspend fun create(command: CreatePlayCommand): PlayMutationResult
    suspend fun update(command: UpdatePlayCommand): PlayMutationResult
    suspend fun delete(command: DeletePlayCommand): DeletePlayResult
    suspend fun retryPending(): PlayWriteResult
}
