package app.kingscore.android.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import app.kingscore.android.feature.plays.DetailScreen
import app.kingscore.android.feature.plays.DetailState
import app.kingscore.android.feature.players.PlayerRoute
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

class PlayerUiTest {
    @get:Rule val compose = createComposeRule()
    private val access = mutableStateOf<AccessState>(AccessState.Connected(account))
    private val repo = FakePlays()

    @Test fun playerPaginationPlayReturnAndRefreshKeepTheParticipantScope() {
        showApp(); openPlayer()
        compose.onNodeWithText("Marta Rossi").assertIsDisplayed()
        compose.onNodeWithText("Sei tu").assertIsDisplayed()
        compose.waitForIdle()
        val before = repo.playerRequests.size
        compose.onNodeWithTag("player-content").performScrollToNode(hasTestTag("more-player-plays"))
        compose.onNodeWithTag("more-player-plays").performClick()
        compose.waitUntil { repo.playerRequests.size == before + 1 }
        assertEquals(HistoryFilters(playerId = player.id), repo.playerRequests.last().first)
        assertNotNull(repo.playerRequests.last().second)
        compose.onNodeWithTag("player-content").performScrollToNode(hasText("2 partite caricate", substring = true))
        compose.onNodeWithText("2 partite caricate", substring = true).assertIsDisplayed()
        capture("phase34-player")
        compose.onNodeWithTag("player-content").performScrollToNode(hasTestTag("play-${second.play.id}"))
        compose.onNodeWithTag("play-${second.play.id}").performClick()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasContentDescription("Punteggio: 0"))
        compose.onNodeWithContentDescription("Punteggio: 0").assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Luca"))
        compose.onNodeWithText("Luca").assertIsDisplayed()
        openParticipant()
        compose.onNodeWithTag("player-detail").assertIsDisplayed()
        assertEquals(before + 1, repo.playerRequests.size)
        assertEquals(1, repo.playerCalls)
        refreshPlayer()
        compose.waitUntil { repo.playerRequests.size == before + 2 }
        assertNull(repo.playerRequests.last().second)
        assertEquals(2, repo.playerCalls)
        compose.onNodeWithTag("player-content").performScrollToNode(hasText("1 partite caricate", substring = true))
        compose.onNodeWithText("1 partite caricate", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Torna indietro").performClick()
        compose.onNodeWithTag("play-detail").assertIsDisplayed()
    }

    @Test fun missingJoinStillOpensFreshPlayerAndMissingOrDeniedMetadataNeverReadsHistory() {
        repo.nullableJoin = true
        repo.playerRead = { null }
        showApp(); openPlayer()
        compose.onNodeWithText("Giocatore non disponibile").assertIsDisplayed()
        assertTrue(repo.playerRequests.isEmpty())
        repo.playerRead = { throw DataFailure(DataProblem.FORBIDDEN) }
        refreshPlayer()
        compose.onNodeWithText("Non hai il permesso di leggere questi dati.").assertIsDisplayed()
        compose.onNodeWithText("Giocatore non disponibile").assertDoesNotExist()
        assertTrue(repo.playerRequests.isEmpty())
        repo.playerRead = { player }
        compose.onNodeWithText("Riprova").performClick()
        compose.onNodeWithText("Marta Rossi").assertIsDisplayed()
        compose.waitUntil { repo.playerRequests.size == 1 }
    }

    @Test fun failedNextPageKeepsRowsAndRetriesTheSameCursor() {
        showApp(); openPlayer()
        repo.failMore = true
        compose.onNodeWithTag("player-content").performScrollToNode(hasTestTag("more-player-plays"))
        compose.onNodeWithTag("more-player-plays").performClick()
        compose.onNodeWithTag("player-content").performScrollToNode(hasText("Riprova"))
        val cursor = repo.playerRequests.last().second
        repo.failMore = false
        compose.onNodeWithText("Riprova").performClick()
        compose.waitForIdle()
        assertEquals(cursor, repo.playerRequests.last().second)
        compose.onNodeWithTag("player-content").performScrollToNode(hasText("2 partite caricate", substring = true))
        compose.onNodeWithText("2 partite caricate", substring = true).assertIsDisplayed()
    }

    @Test fun offlineAndLogoutRemovePlayerAndReconnectReadsFreshMetadata() {
        showApp(); openPlayer()
        compose.onNodeWithText("Marta Rossi").assertIsDisplayed()
        compose.runOnIdle { access.value = AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE) }
        compose.onNodeWithTag("player-detail").assertDoesNotExist()
        compose.onNodeWithTag("authenticated-shell").assertDoesNotExist()
        val pending = CompletableDeferred<PlayerRow?>()
        repo.playerRead = { pending.await() }
        compose.runOnIdle { access.value = AccessState.Connected(account) }
        openPlayer()
        compose.onNodeWithText("Caricamento giocatore…").assertIsDisplayed()
        compose.onNodeWithText("Marta Rossi").assertDoesNotExist()
        pending.complete(player.copy(alias = "Alias aggiornato"))
        compose.onNodeWithText("Alias aggiornato").assertIsDisplayed()
        compose.runOnIdle { access.value = AccessState.SignedOut() }
        compose.onNodeWithTag("player-detail").assertDoesNotExist()
        compose.onNodeWithTag("sign-in").assertIsDisplayed()
    }

    @Test fun accountSwitchDoesNotRestorePreviousPlayerPages() {
        val owners = mutableListOf<UUID>()
        compose.setContent { KingScoreTheme { AppContent(access.value, { _, _ -> }, {}, {}, {},
            playsFactory = { owner -> owners += owner; repo }, onAccessFailure = { access.value = AccessState.Blocked(it) }) } }
        openPlayer()
        val other = account.copy(id = UUID(0, 98))
        compose.runOnIdle { access.value = AccessState.Connected(other) }
        compose.onNodeWithTag("dashboard-content").assertIsDisplayed()
        compose.onNodeWithTag("player-detail").assertDoesNotExist()
        assertEquals(other.id, owners.last())
        openPlayer()
        compose.onNodeWithText("Sei tu").assertDoesNotExist()
        assertEquals(2, repo.playerCalls)
    }

    @Test fun blankAliasFallsBackToNameAndHistoricalIsMeDoesNotIdentifyCurrentAccount() {
        repo.playerRead = { player.copy(alias = "  ", is_me = true, account_user_id = null) }
        showApp(); openPlayer()
        compose.onNodeWithText("Marta Rossi").assertIsDisplayed()
        compose.onNodeWithText("Sei tu").assertDoesNotExist()
        compose.onNodeWithText("Profilo aggiornato:", substring = true).assertIsDisplayed()
    }

    @Test fun playerWithEmptyHistoryIsDistinctFromMissingPlayer() {
        repo.empty = true
        compose.setContent { KingScoreTheme { PlayerRoute(repo, account.id, player.id,
            onAccessFailure = {}, onOpenPlay = {}, onBack = {}) } }
        compose.onNodeWithTag("player-content").performScrollToNode(hasText("Nessuna partita registrata per questo giocatore."))
        compose.onNodeWithText("Nessuna partita registrata per questo giocatore.").assertIsDisplayed()
        compose.onNodeWithText("Giocatore non disponibile").assertDoesNotExist()
        assertEquals(HistoryFilters(playerId = player.id), repo.playerRequests.single().first)
    }

    @Test fun scoreWithoutPlayerIdDoesNotExposeNavigation() {
        val absent = first.copy(scores = first.scores.map { it.copy(score = it.score.copy(player_id = null), player = null) })
        compose.setContent { KingScoreTheme { DetailScreen(DetailState(absent, loading = false), account.id,
            {}, {}, {}, {}, onOpenPlayer = { fail("Missing player ID must not navigate") }) } }
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Punteggi"))
        compose.onAllNodesWithText("Apri giocatore").assertCountEquals(0)
    }

    private fun refreshPlayer() {
        val action = compose.onNodeWithTag("pull-refresh").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Aggiorna" }
        compose.runOnIdle { assertTrue(action.action()) }
        compose.waitForIdle()
    }

    private fun showApp() {
        compose.setContent { KingScoreTheme { AppContent(access.value, { _, _ -> }, {}, {}, {},
            playsFactory = { repo }, onAccessFailure = { access.value = AccessState.Blocked(it) }) } }
    }
    private fun openPlayer() {
        compose.onNodeWithTag("dashboard-content").performScrollToNode(hasTestTag("recent-${first.play.id}"))
        compose.onNodeWithTag("recent-${first.play.id}").performClick()
        openParticipant()
        compose.onNodeWithTag("player-detail").assertIsDisplayed()
    }
    private fun openParticipant() {
        compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("open-player-${first.scores.first().score.id}"))
        // A participant item can exceed the landscape viewport; expose the actual button before tapping.
        compose.onNodeWithTag("open-player-${first.scores.first().score.id}").performScrollTo().assertIsDisplayed().performClick()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "$name.png")
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private class FakePlays : PlaysRepository {
        var playerRead: suspend () -> PlayerRow? = { player }
        var playerCalls = 0
        var failMore = false
        var empty = false
        var nullableJoin = false
        val playerRequests = mutableListOf<Pair<HistoryFilters, PlayCursor?>>()
        override suspend fun player(id: UUID): PlayerRow? { assertEquals(player.id, id); playerCalls++; return playerRead() }
        override suspend fun game(id: UUID) = game
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?): PlayPage {
            if (filters.playerId == null) return PlayPage(listOf(first), null)
            assertEquals(player.id, filters.playerId)
            playerRequests += filters to after
            if (empty) return PlayPage(emptyList(), null)
            if (after != null && failMore) throw DataFailure(DataProblem.INVALID_RESPONSE)
            return if (after == null) PlayPage(listOf(first), PlayCursor(first.play.played_at, first.play.id))
                else PlayPage(listOf(second), null)
        }
        override suspend fun filterOptions() = HistoryOptions()
        override suspend fun detail(id: UUID): PlayDetail {
            val play = if (id == first.play.id) first else second
            return if (nullableJoin) play.copy(scores = play.scores.map { it.copy(player = null) }) else play
        }
        override suspend fun comments(id: UUID, after: CommentCursor?) = CommentPage(emptyList(), null)
    }
    companion object {
        private val account = Account(UUID(0, 99), "Marta", "test@example.com", false)
        private val player = PlayerRow(UUID(0, 10), null, "Marta Rossi", false, null, "Marta", null,
            Instant.parse("2026-10-13T17:00:00Z"), null, account.id)
        private val otherPlayer = player.copy(id = UUID(0, 20), name = "Luca", alias = null, account_user_id = null)
        private val game = GameRow(UUID(0, 30), "Azul", null, null, 2, 4, null)
        private val first = PlayDetail(PlayRow(UUID(0, 1), null, game.id, LocalDate.parse("2026-10-13"),
            null, "Finale deciso all'ultimo turno.", 45, "offline", null, null, false, null), game,
            listOf(ScoreWithPlayer(PlayScoreRow(UUID(0, 11), UUID(0, 1), player.id, BigDecimal.ZERO, true, null, null, 1), player),
                ScoreWithPlayer(PlayScoreRow(UUID(0, 21), UUID(0, 1), otherPlayer.id, null, false, null, null, 2), otherPlayer)))
        private val second = first.copy(play = first.play.copy(id = UUID(0, 2), played_at = LocalDate.parse("2026-10-12"), source = "online"))
    }
}
