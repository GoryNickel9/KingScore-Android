package app.kingscore.android.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.material3.Surface
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.core.auth.*
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.playeditor.*
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*

class PlayEditorUiTest {
    @get:Rule val compose = createComposeRule()
    private val account = Account(UUID(7, 1), "Admin", null, true)
    private val play = UUID(7, 2)
    private val game = GameRow(UUID(7, 3), "Azul", null, null, null, null, null)
    private val anna = PlayerRow(UUID(7, 4), null, "Anna", null, null, null, null, Instant.EPOCH, null, null)
    private val bruno = anna.copy(id = UUID(7, 5), name = "Bruno")
    private val score = UUID(7, 6)
    private val request = UUID(7, 7)
    private val network = object : NetworkMonitor { override val connected = MutableStateFlow(true) }
    private val writer = Writer()
    private val failures = mutableListOf<AuthProblem>()
    private val models = mutableListOf<PlayEditorViewModel>()
    private var catalogs = 0
    private var closed = 0
    private var catalogFailure = false
    private val reads = object : PlaysRepository {
        override suspend fun filterOptions(): HistoryOptions {
            catalogs++; if (catalogFailure) throw DataFailure(DataProblem.INVALID_RESPONSE)
            return HistoryOptions(listOf(game), listOf(anna, bruno))
        }
        override suspend fun player(id: UUID): PlayerRow? = error("unused")
        override suspend fun game(id: UUID): GameRow? = error("unused")
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?): PlayPage = error("unused")
        override suspend fun detail(id: UUID): PlayDetail? = error("unused")
        override suspend fun comments(id: UUID, after: CommentCursor?): CommentPage = error("unused")
    }
    private fun snapshot(legacy: Boolean = false) = PlayEditorResult(PlayDetail(
        PlayRow(play, account.id, game.id, LocalDate.of(2026, 10, 14), "Imported", "Note iniziali", 30,
            if (legacy) null else "offline", "123", Instant.EPOCH, false, null), game,
        if (legacy) (0..63).map { index -> ScoreWithPlayer(PlayScoreRow(UUID(7, 600L + index), play, UUID(7, 300L + index),
            null, null, null, null, null), anna.copy(id = UUID(7, 300L + index), name = "Partecipante $index")) }
        else listOf(ScoreWithPlayer(PlayScoreRow(score, play, anna.id, BigDecimal("5.125"), false, null, "Builder", null), anna))),
        "v1:original", PlayEditability(!legacy, if (legacy) listOf("nullable-winner", "too-many-participants") else emptyList()))
    private inner class Writer : PlayWrites {
        override var pendingRequest: PendingPlayRequest? = null
        var completed: PlayWriteResult? = null
        var closed = false; var editorCalls = 0
        val commands = mutableListOf<Any>()
        var read: suspend () -> PlayEditorResult = { snapshot() }
        var send: suspend () -> PlayMutationResult = { PlayMutationResult(play, "v1:saved", false) }
        var retry: suspend () -> PlayWriteResult = { PlayWriteResult.Saved(PlayMutationResult(play, "v1:saved", true)) }
        override suspend fun restorePending() = pendingRequest
        override suspend fun restoreCompleted() = completed
        override suspend fun editor(playId: UUID): PlayEditorResult { editorCalls++; return read() }
        override suspend fun create(command: CreatePlayCommand): PlayMutationResult { commands += command; return send() }
        override suspend fun update(command: UpdatePlayCommand): PlayMutationResult { commands += command; return send() }
        override suspend fun delete(command: DeletePlayCommand): DeletePlayResult { commands += command; send(); return DeletePlayResult(play, true, false) }
        override suspend fun retryPending(): PlayWriteResult { commands += "retry"; return retry() }
        override fun close() { closed = true }
    }
    private fun model(id: UUID? = null, enabled: Boolean = true, admin: Boolean = true) = PlayEditorViewModel(
        account.copy(isAdmin = admin), network, reads, writer, id, enabled, failures::add,
        today = { LocalDate.of(2026, 10, 14) }, requestId = { request }).also { models += it }
    private fun show(vm: PlayEditorViewModel = model(), scale: Float = 1f) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
            KingScoreTheme { Surface { PlayEditorRoute(vm) { closed++ } } }
        } }
        compose.waitForIdle()
    }
    private fun scroll(tag: String) { compose.onNodeWithTag("editor-content").performScrollToNode(hasTestTag(tag)) }
    private fun click(tag: String) { scroll(tag); compose.onNodeWithTag(tag).performClick(); compose.waitForIdle() }
    private fun replace(tag: String, text: String) { scroll(tag); compose.onNodeWithTag(tag).performTextReplacement(text); compose.waitForIdle() }
    private fun choose(tag: String, id: UUID) { click(tag); compose.onNodeWithTag("editor-option-$id").performClick(); compose.waitForIdle() }
    private fun createForm() { choose("editor-game", game.id); choose("editor-add-player", anna.id) }
    private fun capture(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots")
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @After fun clean() { models.forEach { it.clearMemory() } }

    @Test fun createUsesChosenIdentityExactScoreAndExplicitWinner() {
        show(); scroll("editor-save"); compose.onNodeWithTag("editor-save").assertIsNotEnabled()
        createForm(); click("editor-source-ONLINE"); scroll("editor-date"); capture("editor-create")
        replace("editor-score-${anna.id}", "9007199254740993.125")
        click("editor-winner-${anna.id}"); click("editor-save")
        compose.onNodeWithText("Partita salvata.").assertIsDisplayed()
        val command = writer.commands.single() as CreatePlayCommand
        assertEquals(game.id, command.payload.game_id); assertEquals(anna.id, command.payload.scores.single().player_id)
        assertEquals(PlaySource.ONLINE, command.payload.source)
        assertEquals(BigDecimal("9007199254740993.125"), command.payload.scores.single().score)
        assertTrue(command.payload.scores.single().winner)
        assertNull(command.payload.notes); assertNull(command.payload.scores.single().color)
        compose.onNodeWithTag("editor-save").assertDoesNotExist()
    }
    @Test fun invalidDateShowsFieldErrorWithoutDispatchAndCanBeCorrected() {
        show(); createForm(); replace("editor-date", "2026-02-30"); click("editor-save")
        compose.onNodeWithTag("editor-error").assertIsDisplayed(); assertTrue(writer.commands.isEmpty())
        replace("editor-date", "2026-02-28"); click("editor-save")
        assertEquals(LocalDate.of(2026, 2, 28), (writer.commands.single() as CreatePlayCommand).payload.played_at)
    }
    @Test fun competitiveAndCooperativeResultsAreExplicitAndParticipantRemovalIsStable() {
        show(); createForm(); choose("editor-add-player", bruno.id)
        click("editor-cooperative"); click("editor-team-true")
        scroll("editor-winner-${anna.id}"); compose.onNodeWithTag("editor-winner-${anna.id}").assertIsNotEnabled().assertIsOn()
        click("editor-remove-${bruno.id}"); click("editor-save")
        val input = (writer.commands.single() as CreatePlayCommand).payload
        assertTrue(input.is_cooperative); assertEquals(true, input.team_won); assertEquals(1, input.scores.size); assertTrue(input.scores.single().winner)
    }
    @Test fun editKeepsScoreIdRoleAndOriginalRevisionWithNullableMetadata() {
        show(model(play)); replace("editor-score-${anna.id}", "-0.25")
        replace("editor-notes", ""); click("editor-save")
        val command = writer.commands.single() as UpdatePlayCommand
        assertEquals("v1:original", command.expected_revision); assertEquals(score, command.payload.scores.single().id)
        assertEquals("Builder", command.payload.scores.single().role); assertNull(command.payload.scores.single().color)
        assertEquals("", command.payload.notes); assertEquals(BigDecimal("-0.25"), command.payload.scores.single().score)
    }
    @Test fun conflictComparisonRequiresExplicitDiscardOfLocalDraft() {
        val vm = model(play); show(vm)
        replace("editor-notes", "La mia bozza")
        writer.send = { throw PlayWriteFailure(PlayWriteProblem.CONFLICT) }; click("editor-save")
        scroll("editor-conflict"); compose.onNodeWithTag("editor-conflict").assertIsDisplayed()
        assertEquals("La mia bozza", vm.editor.value.draft?.notes); assertEquals(1, writer.editorCalls)
        val latest = snapshot().let { it.copy(revision = "v1:latest", detail = it.detail.copy(play = it.detail.play.copy(notes = "Dal server"),
            scores = it.detail.scores.map { row -> row.copy(score = row.score.copy(id = UUID(7, 20))) })) }
        writer.read = { latest }; click("editor-read-latest")
        assertEquals("La mia bozza", vm.editor.value.draft?.notes); assertEquals("v1:original", vm.editor.value.snapshot?.revision)
        scroll("editor-use-latest"); capture("editor-conflict")
        click("editor-use-latest"); assertEquals("Dal server", vm.editor.value.draft?.notes); assertFalse(vm.editor.value.dirty)
        writer.send = { PlayMutationResult(play, "v1:saved", false) }; click("editor-save")
        val command = writer.commands.last() as UpdatePlayCommand
        assertEquals("v1:latest", command.expected_revision); assertEquals(UUID(7, 20), command.payload.scores.single().id)
    }
    @Test fun uncertaintyDisablesEditingAndRecoveryUsesOriginalRequest() {
        show(model(play))
        writer.send = { writer.pendingRequest = PendingPlayRequest(request, play, PlayMutationKind.UPDATE); throw PlayWriteFailure(PlayWriteProblem.UNCERTAIN) }
        click("editor-save")
        scroll("editor-score-${anna.id}"); compose.onNodeWithTag("editor-score-${anna.id}").assertIsNotEnabled()
        scroll("editor-save"); compose.onNodeWithTag("editor-save").assertIsNotEnabled()
        click("editor-recover"); compose.onNodeWithText("Operazione precedente recuperata.").assertIsDisplayed()
        assertEquals("retry", writer.commands.last()); assertEquals(2, writer.commands.size)
    }
    @Test fun pendingOtherPlayClearlyReturnsItsOutcomeAndNeverWritesCurrentForm() {
        val other = UUID(7, 99)
        writer.pendingRequest = PendingPlayRequest(request, other, PlayMutationKind.DELETE)
        writer.retry = { PlayWriteResult.Deleted(DeletePlayResult(other, true, true)) }
        show(); compose.onNodeWithTag("editor-pending").assertIsDisplayed()
        click("editor-recover"); compose.onNodeWithTag("editor-result-id").assertTextEquals("Partita: $other")
        assertEquals(listOf("retry"), writer.commands)
    }
    @Test fun historical64RowsRemainReadableAndDeletionRequiresConfirmation() {
        writer.read = { snapshot(legacy = true) }; show(model(play))
        compose.onNodeWithTag("editor-legacy").assertIsDisplayed()
        scroll("editor-legacy-count"); compose.onNodeWithTag("editor-legacy-count").assertTextEquals("64 partecipanti")
        scroll("editor-legacy-score-${UUID(7, 663)}"); compose.onNodeWithText("Partecipante 63").assertIsDisplayed()
        compose.onNodeWithTag("editor-save").assertDoesNotExist()
        click("editor-delete"); compose.onNodeWithText("Annulla").performClick(); assertTrue(writer.commands.isEmpty())
        click("editor-delete"); compose.onNodeWithTag("editor-confirm-delete").performClick()
        compose.onNodeWithText("Partita eliminata.").assertIsDisplayed()
        assertEquals(DeletePlayCommand(request, play, "v1:original"), writer.commands.single())
    }
    @Test fun backAsksBeforeDiscardAndCloseDestroysMemory() {
        val vm = model(play); show(vm); replace("editor-notes", "Non salvato")
        compose.onNodeWithContentDescription("Torna indietro").performClick()
        compose.onNodeWithTag("editor-exit-dialog").assertIsDisplayed()
        compose.onNodeWithText("Continua a modificare").performClick(); assertEquals(0, closed)
        assertEquals("Non salvato", vm.editor.value.draft?.notes)
        compose.onNodeWithContentDescription("Torna indietro").performClick()
        compose.onNodeWithTag("editor-confirm-exit").performClick(); compose.waitForIdle()
        assertEquals(1, closed); assertTrue(writer.closed); assertNull(vm.editor.value.draft); assertTrue(writer.commands.isEmpty())
    }
    @Test fun lossOfInternetClosesPickerAndClearsDraftWithoutReconnectWrite() {
        val vm = model(play); show(vm); click("editor-add-player")
        compose.onNodeWithTag("editor-picker").assertIsDisplayed()
        network.connected.value = false
        compose.waitUntil { vm.editor.value.accessBlocked }; compose.waitForIdle()
        compose.onNodeWithTag("editor-picker").assertDoesNotExist(); compose.onNodeWithTag("editor-blocked").assertIsDisplayed()
        network.connected.value = true; compose.waitForIdle()
        assertNull(vm.editor.value.draft); assertTrue(writer.commands.isEmpty()); assertEquals(listOf(AuthProblem.NETWORK_UNAVAILABLE), failures)
    }
    @Test fun defaultDisabledHasNoCatalogOrWriteControls() {
        show(model(enabled = false)); compose.onNodeWithText("Gestione partite non disponibile in questa versione.").assertIsDisplayed()
        compose.onNodeWithTag("editor-save").assertDoesNotExist(); assertEquals(0, catalogs); assertEquals(0, writer.editorCalls)
    }
    @Test fun nonadminCannotLoadEditorEvenWhenFixtureCapabilityIsEnabled() {
        show(model(admin = false)); compose.onNodeWithText("Non hai il permesso di gestire le partite.").assertIsDisplayed()
        compose.onNodeWithTag("editor-save").assertDoesNotExist(); assertEquals(0, catalogs)
    }
    @Test fun lateSaveAfterClearingAccountCannotShowSuccess() {
        val completion = CompletableDeferred<PlayMutationResult>()
        writer.send = { withContext(NonCancellable) { completion.await() } }
        val vm = model(play); show(vm); click("editor-save")
        compose.runOnIdle { vm.clearMemory() }
        completion.complete(PlayMutationResult(play, "v1:saved", false)); compose.waitForIdle()
        compose.onNodeWithTag("editor-success").assertDoesNotExist(); assertNull(vm.editor.value.snapshot)
    }
    @Test fun compositionRestorationRetainsDraftWithoutReloadingOrWriting() {
        val vm = model(play); val restoration = StateRestorationTester(compose)
        restoration.setContent { KingScoreTheme { Surface { PlayEditorRoute(vm) { closed++ } } } }
        compose.waitForIdle(); replace("editor-notes", "Bozza mantenuta")
        restoration.emulateSavedInstanceStateRestore(); compose.waitForIdle()
        assertEquals("Bozza mantenuta", vm.editor.value.draft?.notes); assertTrue(vm.editor.value.dirty)
        assertEquals(1, catalogs); assertEquals(1, writer.editorCalls); assertTrue(writer.commands.isEmpty())
    }
    @Test fun largeFontFieldsLabelsAndSaveRemainReachable() {
        show(model(play), scale = 1.8f)
        scroll("editor-score-${anna.id}"); compose.onNodeWithTag("editor-score-${anna.id}").assertIsDisplayed()
        capture("editor-large-font"); scroll("editor-save"); compose.onNodeWithTag("editor-save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("editor-save").performClick(); compose.onNodeWithText("Partita salvata.").assertIsDisplayed()
    }
    @Test fun catalogFailurePreservesPendingIdentityAndRecoveryRemainsAvailable() {
        writer.pendingRequest = PendingPlayRequest(request, play, PlayMutationKind.UPDATE)
        catalogFailure = true; show()
        compose.onNodeWithTag("editor-pending").assertIsDisplayed()
        scroll("editor-read-error"); compose.onNodeWithTag("editor-read-error").assertIsDisplayed()
        click("editor-recover"); compose.onNodeWithText("Operazione precedente recuperata.").assertIsDisplayed()
        assertEquals(listOf("retry"), writer.commands)
    }
    @Test fun storageFailureBlocksSaveAndDeleteWithoutOfferingToDiscardRequest() {
        show(model(play)); writer.send = { throw PlayWriteFailure(PlayWriteProblem.STORAGE) }; click("editor-save")
        scroll("editor-error"); compose.onNodeWithTag("editor-error").assertIsDisplayed()
        scroll("editor-delete"); compose.onNodeWithTag("editor-delete").assertIsNotEnabled()
        scroll("editor-save"); compose.onNodeWithTag("editor-save").assertIsNotEnabled()
        assertEquals(1, writer.commands.size)
    }
}
