package app.kingscore.android.ui

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.DebugRecreationActivity
import app.kingscore.android.core.auth.*
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*

/** Production navigation with synthetic repositories/writers; no Auth or remote HTTP. */
class PlayEditorNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val admin = Account(UUID(9, 1), "Admin", null, true)
    private val game = GameRow(UUID(9, 2), "Azul", null, null, null, null, null)
    private val player = PlayerRow(UUID(9, 3), null, "Anna", null, null, null, null, Instant.EPOCH, null, null)
    private val playId = UUID(9, 4)
    private val scoreId = UUID(9, 5)
    private val network = object : NetworkMonitor { override val connected = MutableStateFlow(true) }
    private val access = mutableStateOf<AccessState>(AccessState.Connected(admin))
    private val capability = mutableStateOf(true)
    private val fontScale = mutableFloatStateOf(1f)
    private val writers = mutableListOf<Writer>()
    private val historyQueries = mutableListOf<HistoryFilters>()
    private val commands = mutableListOf<Any>()
    private val pending = mutableMapOf<UUID, PendingPlayRequest>()
    private var gate: CompletableDeferred<Unit>? = null
    private var uncertain = false
    private var current: PlayDetail? = PlayDetail(PlayRow(playId, UUID(9, 99), game.id, LocalDate.of(2026, 10, 14),
        "Imported", "Original", 30, "online", "123", Instant.EPOCH, false, null), game,
        listOf(ScoreWithPlayer(PlayScoreRow(scoreId, playId, player.id, BigDecimal("5.125"), false, "red", "Builder", 1), player)))
    private var revision = "v1:original"
    private lateinit var scenario: ActivityScenario<DebugRecreationActivity>
    private val reads = object : PlaysRepository {
        override suspend fun filterOptions() = HistoryOptions(listOf(game), listOf(player))
        override suspend fun detail(id: UUID) = current?.takeIf { it.play.id == id }
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?): PlayPage {
            historyQueries += filters
            return PlayPage(listOfNotNull(current).filter { filters.source == null || it.play.source == filters.source!!.wireValue }, null)
        }
        override suspend fun comments(id: UUID, after: CommentCursor?) = CommentPage(emptyList(), null)
        override suspend fun game(id: UUID) = game
        override suspend fun player(id: UUID) = player
    }
    private inner class Writer(val actor: UUID) : PlayWrites {
        var closed = 0
        override val pendingRequest get() = pending[actor]
        override suspend fun restorePending() = pending[actor]
        override suspend fun restoreCompleted(): PlayWriteResult? = null
        override suspend fun editor(playId: UUID) = PlayEditorResult(checkNotNull(current), revision, PlayEditability(true, emptyList()))
        private suspend fun dispatch(command: Any, request: UUID, kind: PlayMutationKind, target: UUID?) {
            commands += command
            gate?.let { withContext(NonCancellable) { it.await() } }
            if (uncertain) {
                pending[actor] = PendingPlayRequest(request, target, kind)
                throw PlayWriteFailure(PlayWriteProblem.UNCERTAIN)
            }
        }
        private fun save(input: PlayInput, creating: Boolean = false): PlayMutationResult {
            val old = if (creating) null else current
            val target = if (creating) UUID(9, 6) else playId
            current = PlayDetail(PlayRow(target, old?.play?.user_id ?: actor, input.game_id, input.played_at,
                old?.play?.location, input.notes, input.duration_minutes, input.source.wireValue, old?.play?.bga_table_id,
                old?.play?.created_at ?: Instant.EPOCH, input.is_cooperative, input.team_won), game,
                input.scores.map { ScoreWithPlayer(PlayScoreRow(it.id ?: scoreId, target, it.player_id, it.score, it.winner, it.color, it.role, 1), player) })
            revision = "v1:saved"
            return PlayMutationResult(target, revision, false)
        }
        override suspend fun create(command: CreatePlayCommand): PlayMutationResult {
            dispatch(command, command.request_id, PlayMutationKind.CREATE, null); return save(command.payload, creating = true)
        }
        override suspend fun update(command: UpdatePlayCommand): PlayMutationResult {
            dispatch(command, command.request_id, PlayMutationKind.UPDATE, command.play_id); return save(command.payload)
        }
        override suspend fun delete(command: DeletePlayCommand): DeletePlayResult {
            dispatch(command, command.request_id, PlayMutationKind.DELETE, command.play_id)
            current = null; return DeletePlayResult(command.play_id, true, false)
        }
        override suspend fun retryPending(): PlayWriteResult {
            commands += "retry:${pending.getValue(actor).requestId}"
            val request = pending.remove(actor)!!
            return if (request.kind == PlayMutationKind.DELETE) PlayWriteResult.Deleted(DeletePlayResult(checkNotNull(request.playId), true, true))
                else PlayWriteResult.Saved(PlayMutationResult(request.playId ?: playId, "v1:replayed", true))
        }
        override fun close() { closed++ }
    }
    @Before fun launch() {
        DebugRecreationActivity.content = {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.floatValue)) {
                KingScoreTheme {
                    AppContent(access.value, { _, _ -> }, {}, { access.value = AccessState.SignedOut() }, {},
                        playsFactory = { reads }, onAccessFailure = { access.value = AccessState.Blocked(it) },
                        playWritesFactory = if (capability.value) ({ actor -> Writer(actor).also { writers += it } }) else null,
                        editorNetwork = network)
                }
            }
        }
        scenario = ActivityScenario.launch(DebugRecreationActivity::class.java)
        compose.onNodeWithTag("authenticated-shell").assertIsDisplayed()
    }
    @After fun clean() {
        gate?.complete(Unit)
        try {
            if (::scenario.isInitialized) try {
                scenario.onActivity { it.finishEditorTestInput() }
                compose.waitForIdle()
                rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            } finally { scenario.close() }
        } finally { DebugRecreationActivity.content = null }
    }
    private fun rotate(orientation: Int) {
        var old: DebugRecreationActivity? = null
        var already = false
        val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            android.content.res.Configuration.ORIENTATION_PORTRAIT else android.content.res.Configuration.ORIENTATION_LANDSCAPE
        scenario.onActivity {
            old = it; already = it.resources.configuration.orientation == expected
            it.requestedOrientation = orientation
        }
        if (!already) compose.waitUntil(15_000) {
            var rotated = false
            scenario.onActivity { rotated = it !== old && it.resources.configuration.orientation == expected }
            rotated
        }
        compose.waitForIdle()
    }
    private fun history() { compose.onNodeWithText("Partite").performClick(); compose.waitForIdle() }
    private fun edit() {
        history()
        compose.onNodeWithTag("history-content").performScrollToNode(hasTestTag("play-$playId"))
        compose.onNodeWithTag("play-$playId").performClick()
        compose.onNodeWithTag("manage-play").performClick(); compose.waitForIdle()
    }
    private fun click(tag: String) {
        // Finish text input before scrolling: an IME resize can recompose the lazy target between lookup and tap.
        scenario.onActivity { it.finishEditorTestInput() }
        compose.waitForIdle()
        compose.onNodeWithTag("editor-content").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick(); compose.waitForIdle()
    }
    private fun replace(tag: String, value: String) {
        compose.onNodeWithTag("editor-content").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performTextReplacement(value); compose.waitForIdle()
    }
    private fun capture(name: String, tag: String = "play-editor") {
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "$name.png").outputStream().use {
            compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun defaultCapabilityAndOrdinaryAccountNeverOpenAnEditor() {
        compose.runOnIdle { capability.value = false }; history()
        compose.onNodeWithTag("create-play").assertDoesNotExist()
        compose.runOnIdle { capability.value = true; access.value = AccessState.Connected(admin.copy(isAdmin = false)) }
        history(); compose.onNodeWithTag("create-play").assertDoesNotExist()
        compose.onNodeWithTag("history-content").performScrollToNode(hasTestTag("play-$playId"))
        compose.onNodeWithTag("play-$playId").performClick()
        compose.onNodeWithTag("manage-play").assertDoesNotExist()
        assertTrue(writers.isEmpty()); assertTrue(commands.isEmpty())
        compose.runOnIdle { access.value = AccessState.SignedOut() }
        compose.onNodeWithTag("sign-in").assertIsDisplayed()
    }
    @Test fun createRefreshesHistoryAndPreservesItsAppliedSourceFilter() {
        history(); compose.onNode(hasText("Online") and hasClickAction() and !hasTestTag("play-$playId")).performClick()
        compose.waitForIdle(); val callsBefore = historyQueries.size
        compose.onNodeWithTag("create-play").performClick()
        click("editor-game"); compose.onNodeWithTag("editor-option-${game.id}").performClick()
        click("editor-add-player"); compose.onNodeWithTag("editor-option-${player.id}").performClick()
        replace("editor-score-${player.id}", "9007199254740993.125"); click("editor-save")
        compose.onNodeWithText("Partita salvata.").assertIsDisplayed()
        click("editor-done")
        compose.onNodeWithTag("plays-history").assertIsDisplayed()
        compose.onNode(hasText("Online") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("Nessuna partita").assertIsDisplayed()
        assertEquals(callsBefore + 1, historyQueries.size)
        assertEquals(PlaySource.ONLINE, historyQueries.last().source)
        assertEquals(BigDecimal("9007199254740993.125"), (commands.single() as CreatePlayCommand).payload.scores.single().score)
        assertEquals(1, writers.single().closed)
        assertEquals(admin.id, current!!.play.user_id)
    }
    @Test fun updateOfAnotherOwnersPlayRefreshesDetailAndKeepsMetadata() {
        edit(); replace("editor-notes", "Edited in Android"); click("editor-save"); click("editor-done")
        compose.onNodeWithTag("play-detail").assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Edited in Android"))
        compose.onNodeWithText("Edited in Android").assertIsDisplayed()
        val update = commands.single() as UpdatePlayCommand
        assertEquals("v1:original", update.expected_revision); assertEquals(scoreId, update.payload.scores.single().id)
        assertEquals("Builder", update.payload.scores.single().role)
        assertEquals(UUID(9, 99), current!!.play.user_id)
        assertEquals(1, writers.single().closed)
    }
    @Test fun deletionReturnsToARefreshedMissingDetailAndHistory() {
        edit(); click("editor-delete")
        compose.onNodeWithTag("editor-confirm-delete").performClick(); click("editor-done")
        compose.onNodeWithText("Partita non trovata").assertIsDisplayed()
        history(); compose.onNodeWithTag("play-$playId").assertDoesNotExist()
        assertTrue(commands.single() is DeletePlayCommand); assertEquals(1, writers.single().closed)
    }
    @Test fun recreationRetainsDraftAndExitConfirmationThenReopeningCreatesANewWriter() {
        edit(); replace("editor-notes", "Unsaved private draft")
        compose.onNodeWithText("Home").assertDoesNotExist()
        scenario.recreate()
        compose.onNodeWithTag("editor-content").performScrollToNode(hasTestTag("editor-notes"))
        compose.onNodeWithTag("editor-notes").assertTextContains("Unsaved private draft")
        replace("editor-notes", "Unsaved private draft after recreation")
        assertEquals(1, writers.size); assertEquals(0, writers.single().closed)
        capture("editor-navigation-draft")
        compose.onNodeWithContentDescription("Torna indietro").performClick()
        compose.onNodeWithTag("editor-exit-dialog").assertIsDisplayed()
        capture("editor-navigation-exit", "editor-exit-dialog")
        compose.onNodeWithTag("editor-confirm-exit").performClick()
        assertEquals(1, writers.single().closed); assertTrue(commands.isEmpty())
        compose.onNodeWithTag("manage-play").performClick()
        compose.onNodeWithTag("editor-content").performScrollToNode(hasTestTag("editor-notes"))
        compose.onNodeWithTag("editor-notes").assertTextContains("Original")
        assertEquals(2, writers.size)
    }
    @Test fun rotationWhileSavingRetainsOneWriterAndOneRequest() {
        edit(); replace("editor-notes", "One mutation")
        gate = CompletableDeferred(); click("editor-save")
        compose.waitUntil { commands.size == 1 }
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        scenario.recreate()
        assertEquals(1, writers.size); assertEquals(0, writers.single().closed)
        gate!!.complete(Unit)
        compose.onNodeWithText("Partita salvata.").assertIsDisplayed()
        click("editor-done"); assertEquals(1, commands.size); assertEquals(1, writers.single().closed)
    }
    @Test fun accessRemovalAndRoleRevocationDiscardDraftAndRejectLateSaveResults() {
        edit(); replace("editor-notes", "Private pending draft")
        gate = CompletableDeferred(); click("editor-save"); compose.waitUntil { commands.size == 1 }
        compose.runOnIdle { access.value = AccessState.Connected(admin.copy(isAdmin = false)) }
        compose.onNodeWithTag("play-editor").assertDoesNotExist()
        assertEquals(1, writers.single().closed)
        gate!!.complete(Unit); compose.waitForIdle()
        compose.onNodeWithText("Partita salvata.").assertDoesNotExist()
        history(); compose.onNodeWithTag("create-play").assertDoesNotExist()
        compose.runOnIdle { access.value = AccessState.Connected(admin) }; edit()
        network.connected.value = false
        compose.onNodeWithTag("connection-blocked").assertIsDisplayed()
        assertEquals(1, writers.last().closed)
        compose.onNodeWithTag("editor-notes").assertDoesNotExist()
    }
    @Test fun uncertainRequestSurvivesBlockedAccessAndRemainsBoundToItsAccount() {
        edit(); uncertain = true; replace("editor-notes", "Uncertain change"); click("editor-save")
        val request = pending.getValue(admin.id)
        compose.runOnIdle { access.value = AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE) }
        compose.onNodeWithTag("connection-blocked").assertIsDisplayed()
        compose.runOnIdle { access.value = AccessState.Connected(admin.copy(id = UUID(9, 100))) }
        edit(); compose.onNodeWithTag("editor-pending").assertDoesNotExist()
        compose.runOnIdle { access.value = AccessState.Connected(admin) }
        edit(); compose.onNodeWithTag("editor-pending").assertIsDisplayed()
        click("editor-recover")
        compose.onNodeWithText("Operazione precedente recuperata.").assertIsDisplayed()
        assertEquals("retry:${request.requestId}", commands.last()); assertEquals(2, commands.size)
        assertTrue(writers.all { it.actor in setOf(admin.id, UUID(9, 100)) })
    }
    @Test fun checkingAndLogoutClearTheEditorBeforeTheSameAccountReturns() {
        edit(); replace("editor-notes", "Discard during session verification")
        compose.runOnIdle { access.value = AccessState.Checking }
        compose.onNodeWithTag("checking-session").assertIsDisplayed()
        assertEquals(1, writers.single().closed)
        scenario.recreate()
        compose.runOnIdle { access.value = AccessState.Connected(admin) }
        compose.onNodeWithTag("play-editor").assertIsDisplayed()
        compose.onNodeWithTag("editor-content").performScrollToNode(hasTestTag("editor-notes"))
        compose.onNodeWithTag("editor-notes").assertTextContains("Original")
        assertEquals(2, writers.size)
        compose.runOnIdle { access.value = AccessState.SignedOut() }
        compose.onNodeWithTag("sign-in").assertIsDisplayed()
        assertEquals(1, writers.last().closed)
        compose.runOnIdle { access.value = AccessState.Connected(admin) }
        compose.onNodeWithTag("play-editor").assertDoesNotExist()
        compose.onNodeWithText("Discard during session verification").assertDoesNotExist()
        assertTrue(commands.isEmpty())
    }
    @Test fun largeTextKeepsCreateManageAndEditorExitControlsReachable() {
        compose.runOnIdle { fontScale.floatValue = 1.8f }
        edit(); replace("editor-notes", "Large font draft")
        compose.onNodeWithContentDescription("Torna indietro").performClick()
        compose.onNodeWithTag("editor-confirm-exit").assertIsDisplayed()
        compose.onNodeWithTag("editor-confirm-exit").performClick()
        compose.onNodeWithTag("play-detail").assertIsDisplayed()
    }
}
