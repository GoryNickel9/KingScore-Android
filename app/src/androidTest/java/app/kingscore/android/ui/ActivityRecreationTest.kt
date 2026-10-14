package app.kingscore.android.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import app.kingscore.android.DebugRecreationActivity
import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ActivityRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val access = mutableStateOf<AccessState>(AccessState.Connected(account))
    private val repos = mutableListOf<FixturePlays>()
    private val factory: (UUID) -> PlaysRepository = { id -> FixturePlays(id).also { repos += it } }
    private lateinit var scenario: ActivityScenario<DebugRecreationActivity>

    @Before fun launch() {
        DebugRecreationActivity.content = { KingScoreTheme {
            AppContent(access.value, { _, _ -> }, {}, { access.value = AccessState.SignedOut() }, {},
                playsFactory = factory, onAccessFailure = { access.value = AccessState.Blocked(it) })
        } }
        scenario = ActivityScenario.launch(DebugRecreationActivity::class.java)
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.onNodeWithTag("authenticated-shell").assertIsDisplayed()
    }
    @After fun close() {
        try {
            if (::scenario.isInitialized) try {
                rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                compose.waitForIdle()
            } finally { scenario.close() }
        } finally { DebugRecreationActivity.content = null }
    }

    @Test fun activityRecreationAndRotationKeepDraftDetailAndLoadedHistory() {
        compose.onNodeWithText("Partite").performClick()
        compose.onNodeWithTag("history-content").performScrollToNode(hasTestTag("more-plays"))
        compose.onNodeWithTag("more-plays").performClick()
        compose.onNodeWithTag("history-content").performScrollToNode(hasTestTag("play-${older.play.id}"))
        compose.onNodeWithTag("play-${older.play.id}").performClick()
        addDraft("Bozza conservata 😀")
        val repo = repos.single(); val historyCalls = repo.historyCalls
        val detailCalls = repo.detailCalls
        scenario.recreate()
        compose.onNodeWithTag("comment-input").assertTextContains("Bozza conservata 😀")
        rotate()
        compose.onNodeWithTag("comment-input").assertTextContains("Bozza conservata 😀")
        assertEquals(1, repos.size); assertEquals(0, repo.closed)
        assertEquals(detailCalls, repo.detailCalls); assertTrue(repo.writes.isEmpty())
        compose.onNodeWithTag("cancel-comment").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Torna allo storico").performClick()
        compose.onNodeWithTag("history-content").performScrollToNode(hasTestTag("play-${older.play.id}"))
        compose.onNodeWithTag("play-${older.play.id}").assertIsDisplayed()
        assertEquals(historyCalls, repo.historyCalls)
    }

    @Test fun rotationDuringDispatchDoesNotCloseWriterCancelSaveOrSendAgain() {
        openRecent(); addDraft("Un solo invio")
        val repo = repos.single(); val pending = CompletableDeferred<Unit>(); repo.pending = pending
        compose.onNodeWithTag("save-comment").performScrollTo().performClick()
        compose.waitUntil { repo.writes.size == 1 }
        rotate()
        compose.onNodeWithTag("save-comment").performScrollTo().assertIsNotEnabled()
        assertEquals(0, repo.closed); assertEquals(1, repo.writes.size)
        pending.complete(Unit)
        compose.onNodeWithTag("comment-dialog").assertDoesNotExist()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("comment-success"))
        compose.onNodeWithText("Commento inviato.").assertIsDisplayed()
        assertEquals(1, repo.writes.size)
    }

    @Test fun offlineAndAccountSwitchAfterRecreationDiscardDraftAndRetainedModels() {
        openRecent(); addDraft("Privata per il primo account"); scenario.recreate()
        val old = repos.single()
        compose.runOnIdle { access.value = AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE) }
        compose.onNodeWithTag("connection-blocked").assertIsDisplayed()
        compose.onNodeWithTag("comment-dialog").assertDoesNotExist()
        assertEquals(1, old.closed)
        scenario.recreate()
        compose.onNodeWithTag("authenticated-shell").assertDoesNotExist()
        compose.runOnIdle { access.value = AccessState.Connected(account) }
        openRecent(); addDraft("Seconda bozza")
        compose.onNodeWithText("Privata per il primo account").assertDoesNotExist()
        val reconnected = repos.last()
        compose.runOnIdle { access.value = AccessState.Connected(account.copy(id = UUID(0, 100), name = "Altro account")) }
        compose.onNodeWithTag("comment-dialog").assertDoesNotExist()
        assertEquals(1, reconnected.closed)
        openRecent(); addDraft("Nuova bozza")
        compose.onNodeWithText("Seconda bozza").assertDoesNotExist()
        compose.runOnIdle { access.value = AccessState.SignedOut() }
        compose.onNodeWithTag("sign-in").assertIsDisplayed()
        assertEquals(1, repos.last().closed)
    }

    @Test fun checkingAfterRecreationClosesTheOldSessionAndDoesNotRestoreItsDraft() {
        openRecent(); addDraft("Da scartare durante Checking"); scenario.recreate()
        val old = repos.single()
        compose.runOnIdle { access.value = AccessState.Checking }
        compose.onNodeWithTag("checking-session").assertIsDisplayed()
        assertEquals(1, old.closed)
        compose.runOnIdle { access.value = AccessState.Connected(account) }
        compose.onNodeWithTag("play-detail").assertIsDisplayed()
        compose.onNodeWithTag("comment-dialog").assertDoesNotExist()
        addDraft("Bozza nuova")
        compose.onNodeWithText("Da scartare durante Checking").assertDoesNotExist()
        assertEquals(2, repos.size)
    }

    private fun rotate(orientation: Int = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
        val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        var old: DebugRecreationActivity? = null
        var alreadyRotated = false
        scenario.onActivity {
            old = it; alreadyRotated = it.resources.configuration.orientation == expected
            it.requestedOrientation = orientation
        }
        if (alreadyRotated) return
        compose.waitUntil(10_000) {
            var changed = false
            scenario.onActivity { changed = it !== old && it.resources.configuration.orientation == expected }
            changed
        }
    }
    private fun openRecent() {
        compose.onNodeWithTag("dashboard-content").performScrollToNode(hasTestTag("recent-${recent.play.id}"))
        compose.onNodeWithTag("recent-${recent.play.id}").performClick()
    }
    private fun addDraft(text: String) {
        compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("add-comment"))
        compose.onNodeWithTag("add-comment").performClick()
        compose.onNodeWithTag("comment-input").performTextInput(text)
    }
    private class FixturePlays(private val actor: UUID) : PlaysRepository {
        var closed = 0; var detailCalls = 0; var historyCalls = 0
        val writes = mutableListOf<String>(); var pending: CompletableDeferred<Unit>? = null
        private val comments = mutableListOf<PlayCommentRow>()
        override val commentWrites = object : CommentWrites {
            override fun close() { closed++ }
            override suspend fun create(command: CreateComment): PlayCommentRow {
                writes += command.content; pending?.await()
                return PlayCommentRow(UUID(0, 40), command.play_id, actor, "Fixture", command.content,
                    java.time.Instant.EPOCH, java.time.Instant.EPOCH).also { comments += it }
            }
            override suspend fun update(playId: UUID, commentId: UUID, command: UpdateComment): PlayCommentRow = error("unused")
            override suspend fun delete(playId: UUID, commentId: UUID): PlayCommentRow = error("unused")
        }
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?): PlayPage {
            historyCalls++
            return if (after == null) PlayPage(listOf(recent), PlayCursor(recent.play.played_at, recent.play.id))
                else PlayPage(listOf(older), null)
        }
        override suspend fun detail(id: UUID): PlayDetail { detailCalls++; return if (id == older.play.id) older else recent }
        override suspend fun comments(id: UUID, after: CommentCursor?) = CommentPage(comments.toList(), null)
        override suspend fun filterOptions() = HistoryOptions()
        override suspend fun player(id: UUID): PlayerRow? = null
        override suspend fun game(id: UUID): GameRow? = null
    }
    companion object {
        private val account = Account(UUID(0, 99), "Marta", "test@example.com", false)
        private val recent = PlayDetail(PlayRow(UUID(0, 1), null, null, LocalDate.parse("2026-10-13"),
            null, null, 45, "offline", "Fixture ricreazione", null, false, null),
            GameRow(UUID(0, 10), "Azul", null, null, 2, 4, null), emptyList())
        private val older = recent.copy(play = recent.play.copy(id = UUID(0, 2), played_at = LocalDate.parse("2026-09-13")))
    }
}
