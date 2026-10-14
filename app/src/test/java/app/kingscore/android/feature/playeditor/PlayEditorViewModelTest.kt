package app.kingscore.android.feature.playeditor

import app.kingscore.android.core.auth.*
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.remote.PlaySaveJson
import app.kingscore.android.data.remote.PlayWriteFixtures
import app.kingscore.android.data.repository.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val f = PlayWriteFixtures
    private val network = object : NetworkMonitor { override val connected = MutableStateFlow(true) }
    private val account = Account(f.account, "Admin", null, true)
    private val models = mutableListOf<PlayEditorViewModel>()
    private val failures = mutableListOf<AuthProblem>()
    private val writer = Writer()
    private var catalogs = 0
    private var options: suspend () -> HistoryOptions = { HistoryOptions(
        listOf(GameRow(f.game, "Azul", null, null, null, null, null)),
        listOf(PlayerRow(f.player, null, "Anna", null, null, null, null, Instant.EPOCH, null, null))) }
    private val reads = object : PlaysRepository {
        override suspend fun filterOptions(): HistoryOptions { catalogs++; return options() }
        override suspend fun player(id: UUID): PlayerRow? = error("unused")
        override suspend fun game(id: UUID): GameRow? = error("unused")
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?): PlayPage = error("unused")
        override suspend fun detail(id: UUID): PlayDetail? = error("unused")
        override suspend fun comments(id: UUID, after: CommentCursor?): CommentPage = error("unused")
    }
    private fun snapshot(legacy: Boolean = false, participants: Int = 1): PlayEditorResult =
        PlaySaveJson.editor(f.editor(legacy = legacy, participants = participants), f.play).let {
            it.copy(detail = it.detail.copy(play = it.detail.play.copy(game_id = f.game)))
        }
    private inner class Writer : PlayWrites {
        override var pendingRequest: PendingPlayRequest? = null
        var completed: PlayWriteResult? = null
        var restored = 0; var closed = false; var editorCalls = 0
        val commands = mutableListOf<Any>()
        var read: suspend () -> PlayEditorResult = { snapshot() }
        var send: suspend () -> PlayMutationResult = { PlayMutationResult(f.play, "v1:new", false) }
        var retry: suspend () -> PlayWriteResult = { PlayWriteResult.Saved(send()) }
        override suspend fun restorePending(): PendingPlayRequest? { restored++; return pendingRequest }
        override suspend fun restoreCompleted() = completed
        override suspend fun editor(playId: UUID): PlayEditorResult { editorCalls++; return read() }
        override suspend fun create(command: CreatePlayCommand): PlayMutationResult { commands += command; return send() }
        override suspend fun update(command: UpdatePlayCommand): PlayMutationResult { commands += command; return send() }
        override suspend fun delete(command: DeletePlayCommand): DeletePlayResult {
            commands += command; send(); return DeletePlayResult(command.play_id, true, false)
        }
        override suspend fun retryPending(): PlayWriteResult { commands += "retry"; return retry() }
        override fun close() { closed = true }
    }
    private fun model(id: UUID? = null, enabled: Boolean = true, admin: Boolean = true) = PlayEditorViewModel(
        account.copy(isAdmin = admin), network, reads, writer, id, enabled, failures::add,
        today = { LocalDate.of(2026, 10, 14) }, requestId = { f.request }).also { models += it }
    private suspend fun TestScope.loaded(id: UUID? = null): PlayEditorViewModel = model(id).also { it.load(); advanceUntilIdle() }
    private fun valid() = PlayDraft(f.game, "2026-10-14", scores = listOf(DraftScore(playerId = f.player)))
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { models.forEach { it.clearMemory() }; Dispatchers.resetMain() }

    @Test fun `disabled and nonadmin never read or write`() = runTest(dispatcher) {
        model(enabled = false).apply { load(); save(); requestDelete() }
        model(admin = false).apply { load(); save(); requestDelete() }
        advanceUntilIdle()
        assertEquals(0, catalogs); assertEquals(0, writer.restored); assertTrue(writer.commands.isEmpty())
    }
    @Test fun `constructor defaults to disabled`() = runTest(dispatcher) {
        val vm = PlayEditorViewModel(account, network, reads, writer).also { models += it }
        vm.load(); advanceUntilIdle()
        assertEquals(PlayWriteProblem.UNAVAILABLE, vm.editor.value.writeProblem); assertEquals(0, writer.restored)
    }
    @Test fun `offline never loads catalog and reports mandatory access block`() = runTest(dispatcher) {
        network.connected.value = false
        val vm = model(); vm.load(); advanceUntilIdle()
        assertTrue(vm.editor.value.accessBlocked); assertEquals(0, catalogs)
        assertEquals(listOf(AuthProblem.NETWORK_UNAVAILABLE), failures)
    }
    @Test fun `create preserves decimal text null zero and metadata`() = runTest(dispatcher) {
        val vm = loaded(); assertEquals("2026-10-14", vm.editor.value.draft?.playedAt)
        vm.change(valid().copy(notes = "", duration = "0", scores = listOf(DraftScore(playerId = f.player,
            score = "9007199254740993.125", color = "", role = "builder"))))
        vm.save(); advanceUntilIdle()
        val command = writer.commands.single() as CreatePlayCommand
        assertEquals(BigDecimal("9007199254740993.125"), command.payload.scores.single().score)
        assertEquals("", command.payload.notes); assertEquals(0, command.payload.duration_minutes)
        assertEquals("", command.payload.scores.single().color)
        vm.load(); vm.save(); advanceUntilIdle(); assertEquals(1, writer.commands.size)
    }
    @Test fun `score blank differs from zero and negative decimals`() {
        assertNull(valid().input().scores.single().score)
        for (text in listOf("0", "-0.25", "5.00")) assertEquals(BigDecimal(text),
            valid().copy(scores = listOf(DraftScore(playerId = f.player, score = text))).input().scores.single().score)
    }
    @Test fun `invalid dates durations scores and foreign keys stay local`() = runTest(dispatcher) {
        val vm = loaded()
        val invalid = listOf(valid().copy(playedAt = "2026-02-30"), valid().copy(playedAt = "2026-1-01"),
            valid().copy(duration = "2147483648"), valid().copy(duration = "-1"), valid().copy(gameId = UUID(8, 8)),
            valid().copy(scores = listOf(DraftScore(playerId = UUID(8, 9)))),
            valid().copy(scores = listOf(DraftScore(playerId = f.player, score = "1e3"))),
            valid().copy(scores = listOf(DraftScore(playerId = f.player, score = " 5"))),
            valid().copy(scores = emptyList()))
        for (draft in invalid) {
            vm.change(draft); vm.save(); advanceUntilIdle()
            assertEquals(draft, vm.editor.value.draft); assertEquals(PlayWriteProblem.INVALID_INPUT, vm.editor.value.writeProblem)
        }
        assertTrue(writer.commands.isEmpty())
    }
    @Test fun `aggregate payload budget applies even to fixture writer`() = runTest(dispatcher) {
        val vm = loaded(); vm.change(valid().copy(notes = "x".repeat(1048576)))
        vm.save(); advanceUntilIdle()
        assertEquals(PlayWriteProblem.INVALID_INPUT, vm.editor.value.writeProblem); assertTrue(writer.commands.isEmpty())
    }
    @Test fun `edit uses original revision and score identity without rewriting import metadata`() = runTest(dispatcher) {
        val vm = loaded(f.play); val draft = vm.editor.value.draft!!
        vm.change(draft.copy(notes = "Edited")); vm.save(); advanceUntilIdle()
        val command = writer.commands.single() as UpdatePlayCommand
        assertEquals("v1:opaque", command.expected_revision); assertEquals(f.scoreId, command.payload.scores.single().id)
        assertEquals("red", command.payload.scores.single().color); assertEquals("builder", command.payload.scores.single().role)
        assertEquals("imported", vm.editor.value.snapshot?.detail?.play?.location)
        assertEquals("123", vm.editor.value.snapshot?.detail?.play?.bga_table_id)
    }
    @Test fun `legacy rows remain fully visible read only and explicit delete is possible`() = runTest(dispatcher) {
        writer.read = { snapshot(legacy = true, participants = 64) }
        val vm = loaded(f.play)
        assertEquals(64, vm.editor.value.snapshot?.detail?.scores?.size); assertNull(vm.editor.value.draft)
        vm.change(valid()); vm.save(); vm.delete(); advanceUntilIdle(); assertTrue(writer.commands.isEmpty())
        vm.requestDelete(); assertTrue(vm.editor.value.confirmingDelete)
        vm.delete(); advanceUntilIdle()
        assertEquals(DeletePlayCommand(f.request, f.play, "v1:opaque"), writer.commands.single())
    }
    @Test fun `delete requires confirmation and cancel sends nothing`() = runTest(dispatcher) {
        val vm = loaded(f.play); vm.delete(); vm.requestDelete(); vm.cancelDelete(); vm.delete(); advanceUntilIdle()
        assertTrue(writer.commands.isEmpty())
    }
    @Test fun `double taps and edits during write cannot change command`() = runTest(dispatcher) {
        val completion = CompletableDeferred<PlayMutationResult>(); writer.send = { completion.await() }
        val vm = loaded(); vm.change(valid()); vm.save(); vm.save(); vm.change(valid().copy(notes = "late")); runCurrent()
        assertEquals(1, writer.commands.size); assertNull(vm.editor.value.draft?.notes)
        completion.complete(PlayMutationResult(f.play, "v1:new", false)); advanceUntilIdle()
        assertNotNull(vm.editor.value.result)
    }
    @Test fun `conflict keeps draft until latest is explicitly read and accepted`() = runTest(dispatcher) {
        val vm = loaded(f.play); val draft = vm.editor.value.draft!!.copy(notes = "mine")
        writer.send = { throw PlayWriteFailure(PlayWriteProblem.CONFLICT) }
        vm.change(draft); vm.save(); advanceUntilIdle()
        assertEquals(draft, vm.editor.value.draft); assertTrue(vm.editor.value.conflict); assertEquals(1, writer.editorCalls)
        vm.save(); vm.requestDelete(); assertFalse(vm.editor.value.confirmingDelete)
        val latest = snapshot().let { it.copy(revision = "v1:latest", detail = it.detail.copy(
            play = it.detail.play.copy(notes = "theirs"), scores = it.detail.scores.map { row ->
                row.copy(score = row.score.copy(id = UUID(0, 55))) })) }
        writer.read = { latest }; vm.readConflict(); advanceUntilIdle()
        assertEquals(draft, vm.editor.value.draft); assertEquals("v1:opaque", vm.editor.value.snapshot?.revision)
        vm.useLatest(); assertEquals("theirs", vm.editor.value.draft?.notes)
        assertEquals(UUID(0, 55), vm.editor.value.draft?.scores?.single()?.id); assertFalse(vm.editor.value.conflict)
        writer.send = { PlayMutationResult(f.play, "v1:new", false) }; vm.save(); advanceUntilIdle()
        assertEquals("v1:latest", (writer.commands.last() as UpdatePlayCommand).expected_revision)
    }
    @Test fun `uncertain writes lock draft and retry only original request explicitly`() = runTest(dispatcher) {
        val vm = loaded(f.play); val draft = vm.editor.value.draft!!
        writer.send = { writer.pendingRequest = PendingPlayRequest(f.request, f.play, PlayMutationKind.UPDATE)
            throw PlayWriteFailure(PlayWriteProblem.UNCERTAIN) }
        vm.save(); advanceUntilIdle(); vm.change(draft.copy(notes = "new")); vm.save(); vm.requestDelete()
        assertEquals(draft, vm.editor.value.draft); assertEquals(1, writer.commands.size)
        vm.recover(UUID(0, 77)); advanceUntilIdle(); assertEquals(1, writer.commands.size)
        writer.retry = { PlayWriteResult.Saved(PlayMutationResult(f.play, "v1:new", true)) }
        vm.recover(f.request); vm.recover(f.request); advanceUntilIdle()
        assertEquals(listOf("retry"), writer.commands.drop(1)); assertNotNull(vm.editor.value.recovered)
        assertTrue(vm.editor.value.resultFromRecovery)
        vm.save(); assertEquals(2, writer.commands.size)
    }
    @Test fun `another play pending blocks create and is never automatically sent`() = runTest(dispatcher) {
        writer.pendingRequest = PendingPlayRequest(f.request, f.play, PlayMutationKind.DELETE)
        val vm = loaded(); vm.change(valid()); vm.save(); vm.load(); advanceUntilIdle()
        assertEquals(PlayMutationKind.DELETE, vm.editor.value.pending?.kind); assertTrue(writer.commands.isEmpty())
    }
    @Test fun `pending identity survives catalog failure`() = runTest(dispatcher) {
        writer.pendingRequest = PendingPlayRequest(f.request, f.play, PlayMutationKind.UPDATE)
        options = { throw DataFailure(DataProblem.INVALID_RESPONSE) }
        val vm = loaded()
        assertEquals(f.request, vm.editor.value.pending?.requestId)
        assertEquals(DataProblem.INVALID_RESPONSE, vm.editor.value.readProblem)
    }
    @Test fun `confirmed restoration is distinct from new save and performs no mutation`() = runTest(dispatcher) {
        writer.completed = PlayWriteResult.Saved(PlayMutationResult(f.play, "v1:new", false), restored = true)
        val vm = loaded(); assertEquals(writer.completed, vm.editor.value.recovered)
        assertNull(vm.editor.value.result); assertTrue(writer.commands.isEmpty())
        assertFalse(vm.editor.value.resultFromRecovery)
    }
    @Test fun `storage failure prevents delete and new writes`() = runTest(dispatcher) {
        val vm = loaded(f.play); writer.send = { throw PlayWriteFailure(PlayWriteProblem.STORAGE) }
        vm.save(); advanceUntilIdle(); vm.requestDelete(); vm.delete(); vm.save(); vm.change(valid())
        assertFalse(vm.editor.value.confirmingDelete); assertEquals(1, writer.commands.size)
        assertEquals(PlayWriteProblem.STORAGE, vm.editor.value.writeProblem)
    }
    @Test fun `connectivity loss clears draft immediately without automatic reconnect`() = runTest(dispatcher) {
        val vm = loaded(f.play); network.connected.value = false; runCurrent()
        assertNull(vm.editor.value.snapshot); assertNull(vm.editor.value.draft); assertTrue(vm.editor.value.accessBlocked)
        network.connected.value = true; vm.save(); vm.load(); advanceUntilIdle()
        assertEquals(1, catalogs); assertTrue(writer.commands.isEmpty())
    }
    @Test fun `late noncancellable success cannot repopulate disposed account`() = runTest(dispatcher) {
        val completion = CompletableDeferred<PlayMutationResult>()
        writer.send = { withContext(NonCancellable) { completion.await() } }
        val vm = loaded(); vm.change(valid()); vm.save(); runCurrent(); vm.clearMemory()
        completion.complete(PlayMutationResult(f.play, "v1:new", false)); advanceUntilIdle()
        assertTrue(writer.closed); assertNull(vm.editor.value.result); assertNull(vm.editor.value.draft)
        vm.load(); assertEquals(1, catalogs)
    }
    @Test fun `auth failure clears data before account callback`() = runTest(dispatcher) {
        val vm = loaded(f.play); writer.send = { throw AuthFailure(AuthProblem.INVALID_CREDENTIALS) }
        vm.save(); advanceUntilIdle()
        assertNull(vm.editor.value.draft); assertNull(vm.editor.value.snapshot)
        assertEquals(listOf(AuthProblem.INVALID_CREDENTIALS), failures)
    }
    @Test fun `repeat load cannot silently discard unsaved draft`() = runTest(dispatcher) {
        val vm = loaded(f.play); val draft = vm.editor.value.draft!!.copy(notes = "unsaved")
        vm.change(draft); vm.load(); advanceUntilIdle()
        assertEquals(draft, vm.editor.value.draft); assertEquals(1, catalogs)
    }
    @Test fun `invalid editable snapshot cannot create a writable draft`() = runTest(dispatcher) {
        writer.read = { snapshot().let { it.copy(detail = it.detail.copy(play = it.detail.play.copy(source = "unsupported"))) } }
        val vm = loaded(f.play); vm.change(valid()); vm.save(); vm.requestDelete()
        assertEquals(PlayWriteProblem.INVALID_RESPONSE, vm.editor.value.writeProblem)
        assertNull(vm.editor.value.snapshot); assertNull(vm.editor.value.draft); assertTrue(writer.commands.isEmpty())
    }
    @Test fun `failed conflict read preserves original draft and revision`() = runTest(dispatcher) {
        val vm = loaded(f.play); val draft = vm.editor.value.draft!!.copy(notes = "mine")
        writer.send = { throw PlayWriteFailure(PlayWriteProblem.CONFLICT) }
        vm.change(draft); vm.save(); advanceUntilIdle()
        writer.read = { throw PlayWriteFailure(PlayWriteProblem.NOT_FOUND) }
        vm.readConflict(); advanceUntilIdle(); vm.useLatest(); vm.save()
        assertEquals(draft, vm.editor.value.draft); assertEquals("v1:opaque", vm.editor.value.snapshot?.revision)
        assertTrue(vm.editor.value.conflict); assertEquals(1, writer.commands.size)
    }
    @Test fun `dirty flag follows original draft and rapid edits read current state`() = runTest(dispatcher) {
        val vm = loaded(f.play); val original = vm.editor.value.draft!!
        assertFalse(vm.editor.value.dirty)
        vm.edit { it.copy(notes = "new") }
        vm.edit { it.copy(duration = "50") }
        assertEquals("new", vm.editor.value.draft?.notes); assertEquals("50", vm.editor.value.draft?.duration)
        assertTrue(vm.editor.value.dirty)
        vm.change(original); assertFalse(vm.editor.value.dirty)
    }
    @Test fun `state capabilities block edits and deletion for uncertainty and forbidden`() {
        val loaded = PlayEditorState(draft = valid(), snapshot = snapshot())
        assertTrue(loaded.canEdit); assertTrue(loaded.canDelete)
        for (problem in listOf(PlayWriteProblem.STORAGE, PlayWriteProblem.UNCERTAIN, PlayWriteProblem.FORBIDDEN,
            PlayWriteProblem.UNAVAILABLE, PlayWriteProblem.CLOSED, PlayWriteProblem.INVALID_RESPONSE)) {
            assertFalse(loaded.copy(writeProblem = problem).canEdit); assertFalse(loaded.copy(writeProblem = problem).canDelete)
        }
        assertFalse(loaded.copy(saving = true).canDelete); assertFalse(loaded.copy(conflict = true).canEdit)
        assertFalse(loaded.copy(accessBlocked = true).canEdit)
    }
}
