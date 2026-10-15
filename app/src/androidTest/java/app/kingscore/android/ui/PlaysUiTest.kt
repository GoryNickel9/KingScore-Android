package app.kingscore.android.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.core.auth.Account
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import app.kingscore.android.feature.plays.*
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaysUiTest {
    @get:Rule val compose = createComposeRule()
    private val access = mutableStateOf<AccessState>(AccessState.Connected(account))
    private val repo = FakePlays()

    @Test fun historyOpensDetailAndBackPreservesPages() {
        showApp()
        compose.onNodeWithText("Partite").performClick()
        compose.onNodeWithText("Azul").assertIsDisplayed()
        val callsBeforePagination = repo.historyCalls
        compose.onNodeWithTag("history-content").performScrollToNode(hasTestTag("more-plays"))
        compose.onNodeWithTag("more-plays").performClick()
        compose.waitUntil { repo.historyCalls == callsBeforePagination + 1 }
        compose.onNodeWithTag("history-content").performScrollToIndex(0)
        compose.onNodeWithText("3 partite caricate", substring = true).assertExists()
        capture("phase3-history")
        compose.onNodeWithTag("history-content").performScrollToNode(hasTestTag("play-${id(1)}"))
        compose.onNodeWithTag("play-${id(1)}").performClick()
        compose.onNodeWithText("Dettaglio partita").assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasContentDescription("Punteggio: 0"))
        compose.onNodeWithContentDescription("Punteggio: 0").assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Punteggio non registrato"))
        compose.onNodeWithText("Punteggio non registrato").assertIsDisplayed()
        compose.onNodeWithContentDescription("Posizione: —").assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Sei tu"))
        compose.onNodeWithText("Sei tu").assertIsDisplayed()
        capture("phase3-detail")
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Una partita combattuta."))
        compose.onNodeWithText("Una partita combattuta.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Torna allo storico").performClick()
        compose.onNodeWithTag("history-content").performScrollToIndex(0)
        compose.onNodeWithText("3 partite caricate", substring = true).assertExists()
        assertEquals(callsBeforePagination + 1, repo.historyCalls)
    }

    @Test fun disconnectRemovesDetailAndReconnectionReloadsWithoutCachedRows() {
        showApp()
        compose.onNodeWithText("Partite").performClick()
        compose.onNodeWithTag("play-${id(1)}").performClick()
        compose.onNodeWithText("Dettaglio partita").assertIsDisplayed()
        compose.runOnIdle { access.value = AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE) }
        compose.onNodeWithTag("play-detail").assertDoesNotExist()
        compose.onNodeWithTag("authenticated-shell").assertDoesNotExist()
        compose.onNodeWithText("Azul").assertDoesNotExist()
        val pending = CompletableDeferred<PlayDetail?>()
        repo.pendingDetail = pending
        val calls = repo.detailCalls
        compose.runOnIdle { access.value = AccessState.Connected(account) }
        compose.onNodeWithTag("dashboard-content").assertIsDisplayed()
        compose.onNodeWithTag("recent-${id(1)}").performClick()
        compose.waitUntil { repo.detailCalls > calls }
        compose.onNodeWithText("Caricamento partita…").assertIsDisplayed()
        compose.onNodeWithText("Azul").assertDoesNotExist()
        pending.complete(first)
        compose.onNodeWithText("Azul").assertIsDisplayed()
    }

    @Test fun logoutAndAccountChangeNeverRestorePreviousAccountPages() {
        val owners = mutableListOf<UUID>()
        compose.setContent { KingScoreTheme {
            AppContent(access.value, { _, _ -> }, {}, { access.value = AccessState.SignedOut() }, {},
                playsFactory = { owner -> owners += owner; repo }, onAccessFailure = { access.value = AccessState.Blocked(it) })
        } }
        compose.onNodeWithText("Partite").performClick()
        compose.onNodeWithText("Azul").assertIsDisplayed()
        compose.onNodeWithText("Profilo").performClick()
        compose.onNodeWithTag("sign-out").performClick()
        compose.onNodeWithTag("sign-in").assertIsDisplayed()
        compose.onNodeWithTag("plays-history").assertDoesNotExist()
        repo.nextHistory = PlayPage(emptyList(), null)
        val other = account.copy(id = id(98))
        compose.runOnIdle { access.value = AccessState.Connected(other) }
        compose.onNodeWithText("Partite").performClick()
        compose.onNodeWithText("Nessuna partita").assertIsDisplayed()
        compose.onNodeWithText("Azul").assertDoesNotExist()
        assertEquals(other.id, owners.last())
    }

    @Test fun forbiddenOrMissingDetailIsNotPresentedAsEmptyHistory() {
        compose.setContent { KingScoreTheme { DetailScreen(DetailState(loading = false, problem = DataProblem.FORBIDDEN),
            account.id, {}, {}, {}, {}) } }
        compose.onNodeWithText("Non hai il permesso di leggere questi dati.").assertIsDisplayed()
        compose.onNodeWithText("Partita non trovata").assertDoesNotExist()
    }

    @Test fun cooperativeLossUsesStoredTeamResultAndPositions() {
        val loss = first.copy(play = first.play.copy(is_cooperative = true, team_won = false),
            scores = first.scores.map { it.copy(score = it.score.copy(position = 2, winner = false)) })
        compose.setContent { KingScoreTheme { DetailScreen(DetailState(detail = loss, loading = false), account.id, {}, {}, {}, {}) } }
        compose.onNodeWithText("Sconfitta della squadra").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Posizione: 2").assertCountEquals(2)
        compose.onNodeWithText("Vincitore").assertDoesNotExist()
    }

    private fun showApp() {
        compose.setContent { KingScoreTheme { AppContent(access.value, { _, _ -> }, {}, {}, {},
            playsFactory = { repo }, onAccessFailure = { access.value = AccessState.Blocked(it) }) } }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    private class FakePlays : PlaysRepository {
        override suspend fun player(id: UUID) = first.scores.first().player
        override suspend fun game(id: UUID) = first.game
        var historyCalls = 0
        var detailCalls = 0
        var pendingDetail: CompletableDeferred<PlayDetail?>? = null
        var nextHistory: PlayPage? = null
        override suspend fun filterOptions() = HistoryOptions()
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?): PlayPage {
            historyCalls++
            nextHistory?.let { return it }
            return if (after == null) PlayPage(listOf(first, second), PlayCursor(second.play.played_at, second.play.id))
                else PlayPage(listOf(first.copy(play = first.play.copy(id = id(3)), game = first.game?.copy(name = "Cascadia"))), null)
        }
        override suspend fun detail(id: UUID): PlayDetail? { detailCalls++; return pendingDetail?.await() ?: first }
        override suspend fun comments(id: UUID, after: CommentCursor?) = CommentPage(listOf(PlayCommentRow(
            id(20), id, account.id, "Marta", "Una partita combattuta.", Instant.parse("2026-10-13T18:00:00Z"), Instant.parse("2026-10-13T18:00:00Z"))), null)
    }
    companion object {
        private fun id(n: Int) = UUID(0, n.toLong())
        private val account = Account(id(99), "Marta", "test@example.com", false)
        private val first = PlayDetail(
            PlayRow(id(1), null, id(10), LocalDate.parse("2026-10-13"), "A casa di Luca", "Finale deciso all'ultimo turno.", 0,
                "offline", null, null, false, null),
            GameRow(id(10), "Azul", null, null, 2, 4, null),
            listOf(ScoreWithPlayer(PlayScoreRow(id(11), id(1), id(12), BigDecimal.ZERO, true, null, null, 1),
                PlayerRow(id(12), null, "Marta", false, null, "Marta", null, Instant.EPOCH, null, account.id)),
                ScoreWithPlayer(PlayScoreRow(id(13), id(1), null, null, false, null, null, null), null)),
        )
        private val second = first.copy(play = first.play.copy(id = id(2), source = "online", played_at = LocalDate.parse("2026-10-12")),
            game = first.game?.copy(name = "7 Wonders Duel"))
    }
}
