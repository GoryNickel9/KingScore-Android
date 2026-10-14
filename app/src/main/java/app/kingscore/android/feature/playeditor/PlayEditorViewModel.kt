package app.kingscore.android.feature.playeditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kingscore.android.core.auth.*
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.remote.PlaySaveJson
import app.kingscore.android.data.repository.*
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class PlayEditorState(
    val loading: Boolean = false, val saving: Boolean = false, val draft: PlayDraft? = null,
    val snapshot: PlayEditorResult? = null, val options: HistoryOptions = HistoryOptions(),
    val readProblem: DataProblem? = null, val writeProblem: PlayWriteProblem? = null,
    val pending: PendingPlayRequest? = null, val recovered: PlayWriteResult? = null,
    val result: PlayWriteResult? = null, val resultFromRecovery: Boolean = false, val conflict: Boolean = false,
    val latest: PlayEditorResult? = null, val confirmingDelete: Boolean = false,
    val accessBlocked: Boolean = false, val dirty: Boolean = false,
) {
    val busy: Boolean get() = loading || saving
    val canMutate: Boolean get() = !busy && !accessBlocked && result == null && pending == null && !conflict &&
        writeProblem !in setOf(PlayWriteProblem.STORAGE, PlayWriteProblem.UNCERTAIN, PlayWriteProblem.FORBIDDEN,
            PlayWriteProblem.UNAVAILABLE, PlayWriteProblem.CLOSED, PlayWriteProblem.INVALID_RESPONSE)
    val canEdit: Boolean get() = draft != null && canMutate
    val canDelete: Boolean get() = snapshot != null && canMutate
}

/** Fixture-ready state machine only. Production navigation and write capability remain disabled. */
class PlayEditorViewModel(
    private val account: Account, private val network: NetworkMonitor, private val reads: PlaysRepository,
    private val writes: PlayWrites?, private val playId: UUID? = null,
    private val enabled: Boolean = false, private val onAccessFailure: (AuthProblem) -> Unit = {},
    private val today: () -> LocalDate = { LocalDate.now() }, private val requestId: () -> UUID = UUID::randomUUID,
) : ViewModel() {
    private val state = MutableStateFlow(PlayEditorState())
    val editor: StateFlow<PlayEditorState> = state
    private var job: Job? = null
    private var generation = 0
    private var disposed = false
    private var originalDraft: PlayDraft? = null

    private val networkJob = viewModelScope.launch {
        network.connected.collect { online ->
            if (enabled && account.isAdmin && !online && !disposed && !state.value.accessBlocked)
                reject(AuthProblem.NETWORK_UNAVAILABLE, generation)
        }
    }

    fun load() {
        if (!idle() || state.value.draft != null || !access()) return
        val stamp = ++generation
        job?.cancel()
        state.value = PlayEditorState(loading = true)
        job = viewModelScope.launch {
            try {
                val writer = checkNotNull(writes)
                val pending = writer.restorePending()
                val recovered = if (pending == null) writer.restoreCompleted() else null
                if (!current(stamp)) return@launch
                state.value = state.value.copy(pending = pending, recovered = recovered)
                val options = reads.filterOptions()
                val snapshot = playId?.let { writer.editor(it) }
                val draft = snapshot?.let(PlayDraft::from) ?: if (playId == null) PlayDraft(playedAt = today().toString()) else null
                if (!current(stamp)) return@launch
                originalDraft = draft
                state.value = PlayEditorState(draft = draft, snapshot = snapshot, options = options,
                    pending = pending, recovered = recovered)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: AuthFailure) { reject(failure.problem, stamp) }
            catch (failure: DataFailure) { if (current(stamp)) state.value = state.value.copy(loading = false, readProblem = failure.problem) }
            catch (failure: PlayWriteFailure) { if (current(stamp)) state.value = state.value.copy(loading = false,
                writeProblem = failure.problem, pending = writes?.pendingRequest) }
            catch (_: IllegalArgumentException) { if (current(stamp)) state.value = state.value.copy(loading = false, writeProblem = PlayWriteProblem.INVALID_RESPONSE) }
            catch (_: IllegalStateException) { if (current(stamp)) state.value = state.value.copy(loading = false, writeProblem = PlayWriteProblem.INVALID_RESPONSE) }
        }
    }

    fun change(draft: PlayDraft) {
        if (!access() || !editable()) return
        val copy = draft.copy(scores = draft.scores.toList())
        state.value = state.value.copy(draft = copy, dirty = copy != originalDraft,
            writeProblem = null, confirmingDelete = false)
    }
    fun edit(transform: (PlayDraft) -> PlayDraft) {
        if (!access() || !editable()) return
        change(transform(checkNotNull(state.value.draft)))
    }
    fun save() {
        if (!access() || !editable()) return
        val draft = state.value.draft ?: return
        try {
            val input = draft.input()
            require(state.value.options.games.any { it.id == input.game_id })
            require(input.scores.all { score -> state.value.options.players.any { it.id == score.player_id } })
            val uuid = requestId()
            if (playId == null) {
                val command = CreatePlayCommand(uuid, input)
                PlaySaveJson.createArguments(command)
                mutate { PlayWriteResult.Saved(it.create(command)) }
            } else {
                val command = UpdatePlayCommand(uuid, playId, checkNotNull(state.value.snapshot).revision, input)
                PlaySaveJson.updateArguments(command)
                mutate { PlayWriteResult.Saved(it.update(command)) }
            }
        } catch (_: IllegalArgumentException) { state.value = state.value.copy(writeProblem = PlayWriteProblem.INVALID_INPUT) }
        catch (_: java.time.DateTimeException) { state.value = state.value.copy(writeProblem = PlayWriteProblem.INVALID_INPUT) }
    }
    fun requestDelete() {
        if (!access() || playId == null || state.value.snapshot == null || !writable()) return
        state.value = state.value.copy(confirmingDelete = true, writeProblem = null)
    }
    fun cancelDelete() { if (!state.value.saving) state.value = state.value.copy(confirmingDelete = false) }
    fun delete() {
        if (!state.value.confirmingDelete || !idle() || !access()) return
        val command = DeletePlayCommand(requestId(), checkNotNull(playId), checkNotNull(state.value.snapshot).revision)
        mutate { PlayWriteResult.Deleted(it.delete(command)) }
    }
    fun recover(request: UUID) {
        if (!idle() || !access() || state.value.pending?.requestId != request) return
        mutate(recovering = true) { it.retryPending() }
    }

    fun readConflict() {
        if (!state.value.conflict || state.value.pending != null || playId == null || !idle() || !access()) return
        val stamp = generation
        state.value = state.value.copy(loading = true, readProblem = null, latest = null)
        job = viewModelScope.launch {
            try {
                val latest = checkNotNull(writes).editor(playId)
                if (current(stamp)) state.value = state.value.copy(loading = false, latest = latest)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: AuthFailure) { reject(failure.problem, stamp) }
            catch (failure: PlayWriteFailure) { if (current(stamp)) state.value = state.value.copy(loading = false, writeProblem = failure.problem) }
        }
    }
    /** Explicit discard of the local draft after showing the latest server snapshot. */
    fun useLatest() {
        if (!idle() || !access() || !state.value.conflict || state.value.pending != null) return
        val latest = state.value.latest ?: return
        try {
            val draft = PlayDraft.from(latest)
            originalDraft = draft
            state.value = state.value.copy(snapshot = latest, draft = draft, latest = null,
                conflict = false, writeProblem = null, confirmingDelete = false, dirty = false)
        } catch (_: IllegalArgumentException) { state.value = state.value.copy(writeProblem = PlayWriteProblem.INVALID_RESPONSE) }
    }

    private fun mutate(recovering: Boolean = false, action: suspend (PlayWrites) -> PlayWriteResult) {
        if (!idle()) return
        val stamp = generation
        state.value = state.value.copy(saving = true, writeProblem = null, confirmingDelete = false, result = null)
        job = viewModelScope.launch {
            try {
                val result = action(checkNotNull(writes))
                if (current(stamp)) state.value = state.value.copy(saving = false,
                    result = result, resultFromRecovery = recovering,
                    recovered = if (recovering) result else state.value.recovered, pending = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: AuthFailure) { reject(failure.problem, stamp) }
            catch (failure: PlayWriteFailure) {
                if (current(stamp)) state.value = state.value.copy(saving = false, writeProblem = failure.problem,
                    pending = writes?.pendingRequest, conflict = state.value.conflict || failure.problem == PlayWriteProblem.CONFLICT)
            }
        }
    }
    private fun idle() = !disposed && !state.value.loading && !state.value.saving && !state.value.accessBlocked && state.value.result == null
    private fun editable() = !disposed && state.value.canEdit
    private fun writable() = !disposed && state.value.canMutate
    private fun access(): Boolean {
        if (disposed || state.value.accessBlocked) return false
        if (!enabled || writes == null) { state.value = state.value.copy(writeProblem = PlayWriteProblem.UNAVAILABLE); return false }
        if (!account.isAdmin) { state.value = state.value.copy(writeProblem = PlayWriteProblem.FORBIDDEN); return false }
        if (!network.connected.value) { reject(AuthProblem.NETWORK_UNAVAILABLE, generation); return false }
        return true
    }
    private fun current(stamp: Int): Boolean {
        if (disposed || stamp != generation) return false
        if (!network.connected.value) { reject(AuthProblem.NETWORK_UNAVAILABLE, stamp); return false }
        return true
    }
    private fun reject(problem: AuthProblem, stamp: Int) {
        if (disposed || stamp != generation) return
        generation++; job?.cancel()
        originalDraft = null
        state.value = PlayEditorState(accessBlocked = true)
        onAccessFailure(problem)
    }
    fun clearMemory() {
        disposed = true; generation++; job?.cancel(); networkJob.cancel(); writes?.close()
        originalDraft = null
        state.value = PlayEditorState(accessBlocked = true)
    }
    override fun onCleared() { clearMemory() }
}
